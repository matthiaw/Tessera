---
phase: 1
slug: graph-core-schema-registry-validation-rules
status: approved
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-14
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `01-RESEARCH.md` §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (via `spring-boot-starter-test` 3.5.13) + AssertJ + **jqwik 1.9.2** (property-based, NEW in Phase 1) + Testcontainers 1.20.4 + ArchUnit 1.3 |
| **Config file** | `pom.xml` per module (Surefire + Failsafe configured in parent POM pluginManagement) |
| **Quick run command** | `./mvnw -pl fabric-core,fabric-rules -am test` |
| **Full suite command** | `./mvnw -B verify` |
| **Integration suffix** | `*IT.java` (Failsafe) |
| **Unit suffix** | `*Test.java` (Surefire) |
| **Estimated runtime** | ~5 min full verify (includes ~35s jqwik tenant-bypass fuzz, multi-container Testcontainers reuse) |

---

## Sampling Rate

- **After every task commit:** `./mvnw -pl <touched-module> test` (Surefire unit tests only, <30s)
- **After every plan wave:** `./mvnw -B verify` (Spotless + license + Surefire + Failsafe IT + ArchUnit + JaCoCo)
- **Before `/gsd-verify-work`:** Full `./mvnw -B verify` green PLUS `./mvnw -pl fabric-core -Pjmh -Djmh.dataset=100000 verify` to confirm write-pipeline p95 stays within Phase 0 baseline × 2 (SHACL p95 < 2ms, full write pipeline p95 < 11ms target)
- **Max feedback latency:** ~30s for quick; ~5 min for wave-gate

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-W1-02 | 01-W1 | W1 | CORE-01 | T-01-01 | Single TX: auth→rules→SHACL→Cypher→events→outbox atomic; rollback rolls all | integration | `./mvnw -pl fabric-core -Dit.test=GraphServiceApplyIT verify` | ❌ W0 | ⬜ pending |
| 01-W0-03 | 01-W0 | W0 | CORE-02 | T-01-02 | ArchUnit ban on raw Cypher outside `dev.tessera.core.graph.internal` | unit (ArchUnit) | `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` | ❌ W0 | ⬜ pending |
| 01-W1-03 | 01-W1 | W1 | CORE-03 | T-01-03 | Tenant bypass impossible — jqwik fuzz, 1000+ tries × 7 ops | integration (jqwik + TC) | `./mvnw -pl fabric-core -Dit.test=TenantBypassPropertyIT verify` | ❌ W0 | ⬜ pending |
| 01-W1-01 | 01-W1 | W1 | CORE-04 | — | Node CRUD through GraphService | integration | `./mvnw -pl fabric-core -Dit.test=NodeLifecycleIT verify` | ❌ W0 | ⬜ pending |
| 01-W1-01 | 01-W1 | W1 | CORE-05 | — | Edge CRUD through GraphService | integration | `./mvnw -pl fabric-core -Dit.test=EdgeLifecycleIT verify` | ❌ W0 | ⬜ pending |
| 01-W1-01 | 01-W1 | W1 | CORE-06 | — | System properties on every node/edge (`uuid`, `model_id`, `_type`, `_created_at`, `_updated_at`, `_created_by`, `_source`, `_source_id`) | integration | `./mvnw -pl fabric-core -Dit.test=SystemPropertiesIT verify` | ❌ W0 | ⬜ pending |
| 01-W1-02 | 01-W1 | W1 | CORE-07 | — | Tombstone-default delete; hard-delete explicit opt-in only | integration | `./mvnw -pl fabric-core -Dit.test=TombstoneSemanticsIT verify` | ❌ W0 | ⬜ pending |
| 01-W1-02 | 01-W1 | W1 | CORE-08 | T-01-04 | Tessera-owned timestamps; payload-supplied timestamps ignored | unit | `./mvnw -pl fabric-core -Dtest=TimestampOwnershipTest test` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-01 | — | `schema_node_types` CRUD | integration | `./mvnw -pl fabric-core -Dit.test=SchemaNodeTypeCrudIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-02 | — | `schema_properties` CRUD | integration | `./mvnw -pl fabric-core -Dit.test=SchemaPropertyCrudIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-03 | — | `schema_edge_types` CRUD | integration | `./mvnw -pl fabric-core -Dit.test=SchemaEdgeTypeCrudIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-04 | — | Versioned schema; old versions queryable from `schema_version` snapshot rows | integration | `./mvnw -pl fabric-core -Dit.test=SchemaVersioningReplayIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-05 | — | Property aliases preserve old-slug reads via `schema_property_aliases` | integration | `./mvnw -pl fabric-core -Dit.test=SchemaAliasIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-06 | — | Caffeine schema descriptor cache invalidates on change | unit | `./mvnw -pl fabric-core -Dtest=SchemaCacheInvalidationTest test` | ❌ W0 | ⬜ pending |
| 01-W3-01 | 01-W3 | W2 | SCHEMA-07 | — | Schema registry is single source of truth consumed by SHACL | integration | `./mvnw -pl fabric-core -Dit.test=SchemaToShaclIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-01 | 01-W2 | W2 | SCHEMA-08 | — | Breaking changes rejected unless explicitly forced | integration | `./mvnw -pl fabric-core -Dit.test=SchemaBreakingChangeIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-01 | 01-W3 | W3 | VALID-01 | T-01-05 | SHACL runs synchronously in write TX, rejects invalid mutations | integration | `./mvnw -pl fabric-core -Dit.test=ShaclPreCommitIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-01 | 01-W3 | W3 | VALID-02 | — | Compiled shapes cached per `(model_id, schema_version, type_slug)` | unit | `./mvnw -pl fabric-core -Dtest=ShapeCacheTest test` | ❌ W0 | ⬜ pending |
| 01-W3-01 | 01-W3 | W3 | VALID-03 | — | Targeted validation (single-node in-memory RDF, not full graph) | unit | `./mvnw -pl fabric-core -Dtest=TargetedValidationTest test` | ❌ W0 | ⬜ pending |
| 01-W3-01 | 01-W3 | W3 | VALID-04 | T-01-06 | `ValidationReport` tenant-filtered before consumer/logs | unit | `./mvnw -pl fabric-core -Dtest=ValidationReportFilterTest test` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | VALID-05 | — | Business-rule REJECT outcome from rule engine blocks commit | integration | `./mvnw -pl fabric-rules -Dit.test=BusinessRuleRejectIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-02 | 01-W2 | W2 | EVENT-01 | — | `graph_events` append-only, partitioned, indexed on `(model_id, sequence_nr)` | integration | `./mvnw -pl fabric-core -Dit.test=EventLogSchemaIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-02 | 01-W2 | W2 | EVENT-02 | — | `sequence_nr` from per-tenant Postgres SEQUENCE (CACHE 50), never MAX()+1 | integration + unit | `./mvnw -pl fabric-core -Dit.test=PerTenantSequenceIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-02 | 01-W2 | W2 | EVENT-03 | — | Every mutation emits event with full provenance (source_type/id/system/confidence/extractor_version/llm_model_id/origin_connector_id/origin_change_id) AND both `payload` + `delta` per ADR-7/revision | integration | `./mvnw -pl fabric-core -Dit.test=EventProvenanceIT verify` | ❌ W0 | ⬜ pending |
| 01-W1-02 | 01-W1 | W1 | EVENT-03 (smoke) | — | Wave-1 smoke IT: single write persists `origin_connector_id`, `origin_change_id`, and non-null `delta` | integration | `./mvnw -pl fabric-core -Dit.test=EventProvenanceSmokeIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-03 | 01-W2 | W2 | EVENT-04 | T-01-07 | `graph_outbox` written in same TX as event+Cypher; rollback injection test | integration | `./mvnw -pl fabric-core -Dit.test=OutboxTransactionalIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-03 | 01-W2 | W2 | EVENT-05 | — | In-process outbox poller via `@Scheduled(fixedDelay=500)` + ShedLock + `FOR UPDATE SKIP LOCKED`; publishes via `ApplicationEventPublisher` | integration | `./mvnw -pl fabric-core -Dit.test=OutboxPollerIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-02 | 01-W2 | W2 | EVENT-06 | — | Temporal replay: `get_state_at(T)` reconstructs node state from events | integration | `./mvnw -pl fabric-core -Dit.test=TemporalReplayIT verify` | ❌ W0 | ⬜ pending |
| 01-W2-02 | 01-W2 | W2 | EVENT-07 | — | Full mutation history retrievable per node with origin attribution | integration | `./mvnw -pl fabric-core -Dit.test=AuditHistoryIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-01 | — | Chain-of-responsibility executor, priority-ordered, short-circuit on REJECT | unit | `./mvnw -pl fabric-rules -Dtest=ChainExecutorTest test` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-02 | — | `Rule` interface semantics (id, chain, priority, applies, evaluate) | unit | `./mvnw -pl fabric-rules -Dtest=RuleInterfaceTest test` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-03 | — | All 6 rule outcomes: COMMIT / REJECT / MERGE / OVERRIDE / ADD / ROUTE | unit | `./mvnw -pl fabric-rules -Dtest=RuleOutcomeTest test` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-04 | — | Rules registered via Spring DI per chain; per-tenant activation + priority_override from `reconciliation_rules` table (ADR-7 §RULE-04); `POST /admin/rules/reload/{modelId}` invalidates Caffeine cache | integration | `./mvnw -pl fabric-rules -Dit.test=RuleRegistrationIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-05 | — | Per-tenant × per-type × per-property source authority matrix (`source_authority` table, Caffeine-cached) | integration | `./mvnw -pl fabric-rules -Dit.test=SourceAuthorityIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-06 | — | `reconciliation_conflicts` table writes on contested property, queryable per tenant/entity/source/property | integration | `./mvnw -pl fabric-rules -Dit.test=ConflictRegisterIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-03 | 01-W3 | W3 | RULE-07 | T-01-08 | Write-amplification circuit breaker trips at threshold; pause connector + `connector_dlq` + Micrometer metric | unit + integration | `./mvnw -pl fabric-rules -Dtest=CircuitBreakerTest test` + `-Dit.test=CircuitBreakerIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-02 | 01-W3 | W3 | RULE-08 | — | Every event tracks `origin_connector_id` + `origin_change_id`; echo-loop suppression | integration | `./mvnw -pl fabric-rules -Dit.test=EchoLoopSuppressionIT verify` | ❌ W0 | ⬜ pending |
| 01-W3-01 | 01-W3 | W1 | Perf | — | SHACL validation p95 < 2 ms (cached shape, single-node delta) | JMH bench | `./mvnw -pl fabric-core -Pjmh -Djmh.bench=ShaclValidationBench verify` | ❌ W0 | ⬜ pending |
| 01-W1-02 | 01-W1 | W1 | Perf (baseline) | — | `WritePipelineBench` BASELINE (no SHACL, no rules) p95 < 3 ms — warning-only, informs Wave 3 gate | JMH bench | `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench verify` | ❌ W0 | ⬜ pending |
| 01-W3-03 | 01-W3 | W3 | Perf (full) | — | `WritePipelineBench` FULL-PIPELINE (SHACL + rules) p95 < 11 ms — build-fail gate | JMH bench | `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench -Djmh.dataset=100000 verify` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*Task IDs to be assigned by gsd-planner during plan creation.*
*Threat refs are placeholders — planner fills in from each plan's `<threat_model>` STRIDE register.*

---

## Wave 0 Requirements

All Phase 1 test infrastructure is new. Wave 0 tasks must seed:

- [ ] `pom.xml` (parent) — add `net.jqwik:jqwik-bom:1.9.2` under `dependencyManagement`
- [ ] `fabric-core/pom.xml` — add `net.jqwik:jqwik` test dependency
- [ ] `fabric-rules/pom.xml` — add `net.jqwik:jqwik` test dependency
- [ ] `fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` — record with full provenance fields (D-A1)
- [ ] `fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java` — single write entrypoint interface
- [ ] `fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` — only class allowed raw Cypher
- [ ] `fabric-core/src/test/java/dev/tessera/core/support/SchemaFixtures.java` — reusable builder for test schemas
- [ ] `fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java` — jqwik `Arbitrary<GraphMutation>` provider
- [ ] `fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java` — ArchUnit raw-Cypher ban (CORE-02, deferred from Phase 0 D-15)
- [ ] `fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` — jqwik harness for CORE-03
- [ ] `fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java` — SHACL per-mutation p95
- [ ] `fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java` — full write pipeline p95
- [ ] All integration test files listed in the Requirements → Test Map above — thin shells in Wave 0, filled by owning wave
- [ ] Flyway migrations for: `graph_events`, `graph_outbox`, `schema_node_types`, `schema_properties`, `schema_edge_types`, `schema_change_event`, `schema_version`, `schema_property_aliases`, `schema_edge_type_aliases`, `source_authority`, `reconciliation_conflicts`, `connector_limits`, `connector_dlq`, `reconciliation_rules` (ADR-7 §RULE-04)

**Framework install:** jqwik 1.9.2 is the only new test dependency. JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit all pulled transitively from Phase 0 parent POM.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Spring `@TransactionalEventListener(phase=AFTER_COMMIT)` fires only after Postgres TX commits | EVENT-05 | Timing verification — automated rollback test proves the opposite (that rollback does NOT fire), manual check confirms the positive path under real load | Seed 1000 mutations on a local run, inject a rollback on every 10th, verify Spring event listener fires exactly 900 times |
| Real Jena SHACL p95 under Tessera shape + Caffeine cache hit rate | VALID-01, VALID-02 | Perf envelope needs to be measured on realistic schema not synthetic | Wave 1 JMH benches run automated; human reviews trend over first week of nightly CI to confirm ShaclValidationBench p95 stays < 2 ms |
| jqwik shrinking produces human-readable minimized failures | CORE-03 | jqwik shrinking is non-deterministic; human review of first fuzz failure needed to confirm the reproducer is actionable | When `TenantBypassPropertyIT` fails, inspect the shrunk counterexample and confirm it is usable for adding an `@Example` regression test |

---

## Validation Sign-Off

- [ ] All 35 phase requirements + 2 perf budgets have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (quick) / < 5 min (wave-gate)
- [ ] `nyquist_compliant: true` set in frontmatter after planner assigns task IDs and Wave 0 files land

**Approval:** pending
