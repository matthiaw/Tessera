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

import dev.tessera.core.support.AgePostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared JMH @Trial-scoped state: starts an AGE Testcontainer, runs the V1
 * init SQL, loads the deterministic dataset via {@link SeedGenerator}, and
 * exposes a pooled JDBC {@link Connection} plus the UUID list for the four
 * bench classes to sample against. Dataset size is read from the
 * {@code jmh.dataset} system property (default 100000).
 */
@State(Scope.Benchmark)
public class BenchHarness {

    public PostgreSQLContainer<?> container;
    public Connection conn;
    public List<UUID> uuids;
    public int datasetSize;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        datasetSize = Integer.parseInt(System.getProperty("jmh.dataset", "100000"));

        container = AgePostgresContainer.create();
        container.start();

        conn = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS age");
            s.execute("LOAD 'age'");
            s.execute("SET search_path = ag_catalog, \"$user\", public");
        }

        uuids = SeedGenerator.build(conn, datasetSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
        if (container != null) {
            container.stop();
        }
    }
}
