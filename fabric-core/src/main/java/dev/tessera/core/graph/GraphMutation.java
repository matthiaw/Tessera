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
package dev.tessera.core.graph;

import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The single DTO that flows through {@link GraphService#apply}. Carries the
 * full Phase 1 + Phase 2.5 provenance surface per CONTEXT §D-A1 so extraction
 * work can populate {@code extractorVersion} / {@code llmModelId} without any
 * schema migration or refactor. See also RULE-08 for {@code originConnectorId}
 * + {@code originChangeId} (echo-loop suppression).
 */
public record GraphMutation(
        TenantContext tenantContext,
        Operation operation,
        String type,
        UUID targetNodeUuid,
        Map<String, Object> payload,
        SourceType sourceType,
        String sourceId,
        String sourceSystem,
        BigDecimal confidence,
        String extractorVersion,
        String llmModelId,
        String sourceDocumentId,
        String sourceChunkRange,
        String originConnectorId,
        String originChangeId) {

    public GraphMutation {
        Objects.requireNonNull(tenantContext, "tenantContext must not be null (CORE-03)");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(sourceSystem, "sourceSystem must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be within [0.0, 1.0], got " + confidence);
        }
        if (payload == null) {
            payload = Map.of();
        } else {
            payload = Map.copyOf(payload);
        }
    }

    /** Return a copy with a different payload. Used by the rule engine ENRICH chain. */
    public GraphMutation withPayload(Map<String, Object> newPayload) {
        return new GraphMutation(
                tenantContext,
                operation,
                type,
                targetNodeUuid,
                newPayload,
                sourceType,
                sourceId,
                sourceSystem,
                confidence,
                extractorVersion,
                llmModelId,
                sourceDocumentId,
                sourceChunkRange,
                originConnectorId,
                originChangeId);
    }

    /** Return a copy with a different {@link TenantContext}. Used by jqwik fuzz. */
    public GraphMutation withTenant(TenantContext newContext) {
        return new GraphMutation(
                newContext,
                operation,
                type,
                targetNodeUuid,
                payload,
                sourceType,
                sourceId,
                sourceSystem,
                confidence,
                extractorVersion,
                llmModelId,
                sourceDocumentId,
                sourceChunkRange,
                originConnectorId,
                originChangeId);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Ergonomic builder for tests. Production call sites construct records directly. */
    public static final class Builder {
        private TenantContext tenantContext;
        private Operation operation = Operation.CREATE;
        private String type;
        private UUID targetNodeUuid;
        private Map<String, Object> payload = Map.of();
        private SourceType sourceType = SourceType.SYSTEM;
        private String sourceId = "test";
        private String sourceSystem = "test";
        private BigDecimal confidence = BigDecimal.ONE;
        private String extractorVersion;
        private String llmModelId;
        private String sourceDocumentId;
        private String sourceChunkRange;
        private String originConnectorId;
        private String originChangeId;

        public Builder tenantContext(TenantContext v) {
            this.tenantContext = v;
            return this;
        }

        public Builder operation(Operation v) {
            this.operation = v;
            return this;
        }

        public Builder type(String v) {
            this.type = v;
            return this;
        }

        public Builder targetNodeUuid(UUID v) {
            this.targetNodeUuid = v;
            return this;
        }

        public Builder payload(Map<String, Object> v) {
            this.payload = v;
            return this;
        }

        public Builder sourceType(SourceType v) {
            this.sourceType = v;
            return this;
        }

        public Builder sourceId(String v) {
            this.sourceId = v;
            return this;
        }

        public Builder sourceSystem(String v) {
            this.sourceSystem = v;
            return this;
        }

        public Builder confidence(BigDecimal v) {
            this.confidence = v;
            return this;
        }

        public Builder extractorVersion(String v) {
            this.extractorVersion = v;
            return this;
        }

        public Builder llmModelId(String v) {
            this.llmModelId = v;
            return this;
        }

        public Builder sourceDocumentId(String v) {
            this.sourceDocumentId = v;
            return this;
        }

        public Builder sourceChunkRange(String v) {
            this.sourceChunkRange = v;
            return this;
        }

        public Builder originConnectorId(String v) {
            this.originConnectorId = v;
            return this;
        }

        public Builder originChangeId(String v) {
            this.originChangeId = v;
            return this;
        }

        public GraphMutation build() {
            return new GraphMutation(
                    tenantContext,
                    operation,
                    type,
                    targetNodeUuid,
                    payload,
                    sourceType,
                    sourceId,
                    sourceSystem,
                    confidence,
                    extractorVersion,
                    llmModelId,
                    sourceDocumentId,
                    sourceChunkRange,
                    originConnectorId,
                    originChangeId);
        }
    }
}
