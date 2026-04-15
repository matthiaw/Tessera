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
package dev.tessera.rules.authority;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.support.PipelineFixture;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 02-W0 Task 2 gate: closes 01-VERIFICATION Known Deviations #1 and #2 end to
 * end through the production write funnel ({@code graphService.apply}). Unlike
 * the sibling {@code SourceAuthorityIT} / {@code ConflictRegisterIT} which
 * drive {@code RuleEngine.run} directly with a synthetic {@code RuleContext},
 * this IT exercises the entire pipeline the way a Phase 2 connector will:
 *
 * <ul>
 *   <li>{@link dev.tessera.core.graph.internal.GraphServiceImpl#apply} derives
 *       {@code currentSourceSystem} from the pre-mutation node state and
 *       threads it into {@code ruleEngine.run}.
 *   <li>The rule engine fires {@link AuthorityReconciliationRule}, emits the
 *       appropriate {@link dev.tessera.rules.RuleOutcome.Override} decision,
 *       and the updated {@link dev.tessera.rules.internal.ChainExecutor}
 *       labels {@code winningSourceSystem} correctly on both the incoming-wins
 *       AND current-keeps branches.
 *   <li>{@link dev.tessera.core.rules.ReconciliationConflictsRepository}
 *       persists the row inside the same Postgres transaction as the Cypher
 *       write.
 * </ul>
 *
 * <p>Lives in fabric-rules/src/test instead of fabric-core/src/test (as
 * originally sketched in 02-W0-PLAN.md) because {@code PipelineFixture} —
 * which already wires GraphServiceImpl + RuleEngine + SourceAuthorityMatrix +
 * ReconciliationConflictsRepository — is a fabric-rules test support class,
 * and placing the IT in fabric-core would create a reverse module dependency
 * (fabric-core test classpath → fabric-rules) that ModuleDependencyTest rules
 * forbid. Deviation logged as Rule 3 in 02-W0-SUMMARY.
 */
class GraphServiceAuthorityThreadingIT {

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
    void incomingWinsBranchLabelsConflictRowCorrectlyThroughFunnel() {
        UUID modelId = UUID.randomUUID();
        // Matrix: A outranks B on Person.status
        seedAuthorityMatrix(modelId, "Person", "status", new String[] {"A", "B"});
        fixture.authorityMatrix.invalidateAll();

        TenantContext tenant = TenantContext.of(modelId);

        // Step 1: CREATE from low-authority source B → node._source=B, status=VALUE_FROM_B
        GraphMutationOutcome created = fixture.graphService.apply(createMutation(tenant, "B", "VALUE_FROM_B"));
        UUID nodeUuid = ((GraphMutationOutcome.Committed) created).nodeUuid();

        // Step 2: UPDATE from higher-authority source A with a different value
        //         → incoming-wins branch fires through the funnel.
        fixture.graphService.apply(updateMutation(tenant, nodeUuid, "A", "VALUE_FROM_A"));

        // Assert reconciliation_conflicts row exists with correct labels.
        Map<String, Object> row = fetchRow(modelId);
        assertThat(row)
                .as("incoming-wins branch must produce a conflict row via graphService.apply")
                .isNotNull();
        assertThat(row.get("winning_source_system"))
                .as("incoming-wins branch: winner is the incoming (A)")
                .isEqualTo("A");
        assertThat(row.get("losing_source_system"))
                .as("incoming-wins branch: loser is the current (B)")
                .isEqualTo("B");
        assertThat(row.get("losing_value").toString()).contains("VALUE_FROM_B");
        assertThat(row.get("winning_value").toString()).contains("VALUE_FROM_A");
    }

    @Test
    void currentKeepsBranchLabelsConflictRowCorrectlyThroughFunnel() {
        UUID modelId = UUID.randomUUID();
        seedAuthorityMatrix(modelId, "Person", "status", new String[] {"A", "B"});
        fixture.authorityMatrix.invalidateAll();

        TenantContext tenant = TenantContext.of(modelId);

        // Step 1: CREATE from higher-authority source A → node._source=A, status=VALUE_FROM_A
        GraphMutationOutcome created = fixture.graphService.apply(createMutation(tenant, "A", "VALUE_FROM_A"));
        UUID nodeUuid = ((GraphMutationOutcome.Committed) created).nodeUuid();

        // Step 2: UPDATE from lower-authority source B with a DIFFERENT value.
        //         → current-keeps branch fires through the funnel. The incoming
        //           B write is dropped; current A value stays.
        fixture.graphService.apply(updateMutation(tenant, nodeUuid, "B", "VALUE_FROM_B"));

        // Assert reconciliation_conflicts row exists with correct labels
        // on the current-keeps branch — the historical bug site.
        Map<String, Object> row = fetchRow(modelId);
        assertThat(row)
                .as("current-keeps branch must produce a conflict row via graphService.apply")
                .isNotNull();
        assertThat(row.get("winning_source_system"))
                .as("current-keeps branch: winner must be A (the current source) — NOT B (the incoming)")
                .isEqualTo("A");
        assertThat(row.get("losing_source_system"))
                .as("current-keeps branch: loser is the incoming B")
                .isEqualTo("B");
        assertThat(row.get("losing_value").toString()).contains("VALUE_FROM_B");
        assertThat(row.get("winning_value").toString()).contains("VALUE_FROM_A");
    }

    // -- helpers --

    private GraphMutation createMutation(TenantContext tenant, String source, String value) {
        return GraphMutation.builder()
                .tenantContext(tenant)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("status", value))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("it-" + source)
                .sourceSystem(source)
                .confidence(BigDecimal.ONE)
                .originConnectorId("conn-" + source)
                .originChangeId("chg-" + source + "-" + UUID.randomUUID())
                .build();
    }

    private GraphMutation updateMutation(TenantContext tenant, UUID nodeUuid, String source, String value) {
        return GraphMutation.builder()
                .tenantContext(tenant)
                .operation(Operation.UPDATE)
                .type("Person")
                .targetNodeUuid(nodeUuid)
                .payload(Map.of("status", value))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("it-" + source)
                .sourceSystem(source)
                .confidence(BigDecimal.ONE)
                .originConnectorId("conn-" + source)
                .originChangeId("chg-" + source + "-" + UUID.randomUUID())
                .build();
    }

    private Map<String, Object> fetchRow(UUID modelId) {
        return fixture.jdbc.queryForMap(
                "SELECT model_id::text, type_slug, property_slug,"
                        + " winning_source_system, losing_source_system,"
                        + " winning_value::text AS winning_value,"
                        + " losing_value::text AS losing_value"
                        + " FROM reconciliation_conflicts WHERE model_id = :model_id::uuid",
                new MapSqlParameterSource("model_id", modelId.toString()));
    }

    static void seedAuthorityMatrix(UUID modelId, String typeSlug, String propertySlug, String[] priorityOrder) {
        fixture.jdbc
                .getJdbcTemplate()
                .update(
                        "INSERT INTO source_authority"
                                + " (model_id, type_slug, property_slug, priority_order, updated_at, updated_by)"
                                + " VALUES (?::uuid, ?, ?, ?, ?, ?)",
                        modelId.toString(),
                        typeSlug,
                        propertySlug,
                        priorityOrder,
                        Timestamp.from(Instant.now()),
                        "graph-service-authority-threading-it");
    }
}
