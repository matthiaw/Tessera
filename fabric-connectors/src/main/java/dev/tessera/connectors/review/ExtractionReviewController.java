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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXTR-07 / CONTEXT Decision 6: Review queue API for operator decisions on
 * below-threshold extracted candidates. Follows the same patterns as
 * {@link dev.tessera.connectors.admin.ConnectorAdminController}: JWT tenant
 * extraction, RFC 7807 error shapes, model_id filtering.
 *
 * <p>Accepted and overridden candidates flow through
 * {@link GraphService#apply(GraphMutation)} with confidence 1.0
 * (operator-affirmed). Rejected candidates are recorded and skipped.
 */
@RestController
@RequestMapping("/admin/extraction/review")
public class ExtractionReviewController {

    private final ExtractionReviewRepository reviewRepo;
    private final GraphService graphService;

    public ExtractionReviewController(ExtractionReviewRepository reviewRepo, GraphService graphService) {
        this.reviewRepo = reviewRepo;
        this.graphService = graphService;
    }

    /**
     * List pending review queue entries for the caller's tenant.
     */
    @GetMapping
    public ResponseEntity<?> listPending(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "connector_id", required = false) UUID connectorId,
            @RequestParam(name = "type_slug", required = false) String typeSlug,
            @RequestParam(name = "since", required = false) Instant since,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {

        UUID modelId = extractModelId(jwt);
        if (modelId == null) {
            return badRequest("Missing tenant claim");
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), 200);
        List<ReviewQueueEntry> entries = reviewRepo.findPending(modelId, connectorId, typeSlug, since, effectiveLimit);
        return ResponseEntity.ok(entries);
    }

    /**
     * Accept a review queue entry. Builds a GraphMutation with confidence 1.0
     * and applies it through GraphService.
     */
    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID modelId = extractModelId(jwt);
        if (modelId == null) {
            return badRequest("Missing tenant claim");
        }

        var entry = reviewRepo.findById(id, modelId);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            reviewRepo.markAccepted(id, modelId);
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        ReviewQueueEntry qe = entry.get();
        GraphMutation mutation = buildMutation(qe, modelId, null);
        GraphMutationOutcome outcome = graphService.apply(mutation);

        if (outcome instanceof GraphMutationOutcome.Committed committed) {
            return ResponseEntity.ok(Map.of("nodeUuid", committed.nodeUuid().toString(), "status", "ACCEPTED"));
        } else if (outcome instanceof GraphMutationOutcome.Rejected rejected) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.UNPROCESSABLE_ENTITY, "Graph mutation rejected: " + rejected.reason()));
        }
        return ResponseEntity.internalServerError().build();
    }

    /**
     * Reject a review queue entry with an optional reason.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionRequest body,
            @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = extractModelId(jwt);
        if (modelId == null) {
            return badRequest("Missing tenant claim");
        }

        var entry = reviewRepo.findById(id, modelId);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String reason = body != null ? body.reason() : null;

        try {
            reviewRepo.markRejected(id, modelId, reason);
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("id", id.toString(), "status", "REJECTED"));
    }

    /**
     * Override a review queue entry by specifying the correct merge target node.
     * Builds a GraphMutation as an UPDATE on the specified target node UUID.
     */
    @PostMapping("/{id}/override")
    public ResponseEntity<?> override(
            @PathVariable UUID id, @RequestBody ReviewDecisionRequest body, @AuthenticationPrincipal Jwt jwt) {

        UUID modelId = extractModelId(jwt);
        if (modelId == null) {
            return badRequest("Missing tenant claim");
        }

        if (body == null || body.targetNodeUuid() == null) {
            return badRequest("targetNodeUuid is required for override");
        }

        var entry = reviewRepo.findById(id, modelId);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            reviewRepo.markOverridden(id, modelId, body.targetNodeUuid());
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        ReviewQueueEntry qe = entry.get();
        GraphMutation mutation = buildMutation(qe, modelId, body.targetNodeUuid());
        GraphMutationOutcome outcome = graphService.apply(mutation);

        if (outcome instanceof GraphMutationOutcome.Committed committed) {
            return ResponseEntity.ok(Map.of("nodeUuid", committed.nodeUuid().toString(), "status", "OVERRIDDEN"));
        } else if (outcome instanceof GraphMutationOutcome.Rejected rejected) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.UNPROCESSABLE_ENTITY, "Graph mutation rejected: " + rejected.reason()));
        }
        return ResponseEntity.internalServerError().build();
    }

    private GraphMutation buildMutation(ReviewQueueEntry entry, UUID modelId, UUID targetNodeUuid) {
        Operation op = targetNodeUuid != null ? Operation.UPDATE : Operation.CREATE;
        return GraphMutation.builder()
                .tenantContext(TenantContext.of(modelId))
                .operation(op)
                .type(entry.typeSlug())
                .targetNodeUuid(targetNodeUuid)
                .payload(entry.extractedProperties())
                .sourceType(SourceType.UNSTRUCTURED)
                .sourceId(entry.sourceDocumentId())
                .sourceSystem("extraction-review")
                .confidence(BigDecimal.ONE)
                .extractorVersion(entry.extractorVersion())
                .llmModelId(entry.llmModelId())
                .sourceDocumentId(entry.sourceDocumentId())
                .sourceChunkRange(entry.sourceChunkRange())
                .build();
    }

    private UUID extractModelId(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null) return null;
        try {
            return UUID.fromString(tenant);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ResponseEntity<?> badRequest(String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        return ResponseEntity.badRequest().body(pd);
    }
}
