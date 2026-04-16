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

import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single REST controller for all entity CRUD operations (W2a). The path
 * {@code /api/v1/{model}/entities/{typeSlug}} is parameterised by the
 * model UUID and the schema type slug.
 */
@RestController
@RequestMapping("/api/v1/{model}/entities/{typeSlug}")
public class GenericEntityController {

    /** Default page size for cursor pagination. */
    private static final int DEFAULT_LIMIT = 50;

    /** Maximum allowed page size. */
    private static final int MAX_LIMIT = 500;

    private final EntityDispatcher dispatcher;

    public GenericEntityController(EntityDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** GET — list entities with cursor pagination. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable("model") String model,
            @PathVariable("typeSlug") String typeSlug,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = parseModelId(model);
        enforceTenantMatch(jwt, model);
        TenantContext ctx = TenantContext.of(modelId);
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        long afterSeq = 0;
        if (cursor != null && !cursor.isBlank()) {
            CursorCodec.CursorPosition pos = CursorCodec.decode(cursor);
            afterSeq = pos.lastSeq();
        }

        // Fetch one extra to detect whether there is a next page.
        List<NodeState> nodes = dispatcher.list(ctx, typeSlug, afterSeq, effectiveLimit + 1);
        boolean hasMore = nodes.size() > effectiveLimit;
        List<NodeState> page = hasMore ? nodes.subList(0, effectiveLimit) : nodes;

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            NodeState last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(modelId, typeSlug, last.seq(), last.uuid());
        }

        List<Map<String, Object>> items =
                page.stream().map(GenericEntityController::nodeToMap).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("next_cursor", nextCursor);
        return ResponseEntity.ok(body);
    }

    /** GET/{id} — single entity by UUID. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable("model") String model,
            @PathVariable("typeSlug") String typeSlug,
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = parseModelId(model);
        enforceTenantMatch(jwt, model);
        TenantContext ctx = TenantContext.of(modelId);
        NodeState node = dispatcher
                .getById(ctx, typeSlug, id)
                .orElseThrow(() -> new NotFoundException("Entity " + id + " not found"));
        return ResponseEntity.ok(nodeToMap(node));
    }

    /** POST — create a new entity. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable("model") String model,
            @PathVariable("typeSlug") String typeSlug,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = parseModelId(model);
        enforceTenantMatch(jwt, model);
        TenantContext ctx = TenantContext.of(modelId);
        GraphMutationOutcome outcome = dispatcher.create(ctx, typeSlug, payload);
        return switch (outcome) {
            case GraphMutationOutcome.Committed c -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("uuid", c.nodeUuid().toString(), "seq", c.sequenceNr()));
            case GraphMutationOutcome.Rejected r -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", r.reason(), "rule", r.ruleId() != null ? r.ruleId() : "unknown"));
        };
    }

    /** PUT/{id} — update an existing entity. */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable("model") String model,
            @PathVariable("typeSlug") String typeSlug,
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Object> payload,
            @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = parseModelId(model);
        enforceTenantMatch(jwt, model);
        TenantContext ctx = TenantContext.of(modelId);
        GraphMutationOutcome outcome = dispatcher.update(ctx, typeSlug, id, payload);
        return switch (outcome) {
            case GraphMutationOutcome.Committed c -> ResponseEntity.ok(
                    Map.of("uuid", c.nodeUuid().toString(), "seq", c.sequenceNr()));
            case GraphMutationOutcome.Rejected r -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", r.reason(), "rule", r.ruleId() != null ? r.ruleId() : "unknown"));
        };
    }

    /** DELETE/{id} — tombstone (soft-delete) an entity. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable("model") String model,
            @PathVariable("typeSlug") String typeSlug,
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = parseModelId(model);
        enforceTenantMatch(jwt, model);
        TenantContext ctx = TenantContext.of(modelId);
        GraphMutationOutcome outcome = dispatcher.delete(ctx, typeSlug, id);
        return switch (outcome) {
            case GraphMutationOutcome.Committed c -> ResponseEntity.ok(
                    Map.of("uuid", c.nodeUuid().toString(), "tombstoned", true));
            case GraphMutationOutcome.Rejected r -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", r.reason(), "rule", r.ruleId() != null ? r.ruleId() : "unknown"));
        };
    }

    /**
     * Decision 11: JWT tenant claim must match {model} path segment.
     * Mismatch -> 404 (never 403) via CrossTenantException.
     */
    private static void enforceTenantMatch(Jwt jwt, String model) {
        if (jwt == null) {
            throw new CrossTenantException();
        }
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null || !tenant.equals(model)) {
            throw new CrossTenantException();
        }
    }

    private static UUID parseModelId(String model) {
        try {
            return UUID.fromString(model);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Invalid model ID: " + model);
        }
    }

    private static Map<String, Object> nodeToMap(NodeState node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", node.uuid().toString());
        map.put("type", node.typeSlug());
        map.put("seq", node.seq());
        if (node.createdAt() != null) {
            map.put("created_at", node.createdAt().toString());
        }
        if (node.updatedAt() != null) {
            map.put("updated_at", node.updatedAt().toString());
        }
        map.putAll(node.properties());
        return map;
    }
}
