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

import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.schema.SchemaRegistry;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.UUID;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * REST-05: production-grade OpenAPI document customizer. Promoted from the
 * Wave 0 spike ({@link SpringDocDynamicSpike}) to a full implementation
 * that walks {@link SchemaRegistry#listDistinctExposedModels()} and
 * {@link SchemaRegistry#listExposedTypes(UUID)} on every
 * {@code /v3/api-docs?group=entities} request.
 *
 * <p>With {@code springdoc.cache.disabled=true} (set in application.yml),
 * the customizer re-runs on every doc fetch. Flipping
 * {@code rest_read_enabled} on a type makes it appear in the next
 * {@code /v3/api-docs} hit without a restart (proven by
 * {@code SchemaVersionBumpIT} in Wave 0 and {@code DynamicOpenApiIT} in W2c).
 *
 * <p>Schema names are namespaced as {@code {model}_{slug}Entity} to avoid
 * cross-tenant collisions (RESEARCH Q1 pitfall).
 *
 * <p>Property descriptors are mapped to OpenAPI types:
 * STRING -> string, INTEGER -> integer, BOOLEAN -> boolean,
 * DECIMAL -> number, DATE -> string(date), DATETIME -> string(date-time).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiSchemaCustomizer {

    @Bean
    public GroupedOpenApi entitiesApi(SchemaRegistry registry) {
        return GroupedOpenApi.builder()
                .group("entities")
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(openApi -> {
                    Paths paths = openApi.getPaths() != null ? openApi.getPaths() : new Paths();

                    for (UUID modelId : registry.listDistinctExposedModels()) {
                        String modelSlug = modelId.toString();
                        for (NodeTypeDescriptor type : registry.listExposedTypes(modelId)) {
                            String typeSlug = type.slug();
                            String schemaName = modelSlug + "_" + typeSlug + "Entity";
                            String listSchemaName = modelSlug + "_" + typeSlug + "EntityList";
                            String basePath = "/api/v1/" + modelSlug + "/entities/" + typeSlug;

                            // Build entity schema from property descriptors
                            Schema<?> entitySchema = buildEntitySchema(type);
                            openApi.schema(schemaName, entitySchema);

                            // Build list response schema
                            Schema<?> listSchema = buildListSchema(schemaName);
                            openApi.schema(listSchemaName, listSchema);

                            // Build path item with CRUD operations
                            PathItem collectionItem =
                                    buildCollectionPathItem(modelSlug, typeSlug, schemaName, listSchemaName);
                            paths.addPathItem(basePath, collectionItem);

                            // Build single-resource path item
                            PathItem resourceItem = buildResourcePathItem(modelSlug, typeSlug, schemaName);
                            paths.addPathItem(basePath + "/{id}", resourceItem);
                        }
                    }
                    openApi.setPaths(paths);
                })
                .build();
    }

    private static Schema<?> buildEntitySchema(NodeTypeDescriptor type) {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("uuid", new StringSchema().format("uuid"));
        schema.addProperty("type", new StringSchema());
        schema.addProperty("seq", new Schema<Long>().type("integer").format("int64"));
        schema.addProperty("created_at", new StringSchema().format("date-time"));
        schema.addProperty("updated_at", new StringSchema().format("date-time"));

        if (type.properties() != null) {
            for (PropertyDescriptor prop : type.properties()) {
                schema.addProperty(prop.slug(), mapDataType(prop.dataType()));
            }
        }
        return schema;
    }

    @SuppressWarnings("rawtypes")
    private static Schema mapDataType(String dataType) {
        if (dataType == null) {
            return new StringSchema();
        }
        return switch (dataType.toUpperCase()) {
            case "STRING", "TEXT" -> new StringSchema();
            case "INTEGER", "INT", "LONG" -> new Schema<>().type("integer");
            case "BOOLEAN", "BOOL" -> new Schema<>().type("boolean");
            case "DECIMAL", "DOUBLE", "FLOAT", "NUMBER" -> new Schema<>().type("number");
            case "DATE" -> new StringSchema().format("date");
            case "DATETIME", "TIMESTAMP" -> new StringSchema().format("date-time");
            default -> new StringSchema();
        };
    }

    private static Schema<?> buildListSchema(String entitySchemaName) {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty(
                "items", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/" + entitySchemaName)));
        schema.addProperty("next_cursor", new StringSchema().nullable(true));
        return schema;
    }

    private static PathItem buildCollectionPathItem(
            String modelSlug, String typeSlug, String schemaName, String listSchemaName) {
        // GET list
        Operation getList = new Operation()
                .summary("List " + typeSlug + " entities")
                .operationId("list_" + modelSlug + "_" + typeSlug)
                .addTagsItem(modelSlug)
                .addParametersItem(new Parameter()
                        .name("cursor")
                        .in("query")
                        .required(false)
                        .schema(new StringSchema()))
                .addParametersItem(new Parameter()
                        .name("limit")
                        .in("query")
                        .required(false)
                        .schema(new Schema<Integer>().type("integer").example(50)))
                .responses(new ApiResponses()
                        .addApiResponse("200", jsonResponse("OK", listSchemaName))
                        .addApiResponse("404", problemResponse("Not Found")));

        // POST create
        Operation post = new Operation()
                .summary("Create a " + typeSlug + " entity")
                .operationId("create_" + modelSlug + "_" + typeSlug)
                .addTagsItem(modelSlug)
                .requestBody(new RequestBody()
                        .required(true)
                        .content(new Content()
                                .addMediaType(
                                        "application/json",
                                        new MediaType()
                                                .schema(new Schema<>().$ref("#/components/schemas/" + schemaName)))))
                .responses(new ApiResponses()
                        .addApiResponse("201", jsonResponse("Created", schemaName))
                        .addApiResponse("404", problemResponse("Not Found"))
                        .addApiResponse("422", problemResponse("Validation Failed")));

        PathItem item = new PathItem();
        item.setGet(getList);
        item.setPost(post);
        return item;
    }

    private static PathItem buildResourcePathItem(String modelSlug, String typeSlug, String schemaName) {
        Parameter idParam =
                new Parameter().name("id").in("path").required(true).schema(new StringSchema().format("uuid"));

        // GET single
        Operation get = new Operation()
                .summary("Get " + typeSlug + " entity by ID")
                .operationId("get_" + modelSlug + "_" + typeSlug)
                .addTagsItem(modelSlug)
                .addParametersItem(idParam)
                .responses(new ApiResponses()
                        .addApiResponse("200", jsonResponse("OK", schemaName))
                        .addApiResponse("404", problemResponse("Not Found")));

        // PUT update
        Operation put = new Operation()
                .summary("Update " + typeSlug + " entity")
                .operationId("update_" + modelSlug + "_" + typeSlug)
                .addTagsItem(modelSlug)
                .addParametersItem(idParam)
                .requestBody(new RequestBody()
                        .required(true)
                        .content(new Content()
                                .addMediaType(
                                        "application/json",
                                        new MediaType()
                                                .schema(new Schema<>().$ref("#/components/schemas/" + schemaName)))))
                .responses(new ApiResponses()
                        .addApiResponse("200", jsonResponse("OK", schemaName))
                        .addApiResponse("404", problemResponse("Not Found"))
                        .addApiResponse("422", problemResponse("Validation Failed")));

        // DELETE
        Operation delete = new Operation()
                .summary("Delete " + typeSlug + " entity")
                .operationId("delete_" + modelSlug + "_" + typeSlug)
                .addTagsItem(modelSlug)
                .addParametersItem(idParam)
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("Deleted"))
                        .addApiResponse("404", problemResponse("Not Found")));

        PathItem item = new PathItem();
        item.setGet(get);
        item.setPut(put);
        item.setDelete(delete);
        return item;
    }

    private static ApiResponse jsonResponse(String description, String schemaName) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                "application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + schemaName))));
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                "application/problem+json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
    }
}
