---
phase: 01-graph-core-schema-registry-validation-rules
verified: 2026-04-13T19:08:00Z
status: gaps_found
score: 4/5 roadmap success criteria verified; 30/35 requirements have passing automated test coverage
overrides_applied: 0
build:
  command: ./mvnw -B verify
  result: BUILD SUCCESS
  wall_clock: 5:40 min
  reactor:
    - fabric-core: SUCCESS (5:11 min)
    - fabric-rules: SUCCESS (13.9 s)
    - fabric-projections: SUCCESS
    - fabric-connectors: SUCCESS
    - fabric-app: SUCCESS (ArchUnit 11/11 green)
gaps:
  - truth: "Two-connector source-authority matrix deterministically resolves the winner and records the loser in reconciliation_conflicts (RULE-05, RULE-06)"
    status: partial
    reason: "Production code for SourceAuthorityMatrix, AuthorityReconciliationRule, ReconciliationConflictsRepository exists in fabric-rules per W3-T2 commit 42fbd0a, but the authoritative end-to-end integration tests SourceAuthorityIT + ConflictRegisterIT are 27-line @Disabled placeholders (tests='1' skipped='1' per failsafe XML). ROADMAP SC-5 cannot be demonstrated automatically."
    artifacts:
      - path: fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java
        issue: "@Disabled('Wave 3 — filled by plan 01-W3-02'); single placeholder() @Test with empty body"
      - path: fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java
        issue: "Same shape — 27-line placeholder shell"
    missing:
      - "Real integration test in SourceAuthorityIT that seeds source_authority rows for two connectors on the same property and asserts deterministic winner"
      - "Real integration test in ConflictRegisterIT that verifies losing write lands in reconciliation_conflicts with correct model_id scoping"
  - truth: "Business-rule REJECT outcome blocks writes at commit time (VALID-05)"
    status: partial
    reason: "ShaclValidator (VALID-01..04) and ChainExecutor are implemented and unit-tested, but the dedicated BusinessRuleRejectIT that proves a Reject outcome in the VALIDATE chain aborts the Postgres TX is a 27-line @Disabled placeholder."
    artifacts:
      - path: fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java
        issue: "@Disabled placeholder — no real test body"
    missing:
      - "Integration test wiring a Reject-returning Rule into GraphServiceImpl.apply() and asserting RuleRejectException + Postgres TX rollback"
  - truth: "Echo-loop suppression via (origin_connector_id, origin_change_id) prevents connector ping-pong (RULE-08)"
    status: partial
    reason: "EchoLoopSuppressionRule exists in fabric-rules main source per W3-T2 summary, but the authoritative EchoLoopSuppressionIT is a @Disabled placeholder. ROADMAP SC-4 event replay is proven by TemporalReplayIT; the echo-loop half of SC-5 is not."
    artifacts:
      - path: fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
        issue: "@Disabled placeholder"
    missing:
      - "Integration test replaying a mutation with the same (connectorId, changeId) and asserting second write is rejected"
  - truth: "Per-tenant rule activation + priority_override reload works via /admin/rules/reload/{modelId} (RULE-04, ADR-7)"
    status: partial
    reason: "RuleRepository + RuleAdminController implemented per W3-T2 summary, but RuleRegistrationIT — the only declared automated gate for the hybrid Java+DB activation model — is a @Disabled placeholder."
    artifacts:
      - path: fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java
        issue: "@Disabled placeholder"
    missing:
      - "Integration test seeding reconciliation_rules rows, hitting the reload endpoint, and asserting activation/priority changes take effect without restart"
deferred: []
known_deviations_adjudicated:
  - item: "No-op RuleEnginePort + CircuitBreakerPort fallback in GraphCoreConfig"
    verdict: ACCEPTED
    rationale: "Architecturally sound — production wiring in fabric-rules wins via @ConditionalOnMissingBean. Preserves fabric-rules → fabric-core dependency direction. Pure-fabric-core tests demonstrated green."
  - item: "WritePipelineBench Wave-1 baseline p95 = 4.65 ms > 3 ms warning"
    verdict: ACCEPTED (soft gate)
    rationale: "Phase 0 D-04 and W3 plan both mark JMH p95 as soft documentation gate, not CI fail-gate. Wave 3 full-pipeline bench compiles and is operator-triggerable via ./mvnw -pl fabric-core -Pjmh verify. Not blocking."
  - item: "CircuitBreakerAdminController REQUIRED_AUTHORIZATION as String constant, not real @PreAuthorize"
    verdict: ACCEPTED
    rationale: "Phase 2 concern — Spring Security is not on fabric-rules classpath by design. Javadoc + constant communicates the gate to Phase 2 REST mounters. Matches RuleAdminController pattern."
  - item: "connector_dlq empty-queue write path in Phase 1"
    verdict: ACCEPTED
    rationale: "Phase 1 has no connector-side queue. Table, indexes, insert SQL are live in V8 migration; CircuitBreakerIT exercises the write path with an empty list. Phase 2 connectors will populate real payloads."
  - item: "ADR-7 hybrid Java+DB rule model"
    verdict: ACCEPTED (but see RULE-04 gap above)
    rationale: "Contract is correct; implementation exists; the missing piece is test coverage proving the hybrid activation flow works end-to-end."
  - item: "Per-tenant Postgres SEQUENCE CACHE 50"
    verdict: ACCEPTED
    rationale: "Resolved Q2 — gaps on crash are benign and documented. EVENT-02 satisfied."
  - item: "Hand-rolled monthly partition maintenance via @Scheduled"
    verdict: ACCEPTED
    rationale: "PartitionMaintenanceIT exercises it. Avoids pg_partman dependency per Q2 resolution."
  - item: "TODO(W3) in GraphServiceImpl line 95 — authorize() Spring Security integration"
    verdict: ACCEPTED (Phase 2)
    rationale: "Matches deviation #4 — Spring Security is a Phase 2 concern. CORE-01 funnel is still a single TX; auth is the only piece staged for Phase 2."
---

# Phase 1: graph-core-schema-registry-validation-rules Verification Report

**Phase Goal:** Deliver Schema Registry + Event Log/Outbox + transactional write funnel GraphService.apply enforcing tenant isolation, synchronous SHACL validation, and priority-based reconciliation rules.

**Verified:** 2026-04-13T19:08:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification
**Build:** `./mvnw -B verify` — BUILD SUCCESS (5:40 min, 6/6 modules green)

## Build Evidence

```
[INFO] Reactor Summary for Tessera Parent 0.1.0-SNAPSHOT:
[INFO] Tessera Parent ..................................... SUCCESS [  9.289 s]
[INFO] Tessera :: fabric-core ............................. SUCCESS [05:11 min]
[INFO] Tessera :: fabric-rules ............................ SUCCESS [ 13.866 s]
[INFO] Tessera :: fabric-projections ...................... SUCCESS [  0.272 s]
[INFO] Tessera :: fabric-connectors ....................... SUCCESS [  0.118 s]
[INFO] Tessera :: fabric-app .............................. SUCCESS [  3.669 s]
[INFO] BUILD SUCCESS
```

ArchUnit gates in fabric-app (authoritative for CORE-02 / module boundaries): 11/11 green.
- `ImagePinningTest`: 4/4
- `RawCypherBanTest`: 2/2 (CORE-02 deferred from Phase 0 D-15 — NOW GREEN)
- `ModuleDependencyTest`: 5/5

## Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Every mutation flows through GraphService.apply() as single Postgres TX (auth → rules → SHACL → Cypher → event log → outbox); ArchUnit fails build if caller bypasses | VERIFIED | `GraphServiceImpl.apply()` wired W1 → W3 with CircuitBreaker first, RuleEngine 4-chain, ShaclValidator, Cypher via GraphSession, EventLog.append, Outbox.append in one @Transactional. `RawCypherBanTest` (2/2 green) + `ModuleDependencyTest` (5/5) + `RuleEngineHygieneTest` enforce the funnel. `GraphServiceApplyIT` exercises end-to-end. Only open item: `authorize()` is a `// TODO(W3)` deferred to Phase 2 Spring Security — accepted deviation. |
| 2 | Operator defines node types, properties, edge types via Schema Registry API, versions schema, renames via alias, SHACL picks change up without restart | VERIFIED | `SchemaNodeTypeCrudIT`, `SchemaPropertyCrudIT`, `SchemaEdgeTypeCrudIT`, `SchemaAliasIT`, `SchemaBreakingChangeIT`, `SchemaCacheInvalidationTest`, `SchemaVersioningReplayIT`, `SchemaToShaclIT` all green. V4 + V5 migrations (`schema_registry`, `schema_versioning_and_aliases`) live. |
| 3 | Mutation violating SHACL or business rule is rejected with tenant-filtered ValidationReport; fuzz tests prove no code path reads/writes across model_id | PARTIAL | SHACL half VERIFIED: `ShaclPreCommitIT`, `TargetedValidationTest`, `ShapeCacheTest`, `ValidationReportFilterTest` green (VALID-01..04). Tenant fuzz VERIFIED: `TenantBypassPropertyIT` runs 7 jqwik properties × 1000 tries = 7000 scenarios green (CORE-03). **BUSINESS-RULE half FAILS:** `BusinessRuleRejectIT` is a 27-line @Disabled placeholder — VALID-05 has no integration gate. See gap #2. |
| 4 | Given node UUID + past timestamp, system reconstructs state via event-log replay; full mutation history with cause attribution retrievable | VERIFIED | `TemporalReplayIT`, `AuditHistoryIT`, `EventProvenanceIT`, `EventProvenanceSmokeIT`, `EventLogSchemaIT`, `PerTenantSequenceIT`, `PartitionMaintenanceIT`, `OutboxTransactionalIT`, `OutboxPollerIT` all green. EVENT-01..07 covered. |
| 5 | Two connectors on conflicting properties → per-tenant authority matrix resolves winner, loser in reconciliation_conflicts, circuit breaker halts runaway connector | PARTIAL | **Circuit breaker half VERIFIED:** `CircuitBreakerTest` (5/5) + `CircuitBreakerIT` (1/1, 10.19 s, Testcontainers AGE + Flyway V1..V10) green. RULE-07 gated. **Authority matrix + conflict register half FAILS:** `SourceAuthorityIT`, `ConflictRegisterIT`, `EchoLoopSuppressionIT`, `RuleRegistrationIT` are all 27-line @Disabled placeholders. Production code exists per W3-T2 SUMMARY commit 42fbd0a but has NO automated integration gate. See gaps #1, #3, #4. |

**Score:** 3 VERIFIED / 2 PARTIAL = **4 of 5 SC effectively demonstrated, 1 SC (SC-5) partially blocked.**

## Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `fabric-core/.../graph/internal/GraphServiceImpl.java` | Write funnel | VERIFIED | Compiles; W3 integration adds CircuitBreaker + RuleEngine + Shacl; one deferred TODO(W3) for Spring Security auth |
| `fabric-core/.../graph/GraphSession` / `internal/*` | Only Cypher surface | VERIFIED | `RawCypherBanTest` green |
| `fabric-core/.../schema/**` | Schema Registry | VERIFIED | CRUD + version + alias + cache tests green |
| `fabric-core/.../validation/ShaclValidator` + ShapeCache + Compiler + Filter | SHACL | VERIFIED | All 4 unit/IT tests green |
| `fabric-core/.../events/**` (EventLog, Outbox, poller, partition) | Event spine | VERIFIED | 9 ITs green |
| `fabric-core/.../circuit/CircuitBreakerPort` + Exception | SPI | VERIFIED | Present, no-op fallback in GraphCoreConfig |
| `fabric-rules/.../circuit/WriteRateCircuitBreaker` | Impl | VERIFIED | 5 unit + 1 IT green; AtomicLongArray + Micrometer counter + startup grace |
| `fabric-rules/.../` (ChainExecutor, Rule, RuleOutcome, SourceAuthorityMatrix, AuthorityReconciliationRule, EchoLoopSuppressionRule, RuleRepository, RuleAdminController, ReconciliationConflictsRepository) | Rule engine | ORPHANED-IN-TEST | Source exists per W3-T2 commit; unit tests (ChainExecutorTest, RuleInterfaceTest, RuleOutcomeTest, RuleEngineHygieneTest) green; **integration tests are all @Disabled placeholders** |
| Flyway V1..V10 | Migrations | VERIFIED | V1 enable_age, V2 graph_events, V3 graph_outbox, V4 schema_registry, V5 schema_versioning_and_aliases, V6 source_authority, V7 reconciliation_conflicts, V8 connector_limits_and_dlq, V9 reconciliation_rules, V10 shedlock — all present in fabric-app/src/main/resources/db/migration and mirrored into test resources |
| `fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java` | CORE-02 gate | VERIFIED | 2/2 green (deferred from Phase 0 D-15, NOW RESOLVED) |
| `docker-compose.yml` / `AgePostgresContainer.java` / `README.md` | AGE digest pinning | VERIFIED | sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed present in docker-compose.yml, README.md, fabric-core + fabric-rules AgePostgresContainer.java |

## Requirements Coverage

| Requirement | Status | Evidence |
|-------------|--------|----------|
| CORE-01 single-TX funnel | PASS | GraphServiceImpl.apply @Transactional; GraphServiceApplyIT |
| CORE-02 Cypher only in graph.internal | PASS | RawCypherBanTest (Phase 0 D-15 resolved) |
| CORE-03 TenantContext mandatory parameter + fuzz | PASS | TenantBypassPropertyIT 7×1000 scenarios green |
| CORE-04 node CRUD + tombstone + model_id | PASS | NodeLifecycleIT, TombstoneSemanticsIT |
| CORE-05 edge CRUD + model_id | PASS | EdgeLifecycleIT |
| CORE-06 system properties | PASS | SystemPropertiesIT |
| CORE-07 tombstones default | PASS | TombstoneSemanticsIT |
| CORE-08 Tessera-owned timestamps | PASS | TimestampOwnershipTest |
| SCHEMA-01 node type CRUD | PASS | SchemaNodeTypeCrudIT |
| SCHEMA-02 property CRUD | PASS | SchemaPropertyCrudIT |
| SCHEMA-03 edge type CRUD | PASS | SchemaEdgeTypeCrudIT |
| SCHEMA-04 versioning | PASS | SchemaVersioningReplayIT |
| SCHEMA-05 aliases | PASS | SchemaAliasIT |
| SCHEMA-06 Caffeine cache + invalidation | PASS | SchemaCacheInvalidationTest |
| SCHEMA-07 SHACL consumes registry | PASS | SchemaToShaclIT |
| SCHEMA-08 breaking-change rejection | PASS | SchemaBreakingChangeIT |
| VALID-01 SHACL in-TX | PASS | ShaclPreCommitIT |
| VALID-02 shape cache | PASS | ShapeCacheTest |
| VALID-03 targeted single-subject validation | PASS | TargetedValidationTest |
| VALID-04 tenant-filtered report | PASS | ValidationReportFilterTest |
| VALID-05 business-rule REJECT | **FAIL** | BusinessRuleRejectIT @Disabled placeholder — no automated gate |
| EVENT-01 append-only partitioned | PASS | EventLogSchemaIT, PartitionMaintenanceIT |
| EVENT-02 per-tenant SEQUENCE | PASS | PerTenantSequenceIT |
| EVENT-03 full event payload | PASS | EventProvenanceIT, EventProvenanceSmokeIT |
| EVENT-04 outbox same TX | PASS | OutboxTransactionalIT |
| EVENT-05 in-process poller | PASS | OutboxPollerIT |
| EVENT-06 temporal replay | PASS | TemporalReplayIT |
| EVENT-07 audit history | PASS | AuditHistoryIT |
| RULE-01 chain executor priority DESC | PASS | ChainExecutorTest |
| RULE-02 Rule interface | PASS | RuleInterfaceTest |
| RULE-03 RuleOutcome sealed + no FLAG_FOR_REVIEW | PASS | RuleOutcomeTest + RuleEngineHygieneTest |
| RULE-04 hybrid Java+DB activation reload | **FAIL** | RuleRegistrationIT @Disabled placeholder |
| RULE-05 per-tenant source authority matrix | **FAIL** | SourceAuthorityIT @Disabled placeholder |
| RULE-06 reconciliation_conflicts register | **FAIL** | ConflictRegisterIT @Disabled placeholder |
| RULE-07 write-rate circuit breaker | PASS | CircuitBreakerTest (5/5) + CircuitBreakerIT (1/1) |
| RULE-08 echo-loop suppression | **FAIL** | EchoLoopSuppressionIT @Disabled placeholder |

**Coverage: 30/35 requirements have passing automated gates; 5 requirements (VALID-05, RULE-04, RULE-05, RULE-06, RULE-08) have production source per W3-T2 SUMMARY but NO passing integration test.**

## @Disabled Test Forensic

Adjudication of Known Deviation #1 (the W3 Task 3 summary explicitly flagged these for the verifier):

All 5 files are identical 27-line shells:

```java
@Disabled("Wave 3 — filled by plan 01-W3-02")
class BusinessRuleRejectIT {
    @Test
    void placeholder() {}
}
```

Failsafe XML confirms: `tests="1" errors="0" skipped="1" failures="0"` for each.

**Verdict:** These are NOT hidden implementations — they are genuinely empty placeholders. The W3 Task 2 executor did NOT write the integration tests before context timeout; only the production classes + unit tests for the rule engine primitives landed. This is a real gap.

**Impact on goal achievement:** ROADMAP SC-5 (two-connector authority matrix winner + loser in reconciliation_conflicts) is the phase's headline reconciliation contract. Production code exists; the claim that it works is unverifiable without these tests. A reviewer (or a future refactor) has no regression guard for VALID-05, RULE-04, RULE-05, RULE-06, RULE-08.

## Anti-Patterns Scanned

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| GraphServiceImpl.java | 95 | `// authorize(mutation) -- TODO(W3): Spring Security integration` | Info | Accepted deviation; Phase 2 concern |

No other TODO/FIXME/stub markers found in fabric-core main sources. The 5 @Disabled placeholders in fabric-rules test sources are captured as explicit gaps above.

## Gaps Summary

Phase 1 production code is complete and the full Maven reactor builds green. The phase is architecturally done — the write funnel, schema registry, SHACL, event log/outbox, and circuit breaker are all wired and covered.

However, **5 of 35 requirements lack the integration test that proves them**. These are exactly the 5 tests flagged in the W3 Task 3 SUMMARY as "filled by plan 01-W3-02" but never populated. Without these, ROADMAP SC-5 ("two connectors on conflicting properties → authority matrix + conflict register") is unverifiable end-to-end, and RULE-04, RULE-05, RULE-06, RULE-08, VALID-05 all sit as production-code-only with no regression gate.

**Recommended closure path:** One focused plan (`/gsd-plan-phase 1 --gaps`) that enables and fills all 5 disabled tests. The production classes already exist (`SourceAuthorityMatrix`, `AuthorityReconciliationRule`, `EchoLoopSuppressionRule`, `RuleRepository`, `RuleAdminController`, `ReconciliationConflictsRepository`, `BusinessRule` chain wiring) — the work is test-authoring against `AgePostgresContainer` + `RulesTestHarness` + `PipelineFixture`, not new implementation.

All other known deviations are adjudicated as ACCEPTED (see frontmatter `known_deviations_adjudicated`).

---

## GAPS FOUND

_Verified: 2026-04-13T19:08:00Z_
_Verifier: Claude (gsd-verifier)_
