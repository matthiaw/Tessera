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

import dev.tessera.rules.admin.RuleAdminController;
import dev.tessera.rules.authority.SourceAuthorityMatrix;
import dev.tessera.rules.internal.RuleRepository;
import dev.tessera.rules.support.PipelineFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Integration gate for RULE-04 / ADR-7 §RULE-04 hybrid Java-plus-DB activation
 * model. Proves that {@code reconciliation_rules} rows control per-tenant
 * activation + {@code priority_override} and that the admin reload endpoint
 * invalidates the Caffeine cache so changes take effect without a JVM restart.
 *
 * <p>Scenario:
 *
 * <ol>
 *   <li>Fresh tenant — {@link EchoLoopSuppressionRule} is present with its
 *       compile-time default priority (10_000).
 *   <li>Insert {@code enabled=false} row for that tenant, reload, assert the
 *       rule disappears from {@code activeRulesFor}.
 *   <li>Flip to {@code enabled=true, priority_override=777}, reload, assert the
 *       rule is back with priority 777.
 *   <li>A second, unrelated tenant still sees the compile-time default — proves
 *       per-tenant cache isolation.
 * </ol>
 */
class RuleRegistrationIT {

    private static PipelineFixture fixture;
    private static RuleRepository ruleRepository;
    private static RuleAdminController adminController;

    @BeforeAll
    static void bootFixture() {
        fixture = PipelineFixture.boot(List.of());
        ruleRepository = fixture.ruleRepository;
        adminController = new RuleAdminController(ruleRepository, new SourceAuthorityMatrix(fixture.jdbc));
    }

    @AfterAll
    static void stopFixture() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void dbDrivenActivationAndPriorityOverrideReloadPerTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String targetRuleId = EchoLoopSuppressionRule.RULE_ID;

        // (a) Fresh tenant default: rule is present with compile-time priority.
        List<Rule> initial = ruleRepository.activeRulesFor(tenantA);
        Rule initialHit = findById(initial, targetRuleId);
        assertThat(initialHit)
                .as("fresh tenant must see EchoLoopSuppressionRule as always-on default")
                .isNotNull();
        assertThat(initialHit.priority()).as("compile-time default priority").isEqualTo(10_000);

        // (b) Seed disabled row for tenant A.
        insertRuleRow(tenantA, targetRuleId, false, null);
        adminController.reload(tenantA);
        List<Rule> afterDisable = ruleRepository.activeRulesFor(tenantA);
        assertThat(findById(afterDisable, targetRuleId))
                .as("DB-driven deactivation must remove rule from active list after reload")
                .isNull();

        // (c) Flip to enabled with priority_override=777.
        updateRuleRow(tenantA, targetRuleId, true, 777);
        adminController.reload(tenantA);
        List<Rule> afterOverride = ruleRepository.activeRulesFor(tenantA);
        Rule overridden = findById(afterOverride, targetRuleId);
        assertThat(overridden)
                .as("re-enabled rule must reappear in active list")
                .isNotNull();
        assertThat(overridden.priority())
                .as("priority_override must decorate the rule's effective priority")
                .isEqualTo(777);

        // (d) Second tenant unaffected — still sees compile-time default.
        List<Rule> tenantBRules = ruleRepository.activeRulesFor(tenantB);
        Rule tenantBHit = findById(tenantBRules, targetRuleId);
        assertThat(tenantBHit)
                .as("per-tenant cache isolation — tenant B unaffected by tenant A's row")
                .isNotNull();
        assertThat(tenantBHit.priority())
                .as("tenant B still uses compile-time default")
                .isEqualTo(10_000);
    }

    private static Rule findById(List<Rule> rules, String id) {
        return rules.stream().filter(r -> id.equals(r.id())).findFirst().orElse(null);
    }

    private static void insertRuleRow(UUID modelId, String ruleId, boolean enabled, Integer priorityOverride) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", UUID.randomUUID().toString());
        p.addValue("model_id", modelId.toString());
        p.addValue("rule_id", ruleId);
        p.addValue("enabled", enabled);
        p.addValue("priority_override", priorityOverride);
        p.addValue("updated_at", Timestamp.from(Instant.now()));
        p.addValue("updated_by", "rule-registration-it");
        fixture.jdbc.update(
                """
                INSERT INTO reconciliation_rules
                    (id, model_id, rule_id, enabled, priority_override, updated_at, updated_by)
                VALUES
                    (:id::uuid, :model_id::uuid, :rule_id, :enabled, :priority_override, :updated_at, :updated_by)
                """,
                p);
    }

    private static void updateRuleRow(UUID modelId, String ruleId, boolean enabled, Integer priorityOverride) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        p.addValue("rule_id", ruleId);
        p.addValue("enabled", enabled);
        p.addValue("priority_override", priorityOverride);
        p.addValue("updated_at", Timestamp.from(Instant.now()));
        fixture.jdbc.update(
                """
                UPDATE reconciliation_rules
                   SET enabled = :enabled,
                       priority_override = :priority_override,
                       updated_at = :updated_at
                 WHERE model_id = :model_id::uuid
                   AND rule_id = :rule_id
                """,
                p);
    }
}
