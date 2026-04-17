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
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
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
                source_document_id, source_chunk_range,
                origin_connector_id, origin_change_id, prev_hash
            ) VALUES (
                :model_id::uuid, :sequence_nr, :event_type, :node_uuid::uuid, :edge_uuid::uuid, :type_slug,
                :payload::jsonb, :delta::jsonb, :caused_by, :source_type, :source_id, :source_system,
                :confidence, :extractor_version, :llm_model_id,
                :source_document_id, :source_chunk_range,
                :origin_connector_id, :origin_change_id, :prev_hash
            )
            RETURNING id
            """;

    // D-C2: per-tenant hash_chain_enabled flag cache — avoids a DB round-trip per event.
    // Invalidate via invalidateHashChainConfig(modelId) when tenant config changes.
    private final ConcurrentHashMap<UUID, Boolean> hashChainEnabledCache = new ConcurrentHashMap<>();

    // D-C4: per-tenant JVM lock objects to serialize hash-chain appends within a single JVM.
    // ConcurrentHashMap.computeIfAbsent ensures exactly one lock object per tenant UUID.
    // This is sufficient for MVP (single-instance deployment). Multi-instance deployments
    // require distributed locking (e.g., pg_advisory_lock) — deferred to Phase 5.
    private final ConcurrentHashMap<UUID, Object> tenantLocks = new ConcurrentHashMap<>();

    private static final String HASH_CHAIN_CONFIG_SQL =
            "SELECT hash_chain_enabled FROM model_config WHERE model_id = :mid::uuid";

    private static final String PREDECESSOR_HASH_SQL =
            "SELECT prev_hash FROM graph_events WHERE model_id = :mid::uuid "
                    + "ORDER BY sequence_nr DESC LIMIT 1 FOR UPDATE";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final SequenceAllocator allocator;

    public EventLog(NamedParameterJdbcTemplate jdbc, SequenceAllocator allocator) {
        this.jdbc = jdbc;
        this.allocator = allocator;
    }

    /**
     * D-C2: Invalidate the cached {@code hash_chain_enabled} flag for a tenant.
     * Call this when the tenant's {@code model_config} row is updated.
     */
    public void invalidateHashChainConfig(UUID modelId) {
        hashChainEnabledCache.remove(modelId);
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
        p.addValue("source_document_id", mutation.sourceDocumentId(), Types.VARCHAR);
        p.addValue("source_chunk_range", mutation.sourceChunkRange(), Types.VARCHAR);
        p.addValue("origin_connector_id", mutation.originConnectorId(), Types.VARCHAR);
        p.addValue("origin_change_id", mutation.originChangeId(), Types.VARCHAR);

        // D-C2/C4: hash chain — compute prev_hash and INSERT within the per-tenant JVM lock
        // so that no other thread can read the same predecessor between our read and our commit.
        return appendWithHashChain(ctx.modelId(), payloadJson, p, seq);
    }

    /**
     * D-C2/C4: Execute the INSERT with hash-chain prev_hash computation, serialized per
     * tenant via a JVM lock that spans both the predecessor read AND the INSERT.
     *
     * <p>For non-hash-chain tenants: performs the INSERT immediately with {@code null}
     * prev_hash — no locking overhead.
     *
     * <p>For hash-chain-enabled tenants: acquires a per-tenant JVM {@code synchronized}
     * lock that covers the full sequence: read predecessor → compute hash → INSERT.
     * This prevents two threads from reading the same predecessor and producing duplicate
     * prev_hash values (Pitfall 5 / READ COMMITTED race). The lock is held until the
     * INSERT completes — the surrounding {@code @Transactional} commits immediately after
     * {@code append()} returns, so the lock hold time equals one INSERT round-trip.
     *
     * <p>For multi-instance deployments, replace the JVM lock with a distributed lock
     * (e.g., {@code pg_advisory_lock} session-scoped) before Phase 5.
     */
    private Appended appendWithHashChain(
            UUID modelId, String payloadJson, MapSqlParameterSource p, long seq) {
        boolean enabled = hashChainEnabledCache.computeIfAbsent(modelId, mid -> {
            MapSqlParameterSource cp = new MapSqlParameterSource("mid", mid.toString());
            List<Boolean> rows = jdbc.queryForList(HASH_CHAIN_CONFIG_SQL, cp, Boolean.class);
            return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
        });

        if (!enabled) {
            // Non-hash-chain tenant: insert with null prev_hash immediately, no lock needed.
            p.addValue("prev_hash", null, Types.VARCHAR);
            UUID id = jdbc.queryForObject(INSERT, p, UUID.class);
            if (id == null) throw new IllegalStateException("graph_events insert returned null id");
            return new Appended(id, seq);
        }

        // D-C4: per-tenant JVM lock covers read → compute → INSERT atomically within this JVM.
        // Without this, two concurrent transactions read the same predecessor under READ COMMITTED
        // isolation and produce identical prev_hash values, breaking the linear chain (Pitfall 5).
        Object tenantLock = tenantLocks.computeIfAbsent(modelId, id -> new Object());
        synchronized (tenantLock) {
            MapSqlParameterSource pp = new MapSqlParameterSource("mid", modelId.toString());
            List<String> predecessors = jdbc.queryForList(PREDECESSOR_HASH_SQL, pp, String.class);

            String prevHash;
            if (predecessors.isEmpty()) {
                prevHash = HashChain.genesis();
            } else {
                prevHash = predecessors.get(0);
                if (prevHash == null) {
                    prevHash = HashChain.genesis();
                }
            }

            String newHash = HashChain.compute(prevHash, payloadJson);
            p.addValue("prev_hash", newHash, Types.VARCHAR);

            UUID id = jdbc.queryForObject(INSERT, p, UUID.class);
            if (id == null) throw new IllegalStateException("graph_events insert returned null id");
            return new Appended(id, seq);
        }
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
