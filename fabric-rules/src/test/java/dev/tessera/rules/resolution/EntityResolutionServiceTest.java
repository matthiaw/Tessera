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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityResolutionServiceTest {

    @Mock
    private GraphRepository graphRepository;

    @Mock
    private EmbeddingService embeddingService;

    private final FuzzyNameMatcher fuzzyNameMatcher = new FuzzyNameMatcher();

    private EntityResolutionService service;

    private final UUID modelId = UUID.randomUUID();
    private final TenantContext tenant = TenantContext.of(modelId);
    private final double threshold = 0.85;
    private final String embeddingModel = "nomic-embed-text";

    @BeforeEach
    void setUp() {
        service = new EntityResolutionService(graphRepository, embeddingService, fuzzyNameMatcher);
    }

    @Test
    void exact_name_type_match_returns_match_tier_exact() {
        UUID existingUuid = UUID.randomUUID();
        NodeState existing =
                new NodeState(existingUuid, "Person", Map.of("name", "Jane Smith"), Instant.now(), Instant.now());

        when(graphRepository.queryAll(tenant, "Person")).thenReturn(List.of(existing));

        ResolutionCandidate candidate = new ResolutionCandidate("Person", "Jane Smith", Map.of(), BigDecimal.ONE);
        ResolutionResult result = service.resolve(candidate, tenant, threshold, true, embeddingModel);

        assertThat(result).isInstanceOf(ResolutionResult.Match.class);
        ResolutionResult.Match match = (ResolutionResult.Match) result;
        assertThat(match.existingNodeUuid()).isEqualTo(existingUuid);
        assertThat(match.tier()).isEqualTo("EXACT");
        assertThat(match.score()).isEqualTo(1.0);
    }

    @Test
    void no_exact_match_embedding_similarity_above_threshold_returns_embedding_tier() {
        UUID existingUuid = UUID.randomUUID();

        // No exact match
        when(graphRepository.queryAll(tenant, "Organization")).thenReturn(List.of());

        // Embedding finds a similar entity
        float[] queryEmb = new float[] {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("Acme Corp")).thenReturn(queryEmb);
        when(embeddingService.findSimilar(modelId, embeddingModel, queryEmb, 5))
                .thenReturn(List.of(new EmbeddingService.SimilarEntity(existingUuid, 0.92)));

        // Type match verification
        NodeState node = new NodeState(
                existingUuid, "Organization", Map.of("name", "ACME Corporation"), Instant.now(), Instant.now());
        when(graphRepository.findNode(tenant, "Organization", existingUuid)).thenReturn(Optional.of(node));

        ResolutionCandidate candidate = new ResolutionCandidate("Organization", "Acme Corp", Map.of(), BigDecimal.ONE);
        ResolutionResult result = service.resolve(candidate, tenant, threshold, true, embeddingModel);

        assertThat(result).isInstanceOf(ResolutionResult.Match.class);
        ResolutionResult.Match match = (ResolutionResult.Match) result;
        assertThat(match.existingNodeUuid()).isEqualTo(existingUuid);
        assertThat(match.tier()).isEqualTo("EMBEDDING");
        assertThat(match.score()).isEqualTo(0.92);
    }

    @Test
    void no_exact_no_embedding_fuzzy_match_above_threshold_returns_fuzzy_tier() {
        UUID existingUuid = UUID.randomUUID();
        NodeState existing =
                new NodeState(existingUuid, "Person", Map.of("name", "John Smith"), Instant.now(), Instant.now());

        when(graphRepository.queryAll(tenant, "Person")).thenReturn(List.of(existing));

        // Embedding returns no matches above threshold
        float[] queryEmb = new float[] {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("Jon Smith")).thenReturn(queryEmb);
        when(embeddingService.findSimilar(modelId, embeddingModel, queryEmb, 5)).thenReturn(List.of());

        // "Jon Smith" vs "John Smith" -> Levenshtein similarity = 0.9, above 0.85
        ResolutionCandidate candidate = new ResolutionCandidate("Person", "Jon Smith", Map.of(), BigDecimal.ONE);
        ResolutionResult result = service.resolve(candidate, tenant, threshold, true, embeddingModel);

        assertThat(result).isInstanceOf(ResolutionResult.Match.class);
        ResolutionResult.Match match = (ResolutionResult.Match) result;
        assertThat(match.existingNodeUuid()).isEqualTo(existingUuid);
        assertThat(match.tier()).isEqualTo("FUZZY");
        assertThat(match.score()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void all_three_tiers_fail_returns_review_queue() {
        NodeState existing = new NodeState(
                UUID.randomUUID(), "Person", Map.of("name", "Alice Johnson"), Instant.now(), Instant.now());

        when(graphRepository.queryAll(tenant, "Person")).thenReturn(List.of(existing));

        // Embedding returns no matches above threshold
        float[] queryEmb = new float[] {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("Bob Williams")).thenReturn(queryEmb);
        when(embeddingService.findSimilar(modelId, embeddingModel, queryEmb, 5)).thenReturn(List.of());

        // "Bob Williams" vs "Alice Johnson" -> very low fuzzy similarity
        ResolutionCandidate candidate = new ResolutionCandidate("Person", "Bob Williams", Map.of(), BigDecimal.ONE);
        ResolutionResult result = service.resolve(candidate, tenant, threshold, true, embeddingModel);

        assertThat(result).isInstanceOf(ResolutionResult.NeedsReview.class);
        ResolutionResult.NeedsReview rq = (ResolutionResult.NeedsReview) result;
        assertThat(rq.tier()).isEqualTo("ALL");
    }

    @Test
    void determinism_same_inputs_same_result_twice() {
        UUID existingUuid = UUID.randomUUID();
        NodeState existing =
                new NodeState(existingUuid, "Person", Map.of("name", "Jane Smith"), Instant.now(), Instant.now());

        when(graphRepository.queryAll(tenant, "Person")).thenReturn(List.of(existing));

        ResolutionCandidate candidate = new ResolutionCandidate("Person", "Jane Smith", Map.of(), BigDecimal.ONE);

        ResolutionResult first = service.resolve(candidate, tenant, threshold, true, embeddingModel);
        ResolutionResult second = service.resolve(candidate, tenant, threshold, true, embeddingModel);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void embedding_disabled_skips_tier_2_goes_to_tier_3() {
        UUID existingUuid = UUID.randomUUID();
        NodeState existing =
                new NodeState(existingUuid, "Person", Map.of("name", "John Smith"), Instant.now(), Instant.now());

        when(graphRepository.queryAll(tenant, "Person")).thenReturn(List.of(existing));

        // "Jon Smith" vs "John Smith" -> fuzzy match above threshold
        ResolutionCandidate candidate = new ResolutionCandidate("Person", "Jon Smith", Map.of(), BigDecimal.ONE);
        ResolutionResult result = service.resolve(candidate, tenant, threshold, false, embeddingModel);

        assertThat(result).isInstanceOf(ResolutionResult.Match.class);
        ResolutionResult.Match match = (ResolutionResult.Match) result;
        assertThat(match.tier()).isEqualTo("FUZZY");

        // Verify embedding service was never called
        verify(embeddingService, never()).embed(anyString());
        verify(embeddingService, never()).findSimilar(any(), anyString(), any(), anyInt());
    }
}
