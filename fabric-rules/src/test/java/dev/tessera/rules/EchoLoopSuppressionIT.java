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
package dev.tessera.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.rules.RuleRejectException;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.support.PipelineFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Integration gate for RULE-08 (ROADMAP SC-5 half B) — echo-loop suppression.
 *
 * <p>A mutation whose {@code (originConnectorId, originChangeId)} pair has
 * already been committed for this tenant is rejected before touching AGE. A
 * different {@code originChangeId} from the same connector still flows, and
 * the same pair against a different tenant still flows — proving the key is
 * {@code (model_id, connector, change)} and not "block all traffic from the
 * connector".
 */
class EchoLoopSuppressionIT {

    private static PipelineFixture fixture;

    @BeforeAll
    static void bootFixture() {
        fixture = PipelineFixture.boot(List.of());
    }

    @AfterAll
    static void stopFixture() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void replayingSameOriginPairIsRejectedBeforeAge() {
        UUID modelId = UUID.randomUUID();
        TenantContext tenant = TenantContext.of(modelId);
        String connector = "connector-X";
        String changeId = "change-abc-" + UUID.randomUUID();

        // --- M1: first write, commits ---
        GraphMutation m1 = buildMutation(tenant, connector, changeId, Map.of("foo", "bar"));
        GraphMutationOutcome o1 = fixture.graphService.apply(m1);
        assertThat(o1).isInstanceOf(GraphMutationOutcome.Committed.class);
        long countAfterM1 = countEvents(modelId);
        assertThat(countAfterM1)
                .as("M1 must have landed exactly one event row for this tenant")
                .isEqualTo(1L);

        // --- M2: identical (connector, changeId) must be rejected ---
        GraphMutation m2 = buildMutation(tenant, connector, changeId, Map.of("foo", "baz"));
        assertThatThrownBy(() -> fixture.graphService.apply(m2))
                .as("replay with same (origin_connector_id, origin_change_id) must be rejected")
                .isInstanceOf(RuleRejectException.class)
                .hasMessageContaining("echo loop detected")
                .satisfies(ex -> {
                    RuleRejectException rre = (RuleRejectException) ex;
                    assertThat(rre.ruleId()).isEqualTo(EchoLoopSuppressionRule.RULE_ID);
                });

        // Echo-loop reject fires BEFORE the AGE / event-log write — row count unchanged.
        assertThat(countEvents(modelId))
                .as("rejected replay must not add a graph_events row")
                .isEqualTo(countAfterM1);

        // --- M3: same connector, DIFFERENT changeId → must succeed ---
        String changeId3 = "change-different-" + UUID.randomUUID();
        GraphMutation m3 = buildMutation(tenant, connector, changeId3, Map.of("foo", "qux"));
        GraphMutationOutcome o3 = fixture.graphService.apply(m3);
        assertThat(o3)
                .as("a different origin_change_id from the same connector must not be echo-blocked")
                .isInstanceOf(GraphMutationOutcome.Committed.class);
        assertThat(countEvents(modelId)).isEqualTo(2L);

        // --- M4: SAME (connector, changeId) but different tenant → must succeed ---
        UUID otherModelId = UUID.randomUUID();
        TenantContext otherTenant = TenantContext.of(otherModelId);
        GraphMutation m4 = buildMutation(otherTenant, connector, changeId, Map.of("foo", "cross-tenant"));
        GraphMutationOutcome o4 = fixture.graphService.apply(m4);
        assertThat(o4)
                .as("same origin pair under a different tenant must commit — echo-loop state is per-tenant")
                .isInstanceOf(GraphMutationOutcome.Committed.class);
        assertThat(countEvents(otherModelId)).isEqualTo(1L);
    }

    private static GraphMutation buildMutation(
            TenantContext tenant, String connector, String changeId, Map<String, Object> payload) {
        return GraphMutation.builder()
                .tenantContext(tenant)
                .operation(Operation.CREATE)
                .type("Person")
                .targetNodeUuid(UUID.randomUUID())
                .payload(payload)
                .sourceType(SourceType.STRUCTURED)
                .sourceId("it-echo-src")
                .sourceSystem("it")
                .confidence(BigDecimal.ONE)
                .originConnectorId(connector)
                .originChangeId(changeId)
                .build();
    }

    private static long countEvents(UUID modelId) {
        Long n = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_events WHERE model_id = :model_id::uuid",
                new MapSqlParameterSource("model_id", modelId.toString()),
                Long.class);
        return n == null ? 0L : n;
    }
}
