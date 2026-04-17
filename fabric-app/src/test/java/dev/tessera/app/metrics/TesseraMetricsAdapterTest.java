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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OPS-01: Unit tests for {@link TesseraMetricsAdapter} — verifies that all
 * 4 {@link dev.tessera.core.metrics.MetricsPort} methods delegate correctly
 * to the underlying {@link TesseraMetrics} Micrometer bean.
 *
 * <p>Uses a {@link SimpleMeterRegistry} (no Spring context needed).
 */
class TesseraMetricsAdapterTest {

    private SimpleMeterRegistry registry;
    private TesseraMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Pass null for NamedParameterJdbcTemplate — gauge lambdas null-guard and return 0
        TesseraMetrics metrics = new TesseraMetrics(registry, null);
        adapter = new TesseraMetricsAdapter(metrics);
    }

    @Test
    void recordIngest_delegates_to_ingest_counter() {
        double before = registry.find("tessera.ingest.rate").counter().count();
        adapter.recordIngest();
        double after = registry.find("tessera.ingest.rate").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void recordRuleEvaluation_delegates_to_evaluations_counter() {
        double before = registry.find("tessera.rules.evaluations").counter().count();
        adapter.recordRuleEvaluation();
        double after = registry.find("tessera.rules.evaluations").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void recordConflict_delegates_to_conflicts_counter() {
        double before = registry.find("tessera.conflicts.count").counter().count();
        adapter.recordConflict();
        double after = registry.find("tessera.conflicts.count").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void recordShaclValidationNanos_records_one_timer_observation() {
        long before = registry.find("tessera.shacl.validation.time").timer().count();
        adapter.recordShaclValidationNanos(500_000L);
        long after = registry.find("tessera.shacl.validation.time").timer().count();
        assertThat(after - before).isEqualTo(1L);
    }
}
