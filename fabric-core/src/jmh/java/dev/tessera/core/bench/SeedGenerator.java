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
        }

        // Insert nodes in batches of 1000. MIN-1: literals embedded in Cypher
        // text — no JDBC parameter binding for agtype values.
        final int batch = 1000;
        for (int start = 0; start < nodeCount; start += batch) {
            int end = Math.min(start + batch, nodeCount);
            StringBuilder cypher = new StringBuilder(64 * (end - start));
            cypher.append("SELECT * FROM cypher('")
                    .append(GRAPH_NAME)
                    .append("', $$ CREATE ");
            for (int i = start; i < end; i++) {
                if (i > start) {
                    cypher.append(", ");
                }
                String label = LABELS.get(i % LABELS.size());
                String uuidStr = uuids.get(i).toString();
                String name = label + "-" + i;
                cypher.append("(:")
                        .append(label)
                        .append(" {uuid: ")
                        .append(cypherString(uuidStr))
                        .append(", idx: ")
                        .append(i)
                        .append(", name: ")
                        .append(cypherString(name))
                        .append("})");
            }
            cypher.append(" $$) AS (n agtype)");
            try (Statement s = c.createStatement()) {
                s.execute(cypher.toString());
            }
        }

        // Insert edges: deterministic stride. AGE Cypher does not allow chaining
        // multiple top-level MATCH...CREATE statements in one query, so we drive
        // the batch through UNWIND over a literal list of [src,tgt] pairs.
        for (int start = 0; start < nodeCount; start += batch) {
            int end = Math.min(start + batch, nodeCount);
            StringBuilder pairs = new StringBuilder();
            pairs.append('[');
            boolean first = true;
            for (int i = start; i < end; i++) {
                String srcUuid = uuids.get(i).toString();
                for (int k = 0; k < edgesPerNode; k++) {
                    int targetIdx = (int) (((long) i * 31L + k) % nodeCount);
                    if (targetIdx == i) {
                        // skip self-loop — deterministic dedup
                        continue;
                    }
                    String tgtUuid = uuids.get(targetIdx).toString();
                    if (!first) {
                        pairs.append(',');
                    }
                    first = false;
                    pairs.append('[')
                            .append(cypherString(srcUuid))
                            .append(',')
                            .append(cypherString(tgtUuid))
                            .append(']');
                }
            }
            pairs.append(']');
            if (first) {
                continue; // no edges in this slice
            }

            String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
                    + " UNWIND " + pairs + " AS pair"
                    + " MATCH (a {uuid: pair[0]}), (b {uuid: pair[1]})"
                    + " CREATE (a)-[:" + EDGE_LABEL + "]->(b)"
                    + " RETURN 1 $$) AS (n agtype)";
            try (Statement s = c.createStatement()) {
                s.execute(cypher);
            }
        }

        // MIN-2 workaround: AGE does NOT create indexes on label tables by
        // default. Without these GIN indexes on the properties column, point
        // lookups are sequential scans and the bench numbers are meaningless.
        try (Statement s = c.createStatement()) {
            for (String label : LABELS) {
                s.execute("CREATE INDEX IF NOT EXISTS bench_" + label.toLowerCase() + "_props_gin ON "
                        + GRAPH_NAME + ".\"" + label + "\" USING gin (properties)");
            }
        }

        return uuids;
    }

    /** Single-quote a string for embedding inside a Cypher literal. */
    private static String cypherString(String value) {
        return "'" + value.replace("'", "\\'") + "'";
    }
}
