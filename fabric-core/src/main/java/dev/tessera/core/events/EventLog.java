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
package dev.tessera.core.events;

import dev.tessera.core.events.internal.SequenceAllocator;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.tenant.TenantContext;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * EVENT-01/02/03: append-only writer for {@code graph_events}. Runs inside
 * the caller's {@link org.springframework.transaction.annotation.Transactional}
 * boundary (typically {@code GraphServiceImpl.apply}).
 *
 * <p>Every append computes and persists a {@code delta} JSONB column per
 * EVENT-03 rules:
 *
 * <ul>
 *   <li>CREATE  → delta == full post-state payload
 *   <li>UPDATE  → delta == field-level diff of new vs previous state
 *   <li>TOMBSTONE → delta == {@code {"_tombstoned": true}}
 * </ul>
 *
 * <p>Every row carries {@code origin_connector_id} and {@code origin_change_id}
 * from the incoming {@link GraphMutation} — load-bearing for RULE-08
 * echo-loop suppression.
 */
@Component
public final class EventLog {

    private static final String INSERT =
            """
            INSERT INTO graph_events (
                model_id, sequence_nr, event_type, node_uuid, edge_uuid, type_slug,
                payload, delta, caused_by, source_type, source_id, source_system,
                confidence, extractor_version, llm_model_id,
                origin_connector_id, origin_change_id
            ) VALUES (
                :model_id::uuid, :sequence_nr, :event_type, :node_uuid::uuid, :edge_uuid::uuid, :type_slug,
                :payload::jsonb, :delta::jsonb, :caused_by, :source_type, :source_id, :source_system,
                :confidence, :extractor_version, :llm_model_id,
                :origin_connector_id, :origin_change_id
            )
            RETURNING id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SequenceAllocator allocator;

    public EventLog(NamedParameterJdbcTemplate jdbc, SequenceAllocator allocator) {
        this.jdbc = jdbc;
        this.allocator = allocator;
    }

    /**
     * Append one event row and return {@code (eventId, sequenceNr)}.
     *
     * @param previousState the pre-mutation property map; may be null/empty on CREATE.
     */
    public Appended append(
            TenantContext ctx,
            GraphMutation mutation,
            NodeState state,
            String eventType,
            Map<String, Object> previousState) {
        long seq = allocator.nextSequenceNr(ctx);
        String payloadJson = JsonMaps.toJson(state.properties());
        String deltaJson = JsonMaps.toJson(computeDelta(mutation.operation(), state.properties(), previousState));

        boolean isEdge = eventType.endsWith("_EDGE");

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("sequence_nr", seq);
        p.addValue("event_type", eventType);
        p.addValue("node_uuid", isEdge ? null : state.uuid().toString(), Types.VARCHAR);
        p.addValue("edge_uuid", isEdge ? state.uuid().toString() : null, Types.VARCHAR);
        p.addValue("type_slug", state.typeSlug());
        p.addValue("payload", payloadJson);
        p.addValue("delta", deltaJson);
        p.addValue("caused_by", mutation.sourceId());
        p.addValue("source_type", mutation.sourceType().name());
        p.addValue("source_id", mutation.sourceId());
        p.addValue("source_system", mutation.sourceSystem());
        p.addValue("confidence", mutation.confidence());
        p.addValue("extractor_version", mutation.extractorVersion(), Types.VARCHAR);
        p.addValue("llm_model_id", mutation.llmModelId(), Types.VARCHAR);
        p.addValue("origin_connector_id", mutation.originConnectorId(), Types.VARCHAR);
        p.addValue("origin_change_id", mutation.originChangeId(), Types.VARCHAR);

        UUID id = jdbc.queryForObject(INSERT, p, UUID.class);
        if (id == null) {
            throw new IllegalStateException("graph_events insert returned null id");
        }
        return new Appended(id, seq);
    }

    /** EVENT-03 delta computation. Public for cross-package unit testing (TimestampOwnershipTest). */
    public static Map<String, Object> computeDelta(
            Operation op, Map<String, Object> newState, Map<String, Object> previousState) {
        return switch (op) {
            case CREATE -> newState == null ? Map.of() : new LinkedHashMap<>(newState);
            case TOMBSTONE -> Map.of("_tombstoned", true);
            case UPDATE -> {
                Map<String, Object> diff = new LinkedHashMap<>();
                Map<String, Object> prev = previousState == null ? Map.of() : previousState;
                for (Map.Entry<String, Object> e : newState.entrySet()) {
                    Object oldV = prev.get(e.getKey());
                    if (oldV == null || !oldV.equals(e.getValue())) {
                        diff.put(e.getKey(), e.getValue());
                    }
                }
                yield diff;
            }
        };
    }

    /** Return value of {@link #append}. */
    public record Appended(UUID eventId, long sequenceNr) {}
}
