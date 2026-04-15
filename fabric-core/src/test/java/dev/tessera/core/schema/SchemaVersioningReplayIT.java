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
package dev.tessera.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SCHEMA-04 — old versions queryable via schema_version snapshot rows.
 *
 * <p>Change sequence: createNodeType Person → version 1; addProperty name →
 * version 2; addProperty age → version 3; addProperty email → version 4.
 * At version 3 Person has 2 properties (name, age); at version 4 it has 3.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class SchemaVersioningReplayIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    SchemaRegistry registry;

    @Test
    void historical_read_trims_properties_to_version() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        registry.createNodeType(ctx, CreateNodeTypeSpec.of("Person")); // v1
        registry.addProperty(ctx, "Person", AddPropertySpec.of("name", "string")); // v2
        registry.addProperty(ctx, "Person", AddPropertySpec.of("age", "int")); // v3
        registry.addProperty(ctx, "Person", AddPropertySpec.of("email", "string")); // v4

        NodeTypeDescriptor current = registry.loadFor(ctx, "Person").orElseThrow();
        assertThat(current.properties()).hasSize(3);

        NodeTypeDescriptor atV3 = registry.getAt(ctx, "Person", 3L).orElseThrow();
        assertThat(atV3.properties()).hasSize(2);

        NodeTypeDescriptor atV2 = registry.getAt(ctx, "Person", 2L).orElseThrow();
        assertThat(atV2.properties()).hasSize(1);

        NodeTypeDescriptor atV1 = registry.getAt(ctx, "Person", 1L).orElseThrow();
        assertThat(atV1.properties()).isEmpty();
    }
}
