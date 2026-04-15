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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.internal.ShapeCache;
import java.math.BigDecimal;
import java.util.Map;
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

/** SCHEMA-07 — the SchemaRegistry is the source of truth fed into the SHACL shape compiler. */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class SchemaToShaclIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    GraphService graphService;

    @Autowired
    SchemaRegistry registry;

    @Autowired
    ShapeCache shapeCache;

    @Test
    void applying_a_mutation_populates_the_shape_cache_for_the_tenants_type() {
        shapeCache.invalidateAll();
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        registry.createNodeType(ctx, new CreateNodeTypeSpec("Person", "Person", "Person", "desc"));
        registry.addProperty(ctx, "Person", AddPropertySpec.required("name", "string"));

        long missBefore = shapeCache.raw().stats().missCount();

        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Alice"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-1")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build());

        // The shape cache must have compiled a Shapes object for this tenant's Person type.
        assertThat(shapeCache.raw().estimatedSize()).isGreaterThanOrEqualTo(1);
        assertThat(shapeCache.raw().stats().missCount()).isGreaterThan(missBefore);

        // Second write should hit the cache (same (modelId, schemaVersion, typeSlug)).
        long hitsBefore = shapeCache.raw().stats().hitCount();
        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Bob"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-2")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build());
        assertThat(shapeCache.raw().stats().hitCount()).isGreaterThan(hitsBefore);
    }
}
