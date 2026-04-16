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
package dev.tessera.projections.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.projections.rest.internal.ExposedTypeSource;
import dev.tessera.projections.rest.internal.SpringDocDynamicSpike;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * WAVE 0 SPIKE — de-risks REST-05 / CONTEXT Decision 13 / RESEARCH assumption
 * A1 + A7. Wave 2 OpenApiCustomizer production code depends on this test
 * staying green. If it fails, STOP and escalate to the orchestrator.
 *
 * <p>Test flow empirically pins SpringDoc's runtime lifecycle:
 *
 * <ol>
 *   <li>Register node type with {@code rest_read_enabled=false} — via the
 *       test's mutable {@link ExposedTypeSource}. The Wave 0 spike intentionally
 *       does NOT wire against {@code SchemaRegistry} directly (a Testcontainers
 *       + AGE + Flyway slice is overkill for proving SpringDoc cache timing).
 *       Wave 1 / Wave 2 replace the in-memory source with a SchemaRegistry-backed
 *       adapter that reads the {@code rest_read_enabled} column added by Wave 1.
 *   <li>GET {@code /v3/api-docs?group=entities}, assert path ABSENT.
 *   <li>Flip the flag {@code rest_read_enabled=true}.
 *   <li>GET {@code /v3/api-docs?group=entities} a second time WITHOUT restart,
 *       assert path PRESENT.
 *   <li>Flip back to false, assert path ABSENT again.
 * </ol>
 *
 * <p>Property override {@code springdoc.cache.disabled=true} is load-bearing —
 * it forces SpringDoc to re-run the customizer on every hit.
 */
@SpringBootTest(
        classes = SchemaVersionBumpIT.SpikeApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("spike-openapi")
@TestPropertySource(
        properties = {
            "springdoc.cache.disabled=true",
            "springdoc.api-docs.enabled=true",
            "springdoc.show-actuator=false",
            "management.endpoints.enabled-by-default=false"
        })
class SchemaVersionBumpIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MutableExposedTypeSource mutableSource;

    @Test
    void openApiDocReflectsRuntimeSchemaFlip() {
        String targetPath = "\"/api/v1/spike-tenant/entities/spike-type\"";

        // (1) rest_read_enabled=false — path must be absent
        mutableSource.clear();
        String firstHit = fetchEntitiesGroup();
        assertThat(firstHit)
                .as("with rest_read_enabled=false, the spike type must NOT appear in /v3/api-docs")
                .doesNotContain(targetPath);

        // (2) flip to rest_read_enabled=true — second hit, no restart
        mutableSource.expose("spike-tenant", "spike-type");
        String secondHit = fetchEntitiesGroup();
        assertThat(secondHit)
                .as("WAVE 0 GATE: SpringDoc must rebuild the doc on every hit when "
                        + "springdoc.cache.disabled=true. If this assertion fails, the "
                        + "runtime-schema-flip pattern is broken — STOP and escalate to "
                        + "the orchestrator for the fallback discussion (manual cache "
                        + "invalidation or restart-on-schema-change).")
                .contains(targetPath)
                .contains("spike-tenant_spike-typeEntity");

        // (3) flip back to false — path must disappear again
        mutableSource.clear();
        String thirdHit = fetchEntitiesGroup();
        assertThat(thirdHit)
                .as("flipping rest_read_enabled=false must remove the path on the next hit")
                .doesNotContain(targetPath);
    }

    private String fetchEntitiesGroup() {
        return rest.getForObject("/v3/api-docs/entities", String.class);
    }

    // --- minimal Spring Boot app boot for the spike IT ---

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet
                        .OAuth2ResourceServerAutoConfiguration.class
            })
    @Import(SpringDocDynamicSpike.class)
    static class SpikeApp {

        @Bean
        MutableExposedTypeSource mutableExposedTypeSource() {
            return new MutableExposedTypeSource();
        }
    }

    /** Test-only mutable implementation — flips expose state at runtime. */
    static final class MutableExposedTypeSource implements ExposedTypeSource {
        private final List<ExposedType> entries = new CopyOnWriteArrayList<>();

        void clear() {
            entries.clear();
        }

        void expose(String modelSlug, String typeSlug) {
            entries.add(new ExposedType(modelSlug, typeSlug));
        }

        @Override
        public List<ExposedType> currentlyExposed() {
            return new ArrayList<>(entries);
        }
    }
}
