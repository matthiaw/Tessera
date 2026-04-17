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
package dev.tessera.projections.sql;

import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.schema.SchemaVersionService;
import dev.tessera.core.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQL-01/SQL-02 / D-A2/D-D3: Generates per-tenant per-type PostgreSQL views over AGE label tables.
 *
 * <p>Views bypass Cypher entirely and allow BI tools (Metabase, Looker, PowerBI) to run
 * standard SQL aggregates against the graph data without the ~15x AGE Cypher aggregation cliff.
 *
 * <p>Key design points:
 * <ul>
 *   <li>Reads AGE label tables via plain SQL — {@code (properties::jsonb)->>'key'} cast avoids
 *       the agtype-is-not-jsonb pitfall (Pitfall 1).</li>
 *   <li>View DDL embeds a {@code /* schema_version:N *}{@code /} comment for staleness
 *       detection (D-D3); regeneration is skipped when the version matches.</li>
 *   <li>Each view filters by {@code model_id} (T-04-I1) and excludes tombstoned entities.</li>
 *   <li>Startup regeneration via {@link ApplicationRunner} (D-A2) rebuilds all views after
 *       application restart to survive schema drift.</li>
 * </ul>
 *
 * <p>Thread safety: {@link #activeViews} is a {@link ConcurrentHashMap} so the admin endpoint
 * can read it concurrently with regeneration calls.
 */
@Component
public class SqlViewProjection implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlViewProjection.class);

    /** Fixed AGE graph name — must match GraphSession.GRAPH_NAME. */
    private static final String GRAPH_NAME = "tessera_main";

    private final SchemaRegistry schemaRegistry;
    private final SchemaVersionService schemaVersionService;
    private final NamedParameterJdbcTemplate jdbc;

    /** Active view metadata keyed by view name. Thread-safe. */
    private final ConcurrentHashMap<String, ViewMetadata> activeViews = new ConcurrentHashMap<>();

    public SqlViewProjection(
            SchemaRegistry schemaRegistry,
            SchemaVersionService schemaVersionService,
            NamedParameterJdbcTemplate jdbc) {
        this.schemaRegistry = schemaRegistry;
        this.schemaVersionService = schemaVersionService;
        this.jdbc = jdbc;
    }

    // -----------------------------------------------------------------------
    // ApplicationRunner: startup regeneration (D-A2)
    // -----------------------------------------------------------------------

    /**
     * Called on application startup after the Spring context is ready. Regenerates
     * all SQL views to survive restarts and schema drift.
     */
    @Override
    public void run(ApplicationArguments args) {
        regenerateAll();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Regenerate SQL views for all tenants that have registered node types.
     * Enumerates tenants by querying all distinct model_ids with exposed types.
     */
    public void regenerateAll() {
        List<UUID> modelIds = schemaRegistry.listDistinctExposedModels();
        if (modelIds.isEmpty()) {
            log.debug("SqlViewProjection.regenerateAll: no models with exposed types; nothing to do");
            return;
        }
        for (UUID modelId : modelIds) {
            try {
                regenerateForTenant(TenantContext.of(modelId));
            } catch (Exception e) {
                log.warn("SqlViewProjection.regenerateAll: failed to regenerate views for model_id={}: {}",
                        modelId, e.getMessage(), e);
            }
        }
    }

    /**
     * Regenerate (CREATE OR REPLACE) SQL views for all node types registered for the given tenant.
     *
     * @param ctx tenant context — determines which node types and graph partition to use
     */
    public void regenerateForTenant(TenantContext ctx) {
        long schemaVersion = schemaVersionService.currentVersion(ctx);
        List<NodeTypeDescriptor> types = schemaRegistry.listNodeTypes(ctx);

        if (types.isEmpty()) {
            log.debug("SqlViewProjection.regenerateForTenant: no node types for model_id={}; nothing to do",
                    ctx.modelId());
            return;
        }

        // Resolve all AGE label → schema.table mappings in one query for this graph.
        java.util.Map<String, LabelTableInfo> labelMap = resolveLabelTables();

        for (NodeTypeDescriptor type : types) {
            String viewName = SqlViewNameResolver.resolve(ctx.modelId(), type.slug());
            try {
                regenerateView(ctx, type, viewName, schemaVersion, labelMap);
            } catch (Exception e) {
                log.warn("SqlViewProjection: failed to regenerate view {} for type {} (model_id={}): {}",
                        viewName, type.slug(), ctx.modelId(), e.getMessage(), e);
            }
        }
    }

    /**
     * List active SQL views for a given model (used by the admin endpoint).
     *
     * @param modelId tenant UUID
     * @return list of view metadata records for this tenant
     */
    public List<ViewMetadata> listViews(UUID modelId) {
        String prefix = "v_" + modelId.toString().replace("-", "").substring(0, 8) + "_";
        return activeViews.values().stream()
                .filter(v -> v.viewName().startsWith(prefix))
                .toList();
    }

    // -----------------------------------------------------------------------
    // View generation internals
    // -----------------------------------------------------------------------

    /**
     * Create or replace a single SQL view for the given type.
     *
     * <p>Staleness check (D-D3): reads the existing view definition via
     * {@code pg_get_viewdef} and checks the embedded {@code schema_version:N}
     * comment. If the version matches, regeneration is skipped.
     */
    private void regenerateView(
            TenantContext ctx,
            NodeTypeDescriptor type,
            String viewName,
            long schemaVersion,
            java.util.Map<String, LabelTableInfo> labelMap) {

        // Staleness check — skip if view exists and schema version matches.
        if (viewIsCurrentVersion(viewName, schemaVersion)) {
            log.debug("SqlViewProjection: view {} is current (schema_version={}); skipping", viewName, schemaVersion);
            activeViews.put(viewName, new ViewMetadata(viewName, ctx.modelId(), type.slug(), schemaVersion, Instant.now()));
            return;
        }

        // Resolve the AGE label table for this type slug.
        // AGE label names are the type slug (same as the Cypher label used in GraphSession).
        LabelTableInfo tableInfo = labelMap.get(type.slug());
        if (tableInfo == null) {
            log.debug("SqlViewProjection: no AGE label table found for type '{}' in graph '{}'; "
                    + "skipping view generation (type may not have any nodes yet)",
                    type.slug(), GRAPH_NAME);
            return;
        }

        String ddl = buildViewDdl(viewName, ctx.modelId(), type, tableInfo, schemaVersion);
        jdbc.getJdbcTemplate().execute(ddl);

        activeViews.put(viewName, new ViewMetadata(viewName, ctx.modelId(), type.slug(), schemaVersion, Instant.now()));
        log.info("SqlViewProjection: (re)generated view {} for type '{}' model_id={} schema_version={}",
                viewName, type.slug(), ctx.modelId(), schemaVersion);
    }

    /**
     * Build the {@code CREATE OR REPLACE VIEW} DDL statement.
     *
     * <p>Security notes (T-04-T1): column aliases are derived from property slugs which are
     * validated by the schema registry on creation. We additionally quote all column aliases
     * with double-quotes to prevent SQL injection via crafted property names.
     */
    private String buildViewDdl(
            String viewName,
            UUID modelId,
            NodeTypeDescriptor type,
            LabelTableInfo tableInfo,
            long schemaVersion) {

        StringBuilder cols = new StringBuilder();

        // System columns always present
        cols.append("  (properties::jsonb)->>'uuid' AS uuid,\n");
        cols.append("  (properties::jsonb)->>'model_id' AS model_id,\n");
        cols.append("  (properties::jsonb)->>'_type' AS _type,\n");
        cols.append("  (properties::jsonb)->>'_created_at' AS _created_at,\n");
        cols.append("  (properties::jsonb)->>'_updated_at' AS _updated_at,\n");
        cols.append("  (properties::jsonb)->>'_created_by' AS _created_by,\n");
        cols.append("  (properties::jsonb)->>'_seq' AS _seq");

        // User-defined property columns
        for (PropertyDescriptor prop : type.properties()) {
            if (prop.deprecatedAt() != null) {
                continue; // skip deprecated properties in new views
            }
            String safeAlias = "\"" + prop.slug().replace("\"", "") + "\"";
            String colExpr = buildColumnExpression(prop);
            cols.append(",\n  ").append(colExpr).append(" AS ").append(safeAlias);
        }

        String schemaName = tableInfo.schemaName();
        String tableName = tableInfo.tableName();

        // View DDL with schema_version comment embedded for staleness detection (D-D3).
        return "CREATE OR REPLACE VIEW " + viewName + " AS\n"
                + "/* schema_version:" + schemaVersion
                + " model_id:" + modelId
                + " type:" + type.slug() + " */\n"
                + "SELECT\n"
                + cols + "\n"
                + "FROM \"" + schemaName + "\".\"" + tableName + "\"\n"
                + "WHERE ((properties::jsonb)->>'model_id')::uuid = '" + modelId + "'::uuid\n"
                + "  AND COALESCE(((properties::jsonb)->>'_tombstoned')::boolean, false) = false";
    }

    /**
     * Build a column extraction expression for a property, casting to the appropriate SQL type.
     * Uses {@code (properties::jsonb)->>'slug'} — the agtype-to-text cast (Pitfall 1 fix).
     */
    private static String buildColumnExpression(PropertyDescriptor prop) {
        String slug = prop.slug();
        String base = "(properties::jsonb)->>'" + slug + "'";
        return switch (prop.dataType()) {
            case "INTEGER" -> "(" + base + ")::integer";
            case "BOOLEAN" -> "(" + base + ")::boolean";
            case "TIMESTAMP" -> "(" + base + ")::timestamptz";
            case "REFERENCE" -> "(" + base + ")::uuid";
            default -> base; // STRING and unknown types: plain text via ->>
        };
    }

    /**
     * Check if the existing view (if any) was generated for the given schema version.
     * Reads the view definition via {@code pg_get_viewdef} and parses the embedded comment.
     *
     * @return true if the view exists and its {@code schema_version:N} matches {@code schemaVersion}
     */
    private boolean viewIsCurrentVersion(String viewName, long schemaVersion) {
        try {
            String viewDef = jdbc.queryForObject(
                    "SELECT pg_get_viewdef(:name::regclass, true)",
                    new MapSqlParameterSource("name", viewName),
                    String.class);
            if (viewDef == null) {
                return false;
            }
            // Parse "schema_version:N" from the comment embedded in the view DDL.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("schema_version:(\\d+)")
                    .matcher(viewDef);
            if (m.find()) {
                long existingVersion = Long.parseLong(m.group(1));
                return existingVersion == schemaVersion;
            }
            return false;
        } catch (Exception e) {
            // View doesn't exist or regclass cast fails — needs creation.
            return false;
        }
    }

    /**
     * Query the AGE catalog to resolve all label → schema+table mappings for the main graph.
     * Returns a map keyed by label name (= type slug).
     */
    private java.util.Map<String, LabelTableInfo> resolveLabelTables() {
        String sql = """
                SELECT l.name AS label_name, n.nspname AS schema_name,
                       l.relation::regclass::text AS table_fqn
                  FROM ag_catalog.ag_label l
                  JOIN ag_catalog.ag_graph g ON l.graph = g.graphid
                  JOIN pg_namespace n ON n.oid = l.relation::regclass::oid
                 WHERE g.name = :graph_name AND l.kind = 'v'
                """;
        try {
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    sql, new MapSqlParameterSource("graph_name", GRAPH_NAME));
            java.util.Map<String, LabelTableInfo> result = new java.util.HashMap<>();
            for (java.util.Map<String, Object> row : rows) {
                String labelName = (String) row.get("label_name");
                String schemaName = (String) row.get("schema_name");
                // table_fqn is "schema.table" or just "table" depending on search_path
                String tableFqn = (String) row.get("table_fqn");
                String tableName = tableFqn.contains(".")
                        ? tableFqn.substring(tableFqn.lastIndexOf('.') + 1)
                        : tableFqn;
                result.put(labelName, new LabelTableInfo(schemaName, tableName));
            }
            return result;
        } catch (Exception e) {
            log.warn("SqlViewProjection: could not resolve AGE label tables from ag_catalog: {}; "
                    + "SQL view generation will be skipped", e.getMessage());
            return java.util.Map.of();
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * Metadata about an active SQL view, returned by the admin endpoint.
     *
     * @param viewName      Postgres view name (e.g. {@code v_550e8400_person})
     * @param modelId       tenant UUID this view belongs to
     * @param typeSlug      node type slug
     * @param schemaVersion schema version at time of generation
     * @param generatedAt   timestamp when the view was last regenerated
     */
    public record ViewMetadata(
            String viewName,
            UUID modelId,
            String typeSlug,
            long schemaVersion,
            Instant generatedAt) {}

    /**
     * Resolved AGE label table location.
     *
     * @param schemaName Postgres schema (e.g. {@code tessera_main})
     * @param tableName  Postgres table name (the AGE label storage table)
     */
    private record LabelTableInfo(String schemaName, String tableName) {}
}
