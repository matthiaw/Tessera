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
package dev.tessera.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.metrics.MetricsPort;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.internal.ChainExecutor;
import dev.tessera.rules.internal.RuleRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OPS-01: Unit tests for {@link RuleEngine} metrics integration.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>{@link MetricsPort#recordRuleEvaluation()} fires once per {@link RuleEngine#run(RuleContext)}
 *       call.</li>
 *   <li>{@link MetricsPort#recordConflict()} fires once per conflict in the engine result.</li>
 *   <li>When {@code metricsPort} is {@code null} (test fixtures outside Spring context),
 *       {@code run()} completes without throwing a NullPointerException.</li>
 * </ol>
 */
class RuleEngineMetricsTest {

    /** Simple stub that counts calls to each MetricsPort method. */
    static class RecordingMetricsPort implements MetricsPort {
        int ingestCount = 0;
        int ruleEvalCount = 0;
        int conflictCount = 0;
        long lastNanos = -1L;

        @Override
        public void recordIngest() {
            ingestCount++;
        }

        @Override
        public void recordRuleEvaluation() {
            ruleEvalCount++;
        }

        @Override
        public void recordConflict() {
            conflictCount++;
        }

        @Override
        public void recordShaclValidationNanos(long nanos) {
            lastNanos = nanos;
        }
    }

    private RuleRepository ruleRepository;
    private ChainExecutor chainExecutor;
    private EchoLoopSuppressionRule echoLoopSuppressionRule;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(RuleRepository.class);
        // Return empty list — no rules, so pipeline runs 4 chains cleanly, no conflicts
        when(ruleRepository.activeRulesFor(any(UUID.class))).thenReturn(List.of());

        chainExecutor = new ChainExecutor();
        echoLoopSuppressionRule = mock(EchoLoopSuppressionRule.class);
    }

    private RuleEngine newEngine() {
        return new RuleEngine(ruleRepository, chainExecutor, echoLoopSuppressionRule);
    }

    private RuleContext minimalContext() {
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(TenantContext.of(UUID.randomUUID()))
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Alice"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-1")
                .sourceSystem("crm")
                .confidence(BigDecimal.ONE)
                .build();
        return new RuleContext(mutation.tenantContext(), null, Map.of(), Map.of(), mutation);
    }

    @Test
    void run_records_rule_evaluation() {
        RuleEngine engine = newEngine();
        RecordingMetricsPort port = new RecordingMetricsPort();
        ReflectionTestUtils.setField(engine, "metricsPort", port);

        engine.run(minimalContext());

        assertThat(port.ruleEvalCount)
                .as("recordRuleEvaluation must be called exactly once per run(RuleContext) invocation")
                .isEqualTo(1);
    }

    @Test
    void run_with_conflicts_records_conflict_count() {
        // Arrange: add a RECONCILE rule that produces one Override conflict
        Rule conflictRule = new FakeOverrideRule(
                "conflict-rule", Chain.RECONCILE, 10, "email", "new@example.com", "obsidian", "old@example.com");
        when(ruleRepository.activeRulesFor(any(UUID.class))).thenReturn(List.of(conflictRule));

        RuleEngine engine = newEngine();
        RecordingMetricsPort port = new RecordingMetricsPort();
        ReflectionTestUtils.setField(engine, "metricsPort", port);

        engine.run(minimalContext());

        assertThat(port.conflictCount)
                .as("recordConflict must be called once per conflict produced by the RECONCILE chain")
                .isEqualTo(1);
    }

    @Test
    void null_metricsPort_does_not_throw() {
        RuleEngine engine = newEngine();
        // metricsPort field remains null — not injected outside Spring context

        assertThatCode(() -> engine.run(minimalContext()))
                .as("run() must succeed without NPE when metricsPort is null")
                .doesNotThrowAnyException();
    }

    /** Minimal Rule stub that always emits a single Override outcome on evaluate(). */
    private static final class FakeOverrideRule implements Rule {
        private final String id;
        private final Chain chain;
        private final int priority;
        private final String property;
        private final String winningValue;
        private final String losingSourceSystem;
        private final String losingValue;

        FakeOverrideRule(
                String id,
                Chain chain,
                int priority,
                String property,
                String winningValue,
                String losingSourceSystem,
                String losingValue) {
            this.id = id;
            this.chain = chain;
            this.priority = priority;
            this.property = property;
            this.winningValue = winningValue;
            this.losingSourceSystem = losingSourceSystem;
            this.losingValue = losingValue;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Chain chain() {
            return chain;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean applies(RuleContext ctx) {
            return true;
        }

        @Override
        public RuleOutcome evaluate(RuleContext ctx) {
            return new RuleOutcome.Override(property, winningValue, losingSourceSystem, losingValue);
        }
    }
}
