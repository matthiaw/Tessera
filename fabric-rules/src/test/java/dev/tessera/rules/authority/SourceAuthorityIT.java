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
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
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
 * Integration gate for RULE-05 (ROADMAP SC-5 half A — winner determinism).
 *
 * <p>Seeds a per-tenant {@code source_authority} matrix giving connector
 * {@code A} priority over connector {@code B} on the {@code status} property
 * of the {@code Person} type, then drives the {@link dev.tessera.rules.RuleEngine}
 * with the current value originating from one connector and the incoming
 * mutation from the other. Asserts that the higher-authority connector
 * ({@code A}) wins deterministically regardless of which side arrives first.
 *
 * <p>Note on wiring: {@code GraphServiceImpl.apply} currently passes
 * {@code currentSourceSystem=Map.of()} to the rule engine port (see
 * {@code fabric-core/.../GraphServiceImpl.java} line ~120) — threading the
 * per-property source-system map through the write funnel is a follow-up
 * concern. To keep this test test-only (HARD CONSTRAINT of 01-W4-PLAN.md: no
 * production edits) we exercise the rule engine directly via
 * {@link dev.tessera.rules.RuleEngine#run(RuleContext)} with a populated
 * {@code currentSourceSystem}. This validates the end-to-end RULE-05 contract
 * — {@link SourceAuthorityMatrix} + {@link AuthorityReconciliationRule} +
 * {@link dev.tessera.rules.internal.ChainExecutor} working as a unit against a
 * real V6 {@code source_authority} row in Postgres.
 */
class SourceAuthorityIT {

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
    void higherAuthorityConnectorWinsIndependentOfArrivalOrder() {
        UUID modelId = UUID.randomUUID();
        seedAuthorityMatrix(modelId, "Person", "status", new String[] {"A", "B"});

        // Invalidate caches so the just-inserted row is visible.
        fixture.authorityMatrix.invalidateAll();

        // The RULE-05 determinism contract is about the user-observable winning
        // VALUE ending up in finalProperties. We assert it in both arrival
        // orders. We intentionally do NOT assert on
        // ConflictRecord.winningSourceSystem because the ChainExecutor hard-
        // wires it to ctx.mutation().sourceSystem() — in the "current keeps"
        // branch that field reads back as the incoming source, which is a
        // known production-code quirk (tracked as a Rule-4 flag in 01-W4-SUMMARY,
        // not fixable inside this test-only plan). losingValue + finalProperties
        // already pin the end-to-end contract.

        // --- Case 1: A wrote first; B then tries to overwrite ---
        EngineResult r1 = runReconcile(
                modelId,
                /* incomingSource */ "B",
                /* incomingValue */ "VALUE_FROM_B",
                /* currentSource */ "A",
                /* currentValue */ "VALUE_FROM_A");
        assertThat(r1.rejected())
                .as("rule engine must not reject — this is RECONCILE, not VALIDATE")
                .isFalse();
        assertThat(r1.finalProperties().get("status"))
                .as("A arrived first; authority matrix ranks A above B -> A wins")
                .isEqualTo("VALUE_FROM_A");
        assertThat(r1.conflicts())
                .as("the losing write must be recorded as a conflict entry")
                .hasSize(1);
        assertThat(r1.conflicts().get(0).losingValue())
                .as("the losing value must reflect the incoming B write")
                .isEqualTo("VALUE_FROM_B");
        assertThat(r1.conflicts().get(0).winningValue())
                .as("the winning value must be A's committed value")
                .isEqualTo("VALUE_FROM_A");

        // --- Case 2: flipped — B wrote first; A then overwrites ---
        EngineResult r2 = runReconcile(
                modelId,
                /* incomingSource */ "A",
                /* incomingValue */ "VALUE_FROM_A",
                /* currentSource */ "B",
                /* currentValue */ "VALUE_FROM_B");
        assertThat(r2.rejected()).isFalse();
        assertThat(r2.finalProperties().get("status"))
                .as("B arrived first, A is incoming; A still outranks B -> A wins")
                .isEqualTo("VALUE_FROM_A");
        assertThat(r2.conflicts()).hasSize(1);
        assertThat(r2.conflicts().get(0).losingValue()).isEqualTo("VALUE_FROM_B");
        assertThat(r2.conflicts().get(0).winningValue()).isEqualTo("VALUE_FROM_A");
        // In Case 2 the incoming-wins branch DOES produce correct source-system
        // labelling on the conflict record (incoming is A, current is B) — we
        // pin that half as an additional sanity check.
        assertThat(r2.conflicts().get(0).winningSourceSystem())
                .as("case 2 (incoming wins) records winning_source_system correctly")
                .isEqualTo("A");
        assertThat(r2.conflicts().get(0).losingSourceSystem())
                .as("case 2 (incoming wins) records losing_source_system correctly")
                .isEqualTo("B");
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
                tenant,
                /* descriptor */ null,
                /* currentProperties */ Map.of("status", currentValue),
                /* currentSourceSystem */ Map.of("status", currentSource),
                mutation);
        return fixture.ruleEngine.run(ctx);
    }

    static void seedAuthorityMatrix(UUID modelId, String typeSlug, String propertySlug, String[] priorityOrder) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        p.addValue("type_slug", typeSlug);
        p.addValue("property_slug", propertySlug);
        p.addValue("priority_order", priorityOrder);
        p.addValue("updated_at", Timestamp.from(Instant.now()));
        p.addValue("updated_by", "source-authority-it");
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
                        p.getValue("updated_at"),
                        "source-authority-it");
    }
}
