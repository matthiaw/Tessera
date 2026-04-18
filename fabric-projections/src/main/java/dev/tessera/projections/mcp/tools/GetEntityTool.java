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
 * MCP-04 (D-B4): Get a single entity by type and UUID with configurable neighbor depth.
 *
 * <p>Depth is clamped to [0,3] (T-03-08 DoS mitigation). Depth 0 returns only the entity.
 * Depth 1-3 expands neighbors via {@link GraphRepository#executeTenantCypher}. Read-only;
 * does NOT override {@link #isWriteTool()} (inherits default {@code false}).
 */
@Component
public class GetEntityTool implements ToolProvider {

    static final int MAX_DEPTH = 3;

    private final GraphRepository graphRepository;
    private final ObjectMapper objectMapper;
    private final AclFilterService aclFilterService;
    private final SchemaRegistry schemaRegistry;

    public GetEntityTool(
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
        return "get_entity";
    }

    @Override
    public String toolDescription() {
        return "Get a single entity by type and UUID, including connected entities up to the specified depth"
                + " (0=entity only, 1=direct neighbors, 2=two hops, 3=three hops). Default depth is 1, maximum is 3.";
    }

    @Override
    public String inputSchemaJson() {
        return """
                {"type":"object","properties":{"type":{"type":"string","description":"Entity type slug"},"id":{"type":"string","description":"Entity UUID"},"depth":{"type":"integer","description":"Neighbor expansion depth (0-3, default 1)","default":1,"minimum":0,"maximum":3}},"required":["type","id"]}
                """
                .strip();
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        String type = (String) arguments.get("type");
        String idStr = (String) arguments.get("id");

        if (type == null || type.isBlank()) {
            return ToolResponse.error("type parameter is required");
        }
        if (idStr == null || idStr.isBlank()) {
            return ToolResponse.error("id parameter is required");
        }

        UUID entityId;
        try {
            entityId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return ToolResponse.error("Invalid UUID format for id: " + idStr);
        }

        // Parse and clamp depth to [0, 3] — T-03-08 DoS mitigation
        int depth = 1;
        Object depthArg = arguments.get("depth");
        if (depthArg != null) {
            if (depthArg instanceof Number n) {
                depth = n.intValue();
            } else {
                try {
                    depth = Integer.parseInt(depthArg.toString());
                } catch (NumberFormatException e) {
                    return ToolResponse.error("depth must be an integer between 0 and 3");
                }
            }
            if (depth < 0) depth = 0;
            if (depth > MAX_DEPTH) depth = MAX_DEPTH;
        }

        Optional<NodeState> maybeEntity = graphRepository.findNode(tenant, type, entityId);
        if (maybeEntity.isEmpty()) {
            return ToolResponse.error("Entity not found");
        }

        NodeState entity = maybeEntity.get();
        Set<String> callerRoles = ToolNodeSerializer.extractCallerRoles();
        Optional<NodeTypeDescriptor> maybeDesc = schemaRegistry.loadFor(tenant, type);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "entity",
                maybeDesc.isPresent()
                        ? ToolNodeSerializer.toMap(entity, aclFilterService, maybeDesc.get(), callerRoles)
                        : ToolNodeSerializer.toMap(entity));

        if (depth > 0) {
            // Expand neighbors via tenant-scoped Cypher (model_id injected by GraphRepositoryImpl)
            String cypher =
                    String.format("MATCH path = (n {uuid: \"%s\"})-[*0..%d]-(m) RETURN DISTINCT m", entityId, depth);
            try {
                List<Map<String, Object>> neighborRows = graphRepository.executeTenantCypher(tenant, cypher);
                // Filter out the entity itself from neighbors
                List<Map<String, Object>> neighbors = neighborRows.stream()
                        .filter(row -> {
                            Object uuid = row.get("uuid");
                            return uuid == null || !entityId.toString().equals(uuid.toString());
                        })
                        .toList();
                result.put("neighbors", neighbors);
            } catch (Exception e) {
                // Non-fatal: return entity without neighbors on traversal error
                result.put("neighbors", List.of());
                result.put("neighbors_error", "Unable to expand neighbors: " + e.getMessage());
            }
        }

        try {
            return ToolResponse.ok(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize entity: " + e.getMessage());
        }
    }
}
