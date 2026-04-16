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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 02-W3-02: ETag / Last-Modified delta detection test. Verifies:
 * (a) ETag stored from first response, (b) If-None-Match sent on
 * second request, (c) 304 produces zero candidates, (d) weak ETags
 * passed through verbatim.
 */
class EtagDeltaDetectionIT {

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
    };

    private MappingDefinition mapping(String baseUrl) {
        return new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(new FieldMapping("name", "$.name", null, false)),
                List.of("name"),
                baseUrl + "/api/data");
    }

    @Test
    void etag_stored_and_sent_on_next_request() {
        // First request: returns ETag
        wm.stubFor(get(urlEqualTo("/api/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"v1\"")
                        .withBody("{\"data\": [{\"name\": \"Alice\"}]}")));

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        ConnectorState state = new ConnectorState(null, null, null, 0L, Map.of("connector_id", "c1"));
        TenantContext tenant = TenantContext.of(UUID.randomUUID());

        PollResult first = connector.poll(Clock.systemUTC(), mapping(wm.baseUrl()), state, tenant);
        assertThat(first.nextState().etag()).isEqualTo("\"v1\"");
        assertThat(first.candidates()).hasSize(1);

        // Second request: send If-None-Match, get 304
        wm.resetAll();
        wm.stubFor(get(urlEqualTo("/api/data"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        ConnectorState stateWithEtag = new ConnectorState(null, "\"v1\"", null, 0L, Map.of("connector_id", "c1"));
        PollResult second = connector.poll(Clock.systemUTC(), mapping(wm.baseUrl()), stateWithEtag, tenant);
        assertThat(second.outcome()).isEqualTo(SyncOutcome.NO_CHANGE);
        assertThat(second.candidates()).isEmpty();

        wm.verify(getRequestedFor(urlEqualTo("/api/data")).withHeader("If-None-Match", equalTo("\"v1\"")));
    }

    @Test
    void weak_etag_passed_through_verbatim() {
        wm.stubFor(get(urlEqualTo("/api/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "W/\"weak-v1\"")
                        .withBody("{\"data\": [{\"name\": \"Bob\"}]}")));

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        ConnectorState state = new ConnectorState(null, null, null, 0L, Map.of("connector_id", "c2"));
        TenantContext tenant = TenantContext.of(UUID.randomUUID());

        PollResult result = connector.poll(Clock.systemUTC(), mapping(wm.baseUrl()), state, tenant);
        assertThat(result.nextState().etag()).isEqualTo("W/\"weak-v1\"");

        // Verify weak ETag is sent back verbatim
        wm.resetAll();
        wm.stubFor(get(urlEqualTo("/api/data"))
                .withHeader("If-None-Match", equalTo("W/\"weak-v1\""))
                .willReturn(aResponse().withStatus(304)));

        ConnectorState stateWithWeakEtag =
                new ConnectorState(null, "W/\"weak-v1\"", null, 0L, Map.of("connector_id", "c2"));
        PollResult second = connector.poll(Clock.systemUTC(), mapping(wm.baseUrl()), stateWithWeakEtag, tenant);
        assertThat(second.outcome()).isEqualTo(SyncOutcome.NO_CHANGE);
    }

    @Test
    void new_etag_triggers_full_parse() {
        // First: 304
        wm.stubFor(get(urlEqualTo("/api/data"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        ConnectorState stateV1 = new ConnectorState(null, "\"v1\"", null, 0L, Map.of("connector_id", "c3"));
        TenantContext tenant = TenantContext.of(UUID.randomUUID());

        PollResult notModified = connector.poll(Clock.systemUTC(), mapping(wm.baseUrl()), stateV1, tenant);
        assertThat(notModified.outcome()).isEqualTo(SyncOutcome.NO_CHANGE);

        // Now reconfigure to return new data with new ETag
        wm.resetAll();
        wm.stubFor(get(urlEqualTo("/api/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"v2\"")
                        .withBody("{\"data\": [{\"name\": \"Charlie\"}]}")));

        // Poll without If-None-Match (simulating server returning 200)
        ConnectorState freshState = new ConnectorState(null, null, null, 0L, Map.of("connector_id", "c3"));
        PollResult updated = connector.poll(Clock.systemUTC(), mapping(wm.baseUrl()), freshState, tenant);
        assertThat(updated.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(updated.candidates()).hasSize(1);
        assertThat(updated.nextState().etag()).isEqualTo("\"v2\"");
    }
}
