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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * CORE-02: the SOLE class in the codebase permitted to execute raw Cypher.
 * Enforced at build time by {@code RawCypherBanTest} in fabric-app which
 * forbids pgJDBC and Cypher string constants outside this package.
 *
 * <p>Wave 0 ships a skeleton only — Wave 1 (plan 01-W1-01) fills the
 * {@code apply} and {@code findNode} bodies with the actual Cypher + event
 * log append + outbox insert. The package-private constructor keeps this off
 * the Spring bean registry until that wave.
 */
public final class GraphSession {

    private final NamedParameterJdbcTemplate jdbc;

    GraphSession(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Wave 1 fills. */
    public NodeState apply(TenantContext ctx, GraphMutation mutation) {
        throw new UnsupportedOperationException("GraphSession.apply filled in plan 01-W1-01");
    }

    /** Wave 1 fills. */
    public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) {
        throw new UnsupportedOperationException("GraphSession.findNode filled in plan 01-W1-01");
    }
}
