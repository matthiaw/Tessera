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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.events.internal.SequenceAllocator;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final SequenceAllocator allocator;

    public EventLog(NamedParameterJdbcTemplate jdbc, SequenceAllocator allocator) {
        this.jdbc = jdbc;
        this.allocator = allocator;
    }

    /**
     * EVENT-06: reconstruct the state of a node by folding all events with
     * {@code event_time <= at} in {@code sequence_nr} order. Returns
     * {@link Optional#empty()} if no events exist for the node up to {@code at}.
     *
     * <p>Folding rule: each row's {@code payload} is the authoritative post-state
     * for that mutation, so the last row's payload (within the time window) is
     * the answer. TOMBSTONE rows are surfaced by adding a {@code _tombstoned=true}
     * sentinel to the returned map; callers may treat this as a "deleted" marker.
     */
    public Optional<Map<String, Object>> replayToState(TenantContext ctx, UUID nodeUuid, Instant at) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("node_uuid", nodeUuid.toString());
        p.addValue("at", java.sql.Timestamp.from(at));
        List<Map<String, Object>> folded = jdbc.query(
                """
                SELECT event_type, payload
                  FROM graph_events
                 WHERE model_id = :model_id::uuid
                   AND node_uuid = :node_uuid::uuid
                   AND event_time <= :at
                 ORDER BY sequence_nr ASC
                """,
                p,
                (rs, rowNum) -> {
                    String type = rs.getString("event_type");
                    Map<String, Object> payload = parseJson(rs.getString("payload"));
                    Map<String, Object> result = new LinkedHashMap<>(payload);
                    if ("TOMBSTONE_NODE".equals(type)) {
                        result.put("_tombstoned", Boolean.TRUE);
                    }
                    return result;
                });
        if (folded.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(folded.get(folded.size() - 1));
    }

    /**
     * EVENT-07: full mutation history for a node, ordered by {@code sequence_nr}.
     * Each {@link EventRow} carries the full provenance surface (D-A1) plus the
     * {@code payload}/{@code delta} JSONB columns parsed back into maps.
     */
    public List<EventRow> history(TenantContext ctx, UUID nodeUuid) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("node_uuid", nodeUuid.toString());
        return jdbc.query(
                """
                SELECT id, model_id, sequence_nr, event_type, node_uuid, type_slug,
                       payload, delta, source_type, source_id, source_system, confidence,
                       extractor_version, llm_model_id, origin_connector_id, origin_change_id,
                       event_time
                  FROM graph_events
                 WHERE model_id = :model_id::uuid
                   AND node_uuid = :node_uuid::uuid
                 ORDER BY sequence_nr ASC
                """,
                p,
                EVENT_ROW_MAPPER);
    }

    private static final RowMapper<EventRow> EVENT_ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        UUID id = UUID.fromString(rs.getString("id"));
        UUID modelId = UUID.fromString(rs.getString("model_id"));
        long seq = rs.getLong("sequence_nr");
        String type = rs.getString("event_type");
        String nodeStr = rs.getString("node_uuid");
        UUID nodeUuid = nodeStr == null ? null : UUID.fromString(nodeStr);
        String typeSlug = rs.getString("type_slug");
        Map<String, Object> payload = parseJson(rs.getString("payload"));
        Map<String, Object> delta = parseJson(rs.getString("delta"));
        String sourceType = rs.getString("source_type");
        String sourceId = rs.getString("source_id");
        String sourceSystem = rs.getString("source_system");
        BigDecimal confidence = rs.getBigDecimal("confidence");
        String extractorVersion = rs.getString("extractor_version");
        String llmModelId = rs.getString("llm_model_id");
        String originConnectorId = rs.getString("origin_connector_id");
        String originChangeId = rs.getString("origin_change_id");
        Instant eventTime = rs.getTimestamp("event_time").toInstant();
        return new EventRow(
                id,
                modelId,
                seq,
                type,
                nodeUuid,
                typeSlug,
                payload,
                delta,
                sourceType,
                sourceId,
                sourceSystem,
                confidence,
                extractorVersion,
                llmModelId,
                originConnectorId,
                originChangeId,
                eventTime);
    };

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse JSONB column: " + json, e);
        }
    }

    /**
     * EVENT-07 row carrier — full provenance + payload + delta for a single
     * {@code graph_events} row. Returned by {@link #history}.
     */
    public record EventRow(
            UUID id,
            UUID modelId,
            long sequenceNr,
            String eventType,
            UUID nodeUuid,
            String typeSlug,
            Map<String, Object> payload,
            Map<String, Object> delta,
            String sourceType,
            String sourceId,
            String sourceSystem,
            BigDecimal confidence,
            String extractorVersion,
            String llmModelId,
            String originConnectorId,
            String originChangeId,
            Instant eventTime) {}

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
        // Phase 2 W1 CONTEXT Decision 12: if GraphSession already stamped a
        // _seq onto the node (state.seq() > 0), reuse that allocation so the
        // graph_events.sequence_nr row and the node's _seq property share one
        // monotonic value. Fall back to allocating here when the caller is a
        // legacy test path that bypasses GraphSession.apply's seq stamping.
        long seq = state.seq() > 0 ? state.seq() : allocator.nextSequenceNr(ctx);
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
