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
package dev.tessera.connectors;

import java.util.List;

/**
 * CONN-02 / CONTEXT Decision 16: Jayway JSONPath-based mapping definition.
 * Stored as JSONB in the {@code connectors.mapping_def} column. Parsed
 * and validated by {@code ConnectorRegistry} on load and on admin CRUD.
 *
 * @param sourceEntityType     logical name in the source system
 * @param targetNodeTypeSlug   Tessera Schema Registry slug
 * @param rootPath             JSONPath to iterate rows (e.g. "$.data[*]")
 * @param fields               field mappings
 * @param identityFields       Tessera node identity fields for dedup
 * @param sourceUrl            URL to poll
 */
public record MappingDefinition(
        String sourceEntityType,
        String targetNodeTypeSlug,
        String rootPath,
        List<FieldMapping> fields,
        List<String> identityFields,
        String sourceUrl) {

    public MappingDefinition {
        if (fields == null) {
            fields = List.of();
        }
        if (identityFields == null) {
            identityFields = List.of();
        }
    }
}
