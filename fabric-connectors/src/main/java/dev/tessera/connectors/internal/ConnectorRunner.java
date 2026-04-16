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
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final GraphService graphService;
    private final SyncStatusRepository syncStatusRepo;
    private final Clock clock;

    public ConnectorRunner(GraphService graphService, SyncStatusRepository syncStatusRepo, Clock clock) {
        this.graphService = graphService;
        this.syncStatusRepo = syncStatusRepo;
        this.clock = clock;
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
                GraphMutation mutation = candidate.toMutation(instance.tenant());
                GraphMutationOutcome outcome = graphService.apply(mutation);
                if (outcome instanceof GraphMutationOutcome.Committed) {
                    successCount++;
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
}
