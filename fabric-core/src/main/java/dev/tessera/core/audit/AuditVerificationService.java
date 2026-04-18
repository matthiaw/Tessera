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
package dev.tessera.core.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.events.HashChain;
import dev.tessera.core.tenant.TenantContext;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * AUDIT-02: Sequential hash-chain verification for compliance-sensitive tenants.
 *
 * <p>Walks all {@code graph_events} for a tenant in {@code sequence_nr} order
 * and recomputes {@code SHA-256(prev_hash || payload)} for each event. Any
 * mismatch between the stored {@code prev_hash} and the recomputed value
 * indicates tampering or data corruption.
 *
 * <p>Uses a {@code RowCallbackHandler} (streaming cursor) rather than
 * {@code queryForList()} so that tenants with millions of events do not cause
 * an OOM. The read-only transaction hint routes the query to a read replica if
 * one is available.
 *
 * <p>This service runs OUTSIDE any write transaction — it is a read-only
 * operation and must never acquire write locks.
 *
 * <p>Payload re-compaction: {@code graph_events.payload} is stored as
 * {@code JSONB}. Postgres normalises JSONB (adds spaces after {@code :} and
 * {@code ,}) before storing. The hash was computed at write time against the
 * compact JSON string produced by {@code JsonMaps.toJson()} (no spaces,
 * sorted keys). Verification therefore re-compacts each payload back to the
 * same canonical form before recomputing the expected hash.
 */
@Component
public class AuditVerificationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String EVENTS_SQL = "SELECT sequence_nr, prev_hash, payload "
            + "FROM graph_events "
            + "WHERE model_id = :mid::uuid "
            + "ORDER BY sequence_nr ASC";

    private final NamedParameterJdbcTemplate jdbc;

    public AuditVerificationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Verify the hash chain for a tenant.
     *
     * @param ctx tenant to verify
     * @return {@link AuditVerificationResult#valid(long)} if intact,
     *         {@link AuditVerificationResult#broken(long, String, String, long)} at the first break
     */
    @Transactional(readOnly = true)
    public AuditVerificationResult verify(TenantContext ctx) {
        UUID modelId = ctx.modelId();
        MapSqlParameterSource p = new MapSqlParameterSource("mid", modelId.toString());

        // Mutable state captured by the RowCallbackHandler closure
        final long[] eventsChecked = {0};
        final String[] prevHashHolder = {HashChain.genesis()};
        final AuditVerificationResult[] broken = {null};

        jdbc.query(EVENTS_SQL, p, rs -> {
            // Abort processing once a break is found
            if (broken[0] != null) return;

            long seq = rs.getLong("sequence_nr");
            String storedPrevHash = rs.getString("prev_hash");
            String pgPayload = rs.getString("payload");

            // Re-compact Postgres jsonb text to canonical form used at hash-time
            String payloadJson = recompactJson(pgPayload);

            // The genesis logic: prevHashHolder starts at genesis for the FIRST event,
            // meaning: expected = HashChain.compute(genesis, firstPayload)
            // For subsequent events: expected = HashChain.compute(prev.prev_hash, payload)
            String expectedHash = HashChain.compute(prevHashHolder[0], payloadJson);

            if (!expectedHash.equals(storedPrevHash)) {
                broken[0] = AuditVerificationResult.broken(seq, expectedHash, storedPrevHash, eventsChecked[0]);
                return;
            }

            // Advance: next event's expected-prev is this event's stored prev_hash
            prevHashHolder[0] = storedPrevHash;
            eventsChecked[0]++;
        });

        if (broken[0] != null) {
            return broken[0];
        }
        return AuditVerificationResult.valid(eventsChecked[0]);
    }

    /**
     * Re-compact a Postgres {@code JSONB} text value to the canonical sorted-key compact
     * JSON form that {@code JsonMaps.toJson()} produces. This must exactly reproduce the
     * string that was hashed at write time.
     *
     * <p>Postgres JSONB adds spaces after {@code :} and {@code ,}; this method strips
     * them by parsing and re-serialising with Jackson's default (compact) settings.
     */
    private static String recompactJson(String pgJson) {
        if (pgJson == null || pgJson.isBlank()) {
            return "{}";
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(pgJson, MAP_TYPE);
            // Sort keys to match JsonMaps.toJson() which uses TreeMap
            return MAPPER.writeValueAsString(new TreeMap<>(parsed));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to recompact JSONB payload for hash verification: " + pgJson, e);
        }
    }
}
