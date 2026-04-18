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
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.security.AclFilterService;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * MCP-06: Find the shortest path between two entities by UUID.
 *
 * <p>Delegates to {@link GraphRepository#findShortestPath} which uses AGE shortestPath()
 * with a {@code WHERE ALL(n IN nodes(path) WHERE n.model_id = tenant)} filter to prevent
 * cross-tenant path traversal (T-03-07, Assumption A3 confirmed in Wave 0 spike).
 *
 * <p>Read-only; does NOT override {@link #isWriteTool()} (inherits default {@code false}).
 */
@Component
public class FindPathTool implements ToolProvider {

    private final GraphRepository graphRepository;
    private final ObjectMapper objectMapper;
    private final AclFilterService aclFilterService;
    private final SchemaRegistry schemaRegistry;

    public FindPathTool(
            GraphRepository graphRepository,
            ObjectMapper objectMapper,
            AclFilterService aclFilterService,
            SchemaRegistry schemaRegistry) {
        this.graphRepository = graphRepository;
        this.objectMapper = objectMapper;
        this.aclFilterService = aclFilterService;
        this.schemaRegistry = schemaRegistry;
    }

    @Override
    public String toolName() {
        return "find_path";
    }

    @Override
    public String toolDescription() {
        return "Find the shortest path between two entities by their UUIDs."
                + " Returns the ordered list of nodes along the path."
                + " Both nodes must belong to the current tenant.";
    }

    @Override
    public String inputSchemaJson() {
        return """
                {"type":"object","properties":{"from":{"type":"string","description":"UUID of the starting entity"},"to":{"type":"string","description":"UUID of the destination entity"}},"required":["from","to"]}
                """
                .strip();
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        String fromStr = (String) arguments.get("from");
        String toStr = (String) arguments.get("to");

        if (fromStr == null || fromStr.isBlank()) {
            return ToolResponse.error("from parameter is required");
        }
        if (toStr == null || toStr.isBlank()) {
            return ToolResponse.error("to parameter is required");
        }

        UUID fromUuid;
        UUID toUuid;
        try {
            fromUuid = UUID.fromString(fromStr);
            toUuid = UUID.fromString(toStr);
        } catch (IllegalArgumentException e) {
            return ToolResponse.error("Invalid UUID format");
        }

        List<NodeState> path = graphRepository.findShortestPath(tenant, fromUuid, toUuid);
        if (path.isEmpty()) {
            try {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("path", List.of());
                empty.put("message", "No path found between the specified nodes");
                return ToolResponse.ok(objectMapper.writeValueAsString(empty));
            } catch (JsonProcessingException e) {
                return ToolResponse.error("Failed to serialize response: " + e.getMessage());
            }
        }

        Set<String> callerRoles = ToolNodeSerializer.extractCallerRoles();
        List<Map<String, Object>> nodes = path.stream()
                .map(n -> {
                    Optional<NodeTypeDescriptor> desc = schemaRegistry.loadFor(tenant, n.typeSlug());
                    return desc.isPresent()
                            ? ToolNodeSerializer.toMap(n, aclFilterService, desc.get(), callerRoles)
                            : ToolNodeSerializer.toMap(n);
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", nodes);
        result.put("length", nodes.size() - 1);

        try {
            return ToolResponse.ok(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize path: " + e.getMessage());
        }
    }
}
