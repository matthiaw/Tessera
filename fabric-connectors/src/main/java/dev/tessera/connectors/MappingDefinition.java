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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * CONN-02 / CONTEXT Decision 16: Connector mapping definition.
 * Stored as JSONB in the {@code connectors.mapping_def} column. Parsed
 * and validated by {@code ConnectorRegistry} on load and on admin CRUD.
 *
 * <p>For REST connectors: sourceUrl, rootPath, fields, identityFields are used.
 * <p>For unstructured connectors (Phase 2.5): folderPath, globPattern,
 * chunkStrategy, chunkOverlapChars, confidenceThreshold, provider are used.
 *
 * @param sourceEntityType     logical name in the source system
 * @param targetNodeTypeSlug   Tessera Schema Registry slug
 * @param rootPath             JSONPath to iterate rows (REST connectors)
 * @param fields               field mappings (REST connectors)
 * @param identityFields       Tessera node identity fields for dedup (REST connectors)
 * @param sourceUrl            URL to poll (REST connectors)
 * @param folderPath           absolute path to the folder to scan (unstructured connectors)
 * @param globPattern          glob pattern for file matching (default: "**&#47;*.md")
 * @param chunkStrategy        chunking strategy: "paragraph" or "sentence" (default: "paragraph")
 * @param chunkOverlapChars    overlap characters between chunks (default: 200)
 * @param confidenceThreshold  minimum confidence for auto-merge (default: 0.7)
 * @param provider             LLM provider name (only "anthropic" accepted in Phase 2.5)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingDefinition(
        String sourceEntityType,
        String targetNodeTypeSlug,
        String rootPath,
        List<FieldMapping> fields,
        List<String> identityFields,
        String sourceUrl,
        // Phase 2.5: Unstructured connector fields
        @JsonProperty("folder_path") String folderPath,
        @JsonProperty("glob_pattern") String globPattern,
        @JsonProperty("chunk_strategy") String chunkStrategy,
        @JsonProperty("chunk_overlap_chars") Integer chunkOverlapChars,
        @JsonProperty("confidence_threshold") Double confidenceThreshold,
        String provider) {

    public MappingDefinition {
        if (fields == null) {
            fields = List.of();
        }
        if (identityFields == null) {
            identityFields = List.of();
        }
    }
}
