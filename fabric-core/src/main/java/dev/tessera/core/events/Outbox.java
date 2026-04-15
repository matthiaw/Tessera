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

import dev.tessera.core.tenant.TenantContext;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transactional outbox writer (EVENT-04). Runs in the same transaction as
 * {@link EventLog#append} and the Cypher write — guaranteed by
 * {@code GraphServiceImpl.apply}'s {@code @Transactional} boundary.
 *
 * <p>Row shape matches Debezium Outbox Event Router SMT (aggregatetype,
 * aggregateid, type, payload) so Phase 4 can swap in Debezium without
 * touching the write path.
 */
@Component
public final class Outbox {

    private static final String INSERT =
            """
            INSERT INTO graph_outbox (
                model_id, event_id, aggregatetype, aggregateid, type, payload, routing_hints, status
            ) VALUES (
                :model_id::uuid, :event_id::uuid, :aggregatetype, :aggregateid, :type,
                :payload::jsonb, :routing_hints::jsonb, 'PENDING'
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Outbox(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(
            TenantContext ctx,
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String type,
            Map<String, Object> payload,
            Map<String, Object> routingHints) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("event_id", eventId.toString());
        p.addValue("aggregatetype", aggregateType);
        p.addValue("aggregateid", aggregateId.toString());
        p.addValue("type", type);
        p.addValue("payload", JsonMaps.toJson(payload));
        p.addValue(
                "routing_hints",
                routingHints == null || routingHints.isEmpty() ? null : JsonMaps.toJson(routingHints),
                Types.VARCHAR);

        jdbc.update(INSERT, p);
    }
}
