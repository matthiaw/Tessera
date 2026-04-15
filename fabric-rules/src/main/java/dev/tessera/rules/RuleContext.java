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
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.tenant.TenantContext;
import java.util.Map;

/**
 * Rule execution context per ADR-7 §RULE-02. Carries everything a rule needs
 * to make a decision without reaching back into Spring beans or DB state.
 *
 * <ul>
 *   <li>{@code tenantContext} — mandatory tenant scope.
 *   <li>{@code typeDescriptor} — loaded schema descriptor (may be null for
 *       unregistered types during bootstrap).
 *   <li>{@code currentProperties} — pre-mutation property map. Empty on CREATE.
 *   <li>{@code currentSourceSystem} — the source system that last wrote each
 *       current property (per-property map). Empty on CREATE. Used by the
 *       RECONCILE chain via {@link dev.tessera.rules.authority.SourceAuthorityMatrix}.
 *   <li>{@code mutation} — the incoming mutation with full provenance.
 * </ul>
 */
public record RuleContext(
        TenantContext tenantContext,
        NodeTypeDescriptor typeDescriptor,
        Map<String, Object> currentProperties,
        Map<String, String> currentSourceSystem,
        GraphMutation mutation) {

    public RuleContext {
        currentProperties = currentProperties == null ? Map.of() : Map.copyOf(currentProperties);
        currentSourceSystem = currentSourceSystem == null ? Map.of() : Map.copyOf(currentSourceSystem);
    }
}
