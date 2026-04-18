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
import dev.tessera.core.schema.PropertyDescriptor;
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
 * MCP-02: Describe a specific entity type including properties and connected edge types.
 *
 * <p>Calls {@link SchemaRegistry#loadFor} for the node type and returns full property
 * schema plus outgoing/incoming edge type slugs. Read-only; does NOT override
 * {@link #isWriteTool()} (inherits default {@code false}).
 */
@Component
public class DescribeTypeTool implements ToolProvider {

    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;
    private final AclFilterService aclFilterService;

    public DescribeTypeTool(
            SchemaRegistry schemaRegistry, ObjectMapper objectMapper, AclFilterService aclFilterService) {
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
        this.aclFilterService = aclFilterService;
    }

    @Override
    public String toolName() {
        return "describe_type";
    }

    @Override
    public String toolDescription() {
        return "Describe a specific entity type including all properties, their data types, and connected edge types."
                + " Use list_entity_types first to discover available slugs.";
    }

    @Override
    public String inputSchemaJson() {
        return """
                {"type":"object","properties":{"slug":{"type":"string","description":"The type slug to describe"}},"required":["slug"]}
                """
                .strip();
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        String slug = (String) arguments.get("slug");
        if (slug == null || slug.isBlank()) {
            return ToolResponse.error("slug parameter is required");
        }

        var maybeType = schemaRegistry.loadFor(tenant, slug);
        if (maybeType.isEmpty()) {
            return ToolResponse.error("Type '" + slug + "' not found");
        }
        NodeTypeDescriptor type = maybeType.get();
        Set<String> callerRoles = ToolNodeSerializer.extractCallerRoles();
        if (!aclFilterService.isTypeVisible(type, callerRoles)) {
            return ToolResponse.error("Type '" + slug + "' not found");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slug", type.slug());
        result.put("name", type.name());
        result.put("label", type.label());
        result.put("description", type.description());
        result.put("schema_version", type.schemaVersion());
        result.put("deprecated", type.deprecatedAt() != null);

        List<Map<String, Object>> properties =
                type.properties().stream().map(p -> toPropertyMap(p)).toList();
        result.put("properties", properties);

        // Edge types: list all types and find edges where this type is source or target
        List<NodeTypeDescriptor> allTypes = schemaRegistry.listNodeTypes(tenant);
        List<Map<String, Object>> edgeTypes = allTypes.stream()
                .flatMap(nt -> nt.properties().stream()
                        .filter(p -> "REFERENCE".equalsIgnoreCase(p.dataType())
                                && p.referenceTarget() != null
                                && !p.referenceTarget().isBlank())
                        .map(p -> {
                            Map<String, Object> edge = new LinkedHashMap<>();
                            edge.put("source_type", nt.slug());
                            edge.put("target_type", p.referenceTarget());
                            edge.put("via_property", p.slug());
                            return edge;
                        }))
                .filter(e -> slug.equals(e.get("source_type")) || slug.equals(e.get("target_type")))
                .toList();
        result.put("edge_types", edgeTypes);

        try {
            return ToolResponse.ok(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize type description: " + e.getMessage());
        }
    }

    private Map<String, Object> toPropertyMap(PropertyDescriptor p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", p.slug());
        m.put("data_type", p.dataType());
        m.put("required", p.required());
        m.put("deprecated", p.deprecatedAt() != null);
        return m;
    }
}
