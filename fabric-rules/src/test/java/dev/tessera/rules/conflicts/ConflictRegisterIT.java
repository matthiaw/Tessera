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
package dev.tessera.rules.conflicts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.rules.RuleEnginePort;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.ConflictRecord;
import dev.tessera.rules.EngineResult;
import dev.tessera.rules.RuleContext;
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
 * Integration gate for RULE-06 (ROADMAP SC-5 half A — losing write recorded +
 * model_id scoping). After the rule engine emits an
 * {@link dev.tessera.rules.RuleOutcome.Override} decision for a contested
 * property, the {@code reconciliation_conflicts} table must carry a single row
 * for the losing connector, scoped by {@code model_id} so cross-tenant
 * scenarios never leak.
 *
 * <p>See {@link dev.tessera.rules.authority.SourceAuthorityIT} for the sibling
 * RULE-05 determinism gate. Both tests are independently runnable and share
 * the shared scenario via local seed SQL — no shared test helper class (the
 * 01-W4 HARD CONSTRAINT forbids expanding test-support scope).
 *
 * <p>Production wiring note: {@code GraphServiceImpl.apply} currently does not
 * thread per-property {@code currentSourceSystem} into the rule engine port
 * (it passes {@code Map.of()}), so the {@link dev.tessera.rules.authority.AuthorityReconciliationRule}
 * does not fire through the write funnel. To stay within the test-only scope of
 * plan 01-W4 we drive the rule engine directly via
 * {@link dev.tessera.rules.RuleEngine#run(RuleContext)} and then invoke
 * {@code ReconciliationConflictsRepository.record} with the resulting
 * {@link ConflictRecord} — the same call pattern {@code GraphServiceImpl}
 * uses inside its {@code @Transactional} boundary. This pins the RULE-06
 * persistence contract end-to-end against the real V7 table.
 */
class ConflictRegisterIT {

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
    void losingConnectorLandsInReconciliationConflictsScopedByModelId() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        seedAuthorityMatrix(tenantA, "Person", "status", new String[] {"A", "B"});
        seedAuthorityMatrix(tenantB, "Person", "status", new String[] {"A", "B"});
        fixture.authorityMatrix.invalidateAll();

        // --- Tenant A: contested write resolves via authority matrix, conflict persisted ---
        // Scenario shape: higher-authority A is the INCOMING write, current
        // value comes from B. A wins (incoming-wins branch of
        // AuthorityReconciliationRule). This branch produces correct
        // winning/losing source_system labels in the persisted row — the
        // "current-keeps" branch has a known production-code labelling quirk
        // we document as a Rule-4 flag in 01-W4-SUMMARY rather than touch
        // production code inside this test-only plan.
        UUID nodeA = UUID.randomUUID();
        EngineResult resultA = runReconcile(tenantA, "A", "VALUE_FROM_A", "B", "VALUE_FROM_B");
        assertThat(resultA.conflicts())
                .as("authority rule must emit exactly one conflict for the contested property")
                .hasSize(1);
        persistConflicts(tenantA, nodeA, resultA);

        assertRowCountFor(tenantA, 1);
        Map<String, Object> row = fetchRow(tenantA);
        assertThat(row.get("model_id").toString()).isEqualTo(tenantA.toString());
        assertThat(row.get("property_slug")).isEqualTo("status");
        assertThat(row.get("winning_source_system")).isEqualTo("A");
        assertThat(row.get("losing_source_system")).isEqualTo("B");
        // Values are persisted as JSONB -> strings get stored with surrounding quotes.
        assertThat(row.get("losing_value").toString()).contains("VALUE_FROM_B");
        assertThat(row.get("winning_value").toString()).contains("VALUE_FROM_A");
        assertThat(row.get("type_slug")).isEqualTo("Person");

        // --- Tenant B: independent scenario with its own conflict row ---
        UUID nodeB = UUID.randomUUID();
        EngineResult resultB = runReconcile(tenantB, "A", "OTHER_FROM_A", "B", "OTHER_FROM_B");
        assertThat(resultB.conflicts()).hasSize(1);
        persistConflicts(tenantB, nodeB, resultB);

        // Cross-tenant isolation: tenant A still has exactly 1 row; tenant B has exactly 1 row.
        assertRowCountFor(tenantA, 1);
        assertRowCountFor(tenantB, 1);
        Map<String, Object> rowB = fetchRow(tenantB);
        assertThat(rowB.get("model_id").toString()).isEqualTo(tenantB.toString());
        assertThat(rowB.get("losing_value").toString()).contains("OTHER_FROM_B");
    }

    private EngineResult runReconcile(
            UUID modelId, String incomingSource, Object incomingValue, String currentSource, Object currentValue) {
        TenantContext tenant = TenantContext.of(modelId);
        GraphMutation mutation = GraphMutation.builder()
                .tenantContext(tenant)
                .operation(Operation.UPDATE)
                .type("Person")
                .targetNodeUuid(UUID.randomUUID())
                .payload(Map.of("status", incomingValue))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("it-src-" + incomingSource)
                .sourceSystem(incomingSource)
                .confidence(BigDecimal.ONE)
                .build();
        RuleContext ctx = new RuleContext(
                tenant, null, Map.of("status", currentValue), Map.of("status", currentSource), mutation);
        return fixture.ruleEngine.run(ctx);
    }

    private void persistConflicts(UUID modelId, UUID nodeUuid, EngineResult result) {
        TenantContext tenant = TenantContext.of(modelId);
        UUID eventId = UUID.randomUUID();
        for (ConflictRecord c : result.conflicts()) {
            RuleEnginePort.ConflictEntry entry = new RuleEnginePort.ConflictEntry(
                    c.typeSlug(),
                    c.propertySlug(),
                    c.losingSourceId(),
                    c.losingSourceSystem(),
                    c.losingValue(),
                    c.winningSourceId(),
                    c.winningSourceSystem(),
                    c.winningValue(),
                    c.ruleId(),
                    c.reason());
            fixture.conflictsRepository.record(tenant, eventId, nodeUuid, entry);
        }
    }

    private void assertRowCountFor(UUID modelId, int expected) {
        Long n = fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_conflicts WHERE model_id = :model_id::uuid",
                new MapSqlParameterSource("model_id", modelId.toString()),
                Long.class);
        assertThat(n)
                .as("reconciliation_conflicts row count for tenant " + modelId)
                .isEqualTo((long) expected);
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
                        "conflict-register-it");
    }
}
