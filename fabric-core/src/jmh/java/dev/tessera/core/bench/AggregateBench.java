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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * D-03 query shape #3: aggregation across all nodes, grouped by label.
 *
 * <p>CRIT-3 instrumentation — the Apache AGE "aggregation cliff" is the
 * ~15× slowdown this shape exhibits compared to the same query run as plain
 * SQL against a relational table of the same cardinality. This bench exists
 * explicitly so the cliff is visible in {@code .planning/benchmarks/} on every
 * run and regressions show up in PR diffs.
 */
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class AggregateBench {

    @Benchmark
    public void countByLabel(BenchHarness h, Blackhole bh) throws Exception {
        // NOTE: AGE's Cypher parser rejects ORDER BY on an aggregate alias
        // ("could not find rte for c"), so the ordering is left to the outer
        // SQL wrapper. The benchmark cares about the aggregation cost, not
        // the sort order of the tiny 4-row output.
        String cypher = "SELECT * FROM cypher('" + SeedGenerator.GRAPH_NAME
                + "', $$ MATCH (n) RETURN labels(n), count(*) $$)"
                + " AS (l agtype, c agtype) ORDER BY c DESC";
        try (Statement s = h.conn.createStatement();
                ResultSet rs = s.executeQuery(cypher)) {
            while (rs.next()) {
                bh.consume(rs.getString(1));
                bh.consume(rs.getString(2));
            }
        }
    }
}
