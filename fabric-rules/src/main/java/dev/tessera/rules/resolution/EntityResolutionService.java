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
package dev.tessera.rules.resolution;

import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Three-tier entity resolution pipeline (EXTR-05, CONTEXT.md Decision 5).
 * Determines whether an extracted candidate matches an existing graph entity:
 *
 * <ol>
 *   <li><b>Tier 1 — Exact match:</b> name + type in the graph</li>
 *   <li><b>Tier 2 — Embedding similarity:</b> pgvector cosine similarity (if enabled)</li>
 *   <li><b>Tier 3 — Fuzzy name match:</b> Levenshtein distance</li>
 * </ol>
 *
 * <p>Deterministic: same {@code (name, type, embedding)} against the same DB state
 * yields the same decision on every call.
 */
@Service
public class EntityResolutionService {

    private final GraphRepository graphRepository;
    private final EmbeddingService embeddingService;
    private final FuzzyNameMatcher fuzzyNameMatcher;

    public EntityResolutionService(
            GraphRepository graphRepository,
            EmbeddingService embeddingService,
            FuzzyNameMatcher fuzzyNameMatcher) {
        this.graphRepository = graphRepository;
        this.embeddingService = embeddingService;
        this.fuzzyNameMatcher = fuzzyNameMatcher;
    }

    /**
     * Resolve a candidate against existing graph entities.
     *
     * @param candidate        the extraction candidate to resolve
     * @param tenant           tenant context for graph queries
     * @param threshold        similarity threshold for tier 2 and tier 3
     * @param embeddingEnabled whether tier 2 (embedding similarity) is active
     * @param embeddingModelName the embedding model identifier for tier 2 queries
     * @return resolution result: Match, Create (unused currently), or NeedsReview
     */
    public ResolutionResult resolve(
            ResolutionCandidate candidate,
            TenantContext tenant,
            double threshold,
            boolean embeddingEnabled,
            String embeddingModelName) {

        double bestScore = 0.0;

        // --- Tier 1: Exact name + type match ---
        List<NodeState> existingNodes = graphRepository.queryAll(tenant, candidate.typeSlug());
        for (NodeState node : existingNodes) {
            Object nodeName = node.properties().get("name");
            if (nodeName != null && candidate.name().equals(nodeName.toString())) {
                return new ResolutionResult.Match(node.uuid(), "EXACT", 1.0);
            }
        }

        // --- Tier 2: Embedding similarity (if enabled) ---
        if (embeddingEnabled) {
            float[] queryEmbedding = embeddingService.embed(candidate.name());
            List<EmbeddingService.SimilarEntity> similar =
                    embeddingService.findSimilar(tenant.modelId(), embeddingModelName, queryEmbedding, 5);

            for (EmbeddingService.SimilarEntity se : similar) {
                if (se.similarity() > bestScore) {
                    bestScore = se.similarity();
                }
                if (se.similarity() >= threshold) {
                    // Verify the entity type matches by looking it up in the graph
                    var node = graphRepository.findNode(tenant, candidate.typeSlug(), se.nodeUuid());
                    if (node.isPresent()) {
                        return new ResolutionResult.Match(se.nodeUuid(), "EMBEDDING", se.similarity());
                    }
                }
            }
        }

        // --- Tier 3: Fuzzy name match ---
        for (NodeState node : existingNodes) {
            Object nodeName = node.properties().get("name");
            if (nodeName != null) {
                double sim = fuzzyNameMatcher.similarity(candidate.name(), nodeName.toString());
                if (sim > bestScore) {
                    bestScore = sim;
                }
                if (sim >= threshold) {
                    return new ResolutionResult.Match(node.uuid(), "FUZZY", sim);
                }
            }
        }

        // --- None matched: route to review queue ---
        return new ResolutionResult.NeedsReview("ALL", bestScore);
    }
}
