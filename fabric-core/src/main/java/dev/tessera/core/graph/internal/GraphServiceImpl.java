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

import dev.tessera.core.circuit.CircuitBreakerPort;
import dev.tessera.core.connector.dlq.ConnectorDlqWriter;
import dev.tessera.core.events.EventLog;
import dev.tessera.core.events.Outbox;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.rules.ReconciliationConflictsRepository;
import dev.tessera.core.rules.RuleEnginePort;
import dev.tessera.core.rules.RuleRejectException;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.validation.ShaclValidationException;
import dev.tessera.core.validation.ShaclValidator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CORE-01: the single {@code @Transactional} write funnel. Every mutation
 * flows through {@link #apply}; the Cypher write, event log append, and
 * outbox insert all share one Postgres transaction and roll back atomically.
 *
 * <p>Wave 3 Task 2 wires the rule engine pipeline: VALIDATE →
 * RECONCILE → ENRICH → ROUTE runs before the Cypher write. VALIDATE Reject
 * throws {@link RuleRejectException}; RECONCILE Override accumulates
 * {@code reconciliation_conflicts} rows written after the event log append
 * (so {@code event_id} is available). ROUTE hints are passed to the outbox
 * insert.
 */
@Service
public class GraphServiceImpl implements GraphService {

    private final GraphSession graphSession;
    private final GraphRepositoryImpl graphRepository;
    private final EventLog eventLog;
    private final Outbox outbox;
    private final SchemaRegistry schemaRegistry;
    private final ShaclValidator shaclValidator;
    private final RuleEnginePort ruleEngine;
    private final ReconciliationConflictsRepository conflictsRepository;
    private final CircuitBreakerPort circuitBreaker;

    /**
     * 02-W1-02 / CONTEXT Decision 14: connector-origin DLQ writer. Autowired
     * as an optional field so Phase 1 test fixtures ({@code PipelineFixture})
     * that construct {@code GraphServiceImpl} via the explicit constructor
     * continue to work without a DLQ writer; in production Spring always
     * populates the bean.
     */
    @Autowired(required = false)
    private ConnectorDlqWriter connectorDlqWriter;

    /**
     * Sole constructor. {@code schemaRegistry}, {@code shaclValidator},
     * {@code ruleEngine}, {@code conflictsRepository}, and
     * {@code circuitBreaker} MAY be null for legacy test harnesses (JMH
     * benches, jqwik property tests) that pre-date the Wave 2 Schema
     * Registry / Wave 3 SHACL + rule engine / Wave 3 circuit breaker; in
     * production Spring always wires real beans. Null tolerance is a
     * transitional concession.
     */
    public GraphServiceImpl(
            GraphSession graphSession,
            EventLog eventLog,
            Outbox outbox,
            SchemaRegistry schemaRegistry,
            ShaclValidator shaclValidator,
            RuleEnginePort ruleEngine,
            ReconciliationConflictsRepository conflictsRepository,
            CircuitBreakerPort circuitBreaker) {
        this.graphSession = graphSession;
        this.graphRepository = new GraphRepositoryImpl(graphSession);
        this.eventLog = eventLog;
        this.outbox = outbox;
        this.schemaRegistry = schemaRegistry;
        this.shaclValidator = shaclValidator;
        this.ruleEngine = ruleEngine;
        this.conflictsRepository = conflictsRepository;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public GraphMutationOutcome apply(GraphMutation mutation) {
        // authorize(mutation)                           -- TODO(W3): Spring Security integration
        // SCHEMA-06: load descriptor through Caffeine-cached SchemaRegistry. Permissive in
        // Wave 2 — unregistered types are allowed (bootstrap flows, Wave 1 ITs) but the
        // loadFor call is on the hot path so cache hit rate is observable.
        NodeTypeDescriptor resolvedDescriptor = null;
        if (schemaRegistry != null && !mutation.type().startsWith(GraphSession.EDGE_PREFIX)) {
            Optional<NodeTypeDescriptor> descriptor = schemaRegistry.loadFor(mutation.tenantContext(), mutation.type());
            if (descriptor.isPresent()) {
                resolvedDescriptor = descriptor.get();
            }
        }

        // Capture previous state once — shared between rule engine RECONCILE chain
        // and EVENT-03 delta computation.
        Map<String, Object> previousState = capturePreviousState(mutation);

        // ADR-7 §RULE-02 rule engine pipeline: VALIDATE → RECONCILE → ENRICH → ROUTE.
        // Runs before SHACL so VALIDATE Rejects short-circuit cheapest, and before
        // the Cypher write so ENRICH/MERGE/OVERRIDE outcomes mutate the property
        // state that ends up in AGE + graph_events + graph_outbox.
        //
        // 02-W1-02 / CONTEXT Decision 14: wrap the rule engine + SHACL +
        // graphSession.apply chain in a try/catch. On RuleRejectException or
        // ShaclValidationException, IF the mutation came from a connector
        // (originConnectorId != null) write a DLQ row BEFORE re-throwing. The
        // DLQ writer uses Propagation.REQUIRES_NEW so the insert commits on a
        // separate connection while the outer @Transactional rolls back the
        // graph mutation.
        GraphMutation effective = mutation;
        Map<String, Object> routingHints = Map.of();
        RuleEnginePort.Outcome engineOutcome = null;
        NodeState state;
        try {
            if (ruleEngine != null) {
                // 02-W0 Task 2 closes 01-VERIFICATION Known Deviation #1: thread
                // a per-property source-system map derived from the pre-mutation
                // node state into ruleEngine.run. Empty on CREATE (previousState
                // is Map.of()). AuthorityReconciliationRule.findFirstContested
                // short-circuits on empty currentSourceSystem, so this line is
                // load-bearing for RULE-05/06 firing through the write funnel.
                Map<String, String> currentSourceSystem = deriveCurrentSourceSystemMap(previousState);
                engineOutcome = ruleEngine.run(
                        mutation.tenantContext(), resolvedDescriptor, previousState, currentSourceSystem, mutation);
                if (engineOutcome.rejected()) {
                    throw new RuleRejectException(engineOutcome.rejectReason(), engineOutcome.rejectingRuleId());
                }
                if (!engineOutcome.finalProperties().equals(mutation.payload())) {
                    effective = mutation.withPayload(engineOutcome.finalProperties());
                }
                routingHints = engineOutcome.routingHints();
            }

            // VALID-01: synchronous SHACL pre-commit. Runs inside the @Transactional
            // boundary so a thrown ShaclValidationException rolls back the Cypher
            // write, event-log append, and outbox insert atomically.
            if (shaclValidator != null && resolvedDescriptor != null) {
                shaclValidator.validate(effective.tenantContext(), resolvedDescriptor, effective);
            }

            state = graphSession.apply(effective.tenantContext(), effective);
        } catch (RuleRejectException rre) {
            recordConnectorDlqOnFailure(mutation, "RULE_REJECT", rre.getMessage(), rre.ruleId());
            throw rre;
        } catch (ShaclValidationException sve) {
            recordConnectorDlqOnFailure(mutation, "SHACL_VIOLATION", sve.getMessage(), null);
            throw sve;
        }

        String eventType = deriveEventType(effective);
        EventLog.Appended appended =
                eventLog.append(effective.tenantContext(), effective, state, eventType, previousState);

        // RULE-06 / D-C3: persist any RECONCILE-chain Override decisions. Runs
        // inside the same TX — rollback discards the conflict rows.
        if (engineOutcome != null
                && conflictsRepository != null
                && !engineOutcome.conflicts().isEmpty()) {
            for (RuleEnginePort.ConflictEntry conflict : engineOutcome.conflicts()) {
                conflictsRepository.record(effective.tenantContext(), appended.eventId(), state.uuid(), conflict);
            }
        }

        outbox.append(
                effective.tenantContext(),
                appended.eventId(),
                effective.type(),
                state.uuid(),
                eventType,
                state.properties(),
                routingHints);

        // Seed rule-engine caches with the committed origin-pair (echo loop).
        if (ruleEngine != null) {
            ruleEngine.onCommitted(
                    effective.tenantContext(), state.uuid(), effective.originConnectorId(), effective.originChangeId());
        }

        return new GraphMutationOutcome.Committed(state.uuid(), appended.sequenceNr(), appended.eventId());
    }

    /**
     * 02-W1-02: guarded DLQ write. No-op when the mutation did not originate
     * from a connector ({@code originConnectorId == null}) or when no
     * {@link ConnectorDlqWriter} bean is wired (Phase 1 test fixtures). The
     * DLQ writer itself runs on {@link Propagation#REQUIRES_NEW} so the row
     * commits even when the outer TX rolls back.
     */
    private void recordConnectorDlqOnFailure(
            GraphMutation mutation, String rejectionReason, String rejectionDetail, String ruleId) {
        if (connectorDlqWriter == null || mutation.originConnectorId() == null) {
            return;
        }
        try {
            connectorDlqWriter.record(mutation.tenantContext(), mutation, rejectionReason, rejectionDetail, ruleId);
        } catch (RuntimeException dlqFailure) {
            // A DLQ write failure must NOT mask the original rejection. The
            // outer catch re-throws the RuleReject / ShaclValidation exception
            // immediately after this helper returns; swallowing DLQ write
            // failures is deliberate — operator observability for failed DLQ
            // writes should come from a Spring async handler in a later wave.
        }
    }

    /**
     * Derive the per-property source-system map the rule engine consumes.
     * Wave 1 stamps {@code _source} at the node level (one value per node),
     * so every user-visible property shares the same source-system label.
     * Empty on CREATE. Phase-later refinement can move to per-property
     * source tracking without changing this signature.
     */
    private static Map<String, String> deriveCurrentSourceSystemMap(Map<String, Object> previousState) {
        if (previousState == null || previousState.isEmpty()) {
            return Map.of();
        }
        Object sourceObj = previousState.get("_source");
        if (sourceObj == null) {
            return Map.of();
        }
        String source = sourceObj.toString();
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (String key : previousState.keySet()) {
            if (key == null || key.isEmpty() || key.charAt(0) == '_') {
                continue; // skip system properties (_source, _uuid, _model_id, ...)
            }
            map.put(key, source);
        }
        return Map.copyOf(map);
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
