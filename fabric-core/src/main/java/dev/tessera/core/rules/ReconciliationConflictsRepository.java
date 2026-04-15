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
package dev.tessera.core.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RULE-06 / D-C3 writer for {@code reconciliation_conflicts}. Called by
 * {@code GraphServiceImpl.apply} inside the same {@code @Transactional}
 * boundary as the Cypher write + event log append + outbox insert — a
 * rollback discards any conflict rows written in the same pipeline run.
 *
 * <p>Lives in fabric-core because (a) it uses {@code spring-jdbc} which
 * fabric-core already depends on, and (b) {@code GraphServiceImpl} (also
 * fabric-core) needs to call it with the committed {@code event_id}. The
 * {@link dev.tessera.core.rules.RuleEnginePort.ConflictEntry} DTO is the
 * wire format shared with fabric-rules.
 */
@Component
public class ReconciliationConflictsRepository {

    private static final String INSERT =
            """
            INSERT INTO reconciliation_conflicts (
                id, model_id, event_id, type_slug, node_uuid, property_slug,
                losing_source_id, losing_source_system, losing_value,
                winning_source_id, winning_source_system, winning_value,
                rule_id, reason, created_at
            ) VALUES (
                :id::uuid, :model_id::uuid, :event_id::uuid, :type_slug, :node_uuid::uuid, :property_slug,
                :losing_source_id, :losing_source_system, :losing_value::jsonb,
                :winning_source_id, :winning_source_system, :winning_value::jsonb,
                :rule_id, :reason, :created_at
            )
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationConflictsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(TenantContext ctx, UUID eventId, UUID nodeUuid, RuleEnginePort.ConflictEntry conflict) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", UUID.randomUUID().toString());
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("event_id", eventId.toString());
        p.addValue("type_slug", conflict.typeSlug());
        p.addValue("node_uuid", nodeUuid.toString());
        p.addValue("property_slug", conflict.propertySlug());
        p.addValue("losing_source_id", conflict.losingSourceId());
        p.addValue("losing_source_system", conflict.losingSourceSystem());
        p.addValue("losing_value", toJson(conflict.losingValue()));
        p.addValue("winning_source_id", conflict.winningSourceId());
        p.addValue("winning_source_system", conflict.winningSourceSystem());
        p.addValue("winning_value", toJson(conflict.winningValue()));
        p.addValue("rule_id", conflict.ruleId());
        p.addValue("reason", conflict.reason());
        p.addValue("created_at", java.sql.Timestamp.from(Instant.now()));
        jdbc.update(INSERT, p);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise conflict value to JSON", e);
        }
    }
}
