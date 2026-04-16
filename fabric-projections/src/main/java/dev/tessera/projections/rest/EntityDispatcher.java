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
package dev.tessera.projections.rest;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * REST projection dispatcher (W2a). Sits between {@link GenericEntityController}
 * and the graph core. Checks exposure flags before delegating reads/writes.
 */
@Service
public class EntityDispatcher {

    private final SchemaRegistry schemaRegistry;
    private final GraphRepository graphRepository;
    private final GraphService graphService;

    public EntityDispatcher(SchemaRegistry schemaRegistry, GraphRepository graphRepository, GraphService graphService) {
        this.schemaRegistry = schemaRegistry;
        this.graphRepository = graphRepository;
        this.graphService = graphService;
    }

    /**
     * List entities with cursor pagination. Returns up to {@code limit}
     * nodes after {@code afterSeq}, ordered by {@code _seq}.
     */
    public List<NodeState> list(TenantContext ctx, String typeSlug, long afterSeq, int limit) {
        requireReadEnabled(ctx, typeSlug);
        return graphRepository.queryAllAfter(ctx, typeSlug, afterSeq, limit);
    }

    /** Get a single entity by UUID. */
    public Optional<NodeState> getById(TenantContext ctx, String typeSlug, UUID nodeId) {
        requireReadEnabled(ctx, typeSlug);
        return graphRepository.queryById(ctx, typeSlug, nodeId);
    }

    /** Create a new entity. Returns the committed outcome. */
    public GraphMutationOutcome create(TenantContext ctx, String typeSlug, Map<String, Object> payload) {
        requireWriteEnabled(ctx, typeSlug);
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type(typeSlug)
                .payload(payload)
                .sourceType(SourceType.MANUAL)
                .sourceId("rest-api")
                .sourceSystem("rest-projection")
                .confidence(BigDecimal.ONE)
                .build();
        return graphService.apply(mutation);
    }

    /** Update an existing entity. Returns the committed outcome. */
    public GraphMutationOutcome update(TenantContext ctx, String typeSlug, UUID nodeId, Map<String, Object> payload) {
        requireWriteEnabled(ctx, typeSlug);
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.UPDATE)
                .type(typeSlug)
                .targetNodeUuid(nodeId)
                .payload(payload)
                .sourceType(SourceType.MANUAL)
                .sourceId("rest-api")
                .sourceSystem("rest-projection")
                .confidence(BigDecimal.ONE)
                .build();
        return graphService.apply(mutation);
    }

    /** Tombstone (soft-delete) an entity. Returns the committed outcome. */
    public GraphMutationOutcome delete(TenantContext ctx, String typeSlug, UUID nodeId) {
        requireWriteEnabled(ctx, typeSlug);
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.TOMBSTONE)
                .type(typeSlug)
                .targetNodeUuid(nodeId)
                .payload(Map.of())
                .sourceType(SourceType.MANUAL)
                .sourceId("rest-api")
                .sourceSystem("rest-projection")
                .confidence(BigDecimal.ONE)
                .build();
        return graphService.apply(mutation);
    }

    private NodeTypeDescriptor requireReadEnabled(TenantContext ctx, String typeSlug) {
        NodeTypeDescriptor desc = loadOrThrow(ctx, typeSlug);
        if (!desc.restReadEnabled()) {
            throw new NotFoundException("Type '" + typeSlug + "' is not exposed for read");
        }
        return desc;
    }

    private NodeTypeDescriptor requireWriteEnabled(TenantContext ctx, String typeSlug) {
        NodeTypeDescriptor desc = loadOrThrow(ctx, typeSlug);
        if (!desc.restWriteEnabled()) {
            throw new NotFoundException("Type '" + typeSlug + "' is not exposed for write");
        }
        return desc;
    }

    private NodeTypeDescriptor loadOrThrow(TenantContext ctx, String typeSlug) {
        return schemaRegistry
                .loadFor(ctx, typeSlug)
                .orElseThrow(
                        () -> new NotFoundException("Type '" + typeSlug + "' not found in model " + ctx.modelId()));
    }
}
