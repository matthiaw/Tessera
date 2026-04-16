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
package dev.tessera.connectors.review;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for a row in {@code extraction_review_queue}.
 */
public record ReviewQueueEntry(
        UUID id,
        UUID modelId,
        UUID connectorId,
        String sourceDocumentId,
        String sourceChunkRange,
        String typeSlug,
        Map<String, Object> extractedProperties,
        BigDecimal extractionConfidence,
        String extractorVersion,
        String llmModelId,
        String resolutionTier,
        BigDecimal resolutionScore,
        Instant createdAt,
        Instant decidedAt,
        String decision,
        String decisionReason,
        UUID operatorTargetNodeUuid) {}
