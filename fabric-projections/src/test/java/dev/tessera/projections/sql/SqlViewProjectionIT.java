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
package dev.tessera.projections.sql;

import dev.tessera.projections.rest.ProjectionItApplication;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * SQL-01 / D-D3: Verifies that the SQL view projection generates queryable Postgres views
 * for each node type registered in a tenant's schema, with columns matching the
 * {@code NodeTypeDescriptor} and tombstoned entities excluded.
 *
 * <p>Wave 0 stub — enabled by Plan 04-01.
 */
@Disabled("Wave 0 stub — implementation in Plan 04-01")
@SpringBootTest(
        classes = ProjectionItApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SqlViewProjectionIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("tessera")
                    .withUsername("tessera")
                    .withPassword("tessera")
                    .withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    /**
     * SQL-01: After creating a node type, the corresponding SQL view is queryable
     * in the tenant's schema and returns persisted entities.
     */
    @Test
    void viewCreatedForTenantNodeType() {
        fail("Not yet implemented — SQL-01: view created for tenant node type (Plan 04-01)");
    }

    /**
     * SQL-01: The generated view's columns match the properties defined in the
     * {@code NodeTypeDescriptor}, including system properties (_uuid, _created_at, etc.).
     */
    @Test
    void viewColumnsMatchSchemaProperties() {
        fail("Not yet implemented — SQL-01: view columns match NodeTypeDescriptor (Plan 04-01)");
    }

    /**
     * SQL-01: Tombstoned (soft-deleted) nodes must not appear in the SQL view.
     */
    @Test
    void viewExcludesTombstonedEntities() {
        fail("Not yet implemented — SQL-01: tombstoned nodes not in view (Plan 04-01)");
    }

    /**
     * D-D3: The generated view DDL contains a schema version comment, enabling
     * staleness detection and targeted view regeneration on schema change.
     */
    @Test
    void viewDdlContainsSchemaVersionComment() {
        fail("Not yet implemented — D-D3: view DDL schema version comment for staleness detection (Plan 04-01)");
    }
}
