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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * D-03 query shape #1: point lookup of a node by uuid. This is the cheapest
 * shape and the baseline for all other comparisons. Exercises the GIN index
 * on label properties created by {@link SeedGenerator} (MIN-2 workaround).
 */
@Fork(1)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class PointLookupBench {

    private int counter;

    @Benchmark
    public void pointLookupByUuid(BenchHarness h, Blackhole bh) throws Exception {
        UUID u = h.uuids.get((counter++ & 0x7fffffff) % h.uuids.size());
        String cypher = "SELECT * FROM cypher('" + SeedGenerator.GRAPH_NAME
                + "', $$ MATCH (n {uuid: '" + u + "'}) RETURN n $$) AS (n agtype)";
        try (Statement s = h.conn.createStatement();
                ResultSet rs = s.executeQuery(cypher)) {
            while (rs.next()) {
                bh.consume(rs.getString(1));
            }
        }
    }
}
