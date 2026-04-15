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
package dev.tessera.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 02-W1-02 / SEC-06 / CONTEXT Decision 2: fail-closed startup guard coverage.
 *
 * <p>Three scenarios exercised against a single AGE Testcontainer (shared
 * between test methods; each test resets {@code schema_properties} to a
 * known state in {@link #resetEncryptedMarkers()}):
 *
 * <ol>
 *   <li>Flag OFF + no encrypted rows → {@code verify} returns cleanly.
 *   <li>Flag OFF + one encrypted row → {@code verify} throws
 *       {@link IllegalStateException}.
 *   <li>Flag ON + encrypted row → {@code verify} returns cleanly.
 * </ol>
 *
 * <p>Avoids full {@code @SpringBootTest} context churn — the guard only
 * needs a {@code NamedParameterJdbcTemplate} and a boolean, so constructing
 * it directly is the tightest feedback loop.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncryptionStartupGuardIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    private DataSource ds;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;

    EncryptionStartupGuardIT() {
        // Deferred init — container not yet started at field-init time.
    }

    private void ensureInitialised() {
        if (ds == null) {
            DriverManagerDataSource dds =
                    new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
            dds.setDriverClassName("org.postgresql.Driver");
            this.ds = dds;
            Flyway.configure()
                    .dataSource(ds)
                    .baselineOnMigrate(true)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            this.jdbc = new JdbcTemplate(ds);
            this.named = new NamedParameterJdbcTemplate(ds);
        }
    }

    @AfterEach
    void resetEncryptedMarkers() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM schema_properties");
            jdbc.update("DELETE FROM schema_node_types");
        }
    }

    @Test
    void flag_off_no_encrypted_rows_boots_cleanly() {
        ensureInitialised();
        EncryptionStartupGuard guard = new EncryptionStartupGuard(named, false);
        guard.verify(); // must not throw
    }

    @Test
    void flag_off_with_encrypted_row_refuses_to_boot() {
        ensureInitialised();
        seedEncryptedProperty();
        EncryptionStartupGuard guard = new EncryptionStartupGuard(named, false);
        assertThatThrown(guard);
    }

    @Test
    void flag_on_with_encrypted_row_boots_cleanly() {
        ensureInitialised();
        seedEncryptedProperty();
        EncryptionStartupGuard guard = new EncryptionStartupGuard(named, true);
        guard.verify(); // must not throw — encryption machinery is "on"
    }

    private void seedEncryptedProperty() {
        UUID typeId = UUID.randomUUID();
        UUID propId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO schema_node_types (id, model_id, name, slug, label, description, builtin, created_at)"
                        + " VALUES (?::uuid, ?::uuid, 'Secret', 'Secret', 'Secret', null, false, clock_timestamp())",
                typeId.toString(),
                modelId.toString());
        jdbc.update(
                "INSERT INTO schema_properties"
                        + " (id, model_id, type_slug, name, slug, data_type, required, property_encrypted,"
                        + " property_encrypted_alg, created_at)"
                        + " VALUES (?::uuid, ?::uuid, 'Secret', 'ssn', 'ssn', 'string', true, true, 'AES-256-GCM',"
                        + " clock_timestamp())",
                propId.toString(),
                modelId.toString());
    }

    private static void assertThatThrown(EncryptionStartupGuard guard) {
        Throwable caught = null;
        try {
            guard.verify();
        } catch (IllegalStateException e) {
            caught = e;
        }
        assertThat(caught)
                .as("guard must fail-fast when flag off + encrypted marker present")
                .isNotNull()
                .hasMessageContaining("Field-level encryption is disabled")
                .hasMessageContaining("property_encrypted=TRUE");
    }
}
