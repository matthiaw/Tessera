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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 02-W1-01: assert that Wave 1 Schema Registry exposure + encryption flag
 * columns (V13) round-trip through the JDBC repo, the Caffeine descriptor
 * cache, and the {@link NodeTypeDescriptor} / {@link PropertyDescriptor}
 * record surface. Verifies CONTEXT Decisions 2 + 5.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Declare a node type via {@link SchemaRegistry}. Assert exposure flags
 *       default to false and property encrypted flag defaults to false.
 *   <li>Flip {@code rest_read_enabled} + {@code rest_write_enabled} via direct
 *       JDBC UPDATE (Wave 1 exposes no admin endpoint yet — Wave 2 does).
 *   <li>Flip {@code property_encrypted} + {@code property_encrypted_alg} the
 *       same way.
 *   <li>Invalidate the Caffeine cache for this tenant (mirrors the production
 *       flow where the admin endpoint would invalidate after a row mutation).
 *   <li>Reload descriptor; assert the new column values surface on both the
 *       node-type record and the property record.
 * </ol>
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class SchemaRestExposureColumnsIT {

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

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void exposure_and_encryption_flags_round_trip_through_descriptor_cache() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        // 1. Declare a node type and add one property.
        registry.createNodeType(ctx, new CreateNodeTypeSpec("Article", "Article", "Article", "A content article"));
        registry.addProperty(ctx, "Article", new AddPropertySpec("title", "Title", "string", true));

        NodeTypeDescriptor initial = registry.loadFor(ctx, "Article").orElseThrow();
        assertThat(initial.restReadEnabled()).isFalse();
        assertThat(initial.restWriteEnabled()).isFalse();
        assertThat(initial.properties()).hasSize(1);
        PropertyDescriptor titleInitial = initial.properties().get(0);
        assertThat(titleInitial.encrypted()).isFalse();
        assertThat(titleInitial.encryptedAlg()).isNull();

        // 2 + 3. Flip columns via direct JDBC (the Wave 1 admin endpoint does
        //         not exist yet — Wave 2 will wrap this in `/admin/schema/*/expose`).
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = TRUE, rest_write_enabled = TRUE"
                        + " WHERE model_id = ?::uuid AND slug = ?",
                ctx.modelId().toString(),
                "Article");
        jdbc.update(
                "UPDATE schema_properties SET property_encrypted = TRUE, property_encrypted_alg = ?"
                        + " WHERE model_id = ?::uuid AND type_slug = ? AND slug = ?",
                "AES-256-GCM",
                ctx.modelId().toString(),
                "Article",
                "title");

        // 4. Force a version bump to blow the descriptor cache key — a direct
        //    JDBC mutation does not go through SchemaRegistry so the cache is
        //    stale. Adding another property is the simplest existing hook that
        //    invokes cache.invalidateAll and bumps the schema version.
        registry.addProperty(ctx, "Article", new AddPropertySpec("body", "Body", "string", false));

        // 5. Reload and assert the new columns surfaced.
        NodeTypeDescriptor reloaded = registry.loadFor(ctx, "Article").orElseThrow();
        assertThat(reloaded.restReadEnabled()).isTrue();
        assertThat(reloaded.restWriteEnabled()).isTrue();

        PropertyDescriptor titleReloaded = reloaded.properties().stream()
                .filter(p -> "title".equals(p.slug()))
                .findFirst()
                .orElseThrow();
        assertThat(titleReloaded.encrypted()).isTrue();
        assertThat(titleReloaded.encryptedAlg()).isEqualTo("AES-256-GCM");

        PropertyDescriptor bodyReloaded = reloaded.properties().stream()
                .filter(p -> "body".equals(p.slug()))
                .findFirst()
                .orElseThrow();
        assertThat(bodyReloaded.encrypted()).isFalse();
        assertThat(bodyReloaded.encryptedAlg()).isNull();
    }
}
