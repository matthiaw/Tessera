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
package dev.tessera.projections.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * MCP-05: Execute a tenant-scoped read-only Cypher query against the graph.
 *
 * <p>Delegates to {@link GraphRepository#executeTenantCypher} which:
 * <ul>
 *   <li>Injects {@code model_id} filter for cross-tenant isolation (T-03-06)</li>
 *   <li>Rejects mutation keywords with {@link IllegalArgumentException} (T-03-05)</li>
 * </ul>
 *
 * <p>Read-only; does NOT override {@link #isWriteTool()} (inherits default {@code false}).
 */
@Component
public class TraverseTool implements ToolProvider {

    private final GraphRepository graphRepository;
    private final ObjectMapper objectMapper;

    public TraverseTool(GraphRepository graphRepository, ObjectMapper objectMapper) {
        this.graphRepository = graphRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolName() {
        return "traverse";
    }

    @Override
    public String toolDescription() {
        return "Execute a Cypher query against the tenant's graph."
                + " The query is automatically scoped to the current tenant — do not include model_id filters."
                + " Only read queries are allowed (no CREATE, DELETE, MERGE, SET, REMOVE, DROP)."
                + " Example: MATCH (n:Person)-[:WORKS_AT]->(o:Organization) RETURN n, o LIMIT 10";
    }

    @Override
    public String inputSchemaJson() {
        return """
                {"type":"object","properties":{"query":{"type":"string","description":"Cypher query to execute (read-only)"}},"required":["query"]}
                """
                .strip();
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return ToolResponse.error("query parameter is required");
        }

        try {
            List<Map<String, Object>> results = graphRepository.executeTenantCypher(tenant, query);
            return ToolResponse.ok(objectMapper.writeValueAsString(results));
        } catch (IllegalArgumentException e) {
            // Mutation keyword blocked by GraphRepositoryImpl (T-03-05)
            return ToolResponse.error("Write operations are not permitted via traverse. Only read queries allowed.");
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize query results: " + e.getMessage());
        }
    }
}
