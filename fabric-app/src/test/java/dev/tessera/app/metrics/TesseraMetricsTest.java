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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OPS-01: Unit tests for TesseraMetrics — Prometheus metric registration.
 *
 * <p>Uses a {@link SimpleMeterRegistry} (no Spring context needed) to verify all 6 meters
 * are registered correctly and increment methods work as expected.
 */
class TesseraMetricsTest {

    private SimpleMeterRegistry registry;
    private TesseraMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Pass null for NamedParameterJdbcTemplate — gauge lambdas null-guard and return 0
        metrics = new TesseraMetrics(registry, null);
    }

    // --- Counter registration tests ---

    @Test
    void ingestRateCounterIsRegistered() {
        Counter counter = registry.find("tessera.ingest.rate").counter();
        assertThat(counter).as("tessera.ingest.rate counter must be registered").isNotNull();
    }

    @Test
    void ruleEvaluationsCounterIsRegistered() {
        Counter counter = registry.find("tessera.rules.evaluations").counter();
        assertThat(counter)
                .as("tessera.rules.evaluations counter must be registered")
                .isNotNull();
    }

    @Test
    void conflictsCounterIsRegistered() {
        Counter counter = registry.find("tessera.conflicts.count").counter();
        assertThat(counter)
                .as("tessera.conflicts.count counter must be registered")
                .isNotNull();
    }

    // --- Gauge registration tests ---

    @Test
    void outboxLagGaugeIsRegistered() {
        Gauge gauge = registry.find("tessera.outbox.lag").gauge();
        assertThat(gauge).as("tessera.outbox.lag gauge must be registered").isNotNull();
    }

    @Test
    void replicationSlotLagGaugeIsRegistered() {
        Gauge gauge = registry.find("tessera.replication.slot.lag").gauge();
        assertThat(gauge)
                .as("tessera.replication.slot.lag gauge must be registered")
                .isNotNull();
    }

    // --- Increment method tests ---

    @Test
    void recordIngestIncrementsCounter() {
        double before = registry.find("tessera.ingest.rate").counter().count();
        metrics.recordIngest();
        double after = registry.find("tessera.ingest.rate").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void recordRuleEvaluationIncrementsCounter() {
        double before = registry.find("tessera.rules.evaluations").counter().count();
        metrics.recordRuleEvaluation();
        double after = registry.find("tessera.rules.evaluations").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    void recordConflictIncrementsCounter() {
        double before = registry.find("tessera.conflicts.count").counter().count();
        metrics.recordConflict();
        double after = registry.find("tessera.conflicts.count").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    // --- Timer registration test ---

    @Test
    void shaclTimerIsRegistered() {
        Timer timer = metrics.shaclTimer();
        assertThat(timer).as("shaclTimer() must return a registered Timer").isNotNull();
        assertThat(registry.find("tessera.shacl.validation.time").timer())
                .as("tessera.shacl.validation.time must be in the registry")
                .isNotNull();
    }
}
