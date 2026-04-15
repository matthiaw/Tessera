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
package dev.tessera.core.graph;

/**
 * The single write funnel into the Tessera graph (CORE-01). Every caller
 * outside {@code fabric-core} — connectors, projections, rule tests, the
 * REST layer (Phase 2) and MCP (Phase 3) — reaches the graph through this
 * interface and nothing else.
 *
 * <p>The implementation is {@code @Transactional}: rules, SHACL validation,
 * Cypher write, event log append, and outbox insert all happen in one
 * Postgres transaction. Rollback rolls every side-effect.
 */
public interface GraphService {

    /**
     * Validate, reconcile, enrich, route, and persist a mutation. Returns a
     * {@link GraphMutationOutcome.Committed} on success or
     * {@link GraphMutationOutcome.Rejected} when a rule or SHACL shape vetoes.
     */
    GraphMutationOutcome apply(GraphMutation mutation);
}
