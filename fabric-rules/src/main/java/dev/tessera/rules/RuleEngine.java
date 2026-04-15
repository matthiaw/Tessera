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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.rules.RuleEnginePort;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.internal.ChainExecutor;
import dev.tessera.rules.internal.RuleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Four-chain pipeline facade per ADR-7 §RULE-02 / CONTEXT §D-C1. Runs
 * VALIDATE → RECONCILE → ENRICH → ROUTE in fixed order, threading enriched
 * property state between chains. A VALIDATE-chain Reject short-circuits the
 * pipeline; later chains do not run.
 *
 * <p>Implements the fabric-core {@link RuleEnginePort} SPI so
 * {@code GraphServiceImpl} can call the engine without fabric-core depending
 * on fabric-rules (the dependency direction stays
 * {@code fabric-rules → fabric-core}).
 */
@Service
public class RuleEngine implements RuleEnginePort {

    private final RuleRepository ruleRepository;
    private final ChainExecutor chainExecutor;
    private final EchoLoopSuppressionRule echoLoopSuppressionRule;

    public RuleEngine(
            RuleRepository ruleRepository,
            ChainExecutor chainExecutor,
            EchoLoopSuppressionRule echoLoopSuppressionRule) {
        this.ruleRepository = ruleRepository;
        this.chainExecutor = chainExecutor;
        this.echoLoopSuppressionRule = echoLoopSuppressionRule;
    }

    /** Native pipeline run over a {@link RuleContext}. Tests and {@link #run(TenantContext, NodeTypeDescriptor, Map, Map, GraphMutation)} share this. */
    public EngineResult run(RuleContext ctx) {
        List<Rule> rules = ruleRepository.activeRulesFor(ctx.tenantContext().modelId());

        Map<String, Object> properties = new LinkedHashMap<>(ctx.mutation().payload());
        Map<String, Object> routingHints = new LinkedHashMap<>();
        List<ConflictRecord> conflicts = new ArrayList<>();

        ChainResult validate = chainExecutor.execute(Chain.VALIDATE, rules, ctx, properties);
        if (validate.rejected()) {
            return new EngineResult(
                    true, validate.rejectReason(), validate.rejectingRuleId(), properties, Map.of(), List.of());
        }
        properties = new LinkedHashMap<>(validate.properties());

        ChainResult reconcile = chainExecutor.execute(Chain.RECONCILE, rules, ctx, properties);
        properties = new LinkedHashMap<>(reconcile.properties());
        conflicts.addAll(reconcile.conflicts());
        routingHints.putAll(reconcile.routingHints());

        ChainResult enrich = chainExecutor.execute(Chain.ENRICH, rules, ctx, properties);
        properties = new LinkedHashMap<>(enrich.properties());
        routingHints.putAll(enrich.routingHints());

        ChainResult route = chainExecutor.execute(Chain.ROUTE, rules, ctx, properties);
        properties = new LinkedHashMap<>(route.properties());
        routingHints.putAll(route.routingHints());

        return new EngineResult(false, null, null, properties, routingHints, conflicts);
    }

    // ------------------------------------------------------------------
    // RuleEnginePort SPI bridge (fabric-core → fabric-rules)
    // ------------------------------------------------------------------

    @Override
    public Outcome run(
            TenantContext tenantContext,
            NodeTypeDescriptor descriptor,
            Map<String, Object> currentProperties,
            Map<String, String> currentSourceSystem,
            GraphMutation mutation) {
        RuleContext ctx = new RuleContext(tenantContext, descriptor, currentProperties, currentSourceSystem, mutation);
        EngineResult er = run(ctx);
        List<ConflictEntry> entries = er.conflicts().stream()
                .map(c -> new ConflictEntry(
                        c.typeSlug(),
                        c.propertySlug(),
                        c.losingSourceId(),
                        c.losingSourceSystem(),
                        c.losingValue(),
                        c.winningSourceId(),
                        c.winningSourceSystem(),
                        c.winningValue(),
                        c.ruleId(),
                        c.reason()))
                .toList();
        return new Outcome(
                er.rejected(),
                er.rejectReason(),
                er.rejectingRuleId(),
                er.finalProperties(),
                er.routingHints(),
                entries);
    }

    @Override
    public void onCommitted(TenantContext ctx, UUID nodeUuid, String originConnectorId, String originChangeId) {
        echoLoopSuppressionRule.markRecorded(ctx.modelId(), originConnectorId, originChangeId);
    }
}
