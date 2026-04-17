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
package dev.tessera.connectors.circlead;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.FieldMapping;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.connectors.rest.GenericRestPollerConnector;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * CIRC-01: WireMock-based integration tests for the circlead connector.
 *
 * <p>Verifies that {@link GenericRestPollerConnector} correctly polls the three
 * circlead REST endpoints (Role, Circle, Activity) and maps the response fields
 * to {@link dev.tessera.connectors.CandidateMutation} properties using the
 * circlead MappingDefinition configuration.
 *
 * <p>Does NOT require a Spring context — tests the connector directly with a
 * no-op GraphRepository, following the same pattern as RestPollingConnectorIT.
 */
class CircleadConnectorIT {

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
        public List<java.util.Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher) {
            return List.of();
        }

        @Override
        public List<NodeState> findShortestPath(TenantContext ctx, UUID fromUuid, UUID toUuid) {
            return List.of();
        }
    };

    @Test
    void polls_role_list_and_maps_to_tessera_node() {
        wm.stubFor(
                get(urlPathEqualTo("/circlead/workitem/list"))
                        .withQueryParam("type", equalTo("ROLE"))
                        .withQueryParam("details", equalTo("true"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "content": [
                                                    {
                                                      "id": "role-001",
                                                      "title": "Product Owner",
                                                      "abbreviation": "PO",
                                                      "purpose": "Define the product vision",
                                                      "status": "ACTIVE",
                                                      "type": {"name": "role"}
                                                    }
                                                  ],
                                                  "status": 200
                                                }
                                                """)));

        MappingDefinition mapping = new MappingDefinition(
                "role",
                "role",
                "$.content[*]",
                List.of(
                        new FieldMapping("circlead_id", "$.id", null, true),
                        new FieldMapping("title", "$.title", null, true),
                        new FieldMapping("abbreviation", "$.abbreviation", null, false),
                        new FieldMapping("purpose", "$.purpose", null, false),
                        new FieldMapping("status", "$.status", null, false),
                        new FieldMapping("role_type", "$.type.name", null, false)),
                List.of("circlead_id"),
                wm.baseUrl() + "/circlead/workitem/list?type=ROLE&details=true",
                null,
                null,
                null,
                null,
                null,
                null);

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        PollResult result = connector.poll(
                Clock.systemUTC(),
                mapping,
                new ConnectorState(null, null, null, 0L, Map.of("connector_id", "circlead-role")),
                TenantContext.of(UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).properties().get("title")).isEqualTo("Product Owner");
        assertThat(result.candidates().get(0).properties().get("circlead_id")).isEqualTo("role-001");
        assertThat(result.candidates().get(0).properties().get("abbreviation")).isEqualTo("PO");
        assertThat(result.candidates().get(0).properties().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void polls_circle_list_and_maps_fields() {
        wm.stubFor(
                get(urlPathEqualTo("/circlead/workitem/list"))
                        .withQueryParam("type", equalTo("CIRCLE"))
                        .withQueryParam("details", equalTo("true"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "content": [
                                                    {
                                                      "id": "circle-001",
                                                      "title": "Engineering",
                                                      "abbreviation": "ENG",
                                                      "purpose": "Build and maintain the platform",
                                                      "status": "ACTIVE"
                                                    }
                                                  ],
                                                  "status": 200
                                                }
                                                """)));

        MappingDefinition mapping = new MappingDefinition(
                "circle",
                "circle",
                "$.content[*]",
                List.of(
                        new FieldMapping("circlead_id", "$.id", null, true),
                        new FieldMapping("title", "$.title", null, true),
                        new FieldMapping("abbreviation", "$.abbreviation", null, false),
                        new FieldMapping("purpose", "$.purpose", null, false),
                        new FieldMapping("status", "$.status", null, false)),
                List.of("circlead_id"),
                wm.baseUrl() + "/circlead/workitem/list?type=CIRCLE&details=true",
                null,
                null,
                null,
                null,
                null,
                null);

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        PollResult result = connector.poll(
                Clock.systemUTC(),
                mapping,
                new ConnectorState(null, null, null, 0L, Map.of("connector_id", "circlead-circle")),
                TenantContext.of(UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).properties().get("title")).isEqualTo("Engineering");
        assertThat(result.candidates().get(0).properties().get("circlead_id")).isEqualTo("circle-001");
    }

    @Test
    void polls_activity_list_and_maps_fields() {
        wm.stubFor(
                get(urlPathEqualTo("/circlead/workitem/list"))
                        .withQueryParam("type", equalTo("ACTIVITY"))
                        .withQueryParam("details", equalTo("true"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "content": [
                                                    {
                                                      "id": "activity-001",
                                                      "title": "Sprint Planning",
                                                      "abbreviation": "SP",
                                                      "purpose": "Plan the sprint iteration",
                                                      "status": "ACTIVE"
                                                    }
                                                  ],
                                                  "status": 200
                                                }
                                                """)));

        MappingDefinition mapping = new MappingDefinition(
                "activity",
                "activity",
                "$.content[*]",
                List.of(
                        new FieldMapping("circlead_id", "$.id", null, true),
                        new FieldMapping("title", "$.title", null, true),
                        new FieldMapping("abbreviation", "$.abbreviation", null, false),
                        new FieldMapping("purpose", "$.purpose", null, false),
                        new FieldMapping("status", "$.status", null, false)),
                List.of("circlead_id"),
                wm.baseUrl() + "/circlead/workitem/list?type=ACTIVITY&details=true",
                null,
                null,
                null,
                null,
                null,
                null);

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        PollResult result = connector.poll(
                Clock.systemUTC(),
                mapping,
                new ConnectorState(null, null, null, 0L, Map.of("connector_id", "circlead-activity")),
                TenantContext.of(UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).properties().get("title")).isEqualTo("Sprint Planning");
        assertThat(result.candidates().get(0).properties().get("circlead_id")).isEqualTo("activity-001");
    }

    @Test
    void handles_empty_response_gracefully() {
        wm.stubFor(
                get(urlPathEqualTo("/circlead/workitem/list"))
                        .withQueryParam("type", equalTo("ROLE"))
                        .withQueryParam("details", equalTo("true"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "content": [],
                                                  "status": 200
                                                }
                                                """)));

        MappingDefinition mapping = new MappingDefinition(
                "role",
                "role",
                "$.content[*]",
                List.of(
                        new FieldMapping("circlead_id", "$.id", null, true),
                        new FieldMapping("title", "$.title", null, true)),
                List.of("circlead_id"),
                wm.baseUrl() + "/circlead/workitem/list?type=ROLE&details=true",
                null,
                null,
                null,
                null,
                null,
                null);

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        PollResult result = connector.poll(
                Clock.systemUTC(),
                mapping,
                new ConnectorState(null, null, null, 0L, Map.of("connector_id", "circlead-role-empty")),
                TenantContext.of(UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(result.candidates()).isEmpty();
    }
}
