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
import java.util.List;
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

/** SCHEMA-01 — node type CRUD roundtrip through SchemaRegistry. */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class SchemaNodeTypeCrudIT {

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
    void create_list_get_update_description_deprecate() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        // create
        NodeTypeDescriptor created =
                registry.createNodeType(ctx, new CreateNodeTypeSpec("Person", "Person", "Person", "A human being"));
        assertThat(created.slug()).isEqualTo("Person");
        assertThat(created.description()).isEqualTo("A human being");
        assertThat(created.deprecatedAt()).isNull();

        // list
        List<NodeTypeDescriptor> list = registry.listNodeTypes(ctx);
        assertThat(list).extracting(NodeTypeDescriptor::slug).containsExactly("Person");

        // get via loadFor
        assertThat(registry.loadFor(ctx, "Person")).isPresent();

        // update description
        registry.updateNodeTypeDescription(ctx, "Person", "Updated description");
        NodeTypeDescriptor after = registry.loadFor(ctx, "Person").orElseThrow();
        assertThat(after.description()).isEqualTo("Updated description");

        // deprecate
        registry.deprecateNodeType(ctx, "Person");
        NodeTypeDescriptor deprecated = registry.loadFor(ctx, "Person").orElseThrow();
        assertThat(deprecated.deprecatedAt()).isNotNull();
    }

    @Test
    void tenant_isolation_on_list() {
        TenantContext a = TenantContext.of(UUID.randomUUID());
        TenantContext b = TenantContext.of(UUID.randomUUID());
        registry.createNodeType(a, CreateNodeTypeSpec.of("Alpha"));
        registry.createNodeType(b, CreateNodeTypeSpec.of("Beta"));
        assertThat(registry.listNodeTypes(a))
                .extracting(NodeTypeDescriptor::slug)
                .containsExactly("Alpha");
        assertThat(registry.listNodeTypes(b))
                .extracting(NodeTypeDescriptor::slug)
                .containsExactly("Beta");
    }
}
