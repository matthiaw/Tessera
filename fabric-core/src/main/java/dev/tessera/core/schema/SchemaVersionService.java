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
package dev.tessera.core.schema;

import dev.tessera.core.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCHEMA-04 / D-B2: event-sourced schema versioning with materialized snapshots.
 *
 * <p>Every change is appended to {@code schema_change_event} and a new
 * {@code schema_version} row is materialized carrying a JSON snapshot. The
 * {@code is_current} flag marks the active version per model (exactly one
 * current version enforced by a partial unique index).
 */
@Component
public class SchemaVersionService {

    private final NamedParameterJdbcTemplate jdbc;

    public SchemaVersionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Append a change event and materialize a new snapshot. Returns the new version_nr. */
    @Transactional(propagation = Propagation.REQUIRED)
    public long applyChange(TenantContext ctx, String changeType, String payloadJson, String causedBy) {
        long current = currentVersion(ctx);
        long next = current + 1;

        // 1. Append change event.
        MapSqlParameterSource eventParams = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("model_id", ctx.modelId().toString())
                .addValue("change_type", changeType)
                .addValue("payload", payloadJson)
                .addValue("caused_by", causedBy);
        jdbc.update(
                "INSERT INTO schema_change_event (id, model_id, change_type, payload, caused_by)"
                        + " VALUES (:id::uuid, :model_id::uuid, :change_type, :payload::jsonb, :caused_by)",
                eventParams);

        // 2. Demote old current snapshot.
        MapSqlParameterSource modelParams =
                new MapSqlParameterSource("model_id", ctx.modelId().toString());
        jdbc.update(
                "UPDATE schema_version SET is_current = false"
                        + " WHERE model_id = :model_id::uuid AND is_current = true",
                modelParams);

        // 3. Insert new snapshot row, is_current=true.
        MapSqlParameterSource snapParams = new MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("version_nr", next)
                .addValue("snapshot", payloadJson);
        jdbc.update(
                "INSERT INTO schema_version (model_id, version_nr, snapshot, is_current)"
                        + " VALUES (:model_id::uuid, :version_nr, :snapshot::jsonb, true)",
                snapParams);
        return next;
    }

    public long currentVersion(TenantContext ctx) {
        MapSqlParameterSource p =
                new MapSqlParameterSource("model_id", ctx.modelId().toString());
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_nr), 0) FROM schema_version WHERE model_id = :model_id::uuid",
                p,
                Long.class);
        return v == null ? 0L : v;
    }

    public String snapshotAt(TenantContext ctx, long versionNr) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("version_nr", versionNr);
        return jdbc.queryForObject(
                "SELECT snapshot::text FROM schema_version"
                        + " WHERE model_id = :model_id::uuid AND version_nr = :version_nr",
                p,
                String.class);
    }
}
