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
import java.util.Optional;
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

/** SCHEMA-05 — property rename via schema_property_aliases. */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class SchemaAliasIT {

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
    void rename_property_records_alias_and_old_slug_resolves() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        registry.createNodeType(ctx, CreateNodeTypeSpec.of("Person"));
        registry.addProperty(ctx, "Person", AddPropertySpec.of("fullName", "string"));

        registry.renameProperty(ctx, "Person", "fullName", "name");

        Optional<String> resolved = registry.resolvePropertySlug(ctx, "Person", "fullName");
        assertThat(resolved).contains("name");

        // Unknown slug returns empty
        assertThat(registry.resolvePropertySlug(ctx, "Person", "nonExistent")).isEmpty();

        // loadFor still returns the type with the original property row intact
        // (rename is a logical alias in Wave 2 — the physical schema_properties
        // row is not migrated).
        NodeTypeDescriptor d = registry.loadFor(ctx, "Person").orElseThrow();
        assertThat(d.properties()).extracting(PropertyDescriptor::slug).contains("fullName");
    }
}
