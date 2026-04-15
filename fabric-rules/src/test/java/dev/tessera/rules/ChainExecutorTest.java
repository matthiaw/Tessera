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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.internal.ChainExecutor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RULE-01: priority-sorted chain-of-responsibility executor. Tests locked to
 * ADR-7 §RULE-01 DESC sort (higher priority runs first) and the short-circuit
 * contract on {@link RuleOutcome.Reject}.
 */
class ChainExecutorTest {

    private ChainExecutor executor;
    private RuleContext ctx;

    @BeforeEach
    void setUp() {
        executor = new ChainExecutor();
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(TenantContext.of(UUID.randomUUID()))
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "seed"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-1")
                .sourceSystem("crm")
                .confidence(BigDecimal.ONE)
                .build();
        ctx = new RuleContext(mutation.tenantContext(), null, Map.of(), Map.of(), mutation);
    }

    @Test
    void empty_rule_list_returns_unchanged_starting_properties() {
        ChainResult r = executor.execute(Chain.VALIDATE, List.of(), ctx, Map.of("k", "v"));
        assertThat(r.rejected()).isFalse();
        assertThat(r.properties()).containsEntry("k", "v");
    }

    @Test
    void filters_by_chain_and_ignores_other_chain_rules() {
        List<String> executionOrder = new ArrayList<>();
        Rule validateRule = trackingRule("v1", Chain.VALIDATE, 10, executionOrder);
        Rule reconcileRule = trackingRule("r1", Chain.RECONCILE, 10, executionOrder);

        executor.execute(Chain.VALIDATE, List.of(validateRule, reconcileRule), ctx, Map.of());

        assertThat(executionOrder).containsExactly("v1");
    }

    @Test
    void sorts_by_priority_DESC_higher_first_per_ADR_7() {
        List<String> executionOrder = new ArrayList<>();
        Rule lowPrio = trackingRule("low", Chain.VALIDATE, 10, executionOrder);
        Rule highPrio = trackingRule("high", Chain.VALIDATE, 100, executionOrder);
        Rule midPrio = trackingRule("mid", Chain.VALIDATE, 50, executionOrder);

        executor.execute(Chain.VALIDATE, List.of(lowPrio, highPrio, midPrio), ctx, Map.of());

        assertThat(executionOrder).containsExactly("high", "mid", "low");
    }

    @Test
    void reject_short_circuits_and_later_rules_do_not_run() {
        List<String> executionOrder = new ArrayList<>();
        Rule rejectRule = new FakeRule(
                "reject-me", Chain.VALIDATE, 100, ctx1 -> true, ctx1 -> new RuleOutcome.Reject("boom"), executionOrder);
        Rule commitRule = trackingRule("never", Chain.VALIDATE, 10, executionOrder);

        ChainResult r = executor.execute(Chain.VALIDATE, List.of(rejectRule, commitRule), ctx, Map.of());

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectReason()).isEqualTo("boom");
        assertThat(r.rejectingRuleId()).isEqualTo("reject-me");
        assertThat(executionOrder).containsExactly("reject-me");
    }

    @Test
    void skips_rules_whose_applies_returns_false() {
        List<String> executionOrder = new ArrayList<>();
        Rule skipped = new FakeRule(
                "skipped", Chain.VALIDATE, 100, ctx1 -> false, ctx1 -> RuleOutcome.Commit.INSTANCE, executionOrder);
        Rule runs = trackingRule("runs", Chain.VALIDATE, 10, executionOrder);

        executor.execute(Chain.VALIDATE, List.of(skipped, runs), ctx, Map.of());

        assertThat(executionOrder).containsExactly("runs");
    }

    @Test
    void enrich_add_accumulates_derived_property() {
        Rule add = new FakeRule(
                "enricher", Chain.ENRICH, 10, c -> true, c -> new RuleOutcome.Add("derived", "yes"), new ArrayList<>());

        ChainResult r = executor.execute(Chain.ENRICH, List.of(add), ctx, Map.of("name", "Alice"));

        assertThat(r.rejected()).isFalse();
        assertThat(r.properties()).containsEntry("name", "Alice").containsEntry("derived", "yes");
    }

    @Test
    void merge_overwrites_property_and_reconcile_override_emits_conflict() {
        Rule merge = new FakeRule(
                "merger",
                Chain.RECONCILE,
                20,
                c -> true,
                c -> new RuleOutcome.Merge("email", "winner@example.com"),
                new ArrayList<>());
        Rule override = new FakeRule(
                "override",
                Chain.RECONCILE,
                10,
                c -> true,
                c -> new RuleOutcome.Override("phone", "+1-555", "obsidian", "+1-000"),
                new ArrayList<>());

        ChainResult r = executor.execute(Chain.RECONCILE, List.of(merge, override), ctx, Map.of("name", "Alice"));

        assertThat(r.properties()).containsEntry("email", "winner@example.com");
        assertThat(r.properties()).containsEntry("phone", "+1-555");
        assertThat(r.conflicts()).hasSize(1);
        ConflictRecord conflict = r.conflicts().get(0);
        assertThat(conflict.propertySlug()).isEqualTo("phone");
        assertThat(conflict.losingSourceSystem()).isEqualTo("obsidian");
        assertThat(conflict.losingValue()).isEqualTo("+1-000");
        assertThat(conflict.winningValue()).isEqualTo("+1-555");
        assertThat(conflict.ruleId()).isEqualTo("override");
    }

    @Test
    void incoming_wins_branch_labels_winning_source_system_from_mutation() {
        // 02-W0 Task 2: pin the incoming-wins labelling (01-VERIFICATION
        // Known Deviation #2). losingSourceSystem != mutation.sourceSystem()
        // -> the incoming write won -> winningSourceSystem must come from the
        // mutation's source system.
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(TenantContext.of(UUID.randomUUID()))
                .operation(Operation.UPDATE)
                .type("Person")
                .targetNodeUuid(UUID.randomUUID())
                .payload(Map.of("status", "VALUE_FROM_A"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-A")
                .sourceSystem("A")
                .confidence(BigDecimal.ONE)
                .build();
        RuleContext localCtx = new RuleContext(
                mutation.tenantContext(), null, Map.of("status", "VALUE_FROM_B"), Map.of("status", "B"), mutation);
        Rule override = new FakeRule(
                "incoming-wins",
                Chain.RECONCILE,
                10,
                c -> true,
                // losingSourceSystem = "B" (the current), not the mutation source -> incoming wins
                c -> new RuleOutcome.Override("status", "VALUE_FROM_A", "B", "VALUE_FROM_B"),
                new ArrayList<>());

        ChainResult r = executor.execute(Chain.RECONCILE, List.of(override), localCtx, Map.of());

        assertThat(r.conflicts()).hasSize(1);
        ConflictRecord c = r.conflicts().get(0);
        assertThat(c.winningSourceSystem())
                .as("incoming-wins branch must label winning source system from mutation")
                .isEqualTo("A");
        assertThat(c.losingSourceSystem()).isEqualTo("B");
        assertThat(c.winningValue()).isEqualTo("VALUE_FROM_A");
        assertThat(c.losingValue()).isEqualTo("VALUE_FROM_B");
    }

    @Test
    void current_keeps_branch_labels_winning_source_system_from_current_source_map() {
        // 02-W0 Task 2: pin the current-keeps labelling (01-VERIFICATION
        // Known Deviation #2). losingSourceSystem == mutation.sourceSystem()
        // -> the incoming write lost -> winningSourceSystem must come from
        // ctx.currentSourceSystem() for the contested property, NOT from the
        // mutation source.
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(TenantContext.of(UUID.randomUUID()))
                .operation(Operation.UPDATE)
                .type("Person")
                .targetNodeUuid(UUID.randomUUID())
                .payload(Map.of("status", "VALUE_FROM_B"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-B")
                .sourceSystem("B")
                .confidence(BigDecimal.ONE)
                .build();
        RuleContext localCtx = new RuleContext(
                mutation.tenantContext(), null, Map.of("status", "VALUE_FROM_A"), Map.of("status", "A"), mutation);
        Rule override = new FakeRule(
                "current-keeps",
                Chain.RECONCILE,
                10,
                c -> true,
                // losingSourceSystem = "B" (the mutation source) -> current keeps
                c -> new RuleOutcome.Override("status", "VALUE_FROM_A", "B", "VALUE_FROM_B"),
                new ArrayList<>());

        ChainResult r = executor.execute(Chain.RECONCILE, List.of(override), localCtx, Map.of());

        assertThat(r.conflicts()).hasSize(1);
        ConflictRecord c = r.conflicts().get(0);
        assertThat(c.winningSourceSystem())
                .as("current-keeps branch must label winning source system from ctx.currentSourceSystem")
                .isEqualTo("A");
        assertThat(c.losingSourceSystem()).isEqualTo("B");
        assertThat(c.winningValue()).isEqualTo("VALUE_FROM_A");
        assertThat(c.losingValue()).isEqualTo("VALUE_FROM_B");
    }

    @Test
    void route_accumulates_routing_hints() {
        Rule routeRule = new FakeRule(
                "router",
                Chain.ROUTE,
                10,
                c -> true,
                c -> new RuleOutcome.Route(Map.of("topic", "tenants-a")),
                new ArrayList<>());

        ChainResult r = executor.execute(Chain.ROUTE, List.of(routeRule), ctx, Map.of());

        assertThat(r.routingHints()).containsEntry("topic", "tenants-a");
    }

    // -- helpers --

    private Rule trackingRule(String id, Chain chain, int priority, List<String> order) {
        return new FakeRule(id, chain, priority, ctx1 -> true, ctx1 -> RuleOutcome.Commit.INSTANCE, order);
    }

    private static final class FakeRule implements Rule {
        private final String id;
        private final Chain chain;
        private final int priority;
        private final java.util.function.Predicate<RuleContext> applies;
        private final java.util.function.Function<RuleContext, RuleOutcome> evaluator;
        private final List<String> trace;

        FakeRule(
                String id,
                Chain chain,
                int priority,
                java.util.function.Predicate<RuleContext> applies,
                java.util.function.Function<RuleContext, RuleOutcome> evaluator,
                List<String> trace) {
            this.id = id;
            this.chain = chain;
            this.priority = priority;
            this.applies = applies;
            this.evaluator = evaluator;
            this.trace = trace;
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
            return applies.test(ctx);
        }

        @Override
        public RuleOutcome evaluate(RuleContext ctx) {
            trace.add(id);
            return evaluator.apply(ctx);
        }
    }
}
