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
package dev.tessera.connectors.internal;

import dev.tessera.connectors.CandidateMutation;
import dev.tessera.connectors.ConnectorInstance;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.connectors.review.ExtractionReviewRepository;
import dev.tessera.connectors.review.ReviewQueueEntry;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.rules.resolution.EmbeddingService;
import dev.tessera.rules.resolution.EntityResolutionService;
import dev.tessera.rules.resolution.ResolutionCandidate;
import dev.tessera.rules.resolution.ResolutionResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The ONLY class in fabric-connectors that calls
 * {@link GraphService#apply}. Iterates candidates from a poll result,
 * routes DLQ entries to the DLQ writer, and updates sync status.
 *
 * <p>Per-candidate exception handling: one bad row does not poison the
 * batch. Failed candidates are counted as DLQ entries.
 */
@Component
public class ConnectorRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorRunner.class);

    private static final String DEFAULT_EMBEDDING_MODEL = "nomic-embed-text";

    private final GraphService graphService;
    private final SyncStatusRepository syncStatusRepo;
    private final Clock clock;

    // Phase 2.5: Optional beans for unstructured extraction path
    private final EntityResolutionService entityResolutionService;
    private final EmbeddingService embeddingService;
    private final ExtractionReviewRepository reviewRepository;

    public ConnectorRunner(
            GraphService graphService,
            SyncStatusRepository syncStatusRepo,
            Clock clock,
            @Autowired(required = false) EntityResolutionService entityResolutionService,
            @Autowired(required = false) EmbeddingService embeddingService,
            @Autowired(required = false) ExtractionReviewRepository reviewRepository) {
        this.graphService = graphService;
        this.syncStatusRepo = syncStatusRepo;
        this.clock = clock;
        this.entityResolutionService = entityResolutionService;
        this.embeddingService = embeddingService;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Execute a single poll cycle for the given connector instance.
     */
    public void runOnce(ConnectorInstance instance) {
        LOG.debug(
                "Running connector {} (type={})",
                instance.id(),
                instance.connector().type());

        ConnectorState currentState = syncStatusRepo.getState(instance.id());

        PollResult result;
        try {
            result = instance.connector().poll(clock, instance.mapping(), currentState, instance.tenant());
        } catch (Exception e) {
            LOG.error("Connector {} poll failed: {}", instance.id(), e.getMessage(), e);
            Instant nextPoll = clock.instant().plus(Duration.ofSeconds(instance.pollIntervalSeconds()));
            syncStatusRepo.updateAfterPoll(
                    instance.id(),
                    instance.tenant().modelId(),
                    SyncOutcome.FAILED,
                    currentState.etag(),
                    null,
                    0,
                    0,
                    nextPoll,
                    currentState);
            return;
        }

        long successCount = 0;
        long dlqCount = result.dlq() != null ? result.dlq().size() : 0;

        // Process candidates through the write funnel
        for (CandidateMutation candidate : result.candidates()) {
            try {
                // Phase 2.5: Entity resolution for unstructured extraction candidates
                if (isExtractionCandidate(candidate) && entityResolutionService != null) {
                    boolean shouldSkip = handleExtractionResolution(candidate, instance);
                    if (shouldSkip) {
                        // Candidate routed to review queue -- not counted as DLQ
                        continue;
                    }
                }

                GraphMutation mutation = candidate.toMutation(instance.tenant());
                GraphMutationOutcome outcome = graphService.apply(mutation);
                if (outcome instanceof GraphMutationOutcome.Committed committed) {
                    successCount++;

                    // Phase 2.5: Store embedding after successful apply
                    if (isExtractionCandidate(candidate) && embeddingService != null) {
                        try {
                            String entityName = (String) candidate.properties().getOrDefault("name", "");
                            if (!entityName.isBlank()) {
                                float[] embedding = embeddingService.embed(entityName);
                                embeddingService.store(
                                        committed.nodeUuid(),
                                        instance.tenant().modelId(),
                                        DEFAULT_EMBEDDING_MODEL,
                                        embedding);
                            }
                        } catch (Exception embEx) {
                            LOG.warn(
                                    "Embedding storage failed for node {} (non-fatal): {}",
                                    committed.nodeUuid(),
                                    embEx.getMessage());
                        }
                    }
                } else if (outcome instanceof GraphMutationOutcome.Rejected rejected) {
                    LOG.warn(
                            "Connector {} candidate rejected by rule {}: {}",
                            instance.id(),
                            rejected.ruleId(),
                            rejected.reason());
                    dlqCount++;
                }
            } catch (Exception e) {
                LOG.warn("Connector {} candidate failed: {}", instance.id(), e.getMessage());
                dlqCount++;
            }
        }

        // Determine outcome
        SyncOutcome finalOutcome = result.outcome();
        if (finalOutcome == SyncOutcome.SUCCESS && dlqCount > 0 && successCount > 0) {
            finalOutcome = SyncOutcome.PARTIAL;
        } else if (finalOutcome == SyncOutcome.SUCCESS && dlqCount > 0 && successCount == 0) {
            finalOutcome = SyncOutcome.FAILED;
        }

        // Update sync status
        Instant nextPoll = clock.instant().plus(Duration.ofSeconds(instance.pollIntervalSeconds()));
        ConnectorState nextState = result.nextState();

        syncStatusRepo.updateAfterPoll(
                instance.id(),
                instance.tenant().modelId(),
                finalOutcome,
                nextState != null ? nextState.etag() : null,
                nextState != null && nextState.lastModified() != null
                        ? nextState.lastModified().toString()
                        : null,
                successCount,
                dlqCount,
                nextPoll,
                nextState);

        LOG.info(
                "Connector {} poll complete: outcome={}, processed={}, dlq={}",
                instance.id(),
                finalOutcome,
                successCount,
                dlqCount);
    }

    /**
     * Check if a candidate is from the unstructured extraction path.
     * Extraction candidates always have sourceDocumentId set (non-null).
     */
    private static boolean isExtractionCandidate(CandidateMutation candidate) {
        return candidate.sourceDocumentId() != null;
    }

    /**
     * Handle entity resolution for an extraction candidate.
     * Returns true if the candidate was handled here (match merged or routed to review queue).
     * Returns false if ResolutionResult.Create -- caller should proceed with normal apply.
     */
    private boolean handleExtractionResolution(CandidateMutation candidate, ConnectorInstance instance) {
        double threshold = instance.mapping().confidenceThreshold() != null
                ? instance.mapping().confidenceThreshold()
                : 0.7;
        boolean embeddingEnabled = embeddingService != null;

        ResolutionCandidate resCandidate = new ResolutionCandidate(
                candidate.targetTypeSlug(),
                (String) candidate.properties().getOrDefault("name", ""),
                candidate.properties(),
                candidate.extractionConfidence());

        ResolutionResult resolution = entityResolutionService.resolve(
                resCandidate, instance.tenant(), threshold, embeddingEnabled, DEFAULT_EMBEDDING_MODEL);

        if (resolution instanceof ResolutionResult.NeedsReview rq) {
            // Route to review queue
            if (reviewRepository != null) {
                ReviewQueueEntry entry = new ReviewQueueEntry(
                        UUID.randomUUID(),
                        instance.tenant().modelId(),
                        instance.id(),
                        candidate.sourceDocumentId(),
                        candidate.sourceChunkRange(),
                        candidate.targetTypeSlug(),
                        candidate.properties(),
                        candidate.extractionConfidence(),
                        candidate.extractorVersion(),
                        candidate.llmModelId(),
                        rq.tier(),
                        BigDecimal.valueOf(rq.score()),
                        null, null, null, null, null);
                reviewRepository.insert(entry);
                LOG.info(
                        "Extraction candidate '{}' (type={}) routed to review queue (tier={}, score={})",
                        candidate.properties().get("name"),
                        candidate.targetTypeSlug(),
                        rq.tier(),
                        rq.score());
            }
            return true;
        } else if (resolution instanceof ResolutionResult.Match match) {
            // Merge into existing node -- reconstruct candidate with matched UUID for UPDATE path
            LOG.debug(
                    "Extraction candidate '{}' matched existing node {} (tier={}, score={})",
                    candidate.properties().get("name"),
                    match.existingNodeUuid(),
                    match.tier(),
                    match.score());
            try {
                CandidateMutation mergedCandidate = new CandidateMutation(
                        candidate.targetTypeSlug(),
                        match.existingNodeUuid(),
                        candidate.properties(),
                        candidate.sourceSystem(),
                        candidate.connectorId(),
                        candidate.changeId(),
                        candidate.sourceDocumentId(),
                        candidate.sourceChunkRange(),
                        candidate.extractorVersion(),
                        candidate.llmModelId(),
                        candidate.extractionConfidence());
                GraphMutation mutation = mergedCandidate.toMutation(instance.tenant());
                GraphMutationOutcome outcome = graphService.apply(mutation);
                if (outcome instanceof GraphMutationOutcome.Committed committed && embeddingService != null) {
                    storeEmbeddingSafe(committed.nodeUuid(), instance, candidate);
                }
            } catch (Exception e) {
                LOG.warn("Failed to apply merged candidate: {}", e.getMessage());
            }
            return true; // Handled here, skip default apply in caller
        }

        // ResolutionResult.Create -- leave targetNodeUuid null, proceed with normal apply
        return false;
    }

    /**
     * Store embedding for a node, catching and logging any errors (non-fatal).
     */
    private void storeEmbeddingSafe(UUID nodeUuid, ConnectorInstance instance, CandidateMutation candidate) {
        try {
            String entityName = (String) candidate.properties().getOrDefault("name", "");
            if (!entityName.isBlank()) {
                float[] embedding = embeddingService.embed(entityName);
                embeddingService.store(
                        nodeUuid,
                        instance.tenant().modelId(),
                        DEFAULT_EMBEDDING_MODEL,
                        embedding);
            }
        } catch (Exception embEx) {
            LOG.warn("Embedding storage failed for node {} (non-fatal): {}", nodeUuid, embEx.getMessage());
        }
    }
}
