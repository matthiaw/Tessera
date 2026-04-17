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
package dev.tessera.app.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * OPS-01 / D-B1: Central Micrometer metric registration bean for Tessera.
 *
 * <p>Registers the following meters on construction:
 *
 * <ul>
 *   <li>{@code tessera.ingest.rate} — Counter, tag {@code source=connector}: incremented by
 *       connectors on each reconciled entity write.
 *   <li>{@code tessera.rules.evaluations} — Counter: incremented on each rule-chain evaluation.
 *   <li>{@code tessera.conflicts.count} — Counter: incremented each time a reconciliation conflict
 *       is detected.
 *   <li>{@code tessera.outbox.lag} — Gauge: polls {@code graph_outbox} for pending event count on
 *       each Prometheus scrape.
 *   <li>{@code tessera.replication.slot.lag} — Gauge: polls {@code pg_replication_slots} for WAL
 *       lag bytes; returns {@code -1} when the slot does not exist.
 *   <li>{@code tessera.shacl.validation.time} — Timer: used by callers via {@link #shaclTimer()} to
 *       record SHACL validation duration.
 * </ul>
 *
 * <p>Gauge lambdas null-guard on {@link NamedParameterJdbcTemplate}: when the template is
 * {@code null} (unit tests without a database) they return {@code 0} / {@code -1} gracefully.
 */
@Component
public class TesseraMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(TesseraMetrics.class);

    // Metric names — load-bearing strings referenced by acceptance criteria
    static final String METRIC_INGEST_RATE = "tessera.ingest.rate";
    static final String METRIC_RULES_EVALUATIONS = "tessera.rules.evaluations";
    static final String METRIC_CONFLICTS_COUNT = "tessera.conflicts.count";
    static final String METRIC_OUTBOX_LAG = "tessera.outbox.lag";
    static final String METRIC_REPLICATION_SLOT_LAG = "tessera.replication.slot.lag";
    static final String METRIC_SHACL_VALIDATION_TIME = "tessera.shacl.validation.time";

    private static final String OUTBOX_LAG_QUERY = "SELECT COUNT(*) FROM graph_outbox WHERE status = 'PENDING'";

    private static final String REPLICATION_SLOT_LAG_QUERY =
            """
            SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)
            FROM pg_replication_slots
            WHERE slot_name = 'tessera_outbox_slot'
            """;

    private final Counter ingestRateCounter;
    private final Counter ruleEvaluationsCounter;
    private final Counter conflictsCounter;
    private final Timer shaclValidationTimer;

    /**
     * Constructs and registers all 6 Micrometer meters.
     *
     * @param registry the MeterRegistry provided by Spring Boot Actuator / Prometheus auto-config
     * @param jdbc the JDBC template used for gauge DB polling; may be {@code null} in unit tests
     */
    public TesseraMetrics(MeterRegistry registry, NamedParameterJdbcTemplate jdbc) {
        // --- Counters ---
        this.ingestRateCounter = Counter.builder(METRIC_INGEST_RATE)
                .description("Number of entity ingest events processed by connectors")
                .tag("source", "connector")
                .register(registry);

        this.ruleEvaluationsCounter = Counter.builder(METRIC_RULES_EVALUATIONS)
                .description("Number of rule-chain evaluations performed")
                .register(registry);

        this.conflictsCounter = Counter.builder(METRIC_CONFLICTS_COUNT)
                .description("Number of reconciliation conflicts detected")
                .register(registry);

        // --- Gauges (DB polling) ---
        Gauge.builder(METRIC_OUTBOX_LAG, jdbc, template -> {
                    if (template == null) {
                        return 0.0;
                    }
                    try {
                        Long count = template.queryForObject(OUTBOX_LAG_QUERY, Collections.emptyMap(), Long.class);
                        return count != null ? count.doubleValue() : 0.0;
                    } catch (RuntimeException ex) {
                        LOG.debug("outbox lag gauge query failed: {}", ex.getMessage());
                        return 0.0;
                    }
                })
                .description("Number of pending events in graph_outbox (PENDING status)")
                .register(registry);

        Gauge.builder(METRIC_REPLICATION_SLOT_LAG, jdbc, template -> {
                    if (template == null) {
                        return -1.0;
                    }
                    try {
                        List<Long> rows = template.queryForList(
                                REPLICATION_SLOT_LAG_QUERY, new MapSqlParameterSource(), Long.class);
                        return rows.isEmpty() ? -1.0 : rows.get(0).doubleValue();
                    } catch (RuntimeException ex) {
                        LOG.debug("replication slot lag gauge query failed: {}", ex.getMessage());
                        return -1.0;
                    }
                })
                .description("WAL bytes between current write position and Debezium confirmed flush; -1 if slot absent")
                .register(registry);

        // --- Timer ---
        this.shaclValidationTimer = Timer.builder(METRIC_SHACL_VALIDATION_TIME)
                .description("Duration of SHACL shape validation per entity mutation")
                .register(registry);
    }

    // --- Public increment methods ---

    /** Increments the {@code tessera.ingest.rate} counter by 1. */
    public void recordIngest() {
        ingestRateCounter.increment();
    }

    /** Increments the {@code tessera.rules.evaluations} counter by 1. */
    public void recordRuleEvaluation() {
        ruleEvaluationsCounter.increment();
    }

    /** Increments the {@code tessera.conflicts.count} counter by 1. */
    public void recordConflict() {
        conflictsCounter.increment();
    }

    /**
     * Returns the registered SHACL validation timer. Callers use this to wrap their validation
     * code with {@code shaclTimer().record(() -> validate(...))}.
     *
     * @return the registered {@code tessera.shacl.validation.time} Timer
     */
    public Timer shaclTimer() {
        return shaclValidationTimer;
    }
}
