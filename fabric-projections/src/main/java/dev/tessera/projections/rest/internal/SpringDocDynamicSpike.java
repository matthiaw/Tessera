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
package dev.tessera.projections.rest.internal;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * WAVE 0 SPIKE — de-risks REST-05 / CONTEXT Decision 13 / RESEARCH assumption
 * A1 + A7. This is the minimum viable SpringDoc {@code OpenApiCustomizer}
 * wired to a pull-side {@link ExposedTypeSource}. Wave 2's production
 * {@code OpenApiCustomizer} (REST-05) will reuse exactly this lifecycle
 * pattern; the Wave 0 {@code SchemaVersionBumpIT} proves empirically that
 * the customizer runs on every {@code /v3/api-docs} hit when
 * {@code springdoc.cache.disabled=true}, i.e. runtime schema flips are
 * visible without an application restart.
 *
 * <p>Gated on profile {@code spike-openapi} so the bean is inert in
 * production until Wave 2 wires its real successor. Scan base is
 * {@code dev.tessera.projections.rest} so the spike is only picked up when
 * an explicit test/app configuration imports it.
 *
 * <p>If the IT ever goes red on the runtime-flip branch, STOP and escalate
 * to the orchestrator for the fallback discussion (manual cache-bust via
 * {@code springDocProviders.getWebMvcProvider().getActualGroups()} or a
 * schema-change-triggered application restart).
 */
@Configuration(proxyBeanMethods = false)
@Profile("spike-openapi")
public class SpringDocDynamicSpike {

    /**
     * Publish a {@link GroupedOpenApi} whose customizer walks
     * {@link ExposedTypeSource#currentlyExposed()} on every doc build. The
     * {@code pathsToMatch} glob is intentionally permissive — the customizer
     * adds paths directly, so the built-in path filter must not strip them
     * away.
     */
    @Bean
    public GroupedOpenApi entitiesApi(ExposedTypeSource exposedTypeSource) {
        return GroupedOpenApi.builder()
                .group("entities")
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(openApi -> {
                    Paths paths = openApi.getPaths() != null ? openApi.getPaths() : new Paths();
                    for (ExposedTypeSource.ExposedType t : exposedTypeSource.currentlyExposed()) {
                        String path = "/api/v1/" + t.modelSlug() + "/entities/" + t.typeSlug();
                        // Schema names must be namespaced by model to avoid
                        // cross-tenant collisions (RESEARCH Q1 pitfall).
                        String schemaName = t.modelSlug() + "_" + t.typeSlug() + "Entity";
                        paths.addPathItem(path, buildListPathItem(schemaName));
                        openApi.schema(schemaName, buildEntitySchema());
                    }
                    openApi.setPaths(paths);
                })
                .build();
    }

    private static PathItem buildListPathItem(String schemaName) {
        Operation get = new Operation()
                .summary("List entities (spike)")
                .operationId("list_" + schemaName)
                .responses(new ApiResponses()
                        .addApiResponse(
                                "200",
                                new ApiResponse()
                                        .description("OK")
                                        .content(new Content()
                                                .addMediaType(
                                                        "application/json",
                                                        new MediaType()
                                                                .schema(new Schema<>()
                                                                        .$ref("#/components/schemas/"
                                                                                + schemaName))))));
        return new PathItem().get(get);
    }

    private static Schema<?> buildEntitySchema() {
        return new ObjectSchema()
                .addProperty("uuid", new Schema<String>().type("string").format("uuid"));
    }
}
