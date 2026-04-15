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
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** SCHEMA-05 / D-B3: property and edge-type alias translation tables. */
@Component
public class SchemaAliasService {

    private final NamedParameterJdbcTemplate jdbc;

    public SchemaAliasService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordPropertyAlias(TenantContext ctx, String typeSlug, String oldSlug, String currentSlug) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("type_slug", typeSlug)
                .addValue("old_slug", oldSlug)
                .addValue("current_slug", currentSlug);
        jdbc.update(
                "INSERT INTO schema_property_aliases (model_id, type_slug, old_slug, current_slug)"
                        + " VALUES (:model_id::uuid, :type_slug, :old_slug, :current_slug)"
                        + " ON CONFLICT (model_id, type_slug, old_slug) DO UPDATE"
                        + " SET current_slug = EXCLUDED.current_slug",
                p);
    }

    public Optional<String> resolveCurrentPropertySlug(TenantContext ctx, String typeSlug, String maybeOldSlug) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("type_slug", typeSlug)
                .addValue("old_slug", maybeOldSlug);
        List<String> rows = jdbc.queryForList(
                "SELECT current_slug FROM schema_property_aliases"
                        + " WHERE model_id = :model_id::uuid AND type_slug = :type_slug AND old_slug = :old_slug",
                p,
                String.class);
        return rows.stream().findFirst();
    }

    public void recordEdgeTypeAlias(TenantContext ctx, String oldSlug, String currentSlug) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("old_slug", oldSlug)
                .addValue("current_slug", currentSlug);
        jdbc.update(
                "INSERT INTO schema_edge_type_aliases (model_id, old_slug, current_slug)"
                        + " VALUES (:model_id::uuid, :old_slug, :current_slug)"
                        + " ON CONFLICT (model_id, old_slug) DO UPDATE SET current_slug = EXCLUDED.current_slug",
                p);
    }
}
