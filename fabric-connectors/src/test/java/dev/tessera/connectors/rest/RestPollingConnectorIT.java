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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.FieldMapping;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 02-W3-02: WireMock-backed end-to-end test for
 * {@link GenericRestPollerConnector}. Verifies:
 * (a) Bearer auth sent, (b) JSONPath mapping works,
 * (c) per-row hash dedup skips unchanged rows.
 *
 * <p>Does NOT require Spring context -- unit-tests the connector
 * directly with a mock GraphRepository.
 */
class RestPollingConnectorIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private final GraphRepository emptyRepo = new GraphRepository() {
        @Override
        public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) {
            return Optional.empty();
        }

        @Override
        public List<NodeState> queryAll(TenantContext ctx, String typeSlug) {
            return List.of();
        }

        @Override
        public List<NodeState> queryAllAfter(TenantContext ctx, String typeSlug, long afterSeq, int limit) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher) {
            return List.of();
        }

        @Override
        public List<NodeState> findShortestPath(TenantContext ctx, UUID fromUuid, UUID toUuid) {
            return List.of();
        }
    };

    @Test
    void polls_with_bearer_auth_and_maps_via_jsonpath() {
        wm.stubFor(
                get(urlEqualTo("/api/customers"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withHeader("ETag", "\"v1\"")
                                        .withBody(
                                                """
                                {
                                    "data": [
                                        {"name": "Alice", "email": "ALICE@EXAMPLE.COM"},
                                        {"name": "Bob", "email": "BOB@EXAMPLE.COM"}
                                    ]
                                }
                                """)));

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        MappingDefinition mapping = new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(
                        new FieldMapping("name", "$.name", null, false),
                        new FieldMapping("email", "$.email", "lowercase", false)),
                List.of("name"),
                wm.baseUrl() + "/api/customers",
                null,
                null,
                null,
                null,
                null,
                null);

        ConnectorState state = new ConnectorState(
                null, null, null, 0L, Map.of("bearer_token", "test-token-123", "connector_id", "conn-001"));

        TenantContext tenant = TenantContext.of(UUID.randomUUID());
        PollResult result = connector.poll(Clock.systemUTC(), mapping, state, tenant);

        assertThat(result.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).properties().get("name")).isEqualTo("Alice");
        assertThat(result.candidates().get(0).properties().get("email")).isEqualTo("alice@example.com");
        assertThat(result.candidates().get(0).properties().get("_source_hash")).isNotNull();
        assertThat(result.nextState().etag()).isEqualTo("\"v1\"");

        // Verify Bearer auth was sent
        wm.verify(getRequestedFor(urlEqualTo("/api/customers"))
                .withHeader("Authorization", equalTo("Bearer test-token-123")));
    }

    @Test
    void second_poll_with_unchanged_data_produces_zero_candidates() {
        String responseBody =
                """
                {
                    "data": [{"name": "Alice", "email": "alice@example.com"}]
                }
                """;

        wm.stubFor(get(urlEqualTo("/api/customers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        MappingDefinition mapping = new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(
                        new FieldMapping("name", "$.name", null, false),
                        new FieldMapping("email", "$.email", null, false)),
                List.of("name"),
                wm.baseUrl() + "/api/customers",
                null,
                null,
                null,
                null,
                null,
                null);

        ConnectorState state = new ConnectorState(null, null, null, 0L, Map.of("connector_id", "conn-002"));
        TenantContext tenant = TenantContext.of(UUID.randomUUID());

        // First poll
        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        PollResult first = connector.poll(Clock.systemUTC(), mapping, state, tenant);
        assertThat(first.candidates()).hasSize(1);
        String hash = (String) first.candidates().get(0).properties().get("_source_hash");

        // Create a repo that returns the node with this hash
        GraphRepository repoWithNode = new GraphRepository() {
            @Override
            public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) {
                return Optional.empty();
            }

            @Override
            public List<NodeState> queryAll(TenantContext ctx, String typeSlug) {
                return List.of(new NodeState(
                        UUID.randomUUID(),
                        "Customer",
                        Map.of("name", "Alice", "email", "alice@example.com", "_source_hash", hash),
                        Instant.now(),
                        Instant.now()));
            }

            @Override
            public List<NodeState> queryAllAfter(TenantContext ctx, String typeSlug, long afterSeq, int limit) {
                return List.of();
            }

            @Override
            public List<Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher) {
                return List.of();
            }

            @Override
            public List<NodeState> findShortestPath(TenantContext ctx, UUID fromUuid, UUID toUuid) {
                return List.of();
            }
        };

        // Second poll with same data -- should be skipped via hash dedup
        GenericRestPollerConnector connector2 = new GenericRestPollerConnector(repoWithNode);
        PollResult second = connector2.poll(Clock.systemUTC(), mapping, state, tenant);
        assertThat(second.candidates()).isEmpty();
        assertThat(second.outcome()).isEqualTo(SyncOutcome.SUCCESS);
    }
}
