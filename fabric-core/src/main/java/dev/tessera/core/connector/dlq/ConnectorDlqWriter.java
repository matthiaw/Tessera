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
package dev.tessera.core.connector.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.tenant.TenantContext;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 02-W1-02 / CONTEXT Decision 14: writes a {@code connector_dlq} row when a
 * connector-origin {@link GraphMutation} fails SHACL validation or rule
 * rejection inside {@code GraphServiceImpl.apply}.
 *
 * <p>The write runs with {@link Propagation#REQUIRES_NEW} so it lands on a
 * fresh Postgres connection and commits independently of the outer graph
 * transaction — which is guaranteed to roll back because the caller re-throws
 * the validation / reject exception right after this method returns. The
 * outer rollback erases the attempted graph mutation; the nested DLQ TX
 * preserves the operator-visible record of the failed attempt. This is
 * Spring's standard "audit on exception" recipe, documented here because the
 * literal reading of CONTEXT Decision 14 ("in the same Postgres transaction")
 * would have produced a rolled-back DLQ row invisible to operators. The Wave
 * 1 plan explicitly re-interprets Decision 14 as nested REQUIRES_NEW (see
 * plan task 02-W1-02 behavior note).
 *
 * <p>Writes are restricted to connector-origin mutations
 * ({@code mutation.originConnectorId() != null}). Direct admin writes that
 * fail validation do NOT produce DLQ rows — the DLQ surface is exclusively
 * for upstream connector-submitted attempts.
 */
@Component
public class ConnectorDlqWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public ConnectorDlqWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert one DLQ row on a nested REQUIRES_NEW transaction. Returns the
     * inserted row's primary key.
     *
     * @param ctx              tenant context — drives {@code model_id}
     * @param mutation         the rejected mutation; its
     *                         {@code originConnectorId} MUST be non-null
     * @param rejectionReason  short machine code (e.g.
     *                         {@code "SHACL_VIOLATION"} or
     *                         {@code "RULE_REJECT"})
     * @param rejectionDetail  human-readable detail (exception message)
     * @param ruleId           rule that rejected; null for SHACL failures
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(
            TenantContext ctx, GraphMutation mutation, String rejectionReason, String rejectionDetail, String ruleId) {
        if (mutation.originConnectorId() == null) {
            throw new IllegalArgumentException(
                    "ConnectorDlqWriter.record called with null originConnectorId — caller must guard");
        }
        UUID id = UUID.randomUUID();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id.toString());
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("connector_id", mutation.originConnectorId());
        p.addValue("reason", rejectionReason);
        p.addValue("raw_payload", buildPayloadJson(mutation));
        p.addValue("rejection_reason", rejectionReason);
        p.addValue("rejection_detail", rejectionDetail);
        p.addValue("rule_id", ruleId, Types.VARCHAR);
        p.addValue("origin_change_id", mutation.originChangeId(), Types.VARCHAR);

        jdbc.update(
                """
                INSERT INTO connector_dlq
                    (id, model_id, connector_id, reason, raw_payload,
                     rejection_reason, rejection_detail, rule_id, origin_change_id, created_at)
                VALUES
                    (:id::uuid, :model_id::uuid, :connector_id, :reason, :raw_payload::jsonb,
                     :rejection_reason, :rejection_detail, :rule_id, :origin_change_id, clock_timestamp())
                """,
                p);
        return id;
    }

    /**
     * Serialize the candidate mutation to JSON for the {@code raw_payload}
     * JSONB column. Captures the fields operators need to triage a failed
     * attempt — type, operation, payload, origin tracking.
     */
    private static String buildPayloadJson(GraphMutation m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("operation", m.operation().name());
        dto.put("type", m.type());
        dto.put(
                "targetNodeUuid",
                m.targetNodeUuid() == null ? null : m.targetNodeUuid().toString());
        dto.put("payload", m.payload() == null ? Map.of() : m.payload());
        dto.put("sourceType", m.sourceType().name());
        dto.put("sourceId", m.sourceId());
        dto.put("sourceSystem", m.sourceSystem());
        dto.put("originConnectorId", m.originConnectorId());
        dto.put("originChangeId", m.originChangeId());
        try {
            return MAPPER.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize GraphMutation for DLQ", e);
        }
    }
}
