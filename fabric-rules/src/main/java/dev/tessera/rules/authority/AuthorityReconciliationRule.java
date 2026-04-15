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
package dev.tessera.rules.authority;

import dev.tessera.rules.Chain;
import dev.tessera.rules.Rule;
import dev.tessera.rules.RuleContext;
import dev.tessera.rules.RuleOutcome;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Built-in RECONCILE-chain rule that resolves per-property conflicts using
 * the {@link SourceAuthorityMatrix} (D-C2). For each property in the incoming
 * mutation where:
 *
 * <ol>
 *   <li>a matrix row exists,
 *   <li>the current node already has a value,
 *   <li>the existing value came from a different source system,
 *   <li>the values differ,
 * </ol>
 *
 * <p>the rule emits {@link RuleOutcome.Override} either replacing the current
 * value with the incoming one (if incoming is higher-authority) or preserving
 * the current value and reporting the incoming as "losing" (if the current
 * source outranks the incoming).
 *
 * <p>Because a single {@link RuleOutcome} can only carry one property decision
 * at a time, the rule engine invokes {@link #evaluate(RuleContext)} once per
 * contested property — the {@link #applies(RuleContext)} check selects the
 * first contested property and {@link #evaluate(RuleContext)} resolves it.
 * Subsequent contested properties are handled via subsequent rule passes; for
 * the MVP we emit only the first contested decision per pipeline run, and
 * {@code ChainExecutor} accumulates the rest deterministically as more
 * {@code reconciliation_conflicts} rows can be recorded in follow-up plans.
 */
@Component
public class AuthorityReconciliationRule implements Rule {

    private final SourceAuthorityMatrix matrix;

    public AuthorityReconciliationRule(SourceAuthorityMatrix matrix) {
        this.matrix = matrix;
    }

    @Override
    public String id() {
        return "core.reconcile.source-authority";
    }

    @Override
    public Chain chain() {
        return Chain.RECONCILE;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean applies(RuleContext ctx) {
        return findFirstContested(ctx) != null;
    }

    @Override
    public RuleOutcome evaluate(RuleContext ctx) {
        Contested c = findFirstContested(ctx);
        if (c == null) {
            return RuleOutcome.Commit.INSTANCE;
        }
        UUID modelId = ctx.tenantContext().modelId();
        String typeSlug = ctx.mutation().type();
        int incomingRank =
                matrix.rank(modelId, typeSlug, c.property, ctx.mutation().sourceSystem());
        int currentRank = matrix.rank(modelId, typeSlug, c.property, c.currentSource);

        if (incomingRank < currentRank) {
            // Incoming wins — replace current value, record current source as losing.
            return new RuleOutcome.Override(c.property, c.incomingValue, c.currentSource, c.currentValue);
        } else {
            // Current keeps the value — incoming is reported as the losing value.
            return new RuleOutcome.Override(
                    c.property, c.currentValue, ctx.mutation().sourceSystem(), c.incomingValue);
        }
    }

    private Contested findFirstContested(RuleContext ctx) {
        if (ctx.mutation().payload() == null || ctx.mutation().payload().isEmpty()) {
            return null;
        }
        if (ctx.currentProperties().isEmpty()) {
            return null;
        }
        UUID modelId = ctx.tenantContext().modelId();
        String typeSlug = ctx.mutation().type();
        Map<String, String> sources = ctx.currentSourceSystem();
        for (Map.Entry<String, Object> e : ctx.mutation().payload().entrySet()) {
            String property = e.getKey();
            Object incoming = e.getValue();
            Object current = ctx.currentProperties().get(property);
            if (current == null || Objects.equals(current, incoming)) {
                continue;
            }
            if (!matrix.hasMatrixFor(modelId, typeSlug, property)) {
                continue;
            }
            String currentSource = sources.getOrDefault(property, "");
            if (currentSource.isEmpty() || currentSource.equals(ctx.mutation().sourceSystem())) {
                continue;
            }
            return new Contested(property, current, currentSource, incoming);
        }
        return null;
    }

    private record Contested(String property, Object currentValue, String currentSource, Object incomingValue) {}
}
