/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.tessera.core.events;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OPS-04: per-tenant event-log retention sweep.
 *
 * <p>Runs daily at 02:00. For each tenant with {@code retention_days IS NOT NULL},
 * deletes {@code graph_events} rows older than {@code retention_days} days. The
 * {@code event_type != 'SNAPSHOT'} guard ensures snapshot events are never deleted.
 * The {@code snapshot_boundary} guard (via {@code COALESCE}) ensures events already
 * compacted below the snapshot boundary are not re-deleted.
 *
 * <p><strong>Idempotency:</strong> DELETE with a time-based predicate is naturally
 * idempotent — running the sweep twice for the same tenant produces the same outcome.
 *
 * <p><strong>Distributed locking:</strong> {@code @SchedulerLock} (ShedLock, same
 * {@link dev.tessera.core.events.internal.LockProviderConfig} bean used by the outbox
 * poller) prevents duplicate runs across JVM instances. {@code lockAtMostFor="PT55M"}
 * guarantees the lock is released before the next daily 02:00 run.
 *
 * <p><strong>Transaction isolation:</strong> {@code REQUIRES_NEW} gives the sweep its
 * own transaction separate from any outer caller, matching the OutboxPoller pattern.
 */
@Component
public class EventRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(EventRetentionJob.class);

    /**
     * SELECT all tenants with a configured retention policy.
     * Columns: model_id (UUID), retention_days (int), snapshot_boundary (timestamptz nullable)
     */
    private static final String SELECT_TENANTS_SQL = "SELECT model_id, retention_days, snapshot_boundary"
            + " FROM model_config"
            + " WHERE retention_days IS NOT NULL";

    /**
     * DELETE events older than :days for tenant :model_id.
     *
     * <p>Guards:
     * <ul>
     *   <li>{@code event_type != 'SNAPSHOT'} — snapshot markers are never deleted
     *   <li>{@code event_time > COALESCE(:snapshot_boundary, '-infinity'::timestamptz)} — events
     *       below the snapshot boundary have already been compacted; skip them to avoid double-work
     *   <li>{@code event_time < now() - :days * INTERVAL '1 day'} — retention window
     * </ul>
     */
    private static final String DELETE_SQL = "DELETE FROM graph_events"
            + " WHERE model_id = :model_id::uuid"
            + " AND event_type != 'SNAPSHOT'"
            + " AND event_time < now() - :days * INTERVAL '1 day'"
            + " AND event_time > COALESCE(:snapshot_boundary, '-infinity'::timestamptz)";

    private final NamedParameterJdbcTemplate jdbc;

    public EventRetentionJob(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Daily retention sweep — runs at 02:00.
     *
     * <p>Iterates over all tenants with {@code retention_days IS NOT NULL} and deletes
     * stale events per the retention policy.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "tessera-event-retention", lockAtMostFor = "PT55M")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweep() {
        List<Map<String, Object>> tenants =
                jdbc.query(SELECT_TENANTS_SQL, new MapSqlParameterSource(), (rs, rowNum) -> {
                    UUID modelId = (UUID) rs.getObject("model_id");
                    int retentionDays = rs.getInt("retention_days");
                    Timestamp snapshotBoundary = rs.getTimestamp("snapshot_boundary");
                    return Map.<String, Object>of(
                            "model_id", modelId,
                            "retention_days", retentionDays,
                            "snapshot_boundary", snapshotBoundary);
                });

        if (tenants.isEmpty()) {
            LOG.debug("EventRetentionJob: no tenants with retention policy configured");
            return;
        }

        for (Map<String, Object> tenant : tenants) {
            UUID modelId = (UUID) tenant.get("model_id");
            int days = (Integer) tenant.get("retention_days");
            Timestamp snapshotBoundary = (Timestamp) tenant.get("snapshot_boundary");

            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("model_id", modelId.toString());
            p.addValue("days", days);
            p.addValue("snapshot_boundary", snapshotBoundary);

            int deleted = jdbc.update(DELETE_SQL, p);
            LOG.info(
                    "EventRetentionJob: deleted {} event(s) for tenant {} (retention_days={})", deleted, modelId, days);
        }
    }
}
