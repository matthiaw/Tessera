---
phase: 0
slug: foundations-risk-burndown
status: draft
nyquist_compliant: false
wave_0_complete: true
created: 2026-04-13
---

# Phase 0 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `00-RESEARCH.md` §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (via `spring-boot-starter-test` 3.5.13) + AssertJ + Testcontainers 1.20.4 + ArchUnit 1.3 + JMH 1.37 |
| **Config file** | none at repo root — Maven Surefire + Failsafe configured in parent POM pluginManagement |
| **Quick run command** | `./mvnw -q -pl fabric-core test` |
| **Full suite command** | `./mvnw -B verify` |
| **Estimated runtime** | ~180s (full verify, excluding 1M JMH benchmark) |

---

## Sampling Rate

- **After every task commit:** `./mvnw -q -pl <touched-module> test`
- **After every plan wave:** `./mvnw -B verify` (Spotless + license + Surefire + Failsafe/IT + ArchUnit + JaCoCo)
- **Before `/gsd-verify-work`:** Full `./mvnw -B verify` green + 100k JMH benchmark green + nightly dump/restore rehearsal green + 1M benchmark baseline recorded under `.planning/benchmarks/`
- **Max feedback latency:** ~30s for quick; ~180s for wave-gate

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 00-01-02 | 00-01 | 1 | FOUND-01 | — | Modules build with strict upward deps; cycles fail | integration (enforcer+ArchUnit) | `./mvnw -B verify` | ❌ W0 | ⬜ pending |
| 00-03-04 | 00-03 | 2 | FOUND-01 | — | `fabric-core` has no intra-project deps | unit (ArchUnit) | `./mvnw -pl fabric-app test -Dtest=ModuleDependencyTest#fabric_core_should_not_depend_on_others` | ❌ W0 | ⬜ pending |
| 00-03-04 | 00-03 | 2 | FOUND-01 | — | No module cycles | unit (ArchUnit) | `./mvnw -pl fabric-app test -Dtest=ModuleDependencyTest#modules_should_be_free_of_cycles` | ❌ W0 | ⬜ pending |
| 00-02-02 | 00-02 | 1 | FOUND-02 | — | `docker-compose up -d` + `pg_isready` passes | smoke | `docker compose up -d && docker compose exec -T postgres-age pg_isready -U tessera` | ❌ W0 | ⬜ pending |
| 00-03-04 | 00-03 | 2 | FOUND-02 | — | docker-compose uses `apache/age@sha256:` digest | unit (regex) | `./mvnw -pl fabric-app test -Dtest=ImagePinningTest` | ❌ W0 | ⬜ pending |
| 00-03-03 | 00-03 | 2 | FOUND-03 | — | Flyway V1 creates `age` extension on fresh container | integration | `./mvnw -pl fabric-core verify -Dit.test=FlywayBaselineIT` | ❌ W0 | ⬜ pending |
| 00-03-03 | 00-03 | 2 | FOUND-03 | — | HikariCP `connectionInitSql` loads AGE + search_path on every pooled conn | integration | `./mvnw -pl fabric-core verify -Dit.test=HikariInitSqlIT` | ❌ W0 | ⬜ pending |
| 00-04-05 | 00-04 | 3 | FOUND-04 | — | JMH harness runs all four query shapes vs 100k dataset | benchmark | `./mvnw -pl fabric-core -Pjmh -Djmh.dataset=100000 verify` | ❌ W0 | ⬜ pending |
| 00-04-05 | 00-04 | 3 | FOUND-04 | — | JMH writes JSON result into `.planning/benchmarks/` | benchmark (CI) | CI step asserted with `test -f` | ❌ W0 | ⬜ pending |
| 00-04-02 | 00-04 | 3 | FOUND-04 | — | Seed generator deterministic (same seed → same UUID set) | unit | `./mvnw -pl fabric-core test -Dtest=SeedGeneratorTest` | ❌ W0 | ⬜ pending |
| 00-05-02 | 00-05 | 4 | FOUND-05 | — | `pg_dump`+`pg_restore` on seeded 100k passes fixed query suite | integration (shell+TC) | `./scripts/dump_restore_rehearsal.sh` | ❌ W0 | ⬜ pending |
| 00-05-02 | 00-05 | 4 | FOUND-05 | — | Fixed query suite runs before + after restore | integration | included in `dump_restore_rehearsal.sh` | ❌ W0 | ⬜ pending |
| 00-03-03 | 00-03 | 2 | FOUND-06 | — | Testcontainers boot of `apache/age` from fresh checkout | integration | `./mvnw -pl fabric-core verify -Dit.test=AgePostgresContainerIT` | ❌ W0 | ⬜ pending |
| 00-03-03 | 00-03 | 2 | FOUND-06 | — | AGE `agtype` parameter binding convention (MIN-1) verified | integration | `./mvnw -pl fabric-core verify -Dit.test=AgtypeParameterIT` | ❌ W0 | ⬜ pending |
| 00-01-01 | 00-01 | 1 | Hygiene | — | Spotless fails on unformatted code | build | `./mvnw spotless:check` | ❌ W0 | ⬜ pending |
| 00-01-01 | 00-01 | 1 | Hygiene | — | Missing Apache 2.0 header fails build | build | `./mvnw license:check` | ❌ W0 | ⬜ pending |
| 00-01-01 | 00-01 | 1 | Hygiene | — | JaCoCo report generated (no threshold) | build | `./mvnw verify` → `fabric-*/target/site/jacoco/` | ❌ W0 | ⬜ pending |
| 00-03-01 | 00-03 | 2 | D-16 | — | `TenantContext` record rejects null `modelId` | unit | `./mvnw -pl fabric-core test -Dtest=TenantContextTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*Task IDs assigned by gsd-planner. Status flips to ✅ during execution.*

---

## Wave 0 Requirements

All test infrastructure is greenfield. Wave 0 must create:

- [ ] `pom.xml` (parent) — BOM imports, plugin management (Spotless, license, JaCoCo, enforcer, compiler, surefire, failsafe)
- [ ] `fabric-core/pom.xml`, `fabric-rules/pom.xml`, `fabric-projections/pom.xml`, `fabric-connectors/pom.xml`, `fabric-app/pom.xml`
- [ ] `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- [ ] `fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java` — digest-pinned TC helper
- [ ] `fabric-core/src/test/java/dev/tessera/core/tenant/TenantContextTest.java`
- [ ] `fabric-core/src/test/java/dev/tessera/core/flyway/FlywayBaselineIT.java`
- [ ] `fabric-core/src/test/java/dev/tessera/core/hikari/HikariInitSqlIT.java`
- [ ] `fabric-core/src/test/java/dev/tessera/core/age/AgePostgresContainerIT.java`
- [ ] `fabric-core/src/test/java/dev/tessera/core/age/AgtypeParameterIT.java` — MIN-1 instrumentation
- [ ] `fabric-core/src/jmh/java/dev/tessera/core/bench/SeedGenerator.java` (+ `SeedGeneratorTest.java`)
- [ ] `fabric-core/src/jmh/java/dev/tessera/core/bench/{PointLookup,TwoHopTraversal,Aggregate,OrderedPagination}Bench.java`
- [ ] `fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java`
- [ ] `fabric-app/src/test/java/dev/tessera/arch/ImagePinningTest.java`
- [ ] `fabric-app/src/main/resources/db/migration/V1__enable_age.sql`
- [ ] `fabric-app/src/main/resources/application.yml` — HikariCP `connection-init-sql`
- [ ] `docker-compose.yml`
- [ ] `scripts/dump_restore_rehearsal.sh`, `scripts/verify_queries.sh`, `scripts/check_regression.sh`
- [ ] `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`
- [ ] `.mvn/license-header.txt`
- [ ] `LICENSE`, `NOTICE` — Apache 2.0

**Framework install:** None — JUnit 5, AssertJ, Mockito pulled transitively by `spring-boot-starter-test`. Testcontainers/ArchUnit/JMH pulled by explicit module deps.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Resolve current `apache/age:PG16_latest` sha256 digest | FOUND-02 / D-09 | Digest moves as upstream re-tags — must be captured at planning time | `docker pull apache/age:PG16_latest && docker image inspect --format '{{index .RepoDigests 0}}' apache/age:PG16_latest`, record into docker-compose, Testcontainers, README |
| Fresh-clone developer bring-up | FOUND-02 success criterion 1 | End-to-end UX check covering Docker, Flyway baseline, AGE session init | Clone repo, run documented one-command bring-up, confirm Flyway V1 applied and `SELECT * FROM ag_catalog.ag_graph;` succeeds |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (quick) / < 180s (wave-gate)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
