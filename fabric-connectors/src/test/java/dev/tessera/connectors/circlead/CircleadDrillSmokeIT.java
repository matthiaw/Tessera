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
 * OPS-05: Circlead consumer smoke test for the DR drill.
 *
 * <p>Invoked by {@code scripts/dr_drill.sh} step 9 via Maven failsafe:
 * {@code ./mvnw -pl fabric-connectors failsafe:integration-test -Dit.test=CircleadDrillSmokeIT}
 *
 * <p>Proves all three circlead entity types (Role, Circle, Activity) produce
 * {@link SyncOutcome#SUCCESS} when polled against a WireMock stub — validating
 * that the connector wiring is end-to-end functional.
 */
class CircleadDrillSmokeIT {

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
    void drill_smoke_all_three_circlead_entity_types_succeed() {
        for (String type : List.of("ROLE", "CIRCLE", "ACTIVITY")) {
            wm.stubFor(get(urlPathEqualTo("/circlead/workitem/list"))
                    .withQueryParam("type", equalTo(type))
                    .withQueryParam("details", equalTo("true"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                    { "content": [{"id":"drill-%s","title":"Drill %s","status":"ACTIVE"}], "status": 200 }
                                    """
                                            .formatted(type.toLowerCase(), type))));
        }

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);

        for (String type : List.of("ROLE", "CIRCLE", "ACTIVITY")) {
            MappingDefinition mapping = new MappingDefinition(
                    type.toLowerCase(),
                    type.toLowerCase(),
                    "$.content[*]",
                    List.of(
                            new FieldMapping("circlead_id", "$.id", null, true),
                            new FieldMapping("title", "$.title", null, true)),
                    List.of("circlead_id"),
                    wm.baseUrl() + "/circlead/workitem/list?type=" + type + "&details=true",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            PollResult result = connector.poll(
                    Clock.systemUTC(),
                    mapping,
                    new ConnectorState(null, null, null, 0L, Map.of("connector_id", "drill-" + type.toLowerCase())),
                    TenantContext.of(UUID.randomUUID()));

            assertThat(result.outcome()).as("SyncOutcome for type %s", type).isEqualTo(SyncOutcome.SUCCESS);
            assertThat(result.candidates()).as("candidates for type %s", type).isNotEmpty();
        }
    }
}
