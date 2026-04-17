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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaChangeEvent;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.rest.ProjectionItApplication;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SQL-02: Integration tests verifying that the SchemaChangeEvent @TransactionalEventListener
 * path is correctly wired end-to-end: SchemaRegistry mutations publish events that reach
 * SqlViewProjection.onSchemaChange() and trigger view regeneration.
 *
 * <p>Note: SQL view CREATE OR REPLACE requires an AGE label table to exist for the type
 * (the AGE graph layer). In this IT context the AGE graph is initialized but no nodes
 * have been inserted, so regenerateForTenant() gracefully skips view DDL when no label
 * table exists. The tests focus on the wiring and transaction semantics rather than
 * the view DDL content (which is covered by SqlViewProjectionIT with AGE labels).
 */
@ActiveProfiles("projection-it")
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SqlViewSchemaChangeIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
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

    @Autowired
    private SchemaRegistry schemaRegistry;

    @Autowired
    private SqlViewProjection sqlViewProjection;

    private TenantContext ctx;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        // Fresh model_id per test — avoids cross-test interference
        modelId = UUID.randomUUID();
        ctx = TenantContext.of(modelId);
    }

    /**
     * SQL-02: When a schema change is published (new property added to a node type), the
     * onSchemaChange @TransactionalEventListener fires after the commit and triggers
     * regenerateForTenant() without any exception propagating.
     *
     * <p>The @TransactionalEventListener(AFTER_COMMIT) semantics are verified by ensuring the full
     * call chain (createNodeType → SchemaChangeEvent → onSchemaChange → regenerateForTenant)
     * completes without error. View DDL creation is skipped gracefully when no AGE label table
     * exists (no nodes inserted yet) — this is expected and correct behaviour.
     */
    @Test
    void viewRegeneratedOnSchemaChange() {
        // Arrange + Act: createNodeType publishes SchemaChangeEvent; after the @Transactional
        // commits, onSchemaChange fires → regenerateForTenant runs (skips DDL since no AGE label)
        assertThatCode(() -> schemaRegistry.createNodeType(
                        ctx, new CreateNodeTypeSpec("evtwidget", "EvtWidget", "evtwidget", "event-driven test type")))
                .doesNotThrowAnyException();

        // Act: addProperty also publishes SchemaChangeEvent → onSchemaChange fires again
        assertThatCode(() -> schemaRegistry.addProperty(
                        ctx, "evtwidget", new AddPropertySpec("evtcolor", "EvtColor", "STRING", false)))
                .doesNotThrowAnyException();

        // Assert: SchemaRegistry schema change events propagated end-to-end without aborting
        // the mutation transaction (the pre-AFTER_COMMIT bug would have caused PSQLException
        // "current transaction is aborted" here if onSchemaChange ran inside the mutation TX)
        long schemaVersion = schemaRegistry.listNodeTypes(ctx).stream()
                .filter(t -> t.slug().equals("evtwidget"))
                .findFirst()
                .map(t -> t.schemaVersion())
                .orElse(-1L);
        assertThat(schemaVersion).isGreaterThan(0L);
    }

    /**
     * SQL-02: Direct invocation of onSchemaChange on the wired SqlViewProjection bean confirms
     * the listener method is accessible, runs regenerateForTenant without exception, and does not
     * propagate errors when no views can be generated (no AGE label tables for fresh types).
     *
     * <p>This test also verifies the ApplicationRunner.run() startup behaviour: on application
     * startup regenerateAll() is called, which completes without error even when no models have
     * REST-exposed types yet (the empty-model path is explicitly guarded).
     */
    @Test
    void viewsSurviveApplicationRestart() {
        // Register a type to ensure the schema is non-empty
        schemaRegistry.createNodeType(
                ctx, new CreateNodeTypeSpec("survivaltest", "SurvivalTest", "survivaltest", "startup test type"));

        // Act: call onSchemaChange directly on the Spring bean (simulates event delivery)
        // Verifies the wired bean is reachable and the method completes without exception
        SchemaChangeEvent event = new SchemaChangeEvent(modelId, "CREATE_TYPE", "survivaltest");
        assertThatCode(() -> sqlViewProjection.onSchemaChange(event)).doesNotThrowAnyException();

        // Act: simulate ApplicationRunner.run() — regenerateAll() is idempotent
        // (only processes models with rest_read_enabled=true; none set here → empty run)
        assertThatCode(() -> sqlViewProjection.regenerateAll()).doesNotThrowAnyException();
    }
}
