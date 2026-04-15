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
package dev.tessera.rules.internal;

import dev.tessera.rules.Chain;
import dev.tessera.rules.ChainResult;
import dev.tessera.rules.ConflictRecord;
import dev.tessera.rules.Rule;
import dev.tessera.rules.RuleContext;
import dev.tessera.rules.RuleOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Chain-of-responsibility executor for a single {@link Chain}. Filters the
 * rule list to the target chain, sorts by {@link Rule#priority()} DESC per
 * ADR-7 §RULE-01 (higher runs first), and walks the chain applying outcomes:
 *
 * <ul>
 *   <li>{@link RuleOutcome.Commit} — continue
 *   <li>{@link RuleOutcome.Reject} — short-circuit with reason + rule id
 *   <li>{@link RuleOutcome.Merge} / {@link RuleOutcome.Override} /
 *       {@link RuleOutcome.Add} — accumulate into the mutable property map
 *       (starting from {@code ctx.mutation().payload()})
 *   <li>{@link RuleOutcome.Route} — accumulate into the routing hints map
 * </ul>
 *
 * <p>Pure function: no I/O, no DB access, no network. Rule hygiene test
 * enforces this at the package level.
 */
@Component
public class ChainExecutor {

    /**
     * Execute the given chain against the given rule list. Returns an
     * accumulated {@link ChainResult}. Starting properties come from the
     * caller-supplied {@code startingProperties} so that chains can be
     * threaded (VALIDATE → RECONCILE → ENRICH → ROUTE) with each seeing the
     * enriched state from the previous chain.
     */
    public ChainResult execute(Chain chain, List<Rule> rules, RuleContext ctx, Map<String, Object> startingProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(startingProperties);
        Map<String, Object> routingHints = new LinkedHashMap<>();
        List<ConflictRecord> conflicts = new ArrayList<>();

        List<Rule> scoped = rules.stream()
                .filter(r -> r.chain() == chain)
                .sorted(Comparator.comparingInt(Rule::priority).reversed()) // DESC per ADR-7 §RULE-01
                .toList();

        for (Rule rule : scoped) {
            if (!rule.applies(ctx)) {
                continue;
            }
            RuleOutcome outcome = rule.evaluate(ctx);
            if (outcome instanceof RuleOutcome.Commit) {
                continue;
            } else if (outcome instanceof RuleOutcome.Reject rej) {
                return ChainResult.rejected(rej.reason(), rule.id(), properties);
            } else if (outcome instanceof RuleOutcome.Merge merge) {
                properties.put(merge.propertySlug(), merge.value());
            } else if (outcome instanceof RuleOutcome.Override ov) {
                properties.put(ov.propertySlug(), ov.value());
                conflicts.add(new ConflictRecord(
                        ctx.mutation().type(),
                        ov.propertySlug(),
                        ctx.mutation().sourceId(),
                        ov.losingSourceSystem(),
                        ov.losingValue(),
                        ctx.mutation().sourceId(),
                        ctx.mutation().sourceSystem(),
                        ov.value(),
                        rule.id(),
                        "override by " + rule.id()));
            } else if (outcome instanceof RuleOutcome.Add add) {
                properties.putIfAbsent(add.propertySlug(), add.value());
            } else if (outcome instanceof RuleOutcome.Route route) {
                routingHints.putAll(route.routingHints());
            }
        }
        return ChainResult.ok(properties, routingHints, conflicts);
    }
}
