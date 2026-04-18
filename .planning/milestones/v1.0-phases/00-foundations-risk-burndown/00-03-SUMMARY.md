---
phase: 00-foundations-risk-burndown
plan: 03
subsystem: foundations
tags: [flyway, hikari, age, testcontainers, archunit, tenant-context]
requires: [00-01, 00-02]
provides:
  - TenantContext record (dev.tessera.core.tenant.TenantContext)
  - AgePostgresContainer Testcontainers helper (digest-pinned)
  - Flyway V1 baseline enabling AGE extension + tessera_main graph
  - HikariCP connection-init-sql per-connection AGE priming
  - FlywayItApplication minimal test Spring Boot config in fabric-core
  - ModuleDependencyTest (ArchUnit, D-15, 5 rules)
  - ImagePinningTest (D-09, 4 assertions across 3 sites)
affects:
  - fabric-core (new main/test sources + test resources)
  - fabric-app (main entry point, application.yml, Flyway migration, ArchUnit tests)
  - parent pom.xml (failsafe activated in build.plugins)
  - .mvn/license-header*.txt (split plain/Javadoc variants)
tech-stack:
  added:
    - Spring Boot 3.5.13 (fabric-app entry point + fabric-core test context)
    - Flyway 10.x migration file V1__enable_age.sql
    - Testcontainers 1.20.4 AGE helper
    - ArchUnit 1.3.0 module-direction rules
  patterns:
    - D-10 split: Flyway owns one-time DDL, HikariCP owns per-session priming
    - D-09: one digest → three enforcement sites; CI fails on drift
    - D-15: module-direction only in Phase 0; raw-Cypher ban deferred
    - D-16: TenantContext as record, explicit parameter, never ThreadLocal
    - MIN-1: agtype parameter binding via text cast — instrumented test
key-files:
  created:
    - fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
    - fabric-core/src/test/java/dev/tessera/core/tenant/TenantContextTest.java
    - fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java
    - fabric-core/src/test/java/dev/tessera/core/support/FlywayItApplication.java
    - fabric-core/src/test/java/dev/tessera/core/age/AgePostgresContainerIT.java
    - fabric-core/src/test/java/dev/tessera/core/age/AgtypeParameterIT.java
    - fabric-core/src/test/java/dev/tessera/core/flyway/FlywayBaselineIT.java
    - fabric-core/src/test/java/dev/tessera/core/hikari/HikariInitSqlIT.java
    - fabric-core/src/test/resources/application-flyway-it.yml
    - fabric-core/src/test/resources/db/migration/V1__enable_age.sql
    - fabric-app/src/main/java/dev/tessera/app/TesseraApplication.java
    - fabric-app/src/main/resources/application.yml
    - fabric-app/src/main/resources/db/migration/V1__enable_age.sql
    - fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java
    - fabric-app/src/test/java/dev/tessera/arch/ImagePinningTest.java
    - .mvn/license-header-java.txt
  modified:
    - pom.xml (spotless licenseHeader points at license-header-java.txt;
      failsafe activated in build.plugins)
    - .mvn/license-header.txt (reverted to plain text for mycila)
decisions:
  - Split license-header templates: mycila license-maven-plugin consumes plain
    text (it wraps automatically), Spotless consumes the /* */ wrapped variant
    (it injects verbatim). Two files, one logical header.
  - Duplicate V1__enable_age.sql into fabric-core test resources so ITs need
    not pull fabric-app onto the test classpath. Plan 00-04 adds a text-diff
    guard. File carries a MIRROR OF comment.
  - Testcontainers reuse strategy: `withReuse(true)` set on the helper. When
    the environment does not enable reuse (no ~/.testcontainers.properties)
    Testcontainers logs a WARN and falls back to fresh containers per class —
    correct behavior for CI, opt-in speed-up for local dev.
  - ArchUnit forward-defending rules use `.allowEmptyShould(true)` so empty
    Phase-0 modules do not fail the check. They remain enforced as soon as
    classes land in rules/projections/connectors/app.
  - `maven-failsafe-plugin` activated in parent `build.plugins` (was only in
    pluginManagement). Required for `./mvnw verify` to actually run the 4
    integration tests this plan ships — Rule 2 auto-add.
metrics:
  duration: ~35 minutes wall clock
  completed: 2026-04-14
---

# Phase 00 Plan 03: AGE/Flyway/Hikari wiring + TenantContext + module guards Summary

**One-liner:** Flyway V1 enables AGE, HikariCP primes every pooled connection, TenantContext lands as a null-rejecting record, and ArchUnit + an image-pinning test hard-fail on module-direction or digest drift.

## What shipped

- **TenantContext** (`dev.tessera.core.tenant.TenantContext`) — record with `UUID modelId`, compact constructor null-rejects. No ThreadLocal, no static `current()`. The Phase-1 entry point.
- **Flyway V1 migration** (`V1__enable_age.sql`) — `CREATE EXTENSION IF NOT EXISTS age; LOAD 'age'; SET search_path = ag_catalog, "$user", public; SELECT create_graph('tessera_main');`. Lives in `fabric-app/src/main/resources/db/migration/` with a mirrored copy under `fabric-core/src/test/resources/db/migration/` for ITs.
- **HikariCP init SQL** (`fabric-app/src/main/resources/application.yml`) — `spring.datasource.hikari.connection-init-sql: "LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;"`. Virtual threads enabled. D-10 split-responsibility realized.
- **TesseraApplication** — `@SpringBootApplication` main class.
- **AgePostgresContainer** — Testcontainers helper pinned to digest `apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed`. Byte-identical to `docker-compose.yml` and `README.md`.
- **Four integration tests** (all green):
  1. `AgePostgresContainerIT` — boots the digest-pinned image, loads AGE, runs trivial Cypher.
  2. `AgtypeParameterIT` — MIN-1 instrumentation: agtype parameter binding via text cast round-trip.
  3. `FlywayBaselineIT` — Spring Boot context against Testcontainers AGE; asserts Flyway has applied V1 and the `tessera_main` graph exists in `ag_catalog.ag_graph`.
  4. `HikariInitSqlIT` — obtains two independent pooled connections, asserts both have `ag_catalog` at the front of `SHOW search_path`; runs Cypher with no manual `LOAD 'age'`.
- **ArchUnit ModuleDependencyTest** — five rules verbatim-named per VALIDATION.md:
  - `fabric_core_should_not_depend_on_others`
  - `fabric_rules_should_only_depend_on_core`
  - `fabric_projections_should_not_depend_on_connectors_or_app`
  - `fabric_connectors_should_not_depend_on_projections_or_app`
  - `modules_should_be_free_of_cycles`
- **ImagePinningTest** — four plain JUnit assertions: compose uses digest, compose has no floating tag, helper digest equals compose digest, README digest equals compose digest.

## AGE image digest used

```
apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed
```

Resolved from `apache/age:release_PG16_1.6.0` (upstream no longer publishes `PG16_latest`). Byte-equal across `docker-compose.yml`, `AgePostgresContainer.java`, `README.md`.

## Testcontainers reuse strategy

`.withReuse(true)` is set on the helper. This is opt-in on the developer side (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`). When not enabled (CI default, fresh dev machines) Testcontainers logs `Reuse was requested but the environment does not support the reuse of containers` and falls back to per-class container lifecycle — this is the intended pattern, no code changes needed to toggle it.

## Commit trail

| # | Task | Commit | Notes |
| - | ---- | ------ | ----- |
| 1 | TenantContext + test + license-header split | `9cf1d93` | 3 tests green |
| 2 | Flyway V1, application.yml, TesseraApplication | `7aa4ebb` | fabric-app packages |
| 3 | AgePostgresContainer + 4 ITs + failsafe activation | `896acdc` | 5 ITs green (HikariInitSqlIT has 2 methods) |
| 4 | ArchUnit + ImagePinningTest | `c2ad65d` | 5 ArchUnit rules + 4 pinning assertions green |

## Final verification

```
./mvnw -B verify
[INFO] BUILD SUCCESS
[INFO] Total time: 01:04 min
```

Broken down:
- `fabric-core`: 3 unit tests (TenantContextTest) + 5 integration tests (AgePostgresContainerIT 1, AgtypeParameterIT 1, FlywayBaselineIT 1, HikariInitSqlIT 2) — all green
- `fabric-app`: 4 ImagePinningTest + 5 ModuleDependencyTest (ArchUnit) — all green
- Spotless, license:check, enforcer (banCircularDependencies), JaCoCo report — all green

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] License-header template conflict between Spotless and mycila**
- **Found during:** Task 1 build
- **Issue:** A single `.mvn/license-header.txt` cannot satisfy both Spotless (Palantir) which injects the header verbatim and expects valid Java comment syntax, and mycila license-maven-plugin which auto-wraps plain text in comment markers for the target file extension. First attempt wrapped the file in `/* */`, which broke mycila (it then double-wrapped). Second attempt left it plain, which broke Spotless (it injected raw non-comment text at line 1).
- **Fix:** Split into two files — `.mvn/license-header.txt` (plain text, for mycila) and `.mvn/license-header-java.txt` (wrapped, for Spotless). Parent `pom.xml` updated to point Spotless at the wrapped variant. Mycila already tracks the plain variant.
- **Files modified:** `.mvn/license-header.txt`, `.mvn/license-header-java.txt` (new), `pom.xml`
- **Commit:** `9cf1d93`

**2. [Rule 2 - Missing critical functionality] Failsafe plugin not activated in build.plugins**
- **Found during:** Task 3 verify
- **Issue:** Parent `pom.xml` declared `maven-failsafe-plugin` inside `pluginManagement` only. `./mvnw verify` therefore did not execute any `*IT.java` tests — the plan's four integration tests would have silently been skipped.
- **Fix:** Activate `maven-failsafe-plugin` in the parent `build.plugins` list so every child module runs failsafe on `verify`. Confirmed by the reactor output showing `failsafe:3.5.2:integration-test` and `failsafe:3.5.2:verify` running in `fabric-core`.
- **Files modified:** `pom.xml`
- **Commit:** `896acdc`

**3. [Rule 1 - Bug] ArchUnit `failOnEmptyShould` breaks forward-defending rules in Phase 0**
- **Found during:** Task 4 verify
- **Issue:** ArchUnit 1.3 defaults to `archRule.failOnEmptyShould = true`. Three of the five module-direction rules target packages (`dev.tessera.rules..`, `dev.tessera.projections..`, `dev.tessera.connectors..`) that are empty in Phase 0, so the rules fail with "failed to check any classes" before they can green-light.
- **Fix:** Add `.allowEmptyShould(true)` to the three forward-defending rules. The rules still enforce the ban as soon as classes land in those modules (Phase 1+). Core rule (`fabric_core_should_not_depend_on_others`) and cycle rule do have classes and remain strict.
- **Files modified:** `fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java`
- **Commit:** `c2ad65d`

No architectural decisions required — all fixes were mechanical.

## Self-Check: PASSED

- Files listed above all exist (verified by `./mvnw verify` compilation + test discovery).
- Commits `9cf1d93`, `7aa4ebb`, `896acdc`, `c2ad65d` present in `git log`.
- `./mvnw -B verify` exits 0.
- Digest `sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed` present byte-identically in `docker-compose.yml`, `AgePostgresContainer.java`, `README.md` — verified by `ImagePinningTest` passing.
