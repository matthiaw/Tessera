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
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Thin {@code main()} wrapper around {@link SeedGenerator#build(Connection, int)}.
 *
 * <p>Used by {@code scripts/dump_restore_rehearsal.sh} (FOUND-05 / D-05) to
 * seed a running AGE container with the same 100k dataset the JMH harness
 * uses. Kept under the test source tree so it never ships in a production
 * jar.
 *
 * <p>Connects to a Postgres+AGE instance via JDBC, primes the session
 * ({@code LOAD 'age'} + {@code search_path}), then delegates to
 * {@link SeedGenerator#build(Connection, int)}. The graph
 * ({@value SeedGenerator#GRAPH_NAME}) is dropped and recreated by
 * SeedGenerator, so the caller does not need to pre-create it.
 *
 * <p>Configuration is via {@code -Dseed.*} system properties so the shell
 * script can drive this without rewriting connection strings.
 */
public final class SeedDriver {

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("seed.host", "localhost");
        String port = System.getProperty("seed.port", "5432");
        String db = System.getProperty("seed.db", "tessera");
        String user = System.getProperty("seed.user", "tessera");
        String pw = System.getProperty("seed.password", "tessera");
        int count = Integer.parseInt(System.getProperty("seed.count", "100000"));

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        System.out.println("SeedDriver: connecting to " + url + " (count=" + count + ")");

        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            try (Statement s = c.createStatement()) {
                s.execute("LOAD 'age'");
                s.execute("SET search_path = ag_catalog, \"$user\", public");
            }
            SeedGenerator.build(c, count);
        }
        System.out.println("SeedDriver: seeded " + count + " nodes into " + SeedGenerator.GRAPH_NAME);
    }

    private SeedDriver() {}
}
