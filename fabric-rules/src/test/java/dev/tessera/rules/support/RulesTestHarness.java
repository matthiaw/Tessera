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
package dev.tessera.rules.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Fabric-rules integration-test harness. Mirrors fabric-core's
 * {@code AgeTestHarness} — builds a pooled Hikari {@link DataSource} against
 * a running {@link AgePostgresContainer}, runs all Flyway migrations from
 * {@code classpath:db/migration}, and hands back a
 * {@link NamedParameterJdbcTemplate}. Rule engine ITs use this instead of a
 * full Spring Boot context — the rule engine components under test do not
 * need a web / MCP / circuit-breaker bean graph.
 */
public final class RulesTestHarness {

    private RulesTestHarness() {}

    public static HikariDataSource dataSourceFor(PostgreSQLContainer<?> pg) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(pg.getJdbcUrl());
        cfg.setUsername(pg.getUsername());
        cfg.setPassword(pg.getPassword());
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("TesseraHikariPool-RulesIT");
        cfg.setConnectionInitSql("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
        HikariDataSource ds = new HikariDataSource(cfg);
        migrate(ds);
        return ds;
    }

    public static void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public static NamedParameterJdbcTemplate namedJdbc(DataSource ds) {
        return new NamedParameterJdbcTemplate(ds);
    }
}
