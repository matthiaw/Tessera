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
 * @param targetTypeSlug  Tessera node type slug
 * @param targetNodeUuid  existing node UUID for updates; null for creates
 * @param properties      mapped properties from the source
 * @param sourceSystem    source system identifier
 * @param connectorId     originating connector UUID
 * @param changeId        unique ID for this change (dedup key)
 */
public record CandidateMutation(
        String targetTypeSlug,
        UUID targetNodeUuid,
        Map<String, Object> properties,
        String sourceSystem,
        String connectorId,
        String changeId) {

    /**
     * Convert to a {@link GraphMutation} for the write funnel.
     */
    public GraphMutation toMutation(TenantContext tenant) {
        return new GraphMutation(
                tenant,
                targetNodeUuid != null ? Operation.UPDATE : Operation.CREATE,
                targetTypeSlug,
                targetNodeUuid,
                properties,
                SourceType.STRUCTURED,
                connectorId,
                sourceSystem,
                BigDecimal.ONE,
                null,
                null,
                connectorId,
                changeId);
    }
}
