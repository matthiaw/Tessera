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
package dev.tessera.connectors.rest;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import dev.tessera.connectors.CandidateMutation;
import dev.tessera.connectors.Connector;
import dev.tessera.connectors.ConnectorCapabilities;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.DlqEntry;
import dev.tessera.connectors.FieldMapping;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.connectors.TransformRegistry;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CONN-07: Generic REST polling connector. Uses JDK 21 {@link HttpClient}
 * with Bearer token auth, Jayway JSONPath 2.9.0 with
 * {@link JacksonMappingProvider}, ETag/Last-Modified delta detection at
 * connector level, and per-row {@code _source_hash} dedup (Decision 18).
 *
 * <p>This class MUST NOT call {@code GraphService} directly -- only
 * {@code ConnectorRunner} may (enforced by ArchUnit).
 */
@Component
public class GenericRestPollerConnector implements Connector {

    private static final Logger LOG = LoggerFactory.getLogger(GenericRestPollerConnector.class);

    private final HttpClient httpClient;
    private final GraphRepository graphRepository;
    private final Configuration jsonPathConfig;

    /**
     * The bearer token is NOT injected here. It is resolved per-poll by
     * the runner from the Vault path in {@code credentials_ref}. The
     * runner passes it via the connector state's customState map.
     */
    public GenericRestPollerConnector(GraphRepository graphRepository) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.graphRepository = graphRepository;
        this.jsonPathConfig = Configuration.builder()
                .mappingProvider(new JacksonMappingProvider())
                .build();
    }

    @Override
    public String type() {
        return "rest-poll";
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(true);
    }

    @Override
    public PollResult poll(Clock clock, MappingDefinition mapping, ConnectorState state, TenantContext tenant) {
        String bearerToken =
                state.customState() != null ? (String) state.customState().get("bearer_token") : null;

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(mapping.sourceUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");

            if (bearerToken != null && !bearerToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + bearerToken);
            }

            // Conditional headers (Decision 18, layer 1)
            if (state.etag() != null) {
                requestBuilder.header("If-None-Match", state.etag());
            }
            if (state.lastModified() != null) {
                requestBuilder.header(
                        "If-Modified-Since",
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                state.lastModified().atZone(ZoneOffset.UTC)));
            }

            HttpResponse<String> response =
                    httpClient.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 304) {
                LOG.debug("304 Not Modified for {}", mapping.sourceUrl());
                return PollResult.unchanged(state);
            }

            if (response.statusCode() != 200) {
                LOG.warn("Unexpected status {} from {}", response.statusCode(), mapping.sourceUrl());
                return PollResult.failed(state);
            }

            // Extract ETag and Last-Modified from response
            String newEtag = response.headers().firstValue("ETag").orElse(null);
            java.time.Instant newLastModified = response.headers()
                    .firstValue("Last-Modified")
                    .map(s -> {
                        try {
                            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant();
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .orElse(null);

            // Parse response body via JSONPath
            DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(response.body());
            List<?> rows;
            try {
                rows = ctx.read(mapping.rootPath(), List.class);
            } catch (PathNotFoundException e) {
                LOG.warn("Root path {} not found in response from {}", mapping.rootPath(), mapping.sourceUrl());
                return PollResult.failed(state);
            }

            List<CandidateMutation> candidates = new ArrayList<>();
            List<DlqEntry> dlq = new ArrayList<>();

            for (Object row : rows) {
                try {
                    DocumentContext rowCtx = JsonPath.using(jsonPathConfig).parse(row);
                    Map<String, Object> properties = new LinkedHashMap<>();
                    boolean hasError = false;

                    for (FieldMapping fm : mapping.fields()) {
                        try {
                            Object raw = rowCtx.read(fm.sourcePath());
                            Object transformed = TransformRegistry.apply(fm.transform(), raw);
                            properties.put(fm.target(), transformed);
                        } catch (PathNotFoundException e) {
                            if (fm.required()) {
                                dlq.add(new DlqEntry(
                                        "MAPPING_ERROR", "Required field missing: " + fm.sourcePath(), rowToMap(row)));
                                hasError = true;
                                break;
                            }
                            properties.put(fm.target(), null);
                        }
                    }

                    if (hasError) {
                        continue;
                    }

                    // Per-row hash dedup (Decision 18, layer 2)
                    String sourceHash = SourceHashCodec.hash(mapping.fields(), properties);
                    properties.put("_source_hash", sourceHash);

                    // Check existing node's _source_hash
                    Optional<NodeState> existing =
                            findExistingByIdentity(tenant, mapping.targetNodeTypeSlug(), mapping, properties);
                    if (existing.isPresent()) {
                        Object existingHash = existing.get().properties().get("_source_hash");
                        if (sourceHash.equals(existingHash)) {
                            LOG.debug("Skipping unchanged row (hash match)");
                            continue;
                        }
                    }

                    candidates.add(new CandidateMutation(
                            mapping.targetNodeTypeSlug(),
                            existing.map(NodeState::uuid).orElse(null),
                            properties,
                            mapping.sourceEntityType(),
                            state.customState() != null
                                    ? (String) state.customState().get("connector_id")
                                    : "unknown",
                            UUID.randomUUID().toString(),
                            null,
                            null,
                            null,
                            null,
                            null));

                } catch (Exception e) {
                    dlq.add(new DlqEntry("MAPPING_ERROR", e.getMessage(), rowToMap(row)));
                }
            }

            SyncOutcome outcome;
            if (!dlq.isEmpty() && candidates.isEmpty()) {
                outcome = SyncOutcome.FAILED;
            } else if (!dlq.isEmpty()) {
                outcome = SyncOutcome.PARTIAL;
            } else {
                outcome = SyncOutcome.SUCCESS;
            }

            ConnectorState nextState = new ConnectorState(
                    null,
                    newEtag,
                    newLastModified,
                    state.lastSequence(),
                    state.customState() != null ? state.customState() : Map.of());

            return new PollResult(candidates, nextState, outcome, dlq);

        } catch (IOException e) {
            LOG.error("IO error polling {}: {}", mapping.sourceUrl(), e.getMessage());
            return PollResult.failed(state);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PollResult.failed(state);
        }
    }

    /**
     * Find an existing node by identity fields. For MVP, uses the first
     * identity field to query. A proper identity lookup would use a
     * composite key, but this is sufficient for Phase 2.
     */
    private Optional<NodeState> findExistingByIdentity(
            TenantContext tenant, String typeSlug, MappingDefinition mapping, Map<String, Object> properties) {
        // Simple approach: query all nodes and filter by identity fields
        // In Phase 3+, this should use an indexed identity lookup
        List<NodeState> nodes = graphRepository.queryAll(tenant, typeSlug);
        return nodes.stream()
                .filter(node -> {
                    for (String idField : mapping.identityFields()) {
                        Object expected = properties.get(idField);
                        Object actual = node.properties().get(idField);
                        if (expected == null || !expected.toString().equals(String.valueOf(actual))) {
                            return false;
                        }
                    }
                    return true;
                })
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowToMap(Object row) {
        if (row instanceof Map) {
            return new HashMap<>((Map<String, Object>) row);
        }
        return Map.of("raw", String.valueOf(row));
    }
}
