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
package dev.tessera.core.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Wave 1 test helper: wires a {@link HikariDataSource} against an
 * {@link AgePostgresContainer}, runs the Flyway V1..V9 migrations, and hands
 * back a {@link JdbcTemplate}. Keeps Wave 1 ITs free of Spring context
 * startup costs — we only need a pooled {@code DataSource}.
 *
 * <p>The Hikari pool uses the same {@code connection-init-sql} as
 * {@code application-flyway-it.yml} so every pooled connection has
 * {@code LOAD 'age'} and the AGE search path pre-primed (FOUND-03 / D-10).
 */
public final class AgeTestHarness {

    private AgeTestHarness() {}

    /** Build a pooled DataSource against a running container and migrate it. */
    public static HikariDataSource dataSourceFor(PostgreSQLContainer<?> pg) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(pg.getJdbcUrl());
        cfg.setUsername(pg.getUsername());
        cfg.setPassword(pg.getPassword());
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("TesseraHikariPool-W1IT");
        cfg.setConnectionInitSql("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
        HikariDataSource ds = new HikariDataSource(cfg);
        migrate(ds);
        return ds;
    }

    /** Run Flyway V1..V9 against the given DataSource. */
    public static void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public static JdbcTemplate jdbcTemplate(DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
