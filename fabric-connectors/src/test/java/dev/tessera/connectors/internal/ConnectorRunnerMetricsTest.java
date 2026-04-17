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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tessera.connectors.CandidateMutation;
import dev.tessera.connectors.Connector;
import dev.tessera.connectors.ConnectorCapabilities;
import dev.tessera.connectors.ConnectorInstance;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.metrics.MetricsPort;
import dev.tessera.core.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OPS-01: Unit tests for {@link ConnectorRunner} metrics integration.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>{@link MetricsPort#recordIngest()} fires once per {@link GraphMutationOutcome.Committed}
 *       outcome in {@link ConnectorRunner#runOnce(ConnectorInstance)}.</li>
 *   <li>{@link MetricsPort#recordIngest()} does NOT fire when the outcome is
 *       {@link GraphMutationOutcome.Rejected}.</li>
 *   <li>When {@code metricsPort} is {@code null}, a Committed outcome completes without
 *       throwing a NullPointerException.</li>
 * </ol>
 */
class ConnectorRunnerMetricsTest {

    /** Simple stub that counts calls to each MetricsPort method. */
    static class RecordingMetricsPort implements MetricsPort {
        int ingestCount = 0;
        int ruleEvalCount = 0;
        int conflictCount = 0;
        long lastNanos = -1L;

        @Override
        public void recordIngest() {
            ingestCount++;
        }

        @Override
        public void recordRuleEvaluation() {
            ruleEvalCount++;
        }

        @Override
        public void recordConflict() {
            conflictCount++;
        }

        @Override
        public void recordShaclValidationNanos(long nanos) {
            lastNanos = nanos;
        }
    }

    private GraphService graphService;
    private SyncStatusRepository syncStatusRepo;

    @BeforeEach
    void setUp() {
        graphService = mock(GraphService.class);
        syncStatusRepo = mock(SyncStatusRepository.class);
        // getState() must return a non-null ConnectorState to avoid NPE in runOnce()
        when(syncStatusRepo.getState(any(UUID.class))).thenReturn(ConnectorState.empty());
    }

    private ConnectorRunner newRunner(MetricsPort metricsPort) {
        return new ConnectorRunner(
                graphService,
                syncStatusRepo,
                Clock.systemUTC(),
                null, // EntityResolutionService — optional, not needed
                null, // EmbeddingService — optional, not needed
                null, // ExtractionReviewRepository — optional, not needed
                metricsPort);
    }

    private ConnectorInstance instanceWithOneCandidate() {
        TenantContext tenant = TenantContext.of(UUID.randomUUID());
        UUID connectorId = UUID.randomUUID();

        CandidateMutation candidate = new CandidateMutation(
                "Person",
                null,
                Map.of("name", "Alice"),
                "crm",
                connectorId.toString(),
                "change-1",
                null, // sourceDocumentId — null means structured (not extraction)
                null,
                null,
                null,
                null);

        PollResult pollResult =
                new PollResult(List.of(candidate), ConnectorState.empty(), SyncOutcome.SUCCESS, List.of());

        Connector connector = mock(Connector.class);
        when(connector.type()).thenReturn("rest-poll");
        when(connector.capabilities()).thenReturn(mock(ConnectorCapabilities.class));
        when(connector.poll(any(), any(), any(), any())).thenReturn(pollResult);

        MappingDefinition mapping = new MappingDefinition(
                "Contact", "Person", null, List.of(), List.of(), null, null, null, null, null, null, null);

        return new ConnectorInstance(connectorId, tenant, connector, mapping, null, 60, true);
    }

    @Test
    void runOnce_committed_records_ingest() {
        GraphMutationOutcome committed = new GraphMutationOutcome.Committed(UUID.randomUUID(), 1L, UUID.randomUUID());
        when(graphService.apply(any(GraphMutation.class))).thenReturn(committed);

        RecordingMetricsPort port = new RecordingMetricsPort();
        ConnectorRunner runner = newRunner(port);

        runner.runOnce(instanceWithOneCandidate());

        assertThat(port.ingestCount)
                .as("recordIngest must be called exactly once per Committed outcome")
                .isEqualTo(1);
    }

    @Test
    void runOnce_rejected_does_not_record_ingest() {
        GraphMutationOutcome rejected = new GraphMutationOutcome.Rejected("rule-id", "test rejection");
        when(graphService.apply(any(GraphMutation.class))).thenReturn(rejected);

        RecordingMetricsPort port = new RecordingMetricsPort();
        ConnectorRunner runner = newRunner(port);

        runner.runOnce(instanceWithOneCandidate());

        assertThat(port.ingestCount)
                .as("recordIngest must NOT be called when outcome is Rejected")
                .isEqualTo(0);
    }

    @Test
    void null_metricsPort_committed_does_not_throw() {
        GraphMutationOutcome committed = new GraphMutationOutcome.Committed(UUID.randomUUID(), 1L, UUID.randomUUID());
        when(graphService.apply(any(GraphMutation.class))).thenReturn(committed);

        ConnectorRunner runner = newRunner(null); // null MetricsPort

        assertThatCode(() -> runner.runOnce(instanceWithOneCandidate()))
                .as("runOnce() must succeed without NPE when metricsPort is null")
                .doesNotThrowAnyException();
    }
}
