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
package dev.tessera.core.graph.internal;

import dev.tessera.core.events.EventLog;
import dev.tessera.core.events.Outbox;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CORE-01: the single {@code @Transactional} write funnel. Every mutation
 * flows through {@link #apply}; the Cypher write, event log append, and
 * outbox insert all share one Postgres transaction and roll back atomically.
 *
 * <p>Wave 1 implements the subset of the pipeline from RESEARCH §"Pattern 1:
 * Single Write Funnel". Rule engine (VALIDATE / RECONCILE / ENRICH / ROUTE)
 * and SHACL validation are left as {@code TODO(W3)} markers at the exact
 * call sites Waves 2 and 3 will fill in.
 */
@Service
public class GraphServiceImpl implements GraphService {

    private final GraphSession graphSession;
    private final GraphRepositoryImpl graphRepository;
    private final EventLog eventLog;
    private final Outbox outbox;

    public GraphServiceImpl(GraphSession graphSession, EventLog eventLog, Outbox outbox) {
        this.graphSession = graphSession;
        this.graphRepository = new GraphRepositoryImpl(graphSession);
        this.eventLog = eventLog;
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public GraphMutationOutcome apply(GraphMutation mutation) {
        // authorize(mutation)                           -- TODO(W2): Spring Security integration
        // schemaRegistry.loadFor(ctx, mutation.type())  -- TODO(W2): schema lookup + Caffeine cache
        // ruleEngine.run(Chain.VALIDATE, ruleCtx)       -- TODO(W3): may REJECT
        // ruleEngine.run(Chain.RECONCILE, ruleCtx)      -- TODO(W3): may MERGE/OVERRIDE
        // ruleEngine.run(Chain.ENRICH, ruleCtx)         -- TODO(W3): adds derived fields
        // shaclValidator.validate(ctx, enriched)        -- TODO(W3): synchronous pre-commit

        // Capture previous state for EVENT-03 delta on UPDATE / TOMBSTONE.
        Map<String, Object> previousState = capturePreviousState(mutation);

        NodeState state = graphSession.apply(mutation.tenantContext(), mutation);

        String eventType = deriveEventType(mutation);
        EventLog.Appended appended =
                eventLog.append(mutation.tenantContext(), mutation, state, eventType, previousState);

        outbox.append(
                mutation.tenantContext(),
                appended.eventId(),
                mutation.type(),
                state.uuid(),
                eventType,
                state.properties(),
                Map.of()); // routing_hints empty in W1 — ROUTE chain in W3 populates

        // ruleEngine.run(Chain.ROUTE, ...)              -- TODO(W3): post-commit tag

        return new GraphMutationOutcome.Committed(state.uuid(), appended.sequenceNr(), appended.eventId());
    }

    private Map<String, Object> capturePreviousState(GraphMutation m) {
        if (m.operation() == Operation.CREATE || m.targetNodeUuid() == null) {
            return Map.of();
        }
        if (m.type().startsWith(GraphSession.EDGE_PREFIX)) {
            // Wave 1: edge read-back not implemented; delta for edges is computed from the payload.
            return Map.of();
        }
        Optional<NodeState> existing = graphRepository.findNode(m.tenantContext(), m.type(), m.targetNodeUuid());
        return existing.map(NodeState::properties).orElse(Map.of());
    }

    private static String deriveEventType(GraphMutation m) {
        boolean edge = m.type().startsWith(GraphSession.EDGE_PREFIX);
        return switch (m.operation()) {
            case CREATE -> edge ? "CREATE_EDGE" : "CREATE_NODE";
            case UPDATE -> edge ? "UPDATE_EDGE" : "UPDATE_NODE";
            case TOMBSTONE -> edge ? "TOMBSTONE_EDGE" : "TOMBSTONE_NODE";
        };
    }
}
