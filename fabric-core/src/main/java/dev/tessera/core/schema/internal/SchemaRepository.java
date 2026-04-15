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
package dev.tessera.core.schema.internal;

import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateEdgeTypeSpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.EdgeTypeDescriptor;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SCHEMA-01..03: JDBC-backed CRUD over {@code schema_node_types},
 * {@code schema_properties}, {@code schema_edge_types}. Plain JDBC (not JPA)
 * because the tables mix typed columns with JSONB fields per D-B1.
 */
@Component
public class SchemaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SchemaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    // ---------------- node types ----------------

    public void insertNodeType(TenantContext ctx, CreateNodeTypeSpec spec) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", UUID.randomUUID().toString());
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("name", spec.name());
        p.addValue("slug", spec.slug());
        p.addValue("label", spec.label());
        p.addValue("description", spec.description());
        jdbc.update(
                """
                INSERT INTO schema_node_types (id, model_id, name, slug, label, description, builtin, created_at)
                VALUES (:id::uuid, :model_id::uuid, :name, :slug, :label, :description, false, clock_timestamp())
                """,
                p);
    }

    public Optional<NodeTypeDescriptor> findNodeType(TenantContext ctx, String slug, long schemaVersion) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("slug", slug);
        List<NodeTypeRow> rows = jdbc.query(
                "SELECT model_id, slug, name, label, description, deprecated_at,"
                        + " rest_read_enabled, rest_write_enabled"
                        + " FROM schema_node_types WHERE model_id = :model_id::uuid AND slug = :slug",
                p,
                nodeTypeMapper());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        NodeTypeRow r = rows.get(0);
        List<PropertyDescriptor> props = listProperties(ctx, slug);
        return Optional.of(new NodeTypeDescriptor(
                r.modelId,
                r.slug,
                r.name,
                r.label,
                r.description,
                schemaVersion,
                props,
                r.deprecatedAt,
                r.restReadEnabled,
                r.restWriteEnabled));
    }

    public List<NodeTypeDescriptor> listNodeTypes(TenantContext ctx, long schemaVersion) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        List<NodeTypeRow> rows = jdbc.query(
                "SELECT model_id, slug, name, label, description, deprecated_at,"
                        + " rest_read_enabled, rest_write_enabled"
                        + " FROM schema_node_types WHERE model_id = :model_id::uuid",
                p,
                nodeTypeMapper());
        return rows.stream()
                .map(r -> new NodeTypeDescriptor(
                        r.modelId,
                        r.slug,
                        r.name,
                        r.label,
                        r.description,
                        schemaVersion,
                        listProperties(ctx, r.slug),
                        r.deprecatedAt,
                        r.restReadEnabled,
                        r.restWriteEnabled))
                .toList();
    }

    public void updateNodeTypeDescription(TenantContext ctx, String slug, String description) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("slug", slug);
        p.addValue("description", description);
        jdbc.update(
                "UPDATE schema_node_types SET description = :description"
                        + " WHERE model_id = :model_id::uuid AND slug = :slug",
                p);
    }

    public void deprecateNodeType(TenantContext ctx, String slug) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("slug", slug);
        jdbc.update(
                "UPDATE schema_node_types SET deprecated_at = clock_timestamp()"
                        + " WHERE model_id = :model_id::uuid AND slug = :slug",
                p);
    }

    // ---------------- properties ----------------

    public void insertProperty(TenantContext ctx, String typeSlug, AddPropertySpec spec) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", UUID.randomUUID().toString());
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("type_slug", typeSlug);
        p.addValue("name", spec.name());
        p.addValue("slug", spec.slug());
        p.addValue("data_type", spec.dataType());
        p.addValue("required", spec.required());
        jdbc.update(
                """
                INSERT INTO schema_properties
                  (id, model_id, type_slug, name, slug, data_type, required, created_at)
                VALUES (:id::uuid, :model_id::uuid, :type_slug, :name, :slug, :data_type, :required, clock_timestamp())
                """,
                p);
    }

    public List<PropertyDescriptor> listProperties(TenantContext ctx, String typeSlug) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("type_slug", typeSlug);
        return jdbc.query(
                "SELECT slug, name, data_type, required, default_value, validation_rules, enum_values,"
                        + " reference_target, deprecated_at, property_encrypted, property_encrypted_alg"
                        + " FROM schema_properties WHERE model_id = :model_id::uuid AND type_slug = :type_slug"
                        + " ORDER BY slug",
                p,
                (rs, i) -> new PropertyDescriptor(
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("data_type"),
                        rs.getBoolean("required"),
                        rs.getString("default_value"),
                        rs.getString("validation_rules"),
                        rs.getString("enum_values"),
                        rs.getString("reference_target"),
                        toInstant(rs.getTimestamp("deprecated_at")),
                        rs.getBoolean("property_encrypted"),
                        rs.getString("property_encrypted_alg")));
    }

    public void deprecateProperty(TenantContext ctx, String typeSlug, String propertySlug) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("type_slug", typeSlug);
        p.addValue("slug", propertySlug);
        jdbc.update(
                "UPDATE schema_properties SET deprecated_at = clock_timestamp()"
                        + " WHERE model_id = :model_id::uuid AND type_slug = :type_slug AND slug = :slug",
                p);
    }

    public void deleteProperty(TenantContext ctx, String typeSlug, String propertySlug) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("type_slug", typeSlug);
        p.addValue("slug", propertySlug);
        jdbc.update(
                "DELETE FROM schema_properties WHERE model_id = :model_id::uuid"
                        + " AND type_slug = :type_slug AND slug = :slug",
                p);
    }

    // ---------------- edge types ----------------

    public void insertEdgeType(TenantContext ctx, CreateEdgeTypeSpec spec) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", UUID.randomUUID().toString());
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("name", spec.name());
        p.addValue("slug", spec.slug());
        p.addValue("edge_label", spec.edgeLabel());
        p.addValue("source_type_slug", spec.sourceTypeSlug());
        p.addValue("target_type_slug", spec.targetTypeSlug());
        p.addValue("cardinality", spec.cardinality());
        jdbc.update(
                """
                INSERT INTO schema_edge_types
                  (id, model_id, name, slug, edge_label, source_type_slug, target_type_slug, cardinality, created_at)
                VALUES (:id::uuid, :model_id::uuid, :name, :slug, :edge_label, :source_type_slug,
                        :target_type_slug, :cardinality, clock_timestamp())
                """,
                p);
    }

    public Optional<EdgeTypeDescriptor> findEdgeType(TenantContext ctx, String slug) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("slug", slug);
        List<EdgeTypeDescriptor> rows = jdbc.query(
                "SELECT model_id, slug, name, edge_label, source_type_slug, target_type_slug, cardinality, deprecated_at"
                        + " FROM schema_edge_types WHERE model_id = :model_id::uuid AND slug = :slug",
                p,
                (rs, i) -> new EdgeTypeDescriptor(
                        UUID.fromString(rs.getString("model_id")),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("edge_label"),
                        rs.getString("source_type_slug"),
                        rs.getString("target_type_slug"),
                        rs.getString("cardinality"),
                        toInstant(rs.getTimestamp("deprecated_at"))));
        return rows.stream().findFirst();
    }

    // ---------------- helpers ----------------

    private RowMapper<NodeTypeRow> nodeTypeMapper() {
        return (rs, i) -> new NodeTypeRow(
                UUID.fromString(rs.getString("model_id")),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("label"),
                rs.getString("description"),
                toInstant(rs.getTimestamp("deprecated_at")),
                rs.getBoolean("rest_read_enabled"),
                rs.getBoolean("rest_write_enabled"));
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private record NodeTypeRow(
            UUID modelId,
            String slug,
            String name,
            String label,
            String description,
            Instant deprecatedAt,
            boolean restReadEnabled,
            boolean restWriteEnabled) {}
}
