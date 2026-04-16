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
package dev.tessera.connectors.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Walks the Schema Registry for a tenant and builds a JSON schema string
 * describing all registered node types and their properties. This schema
 * is embedded in the LLM extraction prompt so the model knows which
 * entity types to look for and what properties each type carries.
 *
 * <p>The schema is derived at call time from the Caffeine-cached
 * {@link SchemaRegistry#listNodeTypes} result, satisfying EXTR-03's
 * "no redeploy on new type" requirement.
 */
@Component
public class SchemaRegistrySchemaBuilder {

    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;

    public SchemaRegistrySchemaBuilder(SchemaRegistry schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Build a JSON schema describing the tenant's registered entity types
     * and their properties for LLM extraction prompts.
     *
     * @param tenant the tenant context
     * @return JSON string with entity type definitions
     */
    public String buildExtractionSchema(TenantContext tenant) {
        List<NodeTypeDescriptor> nodeTypes = schemaRegistry.listNodeTypes(tenant);

        List<Map<String, Object>> entityTypes = nodeTypes.stream()
                .filter(nt -> nt.deprecatedAt() == null) // skip deprecated types
                .map(this::describeNodeType)
                .toList();

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("entityTypes", entityTypes);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new ExtractionException("Failed to serialize extraction schema", e);
        }
    }

    private Map<String, Object> describeNodeType(NodeTypeDescriptor nodeType) {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("typeSlug", nodeType.slug());
        desc.put("name", nodeType.name());

        List<Map<String, Object>> properties = nodeType.properties().stream()
                .filter(p -> p.deprecatedAt() == null)
                .map(this::describeProperty)
                .toList();
        desc.put("properties", properties);

        return desc;
    }

    private Map<String, Object> describeProperty(PropertyDescriptor prop) {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("name", prop.slug());
        desc.put("dataType", prop.dataType());
        desc.put("required", prop.required());
        return desc;
    }
}
