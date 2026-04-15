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
package dev.tessera.core.graph.internal;

import dev.tessera.core.tenant.TenantContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Parameterized Cypher template bound to a single AGE graph name. Follows the
 * text-cast agtype idiom A from 01-RESEARCH.md §"Agtype Parameter Binding"
 * (proven by Phase 0 AgtypeParameterIT). Every {@link #forTenant} invocation
 * guarantees {@code model_id} is injected into the params map — a compile-time
 * safeguard that no tenant-less Cypher escapes {@code graph.internal}.
 *
 * <p>Residing inside {@code dev.tessera.core.graph.internal}, this class is
 * the only place in the codebase permitted to hold Cypher string constants
 * (enforced by CORE-02 / RawCypherBanTest).
 */
public record CypherTemplate(String graphName, String cypher, Map<String, Object> params) {

    public CypherTemplate {
        params = Map.copyOf(params);
    }

    /**
     * Build a tenant-scoped template. {@code model_id} is always added to
     * {@code params} — callers must not override it.
     */
    public static CypherTemplate forTenant(
            TenantContext ctx, String graphName, String cypher, Map<String, Object> extraParams) {
        Map<String, Object> merged = new HashMap<>(extraParams == null ? Map.of() : extraParams);
        merged.put("model_id", ctx.modelId().toString());
        return new CypherTemplate(graphName, cypher, merged);
    }
}
