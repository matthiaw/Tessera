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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * A candidate mutation produced by a {@link Connector#poll} call.
 * Converted to a {@link GraphMutation} by the runner before flowing
 * through {@code GraphService.apply}.
 *
 * @param targetTypeSlug       Tessera node type slug
 * @param targetNodeUuid       existing node UUID for updates; null for creates
 * @param properties           mapped properties from the source
 * @param sourceSystem         source system identifier
 * @param connectorId          originating connector UUID
 * @param changeId             unique ID for this change (dedup key)
 * @param sourceDocumentId     Phase 2.5 provenance: SHA-256 of source document (null for structured)
 * @param sourceChunkRange     Phase 2.5 provenance: char offset:length within document (null for structured)
 * @param extractorVersion     Phase 2.5 provenance: extractor version string (null for structured)
 * @param llmModelId           Phase 2.5 provenance: LLM model identifier (null for structured)
 * @param extractionConfidence Phase 2.5 provenance: extraction confidence 0.0-1.0 (null for structured)
 */
public record CandidateMutation(
        String targetTypeSlug,
        UUID targetNodeUuid,
        Map<String, Object> properties,
        String sourceSystem,
        String connectorId,
        String changeId,
        // Phase 2.5 provenance (null for structured connectors)
        String sourceDocumentId,
        String sourceChunkRange,
        String extractorVersion,
        String llmModelId,
        BigDecimal extractionConfidence) {

    /**
     * Convert to a {@link GraphMutation} for the write funnel.
     * When provenance fields are present (unstructured path), uses
     * {@link SourceType#UNSTRUCTURED} per CONTEXT.md Decisions 6 and 8.
     */
    public GraphMutation toMutation(TenantContext tenant) {
        return new GraphMutation(
                tenant,
                targetNodeUuid != null ? Operation.UPDATE : Operation.CREATE,
                targetTypeSlug,
                targetNodeUuid,
                properties,
                sourceDocumentId != null ? SourceType.UNSTRUCTURED : SourceType.STRUCTURED,
                connectorId,
                sourceSystem,
                extractionConfidence != null ? extractionConfidence : BigDecimal.ONE,
                extractorVersion,
                llmModelId,
                sourceDocumentId,
                sourceChunkRange,
                connectorId,
                changeId);
    }
}
