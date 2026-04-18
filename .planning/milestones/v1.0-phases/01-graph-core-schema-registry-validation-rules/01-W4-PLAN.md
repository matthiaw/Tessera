---
phase: 01-graph-core-schema-registry-validation-rules
plan: W4
type: execute
wave: 4
depends_on: [01-W3]
files_modified:
  - fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
autonomous: true
gap_closure: true
requirements: [VALID-05, RULE-04, RULE-05, RULE-06, RULE-08]

must_haves:
  truths:
    - "VALID-05: A VALIDATE-chain rule returning Reject causes GraphServiceImpl.apply() to throw, rolls back the Postgres TX, and leaves zero new rows in graph_events."
    - "RULE-04: Seeding rows into reconciliation_rules + hitting POST /admin/rules/reload/{modelId} toggles activation and priority_override for a tenant without restart."
    - "RULE-05: When two connectors write the same property under a per-tenant source_authority matrix, the higher-priority connector deterministically wins."
    - "RULE-06: The losing write from the RULE-05 scenario lands in reconciliation_conflicts with correct model_id scoping."
    - "RULE-08: A second mutation with the same (origin_connector_id, origin_change_id) is rejected by EchoLoopSuppressionRule before touching AGE."
  artifacts:
    - path: "fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java"
      provides: "VALID-05 integration gate"
      contains: "@Testcontainers"
    - path: "fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java"
      provides: "RULE-04 integration gate"
      contains: "reconciliation_rules"
    - path: "fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java"
      provides: "RULE-05 integration gate (SC-5 half A)"
      contains: "source_authority"
    - path: "fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java"
      provides: "RULE-06 integration gate (SC-5 half A)"
      contains: "reconciliation_conflicts"
    - path: "fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java"
      provides: "RULE-08 integration gate (SC-5 half B)"
      contains: "origin_change_id"
  key_links:
    - from: "BusinessRuleRejectIT"
      to: "GraphServiceImpl.apply() + RuleOutcome.Reject"
      via: "PipelineFixture invocation of write funnel with a VALIDATE-chain Reject rule wired in"
      pattern: "assertThrows.*RuleReject"
    - from: "SourceAuthorityIT / ConflictRegisterIT"
      to: "source_authority (V6) + reconciliation_conflicts (V7)"
      via: "Seed rows via JdbcTemplate, apply two conflicting writes, query back both tables"
      pattern: "source_authority|reconciliation_conflicts"
    - from: "RuleRegistrationIT"
      to: "RuleRepository + RuleAdminController (V9 reconciliation_rules)"
      via: "JDBC seed -> admin reload -> re-query repository"
      pattern: "/admin/rules/reload"
    - from: "EchoLoopSuppressionIT"
      to: "EchoLoopSuppressionRule + RuleEngine.onCommitted"
      via: "Apply mutation M1(origin=A,change=X), then replay M1, assert second call rejected"
      pattern: "origin_change_id|EchoLoop"
---

<objective>
Close the 5 gaps identified in 01-VERIFICATION.md by populating the integration tests that prove VALID-05, RULE-04, RULE-05, RULE-06, RULE-08. Phase 1 production code is complete; this plan is test-authoring only.

Purpose: Provide automated regression gates for the rule engine end-to-end behaviours. Without these, ROADMAP SC-5 is not verifiable end-to-end.

Output: 5 populated JUnit 5 + Testcontainers integration tests replacing the existing 27-line @Disabled placeholders, all passing under `./mvnw -pl fabric-rules -B verify`.

HARD CONSTRAINT — NO NEW PRODUCTION CODE: Every production class needed by these tests already exists per 01-W3-SUMMARY.md (SourceAuthorityMatrix, AuthorityReconciliationRule, EchoLoopSuppressionRule, RuleRepository, RuleAdminController, ReconciliationConflictsRepository, ChainExecutor, Rule, RuleOutcome.Reject, GraphServiceImpl.apply, PipelineFixture, AgePostgresContainer, RulesTestHarness, Flyway V1..V10). If during execution you conclude a tiny production tweak is unavoidable, STOP and surface it as a `note:` on the task — do not silently expand scope.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/ROADMAP.md
@.planning/REQUIREMENTS.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-VERIFICATION.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W3-PLAN.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W3-SUMMARY.md
@fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java
@fabric-rules/src/test/java/dev/tessera/rules/support/PipelineFixture.java
@fabric-rules/src/test/java/dev/tessera/rules/support/RulesTestHarness.java
@fabric-rules/src/test/java/dev/tessera/rules/support/AgePostgresContainer.java

<interfaces>
<!-- Authoritative production class names (from 01-W3-SUMMARY.md + fabric-rules/src/main tree). -->
<!-- Executor MUST read the real source files under fabric-rules/src/main/java/dev/tessera/rules/ -->
<!-- and fabric-core/src/main/java/dev/tessera/core/ to get exact method signatures before writing assertions. -->

Production classes (already exist — DO NOT recreate):
  fabric-core:
    - dev.tessera.core.graph.internal.GraphServiceImpl  (@Transactional apply() write funnel)
    - dev.tessera.core.graph.GraphService               (port)
    - dev.tessera.core.rules.RuleEnginePort             (SPI)
    - dev.tessera.core.validation.ShaclValidator        (pre-commit)
    - dev.tessera.core.events.EventLog                  (append)
    - ReconciliationConflictsRepository                 (inside fabric-core per W3-SUMMARY line 100)
  fabric-rules:
    - dev.tessera.rules.RuleEngine                      (four-chain executor, onCommitted hook)
    - dev.tessera.rules.Rule / RuleContext / RuleOutcome (sealed: Commit, Reject, Merge, Override, Add, Route)
    - dev.tessera.rules.internal.ChainExecutor
    - dev.tessera.rules.internal.RuleRepository         (hybrid Java+DB activation per ADR-7)
    - dev.tessera.rules.authority.SourceAuthorityMatrix
    - dev.tessera.rules.authority.AuthorityReconciliationRule
    - dev.tessera.rules.EchoLoopSuppressionRule          (VALIDATE chain, priority 50)
    - dev.tessera.rules.admin.RuleAdminController        (POJO @Component, PATH = "/admin/rules/reload/{modelId}")

Test infrastructure (already exists — reuse):
    - dev.tessera.rules.support.AgePostgresContainer    (Testcontainers + Flyway V1..V10)
    - dev.tessera.rules.support.PipelineFixture         (builds a wired GraphServiceImpl for ITs)
    - dev.tessera.rules.support.RulesTestHarness        (tenant seeding, baseline graph state)

Flyway migrations already live:
    V6 source_authority
    V7 reconciliation_conflicts
    V9 reconciliation_rules

Reference passing IT (same module, same harness shape):
    - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Fill BusinessRuleRejectIT (VALID-05)</name>
  <files>fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java</files>

  <read_first>
    - fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java (the placeholder — 27 lines, @Disabled)
    - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java (authoritative harness shape — Testcontainers + Flyway + PipelineFixture wiring)
    - fabric-rules/src/test/java/dev/tessera/rules/support/PipelineFixture.java (how to build a GraphServiceImpl for an IT; how to inject a custom Rule list)
    - fabric-rules/src/test/java/dev/tessera/rules/support/RulesTestHarness.java
    - fabric-rules/src/main/java/dev/tessera/rules/Rule.java + RuleOutcome.java (sealed, look up Reject constructor signature)
    - fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java (confirm Chain.VALIDATE enum value name)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (apply() throws what? RuleRejectException or a ValidationException wrapper — check exact type before asserting)
    - fabric-core/src/main/java/dev/tessera/core/events/EventLog.java (method to count rows for a model_id)
  </read_first>

  <action>
    1. DELETE `@Disabled("Wave 3 — filled by plan 01-W3-02")` and the `placeholder()` empty test.
    2. Add `@Testcontainers` class-level + `@Container static AgePostgresContainer pg = ...` matching CircuitBreakerIT's pattern.
    3. In `@BeforeEach`, build a PipelineFixture that injects a single extra Rule: an anonymous `Rule` implementation with `chain() == Chain.VALIDATE`, `priority() == 1000`, `applies(ctx) == true`, and `evaluate(ctx)` returning `new RuleOutcome.Reject("test-rejected", "VALID-05 gate")`. If PipelineFixture does not support custom rule injection, use the `RulesTestHarness` path shown in CircuitBreakerIT; if neither supports it, surface as note and escalate — do not invent a new harness API.
    4. Write ONE @Test `rejectRuleAbortsTransactionAndWritesNothing`:
       - Seed a tenant `modelId = UUID.randomUUID()` and a baseline node type via RulesTestHarness.
       - Capture graph_events row count for this tenant BEFORE the apply call via `fixture.jdbc.queryForObject("SELECT COUNT(*) FROM graph_events WHERE model_id = ?::uuid", Long.class, modelId.toString())` (EventLog has no countForModel method — use JDBC directly).
       - Build a GraphMutation targeting a new node creation for that tenant (mirror the shape used in CircuitBreakerIT / PipelineFixture helpers).
       - Call `graphService.apply(mutation)` inside `assertThatThrownBy(...)` and assert the thrown type matches what `GraphServiceImpl.apply` actually throws on a VALIDATE-chain Reject (read GraphServiceImpl.java to confirm — likely `RuleRejectException` or a wrapper around RuleOutcome.Reject).
       - Assert the thrown exception message or cause contains `"test-rejected"` (the reason from the Reject outcome).
       - Re-run the same JDBC count query and assert it is UNCHANGED after the call (TX rollback proven — no event_log row for the rejected mutation).
       - Assert the target node does NOT exist via a read (`graphService.readNode(...)` or whatever the port exposes).
    5. Keep imports minimal; follow spotless formatting used in CircuitBreakerIT.

    note: If GraphServiceImpl throws a different exception type than RuleRejectException, use the actual type. Do NOT guess — read the source.
  </action>

  <verify>
    <automated>./mvnw -pl fabric-rules -Dit.test=BusinessRuleRejectIT -B verify</automated>
  </verify>

  <acceptance_criteria>
    - `grep -c '@Disabled' fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java` returns 0.
    - `grep -c 'placeholder()' fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java` returns 0.
    - `grep -q '@Testcontainers' fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java` succeeds.
    - `./mvnw -pl fabric-rules -Dit.test=BusinessRuleRejectIT -B verify` exits 0.
    - Failsafe report `fabric-rules/target/failsafe-reports/TEST-dev.tessera.rules.BusinessRuleRejectIT.xml` shows `tests>=1 skipped="0" failures="0" errors="0"`.
    - No files outside `files_modified` are touched (diff scope check).
  </acceptance_criteria>

  <done>VALID-05 has a real automated gate: a VALIDATE-chain Reject proven to abort GraphServiceImpl.apply(), roll back the Postgres TX, and leave graph_events untouched.</done>
</task>

<task type="auto">
  <name>Task 2: Fill RuleRegistrationIT (RULE-04 hybrid Java+DB activation + reload)</name>
  <files>fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java</files>

  <read_first>
    - fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java
    - fabric-rules/src/main/java/dev/tessera/rules/internal/RuleRepository.java (confirm per-tenant cache keys + reload semantics + how to query "active rules for modelId")
    - fabric-rules/src/main/java/dev/tessera/rules/admin/RuleAdminController.java (confirm PATH constant and the method name that performs the reload — `reload(UUID modelId)` or similar)
    - fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql (schema: columns likely include rule_id, model_id, enabled, priority_override)
    - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java (Testcontainers pattern + how to get a JdbcTemplate onto the AgePostgresContainer)
    - fabric-rules/src/test/java/dev/tessera/rules/support/PipelineFixture.java
  </read_first>

  <action>
    1. DELETE @Disabled + placeholder.
    2. Stand up AgePostgresContainer + PipelineFixture. Obtain JdbcTemplate against pg.getJdbcUrl().
    3. Obtain the `RuleRepository` bean from the fixture context; obtain `RuleAdminController` (POJO @Component) from the same context.
    4. Pick a rule bean known to be registered at compile-time — the test should look it up by `ruleId` (e.g., `EchoLoopSuppressionRule.RULE_ID` or whatever constant the source defines — read the source to find it).
    5. Scenario:
       a. `modelId = UUID.randomUUID()`.
       b. First assertion — fresh tenant default: call `ruleRepository.activeRules(modelId)` (use the actual method name — read RuleRepository.java); assert the chosen rule is present with its compile-time default priority (matches `rule.priority()` from the Java class).
       c. Insert row via JdbcTemplate: `INSERT INTO reconciliation_rules(model_id, rule_id, enabled, priority_override) VALUES (?, ?, false, NULL)` — use real column names from V9 migration.
       d. Call `ruleAdminController.reload(modelId)` (or the method that the PATH constant maps to — read source).
       e. Assert `ruleRepository.activeRules(modelId)` NO LONGER contains that ruleId (DB-driven deactivation took effect without JVM restart).
       f. `UPDATE reconciliation_rules SET enabled=true, priority_override=777 WHERE model_id=? AND rule_id=?`.
       g. Call `ruleAdminController.reload(modelId)` again.
       h. Assert the rule is present AND its effective priority == 777 (priority_override decorator applied).
       i. Assert a DIFFERENT `modelId2` tenant is unaffected (fresh tenant still sees the compile-time default — proves per-tenant scoping and Caffeine cache isolation).
    6. Use AssertJ's `assertThat(...)` throughout.

    note: Column names in V9 migration may differ (`enabled` vs `active`, `priority_override` vs `override_priority`). Read the SQL before writing the insert.
  </action>

  <verify>
    <automated>./mvnw -pl fabric-rules -Dit.test=RuleRegistrationIT -B verify</automated>
  </verify>

  <acceptance_criteria>
    - `grep -c '@Disabled' fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java` returns 0.
    - `grep -c 'placeholder()' fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java` returns 0.
    - `grep -q 'reconciliation_rules' fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java` succeeds.
    - `grep -q 'reload' fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java` succeeds.
    - `./mvnw -pl fabric-rules -Dit.test=RuleRegistrationIT -B verify` exits 0.
    - Failsafe XML shows tests>=1, skipped=0, failures=0, errors=0.
    - The test proves BOTH (a) DB-driven deactivation and (b) priority_override decoration for the same tenant, AND (c) a second tenant is unaffected.
  </acceptance_criteria>

  <done>RULE-04 has an automated gate proving ADR-7's hybrid Java+DB activation + priority_override reload works end-to-end via the admin endpoint without restart, and is tenant-scoped.</done>
</task>

<task type="auto">
  <name>Task 3: Fill SourceAuthorityIT + ConflictRegisterIT together (RULE-05 + RULE-06, ROADMAP SC-5 half A)</name>
  <files>fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java, fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java</files>

  <read_first>
    - fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java
    - fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java
    - fabric-rules/src/main/java/dev/tessera/rules/authority/SourceAuthorityMatrix.java (column layout — property key, connector id, priority)
    - fabric-rules/src/main/java/dev/tessera/rules/authority/AuthorityReconciliationRule.java (which chain it lives on — RECONCILE per W3 summary, and how it produces Override/Merge outcomes)
    - fabric-core/src/main/java/dev/tessera/core/ (search for ReconciliationConflictsRepository — it lives in fabric-core per W3-SUMMARY line 100. Confirm its insert SQL and queryable API.)
    - fabric-app/src/main/resources/db/migration/V6__source_authority.sql (exact column names)
    - fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql (exact column names — model_id, property, winning_connector_id, losing_connector_id, losing_value, ...)
    - fabric-rules/src/test/java/dev/tessera/rules/support/PipelineFixture.java (how to apply two mutations under different connector identities)
    - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java (reference Testcontainers shape)
  </read_first>

  <action>
    These two tests share a scenario, so author them TOGETHER to guarantee the single SC-5 end-to-end truth is expressed once and both files pin different halves of it. No shared helper class — duplicate the ~10 lines of seed SQL; keeping each IT file self-contained is preferable to sneaking new test infra into files_modified.

    Shared scenario (implement in BOTH files):
      1. Boot AgePostgresContainer via PipelineFixture.
      2. `modelId = UUID.randomUUID()`.
      3. Seed baseline node type + a single target node `nodeId = UUID.randomUUID()` with an initial property `status = "INITIAL"` via the harness.
      4. Insert source_authority row: `(model_id=modelId, property='status', connector_id='A', priority=100)`.
      5. Insert source_authority row: `(model_id=modelId, property='status', connector_id='B', priority=50)`.
         — Use the real column names from V6 migration; read the SQL.
      6. Apply mutation M_A: connector='A', node=nodeId, property status='VALUE_FROM_A'.
      7. Apply mutation M_B: connector='B', node=nodeId, property status='VALUE_FROM_B'.
         — Order matters only if you want to additionally prove "later lower-priority loses"; for RULE-05 the deterministic winner is A regardless of order. Document the order used in a javadoc comment.

    SourceAuthorityIT assertions (RULE-05 — winner is deterministic):
      - `graphService.readNode(modelId, nodeId).property("status")` equals `"VALUE_FROM_A"`.
      - Run the same scenario TWICE in the test (two separate nodeIds) with mutation order flipped (A first then B; B first then A); assert A wins both times — proves determinism independent of arrival order.
      - Assert the winning value is A's regardless of the order the mutations arrived — this is the RULE-05 contract.

    ConflictRegisterIT assertions (RULE-06 — losing write recorded + model_id scoped):
      - After applying the same scenario, query `reconciliation_conflicts` via JdbcTemplate:
          SELECT model_id, property, winning_connector_id, losing_connector_id, losing_value
          FROM reconciliation_conflicts WHERE model_id=? (use real column names from V7)
      - Assert exactly ONE row.
      - Assert row.model_id == modelId.
      - Assert row.property == 'status'.
      - Assert row.winning_connector_id == 'A'.
      - Assert row.losing_connector_id == 'B'.
      - Assert row.losing_value contains "VALUE_FROM_B".
      - Tenant isolation check: run the scenario for a second `modelId2`; assert the first modelId's reconciliation_conflicts row count is unchanged and the second tenant has its own row. Proves model_id scoping.

    Both files MUST be independently runnable via `-Dit.test=X` — no JUnit dependency between them.

    note: If `reconciliation_conflicts` schema uses different column names (e.g., `winning_connector` not `winning_connector_id`), adjust. Read V7 migration first. Do NOT modify the migration.
    note: If AuthorityReconciliationRule produces a Reject for the loser instead of an Override that triggers conflict recording, the second apply() call will throw — in that case wrap the second apply in an assertThrows and still verify the row landed. Read AuthorityReconciliationRule.java to determine the actual behaviour before deciding.
  </action>

  <verify>
    <automated>./mvnw -pl fabric-rules -Dit.test=SourceAuthorityIT,ConflictRegisterIT -B verify</automated>
  </verify>

  <acceptance_criteria>
    - `grep -c '@Disabled' fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java` returns 0.
    - `grep -c '@Disabled' fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java` returns 0.
    - `grep -c 'placeholder()' fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java` returns 0.
    - `grep -c 'placeholder()' fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java` returns 0.
    - `grep -q 'source_authority' fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java` succeeds.
    - `grep -q 'reconciliation_conflicts' fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java` succeeds.
    - `./mvnw -pl fabric-rules -Dit.test=SourceAuthorityIT,ConflictRegisterIT -B verify` exits 0.
    - Failsafe XML for both ITs: tests>=1, skipped=0, failures=0, errors=0.
    - SourceAuthorityIT proves determinism by running the scenario with BOTH orderings (A-then-B, B-then-A) and asserting A wins both times.
    - ConflictRegisterIT proves model_id scoping by running a second tenant and confirming cross-tenant isolation.
  </acceptance_criteria>

  <done>ROADMAP SC-5 half A is gated: two-connector conflict with a per-tenant authority matrix resolves to the higher-priority connector deterministically, and the losing write lands in reconciliation_conflicts scoped by model_id.</done>
</task>

<task type="auto">
  <name>Task 4: Fill EchoLoopSuppressionIT (RULE-08, ROADMAP SC-5 half B)</name>
  <files>fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java</files>

  <read_first>
    - fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
    - fabric-rules/src/main/java/dev/tessera/rules/EchoLoopSuppressionRule.java (VALIDATE chain, priority 50; confirm how it keys — `(originConnectorId, originChangeId)` — and which exception/outcome it emits; also confirm how RuleEngine.onCommitted seeds the positive-hit cache)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (how originConnectorId and originChangeId flow into RuleEngine.apply and whether the rule rejects or throws)
    - fabric-rules/src/test/java/dev/tessera/rules/support/PipelineFixture.java (how to set originConnectorId + originChangeId on a GraphMutation — the builder likely has withOrigin(connectorId, changeId))
    - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java (reference harness shape)
    - fabric-core/src/main/java/dev/tessera/core/events/EventLog.java (to count events and prove the second write never appended)
  </read_first>

  <action>
    1. DELETE @Disabled + placeholder.
    2. Boot AgePostgresContainer + PipelineFixture.
    3. Seed a tenant modelId + baseline node type.
    4. Build mutation M1 with `originConnectorId='connector-X'` and `originChangeId='change-abc-123'` creating a new node with some property `foo='bar'`.
    5. Call `graphService.apply(M1)`. Assert it succeeds (post-condition: node exists, eventLog has +1 row for modelId).
    6. Build mutation M2 IDENTICAL origin pair: `originConnectorId='connector-X'`, `originChangeId='change-abc-123'`, even pointing at a different property value.
    7. Call `graphService.apply(M2)` inside `assertThatThrownBy(...)` and assert it throws the actual exception type the rule produces (read the source — likely RuleRejectException carrying rule id `echo-loop-suppression` or similar).
    8. Assert `fixture.jdbc.queryForObject("SELECT COUNT(*) FROM graph_events WHERE model_id = ?::uuid", Long.class, modelId.toString())` is still exactly 1 after the rejected second call (proves pre-AGE rejection — the rule fires before any Cypher or event append). EventLog has no countForModel method; use JDBC directly.
    9. Negative control: a THIRD mutation M3 with `originChangeId='change-different'` (same connector) MUST succeed — proves the key is `(connector, change)` and not "block all traffic from connector".
    10. Tenant isolation control: the same `(connector-X, change-abc-123)` pair applied under a DIFFERENT modelId MUST succeed — proves echo-loop state is per-tenant.

    note: The W3 summary says the rule operates at priority 50 in the VALIDATE chain and RuleEngine.onCommitted seeds the positive-hit cache after commit. If the rule instead reads committed state from the event log rather than an in-memory cache, the scenario still works because M1 commits before M2 starts. Do not assume implementation detail — just assert behaviour.
  </action>

  <verify>
    <automated>./mvnw -pl fabric-rules -Dit.test=EchoLoopSuppressionIT -B verify</automated>
  </verify>

  <acceptance_criteria>
    - `grep -c '@Disabled' fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java` returns 0.
    - `grep -c 'placeholder()' fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java` returns 0.
    - `grep -q 'originChangeId\|origin_change_id' fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java` succeeds.
    - `./mvnw -pl fabric-rules -Dit.test=EchoLoopSuppressionIT -B verify` exits 0.
    - Failsafe XML: tests>=1, skipped=0, failures=0, errors=0.
    - Test covers BOTH the rejection path AND the two negative controls (different changeId succeeds, different tenant succeeds) — not just the happy rejection.
  </acceptance_criteria>

  <done>RULE-08 has an automated gate: replaying a mutation with the same (originConnectorId, originChangeId) is rejected before touching AGE; different changeId and different tenant both still flow through. ROADMAP SC-5 half B is gated.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| test → Postgres/AGE | Testcontainer-scoped; isolated per-IT. No production data at risk. |
| test-injected Rule bean → GraphServiceImpl | The VALID-05 test injects a Reject rule; must NOT be left registered after the test class tears down (use fresh PipelineFixture per class). |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-W4-01 | Tampering | Test-injected Reject rule bleeding into unrelated tests | mitigate | PipelineFixture is per-test-class; no static state. AgePostgresContainer is fresh per class (reuse disabled). |
| T-01-W4-02 | Information Disclosure | Test logging leaks tenant UUIDs or connector IDs | accept | Test logs are local; no PII; tenant UUIDs are generated per-run. |
| T-01-W4-03 | DoS | Forgotten @Disabled leaves regression gate silently dead | mitigate | Acceptance criteria on every task grep -c '@Disabled' returns 0; failsafe XML skipped="0" assertion. |
</threat_model>

<verification>
Overall plan-level verification (run after all 4 tasks complete):

```bash
# 1. No disabled tests remain in the gap scope
grep -l '@Disabled' \
  fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
# Expected: no output (all 5 files clean).

# 2. No placeholder methods remain
grep -l 'placeholder()' \
  fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java \
  fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
# Expected: no output.

# 3. fabric-rules module is fully green including the 5 new ITs
./mvnw -pl fabric-rules -B verify
# Expected: BUILD SUCCESS, failsafe reports for all 5 ITs show skipped="0", failures="0", errors="0".

# 4. Full reactor still green (no regression)
./mvnw -B verify
# Expected: BUILD SUCCESS, all 6 modules green.

# 5. Confirm no production files were modified in this plan
git diff --name-only 01-W3..HEAD -- 'fabric-*/src/main/**' ':!fabric-*/src/test/**'
# Expected: no output (test-only plan).
```
</verification>

<success_criteria>
- All 5 target test files no longer contain `@Disabled` or `placeholder()`.
- `./mvnw -pl fabric-rules -B verify` exits 0 with failsafe XML showing all 5 ITs as `tests>=1 skipped=0 failures=0 errors=0`.
- `./mvnw -B verify` (full reactor) exits 0 — no regression in other modules.
- `git diff` shows changes only under `fabric-rules/src/test/` (NO production source modifications). If any production change proved unavoidable, it MUST be called out explicitly in the Wave 4 SUMMARY's Deviations section with rationale.
- VALID-05, RULE-04, RULE-05, RULE-06, RULE-08 all have a named passing integration test in the failsafe report, closing the gap identified in 01-VERIFICATION.md.
- ROADMAP SC-5 is now fully verifiable end-to-end (half A via SourceAuthorityIT + ConflictRegisterIT, half B via EchoLoopSuppressionIT, write-rate breaker already via CircuitBreakerIT).
</success_criteria>

<output>
After completion, create `.planning/phases/01-graph-core-schema-registry-validation-rules/01-W4-SUMMARY.md` documenting:
- Which assertions closed which requirement (VALID-05/RULE-04/RULE-05/RULE-06/RULE-08 → specific test method names).
- Any deviations from the plan (especially any production tweak — MUST be flagged).
- Failsafe numbers: tests/skipped/failures/errors per new IT.
- Full reactor `./mvnw -B verify` wall-clock.
- A note that 01-VERIFICATION.md can now be re-run to mark all 5 gaps closed and Phase 1 as shipped.
</output>
