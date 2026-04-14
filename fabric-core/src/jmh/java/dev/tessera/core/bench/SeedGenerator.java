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
package dev.tessera.core.bench;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Deterministic dataset builder for the Phase 0 JMH harness (D-02).
 *
 * <p>Builds a graph-shaped dataset of N nodes (round-robin across four labels —
 * Person, Org, Doc, Tag) plus {@code edgesPerNode} outbound :RELATES edges per
 * node, fully deterministic from a fixed RNG seed. Same {@code (count, edges,
 * seed)} tuple → byte-identical UUID list across JVMs and runs.
 *
 * <h2>Pitfall workarounds</h2>
 *
 * <p><b>MIN-1 (agtype parameter binding):</b> AGE rejects JDBC parameters typed
 * as {@code agtype} directly. This generator embeds literals into the Cypher
 * text rather than using {@code PreparedStatement} parameter slots — values are
 * pre-quoted via {@link #cypherString(String)} and the entire {@code CREATE}
 * batch is sent as a single SQL statement per chunk. Phase 1 will introduce
 * {@code GraphSession} which wraps the documented text-cast escape hatch for
 * read paths; the seed loader uses literal embedding because it is the only
 * caller and stays trivially auditable.
 *
 * <p><b>MIN-2 (no default indexes on labels):</b> AGE does NOT create indexes
 * on label tables by default. After bulk insert this generator runs
 * {@code CREATE INDEX ... ON tessera_bench."Label" USING gin (properties)} per
 * label so point lookups by {@code uuid} run in O(log n) rather than O(n).
 * Without this workaround the {@link PointLookupBench} numbers are meaningless.
 */
public final class SeedGenerator {

    public static final long DEFAULT_SEED = 0x7E55E2A42026L;
    public static final String GRAPH_NAME = "tessera_bench";
    public static final List<String> LABELS = List.of("Person", "Org", "Doc", "Tag");
    public static final String EDGE_LABEL = "RELATES";

    private SeedGenerator() {}

    /**
     * Pure-Java deterministic UUID list. No JDBC. Used by {@code SeedGeneratorTest}
     * (Surefire) to prove same-seed = same-output without needing Docker.
     */
    public static List<UUID> deterministicUuidList(int count, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<UUID> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new UUID(rnd.nextLong(), rnd.nextLong()));
        }
        return out;
    }

    /** Convenience: defaults to {@code edgesPerNode=4}, {@link #DEFAULT_SEED}. */
    public static List<UUID> build(Connection c, int nodeCount) throws SQLException {
        return build(c, nodeCount, 4, DEFAULT_SEED);
    }

    /**
     * Build a deterministic graph dataset in the {@code tessera_bench} graph.
     *
     * @param c           a JDBC connection on which {@code LOAD 'age'} and
     *                    {@code SET search_path = ag_catalog, "$user", public}
     *                    have already been executed
     * @param nodeCount   total number of nodes to create
     * @param edgesPerNode outbound :RELATES edges per node (target chosen by
     *                    deterministic stride {@code (i*31 + k) % nodeCount})
     * @param seed        RNG seed for the UUID stream
     * @return the deterministic UUID list (same as {@link #deterministicUuidList})
     */
    public static List<UUID> build(Connection c, int nodeCount, int edgesPerNode, long seed) throws SQLException {
        List<UUID> uuids = deterministicUuidList(nodeCount, seed);

        try (Statement s = c.createStatement()) {
            // Drop and recreate the bench graph so each Trial starts clean.
            try {
                s.execute("SELECT drop_graph('" + GRAPH_NAME + "', true)");
            } catch (SQLException ignored) {
                // graph did not exist
            }
            s.execute("SELECT create_graph('" + GRAPH_NAME + "')");
            // Pre-create the vertex + edge labels so the tables exist for direct INSERTs.
            for (String label : LABELS) {
                s.execute("SELECT create_vlabel('" + GRAPH_NAME + "', '" + label + "')");
            }
            s.execute("SELECT create_elabel('" + GRAPH_NAME + "', '" + EDGE_LABEL + "')");
        }

        // MIN-1/performance note: driving 100k node inserts through Cypher
        // `CREATE` statements is prohibitively slow because AGE parses the
        // entire CREATE clause per statement. Instead we insert directly into
        // AGE's vertex label tables via plain SQL `INSERT ... VALUES` — much
        // faster and still 100% valid AGE data because `create_vlabel` set up
        // the label table with the standard `(id graphid, properties agtype)`
        // schema. Same approach for edges: the internal vertex id is looked
        // up from the default id sequence `_label_id_seq` after each insert.
        //
        // We fetch the generated vertex ids per insert via `RETURNING id` so
        // the edge phase can wire start_id/end_id without a MATCH pass.
        // graphid values are stored as raw text (not parsed to long) because
        // AGE does not allow bigint->graphid casts; re-inserting into the edge
        // table needs the original textual form quoted as '<n>'::graphid.
        String[] vertexIds = new String[nodeCount];

        for (String label : LABELS) {
            // Build a single multi-row INSERT per label (round-robin buckets).
            List<Integer> indices = new ArrayList<>(nodeCount / LABELS.size() + 1);
            for (int i = 0; i < nodeCount; i++) {
                if (LABELS.get(i % LABELS.size()).equals(label)) {
                    indices.add(i);
                }
            }
            if (indices.isEmpty()) {
                continue;
            }
            // Chunk to keep any single INSERT under Postgres' statement size ceiling.
            final int chunk = 2000;
            for (int off = 0; off < indices.size(); off += chunk) {
                int to = Math.min(off + chunk, indices.size());
                StringBuilder sql = new StringBuilder();
                sql.append("INSERT INTO ")
                        .append(GRAPH_NAME)
                        .append(".\"")
                        .append(label)
                        .append("\" (properties) VALUES ");
                for (int k = off; k < to; k++) {
                    if (k > off) {
                        sql.append(',');
                    }
                    int i = indices.get(k);
                    String json = "{\"uuid\": \"" + uuids.get(i) + "\", \"idx\": " + i
                            + ", \"name\": \"" + label + "-" + i + "\"}";
                    // agtype literal: single-quoted JSON cast to agtype.
                    sql.append("('").append(json.replace("'", "''")).append("'::agtype)");
                }
                sql.append(" RETURNING id");

                try (Statement s = c.createStatement();
                        ResultSet rs = s.executeQuery(sql.toString())) {
                    int k = off;
                    while (rs.next()) {
                        // Keep raw textual form — graphid re-insert must go
                        // through text cast to avoid bigint->graphid error.
                        vertexIds[indices.get(k)] = rs.getString(1);
                        k++;
                    }
                    if (k != to) {
                        throw new SQLException(
                                "label " + label + " INSERT RETURNING returned " + (k - off) + " ids, expected "
                                        + (to - off));
                    }
                }
            }
        }

        // MIN-2 workaround: AGE does NOT create indexes on label tables by
        // default. Add GIN indexes on the properties column so point lookups
        // by `{uuid: ...}` run through the index once the benches are live.
        try (Statement s = c.createStatement()) {
            for (String label : LABELS) {
                s.execute("CREATE INDEX IF NOT EXISTS bench_" + label.toLowerCase() + "_props_gin ON "
                        + GRAPH_NAME + ".\"" + label + "\" USING gin (properties)");
            }
        }

        // Insert edges directly into the RELATES edge label table. Edge rows
        // need (start_id, end_id, properties); the id column autoincrements
        // from the label's default sequence. Same chunking strategy.
        final int edgeChunk = 4000;
        List<String[]> edgePairs = new ArrayList<>(nodeCount * edgesPerNode);
        for (int i = 0; i < nodeCount; i++) {
            String srcId = vertexIds[i];
            for (int k = 0; k < edgesPerNode; k++) {
                int targetIdx = (int) (((long) i * 31L + k) % nodeCount);
                if (targetIdx == i) {
                    continue; // deterministic self-loop dedup
                }
                edgePairs.add(new String[] {srcId, vertexIds[targetIdx]});
            }
        }
        for (int off = 0; off < edgePairs.size(); off += edgeChunk) {
            int to = Math.min(off + edgeChunk, edgePairs.size());
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ")
                    .append(GRAPH_NAME)
                    .append(".\"")
                    .append(EDGE_LABEL)
                    .append("\" (start_id, end_id, properties) VALUES ");
            for (int k = off; k < to; k++) {
                if (k > off) {
                    sql.append(',');
                }
                String[] pair = edgePairs.get(k);
                sql.append("('")
                        .append(pair[0])
                        .append("'::graphid, '")
                        .append(pair[1])
                        .append("'::graphid, '{}'::agtype)");
            }
            try (Statement s = c.createStatement()) {
                s.execute(sql.toString());
            }
        }

        // Edge table indexes (MIN-2 extension): AGE does NOT create btree
        // indexes on start_id / end_id by default. Without them, every
        // `MATCH (n)-[:RELATES]->(m)` traversal degrades to a sequential
        // scan of the edge table — which on 100k nodes × 4 edges is 400k
        // rows per outbound hop. These indexes unlock reasonable traversal
        // performance for the TwoHopTraversalBench and any Phase 1 workload
        // that touches edges.
        try (Statement s = c.createStatement()) {
            s.execute("CREATE INDEX IF NOT EXISTS bench_relates_start ON "
                    + GRAPH_NAME + ".\"" + EDGE_LABEL + "\" (start_id)");
            s.execute("CREATE INDEX IF NOT EXISTS bench_relates_end ON "
                    + GRAPH_NAME + ".\"" + EDGE_LABEL + "\" (end_id)");
            s.execute("ANALYZE " + GRAPH_NAME + ".\"" + EDGE_LABEL + "\"");
            for (String label : LABELS) {
                s.execute("ANALYZE " + GRAPH_NAME + ".\"" + label + "\"");
            }
        }

        return uuids;
    }

    /** Single-quote a string for embedding inside a Cypher literal. */
    private static String cypherString(String value) {
        return "'" + value.replace("'", "\\'") + "'";
    }
}
