---
phase: 4
slug: sql-view-kafka-projections-hash-chained-audit
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers (AGE + Kafka) |
| **Config file** | `fabric-projections/src/test/resources/application-projection-it.yml` |
| **Quick run command** | `mvn test -pl fabric-core,fabric-projections -Dtest=SqlView*,HashChain*,Debezium* -Dspotless.check.skip=true` |
| **Full suite command** | `mvn verify -pl fabric-core,fabric-projections -Dspotless.check.skip=true` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick test command
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | SQL-01 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=SqlViewProjectionIT` | ❌ W0 | ⬜ pending |
| 04-01-02 | 01 | 1 | SQL-02 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=SqlViewSchemaChangeIT` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | KAFKA-01 | — | N/A | integration | `mvn test -pl fabric-core -Dtest=DebeziumOutboxIT` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | KAFKA-02 | — | N/A | integration | `mvn test -pl fabric-core -Dtest=DebeziumTopicRoutingIT` | ❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 2 | AUDIT-01 | — | N/A | integration | `mvn test -pl fabric-core -Dtest=HashChainAppendIT` | ❌ W0 | ⬜ pending |
| 04-03-02 | 03 | 2 | AUDIT-02 | — | N/A | integration | `mvn test -pl fabric-core -Dtest=HashChainVerifyIT` | ❌ W0 | ⬜ pending |
| 04-04-01 | 04 | 3 | KAFKA-03 | — | N/A | integration | `mvn test -pl fabric-core -Dtest=ReplicationSlotHealthIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `SqlViewProjectionIT.java` — stubs for SQL-01 view generation
- [ ] `SqlViewSchemaChangeIT.java` — stubs for SQL-02 schema-driven regeneration
- [ ] `DebeziumOutboxIT.java` — stubs for KAFKA-01 outbox CDC
- [ ] `HashChainAppendIT.java` — stubs for AUDIT-01 hash chain write
- [ ] `HashChainVerifyIT.java` — stubs for AUDIT-02 verification endpoint

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Metabase/Looker connects to SQL views | SQL-01 | Requires real BI tool connection | 1. Start Tessera + Postgres 2. Connect Metabase to Postgres 3. Verify views appear as tables 4. Run aggregate query |
| Kafka consumer reads topic events | KAFKA-01 | Requires running Kafka consumer | 1. Start full Docker Compose 2. Create entity 3. Consume from topic 4. Verify event payload |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
