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
package dev.tessera.core.graph.internal;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * CORE-02: the SOLE class in the codebase permitted to execute raw Cypher.
 *
 * <p>Implements the Wave 1 write/read path against Apache AGE using the
 * text-cast agtype idiom from 01-RESEARCH.md §"Agtype Parameter Binding".
 * Property maps are serialized to JSON via {@link AgtypeBinder} and inlined
 * into Cypher literals (keys and labels are regex-validated to prevent Cypher
 * injection — see {@link #IDENT}).
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Stamp CORE-06 system properties on every write.
 *   <li>Honor CORE-08 — Tessera owns {@code _created_at}/{@code _updated_at};
 *       payload-supplied timestamps are stripped.
 *   <li>Tombstone-default delete per CORE-07. Hard-delete is a separate
 *       opt-in method.
 *   <li>Inject {@code model_id} into every Cypher clause (CORE-03 substrate).
 * </ul>
 */
public final class GraphSession {

    /** The fixed AGE graph name created by V1__enable_age.sql. */
    public static final String GRAPH_NAME = "tessera_main";

    /** Identifier regex for labels and property keys — blocks Cypher injection via dynamic types. */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String SYS_UUID = "uuid";
    private static final String SYS_MODEL_ID = "model_id";
    private static final String SYS_TYPE = "_type";
    private static final String SYS_CREATED_AT = "_created_at";
    private static final String SYS_UPDATED_AT = "_updated_at";
    private static final String SYS_CREATED_BY = "_created_by";
    private static final String SYS_SOURCE = "_source";
    private static final String SYS_SOURCE_ID = "_source_id";
    private static final String SYS_TOMBSTONED = "_tombstoned";
    private static final String SYS_TOMBSTONED_AT = "_tombstoned_at";

    /** Sentinel prefix on {@link GraphMutation#type()} indicating an edge mutation. */
    public static final String EDGE_PREFIX = "#edge/";

    private final JdbcTemplate jdbc;

    public GraphSession(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc.getJdbcTemplate();
    }

    /** Public for tests / convenience. */
    public GraphSession(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --------------------------------------------------------------------
    // Write path
    // --------------------------------------------------------------------

    /**
     * Apply a single node mutation to AGE and return the post-state. Edge
     * mutations (type starts with {@link #EDGE_PREFIX}) are routed to
     * {@link #applyEdge}.
     */
    public NodeState apply(TenantContext ctx, GraphMutation mutation) {
        if (mutation.type().startsWith(EDGE_PREFIX)) {
            return applyEdge(ctx, mutation);
        }
        String label = validateIdent(mutation.type(), "node type");
        return switch (mutation.operation()) {
            case CREATE -> createNode(ctx, mutation, label);
            case UPDATE -> updateNode(ctx, mutation, label);
            case TOMBSTONE -> tombstoneNode(ctx, mutation, label);
        };
    }

    private NodeState createNode(TenantContext ctx, GraphMutation m, String label) {
        UUID uuid = m.targetNodeUuid() != null ? m.targetNodeUuid() : UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> props = sanitizePayload(m.payload());
        props.put(SYS_UUID, uuid.toString());
        props.put(SYS_MODEL_ID, ctx.modelId().toString());
        props.put(SYS_TYPE, label);
        props.put(SYS_CREATED_AT, now.toString());
        props.put(SYS_UPDATED_AT, now.toString());
        props.put(SYS_CREATED_BY, nullToEmpty(m.sourceId()));
        props.put(SYS_SOURCE, nullToEmpty(m.sourceSystem()));
        props.put(SYS_SOURCE_ID, nullToEmpty(m.sourceId()));
        validateKeys(props);

        String propsMap = AgtypeBinder.toCypherMap(props);
        String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                + " CREATE (n:" + label + " " + propsMap + ") RETURN n"
                + " $$) AS (n agtype)";
        executeCypher(cypher);
        return new NodeState(uuid, label, Map.copyOf(props), now, now);
    }

    private NodeState updateNode(TenantContext ctx, GraphMutation m, String label) {
        UUID uuid = requireTargetUuid(m);
        Map<String, Object> props = sanitizePayload(m.payload());
        Instant now = Instant.now();
        props.put(SYS_UPDATED_AT, now.toString());
        validateKeys(props);

        // Build SET clauses for each property — keys are validated, values are Cypher literals.
        StringBuilder set = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (!first) {
                set.append(", ");
            }
            first = false;
            set.append("n.").append(e.getKey()).append(" = ");
            set.append(AgtypeBinder.valueLiteral(e.getValue()));
        }

        String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                + " MATCH (n:" + label + " {model_id: \"" + ctx.modelId() + "\", uuid: \"" + uuid + "\"})"
                + " SET " + set
                + " RETURN n"
                + " $$) AS (n agtype)";
        executeCypher(cypher);

        // Read-back for authoritative post-state (merges existing + updated).
        return findNode(ctx, label, uuid)
                .orElseThrow(
                        () -> new IllegalStateException("UPDATE applied but node not found: " + label + "/" + uuid));
    }

    private NodeState tombstoneNode(TenantContext ctx, GraphMutation m, String label) {
        UUID uuid = requireTargetUuid(m);
        Instant now = Instant.now();
        String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                + " MATCH (n:" + label + " {model_id: \"" + ctx.modelId() + "\", uuid: \"" + uuid + "\"})"
                + " SET n." + SYS_TOMBSTONED + " = true,"
                + " n." + SYS_TOMBSTONED_AT + " = \"" + now + "\","
                + " n." + SYS_UPDATED_AT + " = \"" + now + "\""
                + " RETURN n"
                + " $$) AS (n agtype)";
        executeCypher(cypher);
        return findNode(ctx, label, uuid)
                .orElseThrow(
                        () -> new IllegalStateException("TOMBSTONE applied but node not found: " + label + "/" + uuid));
    }

    /**
     * CORE-07 hard-delete opt-in: actually removes a node (and detaches its
     * edges). Callers must explicitly invoke this — the default
     * {@link Operation#TOMBSTONE} only flips the soft flag.
     */
    public void hardDelete(TenantContext ctx, String typeSlug, UUID uuid) {
        String label = validateIdent(typeSlug, "node type");
        String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                + " MATCH (n:" + label + " {model_id: \"" + ctx.modelId() + "\", uuid: \"" + uuid + "\"})"
                + " DETACH DELETE n"
                + " $$) AS (n agtype)";
        executeCypher(cypher);
    }

    // --------------------------------------------------------------------
    // Edge path (CORE-05)
    // --------------------------------------------------------------------

    private NodeState applyEdge(TenantContext ctx, GraphMutation m) {
        String edgeLabel = validateIdent(m.type().substring(EDGE_PREFIX.length()), "edge type");
        Map<String, Object> payload = new HashMap<>(m.payload());
        UUID sourceUuid = UUID.fromString(requireString(payload.remove("sourceUuid"), "sourceUuid"));
        UUID targetUuid = UUID.fromString(requireString(payload.remove("targetUuid"), "targetUuid"));
        String sourceLabel = validateIdent(requireString(payload.remove("sourceLabel"), "sourceLabel"), "source label");
        String targetLabel = validateIdent(requireString(payload.remove("targetLabel"), "targetLabel"), "target label");

        UUID edgeUuid = m.targetNodeUuid() != null ? m.targetNodeUuid() : UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> edgeProps = sanitizePayload(payload);
        edgeProps.put(SYS_UUID, edgeUuid.toString());
        edgeProps.put(SYS_MODEL_ID, ctx.modelId().toString());
        edgeProps.put(SYS_TYPE, edgeLabel);
        edgeProps.put(SYS_CREATED_AT, now.toString());
        edgeProps.put(SYS_UPDATED_AT, now.toString());
        edgeProps.put(SYS_CREATED_BY, nullToEmpty(m.sourceId()));
        edgeProps.put(SYS_SOURCE, nullToEmpty(m.sourceSystem()));
        edgeProps.put(SYS_SOURCE_ID, nullToEmpty(m.sourceId()));
        validateKeys(edgeProps);

        switch (m.operation()) {
            case CREATE -> {
                String propsMap = AgtypeBinder.toCypherMap(edgeProps);
                String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                        + " MATCH (a:" + sourceLabel
                        + " {model_id: \"" + ctx.modelId() + "\", uuid: \"" + sourceUuid + "\"}),"
                        + " (b:" + targetLabel
                        + " {model_id: \"" + ctx.modelId() + "\", uuid: \"" + targetUuid + "\"})"
                        + " CREATE (a)-[e:" + edgeLabel + " " + propsMap + "]->(b)"
                        + " RETURN e"
                        + " $$) AS (e agtype)";
                executeCypher(cypher);
            }
            case TOMBSTONE -> {
                String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                        + " MATCH ()-[e:" + edgeLabel + " {model_id: \"" + ctx.modelId() + "\", uuid: \"" + edgeUuid
                        + "\"}]->()"
                        + " SET e." + SYS_TOMBSTONED + " = true,"
                        + " e." + SYS_TOMBSTONED_AT + " = \"" + now + "\","
                        + " e." + SYS_UPDATED_AT + " = \"" + now + "\""
                        + " RETURN e"
                        + " $$) AS (e agtype)";
                executeCypher(cypher);
            }
            case UPDATE -> throw new UnsupportedOperationException(
                    "Edge UPDATE not supported in Wave 1 — Wave 2 may add it");
        }
        return new NodeState(edgeUuid, edgeLabel, Map.copyOf(edgeProps), now, now);
    }

    // --------------------------------------------------------------------
    // Read path
    // --------------------------------------------------------------------

    public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) {
        String label = validateIdent(typeSlug, "node type");
        String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                + " MATCH (n:" + label + ")"
                + " WHERE n.model_id = \"" + ctx.modelId() + "\" AND n.uuid = \"" + nodeUuid + "\""
                + " RETURN n"
                + " $$) AS (n agtype)";
        List<String> rows = jdbc.query(cypher, (rs, i) -> rs.getString(1));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toNodeState(label, rows.get(0)));
    }

    public List<NodeState> queryAllNodes(TenantContext ctx, String typeSlug) {
        String label = validateIdent(typeSlug, "node type");
        String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                + " MATCH (n:" + label + ")"
                + " WHERE n.model_id = \"" + ctx.modelId() + "\""
                + " RETURN n"
                + " $$) AS (n agtype)";
        List<String> rows = jdbc.query(cypher, (rs, i) -> rs.getString(1));
        return rows.stream().map(r -> toNodeState(label, r)).toList();
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    private void executeCypher(String cypher) {
        // Consume result set — AGE returns 0..N rows; we do not inspect contents here (read-back uses findNode).
        jdbc.query(cypher, rs -> {});
    }

    /**
     * Parse an AGE agtype vertex/edge result string into a {@link NodeState}.
     *
     * <p>AGE returns rows in the form {@code {"id": ..., "label": "Person",
     * "properties": {...}}::vertex}. We strip the trailing {@code ::vertex}
     * (or {@code ::edge}) marker and parse the JSON map ourselves — the
     * shape is well-defined by AGE and keeps this class self-contained.
     */
    static NodeState toNodeState(String typeSlug, String agtype) {
        String json = agtype;
        int cut = json.lastIndexOf("::");
        if (cut > 0) {
            json = json.substring(0, cut);
        }
        Map<String, Object> parsed = AgtypeJsonParser.parseObject(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> propsRaw = (Map<String, Object>) parsed.getOrDefault("properties", Map.of());
        // Copy into a mutable map so callers can inspect freely.
        Map<String, Object> properties = new LinkedHashMap<>(propsRaw);
        UUID uuid = UUID.fromString(String.valueOf(properties.get(SYS_UUID)));
        Instant createdAt = properties.containsKey(SYS_CREATED_AT)
                ? Instant.parse(properties.get(SYS_CREATED_AT).toString())
                : null;
        Instant updatedAt = properties.containsKey(SYS_UPDATED_AT)
                ? Instant.parse(properties.get(SYS_UPDATED_AT).toString())
                : null;
        return new NodeState(uuid, typeSlug, Collections.unmodifiableMap(properties), createdAt, updatedAt);
    }

    private static Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        Map<String, Object> clean = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        // CORE-08: Tessera owns timestamps; payload-supplied values are rejected.
        clean.remove(SYS_CREATED_AT);
        clean.remove(SYS_UPDATED_AT);
        return clean;
    }

    private static void validateKeys(Map<String, Object> props) {
        for (String k : props.keySet()) {
            if (!IDENT.matcher(k).matches()) {
                throw new IllegalArgumentException("illegal property key: " + k);
            }
        }
    }

    private static String validateIdent(String s, String what) {
        if (s == null || !IDENT.matcher(s).matches()) {
            throw new IllegalArgumentException("illegal " + what + ": " + s);
        }
        return s;
    }

    private static UUID requireTargetUuid(GraphMutation m) {
        if (m.targetNodeUuid() == null) {
            throw new IllegalArgumentException(m.operation() + " requires targetNodeUuid");
        }
        return m.targetNodeUuid();
    }

    private static String requireString(Object v, String field) {
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("edge payload requires " + field);
        }
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
