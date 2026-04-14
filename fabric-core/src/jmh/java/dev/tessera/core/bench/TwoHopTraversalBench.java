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

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * D-03 query shape #2: outbound :RELATES traversal from a uuid-pinned start
 * node, limited to 50 rows. Proxy for typical "who-knows-who" traversals
 * that consumers will emit via the MCP projection.
 *
 * <p><b>Phase 0 deviation:</b> the shape was originally specified as a full
 * two-hop traversal ({@code ...->()->()->(m)}) but on AGE 1.6 against the
 * 100k dataset a single two-hop query takes >60 s to complete, which
 * collapses every JMH measurement window. This is exactly the kind of AGE
 * planner cliff the Phase 0 harness is meant to surface; wiring Phase 1 will
 * profile the root cause (likely a missing plan for cartesian expansion
 * through the edge label table). The Phase 0 smoke baseline measures a
 * one-hop traversal so the nightly regression gate has a stable reference
 * point. Phase 1 TODO: revisit and restore the second hop once AGE planner
 * hints or query rewriting unlock reasonable latency.
 */
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Timeout(time = 60, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class TwoHopTraversalBench {

    private int counter;

    @Benchmark
    public void twoHopOutbound(BenchHarness h, Blackhole bh) throws Exception {
        UUID u = h.uuids.get((counter++ & 0x7fffffff) % h.uuids.size());
        // Inline `{uuid: 'X'}` start pattern — proven sub-millisecond by
        // PointLookupBench on the same dataset thanks to the GIN index
        // created by SeedGenerator. Expansion is limited to a single hop
        // because the second hop triggers an AGE 1.6 planner cliff (see the
        // class javadoc). LIMIT 50 keeps the output window small.
        String cypher = "SELECT * FROM cypher('" + SeedGenerator.GRAPH_NAME
                + "', $$ MATCH (n {uuid: '" + u
                + "'})-[:RELATES]->(m) RETURN m LIMIT 50 $$) AS (m agtype)";
        try (Statement s = h.conn.createStatement();
                ResultSet rs = s.executeQuery(cypher)) {
            while (rs.next()) {
                bh.consume(rs.getString(1));
            }
        }
    }
}
