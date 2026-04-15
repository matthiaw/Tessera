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
package dev.tessera.core.events.internal;

import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EVENT-01 supporting infra: hand-rolled monthly partition creator for
 * {@code graph_events}. Per RESEARCH §"Open Questions Q2 RESOLVED" this is the
 * deliberately-chosen alternative to {@code pg_partman} — keeping the AGE
 * Postgres image free of an extra extension (CRIT-1/2 surface containment).
 *
 * <p>Runs weekly (Sundays at 02:00) so a missed week never strands writes at
 * the month boundary; partition creation is idempotent
 * ({@code CREATE TABLE IF NOT EXISTS}) so multiple instances racing the cron
 * is safe and ShedLock is intentionally NOT used here. The Outbox poller (Wave
 * 2 Task 3) is where ShedLock matters.
 *
 * <p>{@link #createPartitionFor(YearMonth)} is exposed publicly so integration
 * tests can drive the logic directly without waiting for the cron firing.
 */
@Component
public final class PartitionMaintenanceTask {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionMaintenanceTask.class);

    private final JdbcTemplate jdbc;

    public PartitionMaintenanceTask(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc.getJdbcTemplate();
    }

    /** Weekly Sunday 02:00. */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void ensureNextMonthPartition() {
        YearMonth next = YearMonth.now().plusMonths(1);
        createPartitionFor(next);
    }

    /**
     * Idempotently create the {@code graph_events_yYYYYmMM} partition that
     * covers {@code [first-of-month, first-of-next-month)} for {@code ym}.
     */
    public void createPartitionFor(YearMonth ym) {
        String name = partitionName(ym);
        String from = ym.atDay(1).toString(); // YYYY-MM-01
        String to = ym.plusMonths(1).atDay(1).toString();
        String ddl = "CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF graph_events" + " FOR VALUES FROM ('" + from
                + "') TO ('" + to + "')";
        LOG.info("Ensuring graph_events partition {} for [{}, {})", name, from, to);
        jdbc.execute(ddl);
    }

    /** Pure helper: deterministic partition name for a given month. */
    public static String partitionName(YearMonth ym) {
        return String.format("graph_events_y%04dm%02d", ym.getYear(), ym.getMonthValue());
    }
}
