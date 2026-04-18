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
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.security.AclFilterService;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MCP-02: List all entity types available in the current tenant's schema.
 *
 * <p>Queries {@link SchemaRegistry#listNodeTypes} and returns type slugs, names,
 * descriptions, and schema versions as a JSON array. Read-only; does NOT override
 * {@link #isWriteTool()} (inherits default {@code false}).
 */
@Component
public class ListEntityTypesTool implements ToolProvider {

    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;
    private final AclFilterService aclFilterService;

    public ListEntityTypesTool(
            SchemaRegistry schemaRegistry, ObjectMapper objectMapper, AclFilterService aclFilterService) {
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
        this.aclFilterService = aclFilterService;
    }

    @Override
    public String toolName() {
        return "list_entity_types";
    }

    @Override
    public String toolDescription() {
        return "List all entity types available in the current tenant's schema. Returns type slugs, names, and descriptions.";
    }

    @Override
    public String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        Set<String> callerRoles = ToolNodeSerializer.extractCallerRoles();
        List<NodeTypeDescriptor> types = schemaRegistry.listNodeTypes(tenant);
        List<Map<String, Object>> result = types.stream()
                .filter(t -> aclFilterService.isTypeVisible(t, callerRoles))
                .map(t -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("slug", t.slug());
                    entry.put("name", t.name());
                    entry.put("description", t.description());
                    entry.put("schema_version", t.schemaVersion());
                    return entry;
                })
                .toList();
        try {
            return ToolResponse.ok(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize entity types: " + e.getMessage());
        }
    }
}
