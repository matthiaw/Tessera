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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1 / Wave 2 / 01-W2-03 (EVENT-05): in-process outbox poller.
 *
 * <p>Runs every 500 ms, picks up to {@value #BATCH_SIZE} {@code PENDING}
 * {@code graph_outbox} rows using {@code FOR UPDATE SKIP LOCKED}, publishes
 * each row to Spring's {@link ApplicationEventPublisher} as a
 * {@link GraphEventPublished}, and marks the row {@code DELIVERED} — all
 * inside the same poll-batch transaction.
 *
 * <p><strong>Distributed locking.</strong> {@code @SchedulerLock} (ShedLock 5.x
 * via {@link dev.tessera.core.events.internal.LockProviderConfig}) prevents
 * two JVMs from racing the same rows. The {@code FOR UPDATE SKIP LOCKED}
 * clause is a belt-and-braces guarantee inside a single JVM: a slow poll still
 * cannot be double-processed by a second tick because the row is row-locked
 * until the TX commits.
 *
 * <p><strong>Delivery semantics.</strong> At-least-once — listeners must be
 * idempotent (see Javadoc on {@link GraphEventPublished}). If any listener
 * throws the whole poll-batch rolls back and every row in the batch is
 * re-polled on the next tick.
 *
 * <p><strong>Routing hints plumbing.</strong> The row mapper explicitly reads
 * the {@code routing_hints} JSONB column via {@code rs.getString("routing_hints")}
 * and hydrates it onto the published record. Dropping this column here would
 * silently break Phase 2+ projection routing.
 *
 * <p>NOTE on design: an alternative is
 * {@code @TransactionalEventListener(phase=AFTER_COMMIT)} inside the write TX;
 * we chose polling so the write path stays ignorant of delivery and so Phase 4
 * can swap this poller for Debezium without touching {@code GraphServiceImpl}.
 */
@Component
public class OutboxPoller {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Note: the literal "FOR UPDATE SKIP LOCKED" and "routing_hints" below are
    // asserted by grep in 01-W2-03 acceptance criteria — do not reformat.
    private static final String SELECT_SQL = "SELECT id, model_id, event_id, aggregatetype, aggregateid, type, "
            + "payload, routing_hints, created_at "
            + "FROM graph_outbox WHERE status = 'PENDING' "
            + "ORDER BY created_at LIMIT " + BATCH_SIZE + " "
            + "FOR UPDATE SKIP LOCKED";

    private static final String MARK_DELIVERED_SQL =
            "UPDATE graph_outbox SET status = 'DELIVERED', delivered_at = clock_timestamp() " + "WHERE id = :id::uuid";

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;

    public OutboxPoller(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    /** Poll tick — fresh TX per batch. */
    @Scheduled(fixedDelay = 500)
    @SchedulerLock(name = "tessera-outbox-poller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0.1S")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void poll() {
        List<PolledRow> pending = jdbc.query(SELECT_SQL, new MapSqlParameterSource(), ROW_MAPPER);
        if (pending.isEmpty()) {
            return;
        }
        for (PolledRow row : pending) {
            try {
                publisher.publishEvent(row.event());
            } catch (RuntimeException e) {
                LOG.error(
                        "Outbox listener threw for event {} — rolling back batch",
                        row.event().eventId(),
                        e);
                throw e;
            }
            jdbc.update(MARK_DELIVERED_SQL, new MapSqlParameterSource("id", row.outboxId()));
        }
        LOG.debug("Outbox poll delivered {} row(s)", pending.size());
    }

    /** Internal carrier binding outbox-row PK to the public event record. */
    private record PolledRow(String outboxId, GraphEventPublished event) {}

    private static final RowMapper<PolledRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        String outboxId = rs.getString("id");
        UUID modelId = UUID.fromString(rs.getString("model_id"));
        UUID eventId = UUID.fromString(rs.getString("event_id"));
        String aggregateType = rs.getString("aggregatetype");
        UUID aggregateId = UUID.fromString(rs.getString("aggregateid"));
        String type = rs.getString("type");
        Map<String, Object> payload = parseJson(rs.getString("payload"));
        // EXPLICIT routing_hints column mapping — required by 01-W2-03 acceptance.
        Map<String, Object> routingHints = parseJson(rs.getString("routing_hints"));
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        GraphEventPublished event = new GraphEventPublished(
                modelId, eventId, aggregateType, aggregateId, type, payload, routingHints, createdAt);
        return new PolledRow(outboxId, event);
    };

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse JSONB column: " + json, e);
        }
    }
}
