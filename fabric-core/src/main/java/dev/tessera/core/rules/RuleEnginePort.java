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
package dev.tessera.core.rules;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fabric-core SPI that {@code fabric-rules} implements. Keeps fabric-core
 * free of any dependency on the rule engine — the dependency direction is
 * always {@code fabric-rules → fabric-core}, never the reverse. The rule
 * engine is wired in by Spring when both modules are on the classpath;
 * legacy test harnesses pre-W3 pass {@code null} and skip the rule phase.
 */
public interface RuleEnginePort {

    /**
     * Run the four-chain pipeline (VALIDATE → RECONCILE → ENRICH → ROUTE)
     * against the given context. Returns an {@link Outcome} carrying the
     * final property map, routing hints, conflict records, and (if
     * rejected) the rejection reason and rule id.
     */
    Outcome run(
            TenantContext tenantContext,
            NodeTypeDescriptor descriptor,
            Map<String, Object> currentProperties,
            Map<String, String> currentSourceSystem,
            GraphMutation mutation);

    /**
     * Called by {@code GraphServiceImpl} after a mutation is successfully
     * committed, so the rule engine can seed its own caches (e.g. the echo-
     * loop positive hit cache). Default is a no-op.
     */
    default void onCommitted(
            TenantContext tenantContext, UUID nodeUuid, String originConnectorId, String originChangeId) {}

    /** End-to-end pipeline result. */
    record Outcome(
            boolean rejected,
            String rejectReason,
            String rejectingRuleId,
            Map<String, Object> finalProperties,
            Map<String, Object> routingHints,
            List<ConflictEntry> conflicts) {
        public Outcome {
            finalProperties = finalProperties == null ? Map.of() : Map.copyOf(finalProperties);
            routingHints = routingHints == null ? Map.of() : Map.copyOf(routingHints);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    /** Per-property conflict decision (ADR-7 RECONCILE chain OVERRIDE outcome). */
    record ConflictEntry(
            String typeSlug,
            String propertySlug,
            String losingSourceId,
            String losingSourceSystem,
            Object losingValue,
            String winningSourceId,
            String winningSourceSystem,
            Object winningValue,
            String ruleId,
            String reason) {}
}
