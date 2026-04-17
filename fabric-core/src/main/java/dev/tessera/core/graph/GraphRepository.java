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

import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-aware read entry point to the graph. {@link TenantContext} is a
 * mandatory explicit first parameter on every call (CORE-03, D-16) — never a
 * ThreadLocal, never a session-scoped bean.
 */
public interface GraphRepository {

    Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid);

    List<NodeState> queryAll(TenantContext ctx, String typeSlug);

    /**
     * Cursor-paginated read: returns up to {@code limit} nodes of the given
     * type whose {@code _seq} is strictly greater than {@code afterSeq},
     * ordered by {@code _seq} ascending. Used by the REST projection
     * dispatcher for stable cursor pagination (W2a).
     */
    List<NodeState> queryAllAfter(TenantContext ctx, String typeSlug, long afterSeq, int limit);

    /**
     * Single-node lookup by UUID with tenant filter. Alias for
     * {@link #findNode} — exists as a semantic marker for the REST
     * projection dispatcher (W2a).
     */
    default Optional<NodeState> queryById(TenantContext ctx, String typeSlug, UUID nodeId) {
        return findNode(ctx, typeSlug, nodeId);
    }

    /**
     * Execute a tenant-scoped read-only Cypher query. The implementation MUST
     * inject {@code model_id} filtering and MUST reject mutation keywords
     * (DELETE, CREATE, MERGE, SET, REMOVE, DROP, DETACH).
     * Used by MCP traverse() tool (MCP-05).
     *
     * @throws IllegalArgumentException if {@code cypher} contains mutation keywords
     */
    List<Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher);

    /**
     * Find shortest path between two node UUIDs within a tenant's graph.
     * Returns list of {@link NodeState} along the path (including start and end
     * nodes), or empty if no path exists. Uses AGE shortestPath() with the
     * spike-confirmed pattern from 03-00-SUMMARY.md (Assumption A3).
     *
     * <p>Cross-tenant isolation is enforced via
     * {@code WHERE ALL(n IN nodes(path) WHERE n.model_id = tenant)} in
     * Cypher — NOT post-filtered in Java (T-03-03 mitigation).
     * Used by MCP find_path() tool (MCP-06).
     */
    List<NodeState> findShortestPath(TenantContext ctx, UUID fromUuid, UUID toUuid);
}
