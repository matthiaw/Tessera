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
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.graph.internal.GraphSession;
import dev.tessera.core.rules.RuleRejectException;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.support.PipelineFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration gate for VALID-05: a VALIDATE-chain rule returning
 * {@link RuleOutcome.Reject} causes {@code GraphServiceImpl.apply()} to throw
 * {@link RuleRejectException}, the Postgres transaction rolls back, and the
 * {@code graph_events} table stays untouched for the rejected mutation.
 *
 * <p>Boots the real AGE Postgres Testcontainer via {@link PipelineFixture} and
 * injects a single test-local {@link Rule} in the VALIDATE chain that rejects
 * unconditionally. This proves the end-to-end write funnel honours rule
 * rejections inside the same transactional boundary as the Cypher write and
 * event-log append.
 */
@Testcontainers
class BusinessRuleRejectIT {

    private static final String REJECT_REASON = "VALID-05 gate — test-rejected";
    private static final String REJECT_RULE_ID = "it.validate.always-reject";

    private static PipelineFixture fixture;

    @BeforeAll
    static void bootFixture() {
        Rule alwaysReject = new Rule() {
            @Override
            public String id() {
                return REJECT_RULE_ID;
            }

            @Override
            public Chain chain() {
                return Chain.VALIDATE;
            }

            @Override
            public int priority() {
                return 1_000;
            }

            @Override
            public boolean applies(RuleContext ctx) {
                return true;
            }

            @Override
            public RuleOutcome evaluate(RuleContext ctx) {
                return new RuleOutcome.Reject(REJECT_REASON);
            }
        };
        fixture = PipelineFixture.boot(List.of(alwaysReject));
    }

    @AfterAll
    static void stopFixture() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void rejectRuleAbortsTransactionAndWritesNothing() {
        UUID modelId = UUID.randomUUID();
        TenantContext tenant = TenantContext.of(modelId);
        UUID targetNodeUuid = UUID.randomUUID();

        long before = countEvents(modelId);

        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(tenant)
                .operation(Operation.CREATE)
                .type("Person")
                .targetNodeUuid(targetNodeUuid)
                .payload(Map.of("name", "rejected-by-test"))
                .sourceType(SourceType.SYSTEM)
                .sourceId("it-business-rule-reject")
                .sourceSystem("it")
                .confidence(BigDecimal.ONE)
                .originConnectorId("it-connector")
                .originChangeId("it-change-" + UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> fixture.graphService.apply(mutation))
                .isInstanceOf(RuleRejectException.class)
                .hasMessageContaining("test-rejected")
                .satisfies(ex -> {
                    RuleRejectException rre = (RuleRejectException) ex;
                    assertThat(rre.ruleId()).isEqualTo(REJECT_RULE_ID);
                });

        // TX rollback proven: graph_events row count for this tenant is unchanged.
        long after = countEvents(modelId);
        assertThat(after)
                .as("graph_events must be untouched — VALIDATE Reject rolls the TX back")
                .isEqualTo(before);

        // And the target node never landed in AGE. A read-only GraphSession over
        // the same JdbcTemplate is sufficient — findNode is side-effect-free.
        Optional<NodeState> readBack = new GraphSession(fixture.jdbc).findNode(tenant, "Person", targetNodeUuid);
        assertThat(readBack).as("rejected node must not exist in AGE").isEmpty();
    }

    private static long countEvents(UUID modelId) {
        Long n = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_events WHERE model_id = :model_id::uuid",
                new MapSqlParameterSource("model_id", modelId.toString()),
                Long.class);
        return n == null ? 0L : n;
    }
}
