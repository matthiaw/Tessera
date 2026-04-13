# Phase 0: Foundations & Risk Burndown — Research

**Researched:** 2026-04-13
**Domain:** Java/Maven multi-module scaffolding; Postgres 16 + Apache AGE 1.6 bring-up; JMH benchmark harness; pg_dump/pg_restore rehearsal; GitHub Actions CI; OSS hygiene (Spotless/Palantir, license headers, JaCoCo, ArchUnit)
**Confidence:** HIGH (all decisions are locked; research is synthesis + prescription, not exploration)

## Summary

Phase 0 is entirely prescriptive. Sixteen implementation decisions (D-01..D-16) are locked in `00-CONTEXT.md`; the canonical `.planning/research/` tree already specifies versions, patterns, and pitfalls at a level of detail that removes Phase 0 decision-making. This RESEARCH.md is a consolidation: it copies the locked constraints verbatim, maps each FOUND requirement to the exact libraries/patterns/files that satisfy it, pre-lists the pitfalls the planner must instrument against, and defines a validation architecture for the Nyquist gate.

The phase has five deliverables and nothing else: (1) a Maven multi-module scaffold with Palantir Spotless / Apache-2.0 headers / JaCoCo / ArchUnit module-direction enforcement, (2) a Docker Compose bringing up `apache/age` pinned by sha256 digest, (3) a Flyway V1 baseline that runs `CREATE EXTENSION age` paired with HikariCP `connectionInitSql` for per-session `LOAD 'age'`, (4) a reusable JMH benchmark harness living in a dedicated source set under `fabric-core`, wired into GitHub Actions for 100k-on-push / 1M-nightly runs, (5) a nightly `pg_dump`/`pg_restore` rehearsal CI job using the same 100k seed dataset and a fixed query suite. The `TenantContext` record ships as a structural primitive in `fabric-core` with no consumers yet.

**Primary recommendation:** Execute exactly what `00-CONTEXT.md` specifies. The planner's freedom is tactical (POM layout, CI workflow file count, Testcontainers reuse strategy, JMH iteration counts, benchmark regression threshold). Do not relitigate locked decisions. Do not leak Phase 1 work (no graph-core code, no schema registry, no raw-Cypher ArchUnit rule, no SpotBugs, no JaCoCo threshold, no Vault).

## User Constraints (from CONTEXT.md)

### Locked Decisions

**Benchmark Harness (FOUND-04)**
- **D-01:** Driver is **JMH microbenchmarks** living in a dedicated benchmark source set under `fabric-core` (tests-adjacent, not a new module). Same JMH infrastructure will be reused in Phase 1 for SHACL validation perf.
- **D-02:** Dataset is built by a **deterministic seed generator** (Java, fixed RNG seed) producing both the 100k and 1M node datasets with graph-shaped edges (few edges per node, multi-label sample). No Git LFS fixture dump, no Flyway-loop data generation.
- **D-03:** Query shapes covered: point-lookup by uuid, 2-hop traversal, aggregate (count/group-by-label), ordered pagination (cursor-style). These are the four shapes FOUND-04 requires.
- **D-04:** CI execution model: **100k runs on every push, 1M runs nightly.** Results are written as JSON to `.planning/benchmarks/{iso-timestamp}-{dataset}.json`. Regression threshold (planner-set, tentatively +25% p95 vs last green nightly) hard-fails the nightly build.

**Dump/Restore Rehearsal (FOUND-05)**
- **D-05:** Seed data **reuses the 100k benchmark dataset**. One generator feeds both the benchmark harness and the rehearsal.
- **D-06:** Verification is a **fixed query suite + row-count diff** — point-lookup, traversal, aggregate, count-by-label before `pg_dump`; same suite after `pg_restore` on a fresh container; any divergence hard-fails. No whole-graph hash.
- **D-07:** Cadence: **nightly CI job, hard-fail on mismatch.**

**Docker Compose & Image Pinning (FOUND-02, FOUND-03, FOUND-06)**
- **D-08:** **Single `docker-compose.yml` with PostgreSQL+AGE only.** Vault deferred to Phase 2.
- **D-09:** AGE image is **upstream `apache/age` pinned by sha256 digest** (not by the `PG16_latest` moving tag). Digest appears in (a) `docker-compose.yml`, (b) Testcontainers wiring, (c) README snippet. One logical value, three enforcement sites. Self-built image rejected.
- **D-10:** AGE session init split:
  - **Flyway V1 baseline** runs `CREATE EXTENSION IF NOT EXISTS age` (one-time DDL).
  - **HikariCP `connectionInitSql`** (`LOAD 'age'; SET search_path = ag_catalog, "$user", public;`) owns per-session priming for every pooled connection, including Flyway's own.
  - Init SQL mounted into the container image is rejected.

**CI, Build Hygiene, Tenancy Scope (FOUND-01)**
- **D-11:** CI platform is **GitHub Actions**, public repo workflows. Self-hosted IONOS runners deferred.
- **D-12:** Hygiene plugins landing in Phase 0 (all **hard-fail**):
  - `spotless-maven-plugin` — **Palantir Java Format**
  - `license-maven-plugin` (mycila) — Apache 2.0 headers on every `.java`
  - `jacoco-maven-plugin` 0.8.12+ — coverage report only (no threshold yet)
  - **SpotBugs deferred to Phase 1.**
- **D-13:** Maven enforcer bans cycles and enforces Maven ≥ 3.9 + Java 21 toolchain. Maven Wrapper (`mvnw`) committed.
- **D-14:** Multi-module layout is exactly the five modules: `fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`, under a parent POM with a dependency-management BOM. Dependency direction strictly upward from `fabric-core`.
- **D-15:** ArchUnit test in Phase 0 covers **module dependency direction only** (upward-only, no cycles, `fabric-connectors` does not depend on `fabric-projections`, etc.). **Raw-Cypher ban (CORE-02) deferred to Phase 1.**
- **D-16:** `TenantContext` **ships in Phase 0** as a structural primitive in `fabric-core` — a record with `model_id` (UUID) plus helpers, designed to be a mandatory method parameter (never a `ThreadLocal`). No consumers yet.

### Claude's Discretion

The planner has flexibility on:
- Exact Maven parent POM shape and BOM coordinates (must import `spring-boot-dependencies` and pin Java 21 via `maven-compiler-plugin` 3.13+ with `<release>21</release>` and `-parameters`).
- Maven Surefire/Failsafe separation (unit vs `*IT.java` integration tests) — expected but not negotiated.
- JVM args for JaCoCo + JUnit 5 on Java 21 (Mockito agent setup).
- Benchmark regression threshold exact value and warmup/iteration counts — pick defaults that keep nightly CI under a reasonable wall-clock budget.
- Testcontainers reuse strategy (`TESTCONTAINERS_REUSE_ENABLE`) and whether singletons are used; optimize for local dev loop speed.
- Specific file layout under `.github/workflows/` (one workflow vs split push-vs-nightly).
- Contributor files required for OSS posture: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue templates, PR template, `NOTICE`, `LICENSE` — planner decides whether they ship with Phase 0 or trail slightly.

### Deferred Ideas (OUT OF SCOPE)

- **Vault container in dev compose** — deferred to Phase 2 (SEC-02).
- **`graph.internal` raw-Cypher ban (ArchUnit)** — deferred to Phase 1 where `GraphSession` actually exists.
- **SpotBugs** — deferred to Phase 1.
- **JaCoCo coverage threshold** — plugin lands Phase 0, threshold lands Phase 1.
- **Self-built `apache/age` image** — deferred indefinitely.
- **Postgres RLS on AGE label tables** — Phase 1 research flag.
- **ADR directory (`.planning/adr/`) as first-class docs** — planner may propose or defer.
- **Field-level encryption** — Phase 2 SEC-06 decision.
- **Graph core code, schema registry, projections, connectors, rule engine, SHACL integration** — all Phase 1+.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **FOUND-01** | Maven multi-module project (`fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`) with strict upward dependency direction enforced by Maven enforcer + ArchUnit | Standard Stack §Maven; Architecture §Module Layout; D-13, D-14, D-15 |
| **FOUND-02** | Reproducible local dev environment: Docker Compose with Postgres 16 + Apache AGE 1.6 pinned to image digest | Standard Stack §Docker; Code Examples §docker-compose; D-08, D-09 |
| **FOUND-03** | Flyway baseline migration enabling the `age` extension and `LOAD 'age'` session-init via HikariCP `connectionInitSql` | Standard Stack §Flyway/Hikari; Code Examples §Flyway V1 + Hikari config; D-10 |
| **FOUND-04** | Benchmark harness (100k / 1M nodes) covering point-lookup, 2-hop traversal, aggregate, and ordered pagination — runnable in CI | Standard Stack §JMH; Architecture §Benchmark Harness; Pitfalls §CRIT-3; D-01..D-04 |
| **FOUND-05** | `pg_dump` / `pg_restore` rehearsal on seeded AGE database runs green in CI | Pitfalls §CRIT-1; Code Examples §pg_dump recipe; D-05, D-06, D-07 |
| **FOUND-06** | Testcontainers integration test harness using `apache/age:PG16_latest` | Standard Stack §Testcontainers; Code Examples §Testcontainers-AGE recipe; D-09 |

## Standard Stack

All versions inherited from `.planning/research/STACK.md`. No version choices in Phase 0 — these are locks to implement.

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java (Corretto) | **21** (LTS) | Runtime | Locked decision; virtual threads, records, pattern matching `[CITED: research/STACK.md]` |
| Spring Boot | **3.5.13** | App framework | Current supported line; 3.4.x EOL; 4.0 still stabilizing `[CITED: research/STACK.md]` |
| PostgreSQL | **16 (16.6+)** | Primary store | AGE 1.6.0 is hard-bound to PG16 major `[CITED: research/STACK.md]` |
| Apache AGE | **1.6.0 PG16 branch** (tag `PG16/v1.6.0-rc0`) | Graph extension | Only AGE line matching PG16 `[CITED: apache/age releases]` |
| pgJDBC | **42.7.5** | DB driver | Standard `org.postgresql:postgresql`; works with AGE via search_path `[CITED: research/STACK.md]` |
| Maven | **3.9.x** | Build | Matches circlead conventions; Wrapper committed `[CITED: research/STACK.md]` |
| Maven Compiler Plugin | **3.13.0+** | Java 21 compilation | `<release>21</release>`, `-parameters` `[CITED: research/STACK.md]` |

### Supporting (Phase 0 scope only)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `spring-boot-starter-jdbc` | 3.5.13 | HikariCP + JdbcTemplate for benchmark wiring | Pulled in by `fabric-core` for benchmark harness — no GraphService yet |
| `org.flywaydb:flyway-core` | 10.x (BOM) | Migration runner | V1 baseline with `CREATE EXTENSION age` |
| `org.flywaydb:flyway-database-postgresql` | 10.x (BOM) | PG driver split | Flyway 10 split PG support into a separate module `[CITED: research/STACK.md]` |
| `org.testcontainers:testcontainers` | **1.20.4** | Base Testcontainers | Pinned in testcontainers-bom |
| `org.testcontainers:postgresql` | 1.20.4 | Postgres container | Use with `apache/age` as compatible substitute |
| `org.testcontainers:junit-jupiter` | 1.20.4 | JUnit 5 integration | Standard |
| `com.tngtech.archunit:archunit-junit5` | **1.3.x** | Module dependency direction tests | D-15 scope only: upward-only, no cycles |
| `org.openjdk.jmh:jmh-core` | **1.37** (current stable) `[ASSUMED — planner verify latest]` | Benchmark harness | D-01 |
| `org.openjdk.jmh:jmh-generator-annprocess` | 1.37 | JMH annotation processor | Required by JMH source set |
| `org.junit.jupiter:junit-jupiter` | via spring-boot-test | Unit tests | Surefire |
| `org.assertj:assertj-core` | via spring-boot-test | Assertions | Standard |

### Build Plugins (hygiene — all hard-fail per D-12)
| Plugin | Version | Purpose | Notes |
|--------|---------|---------|-------|
| `spotless-maven-plugin` | **2.44.x** `[CITED: research/STACK.md]` | Format enforcement | **Palantir Java Format** (D-12, preferred over Google for less-noisy diffs) |
| `license-maven-plugin` (com.mycila) | **4.x** (`4.5` or newest) `[ASSUMED — planner verify]` | Apache 2.0 headers on every `.java` | Required for Apache-licensed OSS repo |
| `jacoco-maven-plugin` | **0.8.12+** | Coverage report | **No threshold gate** in Phase 0 (D-12) |
| `maven-enforcer-plugin` | 3.5.x | Banned cycles, Maven 3.9+, Java 21 toolchain | D-13 |
| `maven-compiler-plugin` | 3.13.0+ | Java 21 | `<release>21</release>`, `-parameters` |
| `maven-surefire-plugin` | 3.5.x | Unit tests | Configure `<argLine>` for Mockito agent on Java 21+ |
| `maven-failsafe-plugin` | 3.5.x | Integration tests (`*IT.java`) | Runs after Surefire; enables parallel CI staging |

**Installation:** Dependencies are declared in module POMs; versions come from a `<dependencyManagement>` BOM in the parent POM. No standalone `mvn install` of third-party artifacts needed.

**Version verification:** Before writing the Standard Stack table into PLAN.md the planner should run `npm view`-equivalent checks for:
- `mvn help:describe` / Maven Central for latest `jmh-core`, `license-maven-plugin`, `spotless-maven-plugin` patch versions
- `apache/age` Docker Hub digest for the current `PG16_latest` tag (required by D-09)
Training-data versions may be months stale; the two items marked `[ASSUMED]` above are verification targets.

## Architecture Patterns

### Recommended Project Structure

```
tessera/
├── pom.xml                         # parent POM with BOM, plugin management, hygiene plugins
├── mvnw, mvnw.cmd, .mvn/wrapper/   # Maven Wrapper
├── docker-compose.yml              # postgres-age only; digest-pinned
├── README.md                       # contains the pinned AGE digest as canonical reference
├── LICENSE, NOTICE                 # Apache 2.0
├── CONTRIBUTING.md, CODE_OF_CONDUCT.md    # OSS posture (planner discretion on exact timing)
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                  # push: build + 100k bench + unit/IT
│   │   └── nightly.yml             # nightly: 1M bench + pg_dump/pg_restore rehearsal
│   ├── ISSUE_TEMPLATE/
│   └── PULL_REQUEST_TEMPLATE.md
├── .planning/
│   └── benchmarks/                 # JSON results land here (D-04)
├── fabric-core/                    # TenantContext record lives here in Phase 0
│   ├── pom.xml
│   ├── src/main/java/
│   │   └── dev/tessera/core/tenant/TenantContext.java
│   ├── src/test/java/
│   ├── src/jmh/java/               # D-01: dedicated benchmark source set
│   │   └── dev/tessera/core/bench/
│   │       ├── SeedGenerator.java           # D-02: deterministic
│   │       ├── PointLookupBench.java        # D-03: 4 shapes
│   │       ├── TwoHopTraversalBench.java
│   │       ├── AggregateBench.java
│   │       └── OrderedPaginationBench.java
│   └── src/test/resources/db/migration/
│       └── V1__enable_age.sql               # D-10 Flyway baseline
├── fabric-rules/pom.xml            # empty skeleton (dep on fabric-core only)
├── fabric-projections/pom.xml      # empty skeleton (dep on fabric-rules, fabric-core)
├── fabric-connectors/pom.xml       # empty skeleton (dep on fabric-rules, fabric-core)
├── fabric-app/                     # Spring Boot main; deps on all other modules
│   ├── pom.xml
│   └── src/main/resources/
│       └── application.yml         # HikariCP connectionInitSql (D-10)
└── scripts/
    └── dump_restore_rehearsal.sh   # used by nightly workflow (FOUND-05)
```

**Module dependency direction (enforced by Maven enforcer + ArchUnit):**

```
fabric-app       -> fabric-projections, fabric-connectors, fabric-rules, fabric-core
fabric-projections -> fabric-rules, fabric-core
fabric-connectors  -> fabric-rules, fabric-core
fabric-rules       -> fabric-core
fabric-core        -> (no internal deps)
```

### Pattern 1: Parent POM with Dependency-Management BOM
**What:** Single parent POM imports `spring-boot-dependencies`, `testcontainers-bom`, and declares custom properties for JMH / Palantir / license-plugin / JaCoCo. Child modules declare deps without versions.
**When to use:** Every module inherits from parent.
**Example:** see `.planning/research/STACK.md` §Installation for the canonical snippet; Phase 0 stops at Spring Boot + Testcontainers BOMs (Spring AI and Spring Cloud BOMs wait for Phases 2/3 when they are actually consumed).

### Pattern 2: AGE Session Init Split (D-10)
**What:** Two-layer AGE priming, each responsible for one lifecycle.
**When to use:** Always. This is the correct pattern from `.planning/research/STACK.md` §The Testcontainers-AGE recipe.

```sql
-- fabric-app/src/main/resources/db/migration/V1__enable_age.sql
CREATE EXTENSION IF NOT EXISTS age;
```

```yaml
# fabric-app/src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tessera
    username: tessera
    password: tessera
    hikari:
      connection-init-sql: "LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;"
  flyway:
    enabled: true
    locations: classpath:db/migration
```

**Why two layers:** `CREATE EXTENSION` is one-time DDL. `LOAD 'age'` and `SET search_path` are **session-local**; every pooled connection must run them, including Flyway's own connection (hence `connection-init-sql` applies to Flyway too). Init SQL baked into the container image is rejected (D-10) because it hides setup from Flyway's migration history, making it impossible to verify on a fresh checkout.

### Pattern 3: Digest-Pinned AGE Image with Three Enforcement Sites (D-09)
**What:** One sha256 digest, three files that must all reference it.

```yaml
# docker-compose.yml
services:
  postgres-age:
    image: apache/age@sha256:<PINNED_DIGEST>   # ONE canonical value
    environment:
      POSTGRES_DB: tessera
      POSTGRES_USER: tessera
      POSTGRES_PASSWORD: tessera
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tessera -d tessera"]
      interval: 5s
      timeout: 5s
      retries: 10
```

```java
// fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java
public final class AgePostgresContainer {
  public static final DockerImageName IMAGE =
      DockerImageName.parse("apache/age@sha256:<PINNED_DIGEST>")
          .asCompatibleSubstituteFor("postgres");
}
```

```markdown
<!-- README.md -->
## Environment
We pin Apache AGE to `apache/age@sha256:<PINNED_DIGEST>` (PG16 branch, v1.6.0).
Bump procedure: update docker-compose.yml, AgePostgresContainer, and this README — all three must match.
```

**Why three sites, not one-with-sourcing:** Simpler than build-time token replacement; a drift between the three is caught by the dump/restore CI job anyway (restore happens in the same pinned image). One logical value, three grep-able enforcement sites.

### Pattern 4: JMH Dedicated Source Set under fabric-core (D-01)
**What:** `src/jmh/java` source set wired via `build-helper-maven-plugin` (or JMH's own Maven archetype config), producing an executable benchmark JAR. Not a new Maven module — benchmark code lives under `fabric-core` so Phase 1 SHACL benchmarks reuse the same infrastructure.

**Why not a new module:** Avoids proliferation; the dataset generator and query builders are reused from the same module that will later own `GraphRepository`. Separate Maven profile (`-Pjmh`) builds the benchmark JAR; CI invokes it.

**Deterministic seed generator (D-02):** `java.util.Random(seedLong)` (never `SecureRandom`). Parameterized by dataset size (100_000, 1_000_000). Produces nodes with multi-label samples and few edges per node (graph-shaped, not star-shaped). **No Git LFS fixture dump, no Flyway-loop generation** — the generator runs at benchmark startup (`@Setup(Level.Trial)`) against a freshly-baselined Testcontainers DB.

### Pattern 5: TenantContext as Structural Primitive (D-16)
**What:** A record in `fabric-core`, designed to be threaded as a method parameter from Phase 1 onward. No consumers in Phase 0.

```java
// fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
package dev.tessera.core.tenant;

import java.util.UUID;

public record TenantContext(UUID modelId) {
  public TenantContext {
    if (modelId == null) throw new IllegalArgumentException("modelId is required");
  }
  public static TenantContext of(UUID modelId) { return new TenantContext(modelId); }
}
```

**Anti-pattern forbidden:** `ThreadLocal<TenantContext>` anywhere. Phase 1 will enforce this via type system (compile error on missing parameter) rather than runtime check.

### Anti-Patterns to Avoid (Phase 0 specific)

- **Writing graph-core code.** No `GraphService`, no `GraphRepository`, no `GraphSession`. Those are Phase 1.
- **Placeholder `graph.internal` package for raw-Cypher ArchUnit rule.** D-15 explicitly defers CORE-02 to Phase 1. Do not invent empty packages.
- **Container-baked AGE init SQL.** D-10 rejects it — breaks Flyway's migration history.
- **Moving tag `apache/age:PG16_latest`.** D-09 requires the sha256 digest.
- **Vault service in `docker-compose.yml`.** D-08 defers to Phase 2.
- **SpotBugs, JaCoCo threshold, self-built AGE image.** All deferred per D-12 and deferred list.
- **Liquibase, Zonky embedded Postgres, Google Java Format.** Palantir is preferred (D-12); the other two are banned in `research/STACK.md` §What NOT to Use.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Benchmark harness | Hand-rolled `System.nanoTime()` micro-timer loops | **JMH** 1.37 | JVM warmup, dead-code elimination, forking, histogram output are all JMH-standard — rolling your own means results you can't trust |
| Deterministic data generation | Copy-paste fixture file; Flyway loop generating rows in SQL | Java seed generator with fixed `Random(seed)` per D-02 | SQL Flyway-loops are slow (no JIT) and hide generation logic in migration history; copied fixtures bloat Git |
| Postgres+AGE container lifecycle in tests | `@BeforeAll` + manual `docker exec` | **Testcontainers** `PostgreSQLContainer` with AGE image substitute | Lifecycle, port mapping, readiness, network cleanup all handled |
| Module dependency direction enforcement | Code review / wiki rule | **ArchUnit 1.3** + **maven-enforcer-plugin** banned cycles | Compile-time failure beats human review; ArchUnit catches `fabric-connectors -> fabric-projections`, enforcer catches cycles |
| Java format | Checkstyle + custom rules | **Spotless** with Palantir Java Format | One config, zero argument, Palantir minimizes diff noise |
| License header enforcement | Git pre-commit hook | **license-maven-plugin (mycila)** with Apache 2.0 template | Hard-fails CI; no per-developer setup |
| Postgres major upgrade runbook | "Just run `pg_upgrade`" | `pg_dump` / `pg_restore` rehearsal — **instrumented in CI** | AGE blocks `pg_upgrade` (Pitfalls §CRIT-1); untested DR runbooks fail when needed |
| HikariCP AGE priming | Per-query `LOAD 'age'` in application code | HikariCP `connectionInitSql` at pool level | Runs once per pooled connection; Flyway's own connection gets it too |
| Coverage measurement | Custom bytecode instrumentation | **JaCoCo 0.8.12+** | 0.8.11 had Java 21 issues; 0.8.12 is known-good `[CITED: research/STACK.md]` |

**Key insight:** Phase 0's job is to make every subsequent phase cheap. Every hand-rolled utility here is a tax on every future phase. Prefer boring, well-supported libraries and put the creativity into the benchmark dataset shape and the regression threshold.

## Common Pitfalls

Phase 0 must **instrument against** (not fix) the following pitfalls — the instrumentation is the deliverable. Full text in `.planning/research/PITFALLS.md`.

### CRIT-1: Apache AGE blocks `pg_upgrade`
**What goes wrong:** `pg_upgrade` refuses to process AGE-managed tables because of `reg*` OID-referencing columns. Every Postgres major upgrade requires dump/restore.
**Why it happens:** AGE stores label metadata via `regclass`/`regnamespace`; OIDs are not stable across `pg_upgrade`.
**How to avoid in Phase 0:** FOUND-05 rehearsal exists precisely for this. Run `pg_dump --format=custom` + `pg_restore` nightly on the 100k seed, verify via the fixed query suite (D-06), hard-fail on any divergence (D-07). Runbook doc must state explicitly: "PostgreSQL major upgrades require dump/restore; `pg_upgrade` is not supported on AGE databases."
**Warning signs:** any runbook that assumes `pg_upgrade`; CI that only tests a single PG version; no timing data for dump/restore.

### CRIT-2: AGE release cadence lags Postgres
**What goes wrong:** AGE releases lag PG minors; you may need to hold a Postgres security patch because AGE hasn't validated it yet (e.g., PG 17.1 ABI change near-miss, `ResultRelInfo`).
**How to avoid in Phase 0:** Pin the exact PG minor in `docker-compose.yml` (never `:latest`). Subscribe to apache/age GitHub releases. Document in README: "Postgres minor upgrades require AGE compatibility confirmation; expected lag up to 2 weeks."

### CRIT-3: AGE Cypher aggregation cliff (~15× slower than SQL)
**What goes wrong:** Cypher `GROUP BY` / `ORDER BY` / aggregate queries are 15× slower than equivalent SQL on AGE (upstream issue #2194). Aggregations don't push down through `agtype`.
**How to avoid in Phase 0:** The benchmark harness **must** include the aggregate shape (D-03). Phase 0 does not fix the cliff — Phase 4 SQL projection does — but Phase 0 **measures** it so regressions are visible and so Phase 1 planning has real numbers to work from. Nightly 1M aggregate numbers establish the baseline.

### MIN-1: `agtype` parameter binding
**What goes wrong:** AGE does not accept `$1`-style JDBC parameter binding inside Cypher function calls; parameters must be passed as an `agtype` map.
**How to avoid in Phase 0:** Benchmark query runners must use AGE's parameter convention from day one, not standard `PreparedStatement` placeholders. Integration test this in `fabric-core` Testcontainers tests (FOUND-06) to catch the convention mismatch before Phase 1 builds `GraphSession`.

### MIN-2: No default indexes on new labels
**What goes wrong:** AGE does not auto-index new labels; first-query latency is terrible until indexes are created by hand.
**How to avoid in Phase 0:** The seed generator (D-02) must emit required indexes alongside the seed data — document which label/property combos are indexed. This captures the indexing discipline Phase 1 will formalize in the schema registry.

### Phase 0 Specific: Spotless / License Hard-Fail on Bootstrap Commit
**What goes wrong:** First commit has unformatted code / missing headers; CI red on project day 1.
**How to avoid:** Run `mvn spotless:apply` + `mvn license:format` once locally before first push. Document the workflow in `CONTRIBUTING.md`.

### Phase 0 Specific: Testcontainers Reuse Cache Staleness
**What goes wrong:** Developer enables `testcontainers.reuse.enable=true` (D discretion), schema state from a previous run leaks into the next test run.
**How to avoid:** If reuse is enabled, every integration test must run Flyway baseline + a truncation step in `@BeforeEach`. Document the tradeoff explicitly.

## Code Examples

Verified patterns drawn from `.planning/research/STACK.md` and `.planning/research/ARCHITECTURE.md`. All examples are illustrative — the planner may adjust identifiers and packaging.

### Parent POM skeleton

```xml
<!-- Source: .planning/research/STACK.md §Installation, adapted for Phase 0 scope -->
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.tessera</groupId>
  <artifactId>tessera-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.13</version>
    <relativePath/>
  </parent>

  <modules>
    <module>fabric-core</module>
    <module>fabric-rules</module>
    <module>fabric-projections</module>
    <module>fabric-connectors</module>
    <module>fabric-app</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <testcontainers.version>1.20.4</testcontainers.version>
    <archunit.version>1.3.0</archunit.version>
    <jmh.version>1.37</jmh.version>
    <palantir-java-format.version>2.50.0</palantir-java-format.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type><scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version>
          <configuration>
            <release>21</release>
            <parameters>true</parameters>
          </configuration>
        </plugin>
        <plugin>
          <groupId>com.diffplug.spotless</groupId>
          <artifactId>spotless-maven-plugin</artifactId>
          <version>2.44.0</version>
          <configuration>
            <java>
              <palantirJavaFormat>
                <version>${palantir-java-format.version}</version>
              </palantirJavaFormat>
            </java>
          </configuration>
          <executions>
            <execution>
              <goals><goal>check</goal></goals>
              <phase>verify</phase>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>com.mycila</groupId>
          <artifactId>license-maven-plugin</artifactId>
          <version>4.5</version>
          <!-- configure Apache 2.0 header template; hard-fail on missing headers -->
        </plugin>
        <plugin>
          <groupId>org.jacoco</groupId>
          <artifactId>jacoco-maven-plugin</artifactId>
          <version>0.8.12</version>
          <!-- report only; no threshold gate (D-12) -->
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-enforcer-plugin</artifactId>
          <version>3.5.0</version>
          <!-- banned cycles, require Maven 3.9+, Java 21 -->
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

### Flyway V1 + HikariCP init (D-10)

```sql
-- fabric-app/src/main/resources/db/migration/V1__enable_age.sql
-- Source: .planning/research/STACK.md §The Testcontainers-AGE recipe
CREATE EXTENSION IF NOT EXISTS age;
```

```yaml
# fabric-app/src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tessera
    username: tessera
    password: tessera
    hikari:
      connection-init-sql: "LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;"
  flyway:
    enabled: true
```

### Testcontainers-AGE wiring (FOUND-06)

```java
// Source: .planning/research/STACK.md §The Testcontainers-AGE recipe
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class AgePostgres {
  public static final DockerImageName IMAGE = DockerImageName
      .parse("apache/age@sha256:<PINNED_DIGEST>")
      .asCompatibleSubstituteFor("postgres");

  public static PostgreSQLContainer<?> newContainer() {
    return new PostgreSQLContainer<>(IMAGE)
        .withDatabaseName("tessera")
        .withUsername("tessera")
        .withPassword("tessera")
        .withUrlParam("options", "-c search_path=ag_catalog,public");
  }
}
```

### ArchUnit module direction test (D-15 scope only)

```java
// fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

class ModuleDependencyTest {

  private final JavaClasses classes =
      new ClassFileImporter().importPackages("dev.tessera");

  @Test
  void modules_should_be_free_of_cycles() {
    SlicesRuleDefinition.slices()
        .matching("dev.tessera.(*)..")
        .should().beFreeOfCycles()
        .check(classes);
  }

  @Test
  void fabric_core_should_not_depend_on_others() {
    noClasses().that().resideInAPackage("dev.tessera.core..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "dev.tessera.rules..",
            "dev.tessera.projections..",
            "dev.tessera.connectors..",
            "dev.tessera.app..")
        .check(classes);
  }

  // Similar tests for fabric-rules -> fabric-core only, etc.
  // NOTE: raw-Cypher ban (CORE-02) deferred to Phase 1 (D-15)
}
```

### JMH benchmark skeleton (D-01, D-03)

```java
// fabric-core/src/jmh/java/dev/tessera/core/bench/PointLookupBench.java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class PointLookupBench {

  @Param({"100000", "1000000"})
  public int datasetSize;

  private PostgreSQLContainer<?> container;
  private JdbcTemplate jdbc;
  private List<UUID> sampledUuids;

  @Setup(Level.Trial)
  public void setup() {
    container = AgePostgres.newContainer();
    container.start();
    // Flyway baseline, HikariCP init, seed generation
    new SeedGenerator(42L).seed(datasetSize, jdbc);
    sampledUuids = new SeedGenerator(42L).sampledUuids(1000);
  }

  @Benchmark
  public Object pointLookupByUuid(Blackhole bh) {
    UUID uuid = sampledUuids.get(ThreadLocalRandom.current().nextInt(sampledUuids.size()));
    // Cypher via AGE, using agtype parameter convention (MIN-1 mitigation)
    return jdbc.queryForList(
        "SELECT * FROM cypher('tessera_main', $$ MATCH (n {uuid: '" + uuid + "'}) RETURN n $$) AS (n agtype);");
  }
}
```

### pg_dump / pg_restore rehearsal (FOUND-05, D-05/D-06/D-07)

```bash
#!/usr/bin/env bash
# scripts/dump_restore_rehearsal.sh
set -euo pipefail

AGE_IMAGE="apache/age@sha256:<PINNED_DIGEST>"
DUMP_FILE="/tmp/tessera-rehearsal.dump"

# 1. Start source container; seed 100k using the same generator as the benchmark (D-05)
docker run -d --name tessera-src -e POSTGRES_PASSWORD=pw -p 5433:5432 "$AGE_IMAGE"
# wait ready, run Flyway baseline, invoke seed generator
./mvnw -pl fabric-core exec:java -Dexec.mainClass=dev.tessera.core.bench.SeedGenerator \
  -Dexec.args="--size=100000 --jdbc-url=jdbc:postgresql://localhost:5433/tessera"

# 2. Run the fixed verification suite BEFORE dump (D-06)
./scripts/verify_queries.sh 5433 > /tmp/before.txt

# 3. pg_dump custom format
docker exec tessera-src pg_dump -U tessera -Fc -f /tmp/dump.bin tessera
docker cp tessera-src:/tmp/dump.bin "$DUMP_FILE"

# 4. Start fresh target container; restore
docker run -d --name tessera-dst -e POSTGRES_PASSWORD=pw -p 5434:5432 "$AGE_IMAGE"
# wait ready, CREATE EXTENSION age, LOAD 'age'
docker cp "$DUMP_FILE" tessera-dst:/tmp/dump.bin
docker exec tessera-dst pg_restore -U tessera -d tessera /tmp/dump.bin

# 5. Run the same verification suite AFTER restore
./scripts/verify_queries.sh 5434 > /tmp/after.txt

# 6. Row-count diff + fixed query suite result diff — hard-fail on divergence (D-07)
diff -u /tmp/before.txt /tmp/after.txt
```

### GitHub Actions CI skeleton (D-11)

```yaml
# .github/workflows/ci.yml — runs on every push/PR (D-04: 100k benchmark on push)
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'corretto', java-version: '21' }
      - run: ./mvnw -B verify                   # compile, spotless, license, surefire, failsafe, archunit
      - run: ./mvnw -B -pl fabric-core -Pjmh -Djmh.dataset=100000 verify
      - run: cp fabric-core/target/jmh-result.json .planning/benchmarks/$(date -Iseconds)-100k.json

# .github/workflows/nightly.yml — runs on schedule
name: nightly
on:
  schedule: [{ cron: '0 3 * * *' }]
jobs:
  bench-1m:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'corretto', java-version: '21' }
      - run: ./mvnw -B -pl fabric-core -Pjmh -Djmh.dataset=1000000 verify
      - name: regression-check
        run: ./scripts/check_regression.sh fabric-core/target/jmh-result.json  # +25% p95 threshold
      - run: cp fabric-core/target/jmh-result.json .planning/benchmarks/$(date -Iseconds)-1M.json
  dump-restore:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./scripts/dump_restore_rehearsal.sh   # FOUND-05; hard-fail on divergence
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Google Java Format in Spotless | **Palantir Java Format** | — (preference, D-12) | Less noisy diffs; better formatting for records + pattern matching |
| Spring Cloud Vault via `bootstrap.yml` | `spring.config.import=vault://` Config Data API | Spring Cloud Vault 3.0 / Spring Boot 2.4 | Deprecated path; **Phase 2 concern**, not Phase 0 |
| Zonky embedded Postgres for integration tests | **Testcontainers** with `apache/age` image substitute | AGE ever | Zonky cannot load loadable extensions; Testcontainers is the only viable path |
| `pg_upgrade` for Postgres majors | **`pg_dump` / `pg_restore`** | AGE 1.x ever | AGE uses `reg*` OID columns; `pg_upgrade` refuses |
| Liquibase XML migrations | **Flyway** plain SQL | — (preference, research/STACK.md) | Flyway maps better to AGE's `CREATE EXTENSION` + `LOAD 'age'` pattern |
| Static Docker tags (`:latest`, `:PG16_latest`) | **sha256 digest pinning** | — (D-09) | Prevents silent upstream retags |

**Deprecated/outdated (do NOT pull into Phase 0):**
- **Spring Boot 3.4.x**: open-source EOL
- **AGE 1.5.0 on PG16**: superseded by 1.6.0
- **Spring AI 2.0.0-Mx milestones**: milestone quality — not a Phase 0 concern regardless (no MCP code in Phase 0)
- **JaCoCo 0.8.11**: Java 21 compatibility issues; use 0.8.12+

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via `spring-boot-starter-test` 3.5.13) + AssertJ + Testcontainers 1.20.4 + ArchUnit 1.3 + JMH 1.37 |
| Config file | none at repo root — Maven Surefire + Failsafe configured in parent POM pluginManagement |
| Quick run command | `./mvnw -q -pl fabric-core test` |
| Full suite command | `./mvnw -B verify` (runs Spotless check, license check, Surefire, Failsafe/IT, ArchUnit, JaCoCo report) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| **FOUND-01** | All five modules build with strict upward deps; cycles fail | integration (ArchUnit + enforcer) | `./mvnw -B verify` | ❌ Wave 0 |
| **FOUND-01** | `fabric-core` has no intra-project deps | unit (ArchUnit) | `./mvnw -pl fabric-app test -Dtest=ModuleDependencyTest#fabric_core_should_not_depend_on_others` | ❌ Wave 0 |
| **FOUND-01** | No cycles between modules | unit (ArchUnit) | `./mvnw -pl fabric-app test -Dtest=ModuleDependencyTest#modules_should_be_free_of_cycles` | ❌ Wave 0 |
| **FOUND-02** | `docker-compose up -d` brings up AGE container and `pg_isready` passes | smoke | `docker compose up -d && docker compose exec -T postgres-age pg_isready -U tessera` | ❌ Wave 0 |
| **FOUND-02** | docker-compose.yml uses `apache/age@sha256:...` digest (not a moving tag) | unit (grep/regex) | `./mvnw -pl fabric-app test -Dtest=ImagePinningTest` | ❌ Wave 0 |
| **FOUND-03** | Flyway V1 creates the `age` extension on a fresh container | integration | `./mvnw -pl fabric-core verify -Dit.test=FlywayBaselineIT` | ❌ Wave 0 |
| **FOUND-03** | HikariCP `connectionInitSql` loads AGE and sets search_path on every pooled connection | integration | `./mvnw -pl fabric-core verify -Dit.test=HikariInitSqlIT` | ❌ Wave 0 |
| **FOUND-04** | JMH harness runs all four query shapes against 100k dataset | benchmark | `./mvnw -pl fabric-core -Pjmh -Djmh.dataset=100000 verify` | ❌ Wave 0 |
| **FOUND-04** | JMH produces JSON result into `.planning/benchmarks/` | benchmark (CI step) | CI workflow step; asserted by `test -f` | ❌ Wave 0 |
| **FOUND-04** | Seed generator is deterministic (same seed → same UUID set) | unit | `./mvnw -pl fabric-core test -Dtest=SeedGeneratorTest` | ❌ Wave 0 |
| **FOUND-05** | `pg_dump` + `pg_restore` on seeded 100k dataset passes fixed query suite | integration (shell + Testcontainers) | `./scripts/dump_restore_rehearsal.sh` | ❌ Wave 0 |
| **FOUND-05** | Fixed query suite (point-lookup, traversal, aggregate, count-by-label) runs against before + after | integration | included in `dump_restore_rehearsal.sh` | ❌ Wave 0 |
| **FOUND-06** | Testcontainers boot of `apache/age` image works from fresh checkout | integration | `./mvnw -pl fabric-core verify -Dit.test=AgePostgresContainerIT` | ❌ Wave 0 |
| **FOUND-06** | AGE parameter binding convention (MIN-1) verified via integration test | integration | `./mvnw -pl fabric-core verify -Dit.test=AgtypeParameterIT` | ❌ Wave 0 |
| Hygiene | Spotless check fails on unformatted code | build (Spotless) | `./mvnw spotless:check` | ❌ Wave 0 |
| Hygiene | Missing Apache 2.0 header fails build | build (license-plugin) | `./mvnw license:check` | ❌ Wave 0 |
| Hygiene | JaCoCo report generated (no threshold) | build | `./mvnw verify` → `fabric-*/target/site/jacoco/` | ❌ Wave 0 |
| D-16 | `TenantContext` record rejects null `modelId` | unit | `./mvnw -pl fabric-core test -Dtest=TenantContextTest` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./mvnw -q -pl <touched-module> test` (fast feedback; skips Failsafe/IT)
- **Per wave merge:** `./mvnw -B verify` (full suite: Spotless + license + Surefire + Failsafe/IT + ArchUnit + JaCoCo report)
- **Phase gate:** Full `./mvnw -B verify` green + 100k JMH benchmark green + nightly dump/restore rehearsal green + 1M benchmark baseline recorded in `.planning/benchmarks/`, before `/gsd-verify-work`.

### Wave 0 Gaps

All test infrastructure is greenfield — every file below is missing and Wave 0 must create it:

- [ ] `pom.xml` (parent) — BOM imports, plugin management for Spotless / license / JaCoCo / enforcer / compiler / surefire / failsafe
- [ ] `fabric-core/pom.xml`, `fabric-rules/pom.xml`, `fabric-projections/pom.xml`, `fabric-connectors/pom.xml`, `fabric-app/pom.xml`
- [ ] `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` — Maven Wrapper committed
- [ ] `fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java` — digest-pinned Testcontainers helper
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
- [ ] License header template (`.mvn/license-header.txt` or `header.txt`)
- [ ] `LICENSE`, `NOTICE` — Apache 2.0
- [ ] (Planner discretion) `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE.md`

**Framework install:** None — JUnit 5, AssertJ, Mockito all pulled transitively by `spring-boot-starter-test`. Testcontainers/ArchUnit/JMH pulled by explicit module deps.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Java 21 (Corretto) | All modules (locked decision) | ✓ (per CLAUDE.md) | OpenJDK 23 present; JDK 21 in `~/Programmming/Services/` | — |
| Maven 3.9+ | Multi-module build | ✓ (per CLAUDE.md) | 3.9 | Maven Wrapper (`mvnw`) committed so contributors don't need global install |
| Docker 20.10+ | Testcontainers + docker-compose | ✓ (per CLAUDE.md) | 27.4 | — |
| Docker Compose v2 | Local dev loop (FOUND-02) | ✓ (bundled with Docker 27.4) | v2 | — |
| `apache/age` image (PG16 branch) | FOUND-02, FOUND-06 | Pulled on first use | sha256 digest to be captured | — |
| GitHub Actions runner | D-11 (CI) | ✓ (public GitHub) | ubuntu-latest | Self-hosted IONOS runner deferred |
| `pg_dump` / `pg_restore` | FOUND-05 | ✓ inside container | matches PG 16 | runs via `docker exec`, no host-side install required |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** none.

## Project Constraints (from CLAUDE.md)

Directives extracted from `./CLAUDE.md` (Tessera project) and `~/CLAUDE.md` (user global + project layout). The planner must not recommend approaches that contradict any of these:

- **Language and build:** Java 21 + Maven 3.9+. No Gradle. No Kotlin in main source set.
- **Primary store:** PostgreSQL 16 + Apache AGE 1.6 (locked, ADR-1). No Neo4j, no JanusGraph, no ArangoDB in Phase 0.
- **Schema validation:** SHACL via Apache Jena (ADR-2). **Not in Phase 0 scope** — Phase 1.
- **Rule engine:** Custom chain-of-responsibility (ADR-3). **Not in Phase 0 scope** — Phase 1. No Drools/Easy Rules/RuleBook.
- **Event transport:** Postgres is authoritative; Kafka is a projection (ADR-4). **Not in Phase 0 scope** — Phase 4.
- **Schema registry location:** same database as graph (ADR-5). **Not in Phase 0 scope** — Phase 1.
- **Hosting:** self-hosted first (IONOS VPS, Docker Compose). Must remain portable — no cloud-specific lock-in. Phase 0 CI runs on public GitHub Actions runners.
- **License:** Apache 2.0. Every `.java` file needs the header (enforced by `license-maven-plugin`).
- **Tenant isolation:** every node, edge, event scoped by `model_id`. `TenantContext` ships in Phase 0 as a structural primitive (D-16) but has no graph-facing consumers yet.
- **Secrets:** KMS for connector credentials, never in Postgres or graph. **Not in Phase 0 scope** — Vault deferred to Phase 2 (D-08).
- **Team:** solo; OSS posture from day one. CONTRIBUTING.md / issue templates / PR template may ship with Phase 0 (planner discretion).
- **Commits:** no `Co-Authored-By` lines (user global instruction).
- **Workflow:** all file-changing work goes through a GSD command (CLAUDE.md §GSD Workflow Enforcement). Phase 0 work is `/gsd-execute-phase` territory.
- **Conventions section of CLAUDE.md:** empty — Phase 0 will begin populating it (e.g., "Palantir Java Format via Spotless", "Apache 2.0 headers mandatory", "TenantContext is an explicit parameter, never ThreadLocal").

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | JMH current stable is 1.37 | Standard Stack | Low — planner verifies before POM write; bumping is trivial |
| A2 | `license-maven-plugin` (mycila) current is 4.5 | Standard Stack | Low — same, verify at POM write |
| A3 | Palantir Java Format 2.50.0 is compatible with Java 21 | Code Examples | Low — Spotless + Palantir on Java 21 is a well-trodden path; planner verifies |
| A4 | `apache/age:PG16_latest` currently resolves to AGE 1.6.0 PG16 branch | Standard Stack, D-09 | **MEDIUM** — planner MUST resolve the current sha256 digest as an early task and record it in docker-compose/testcontainers/README |
| A5 | `testcontainers.reuse.enable=true` does not interfere with AGE `CREATE EXTENSION` idempotency | Pitfalls (Phase 0 specific) | Low — `IF NOT EXISTS` covers it, but the benchmark harness should truncate explicitly |
| A6 | Planner's chosen benchmark regression threshold (tentatively +25% p95) keeps nightly CI wall-clock under budget | Discretion | Low — planner tunes iteration counts to fit |

The rest of this research is either verbatim copy of CONTEXT.md / research/ sources or direct citation — no speculative content elsewhere.

## Open Questions (RESOLVED)

1. **Exact `apache/age` sha256 digest at the time of pinning (A4).**
   - What we know: D-09 mandates digest pinning, three enforcement sites.
   - What's unclear: the actual digest — it moves over time as `PG16_latest` is re-tagged.
   - RESOLVED: digest is resolved at runtime in plan 00-02 Task 1 via `docker pull apache/age:PG16_latest && docker image inspect --format '{{index .RepoDigests 0}}' apache/age:PG16_latest`, then embedded in `docker-compose.yml` (plan 02 Task 2), `README.md` (plan 02 Task 3), and `fabric-core/.../AgePostgresContainer.java` (plan 03 Task 3). The three-site identity is enforced by `ImagePinningTest` in plan 00-03 Task 4.

2. **Benchmark regression threshold concrete value.**
   - What we know: CONTEXT tentatively suggests +25% p95 vs last green nightly; D-04 gives planner discretion.
   - What's unclear: whether +25% is too loose (regressions leak) or too tight (flaky 1M runs cause red nights).
   - RESOLVED: +25% default encoded in `scripts/check_regression.sh` per plan 00-04 Task 4 (configurable via `REGRESSION_THRESHOLD_PCT` env var). Tighten to +10% after the first two-week variance baseline is observed in nightly CI.

3. **JMH iteration / warmup counts vs CI wall-clock budget.**
   - What we know: 100k runs every push (must be fast; <5 min budget), 1M runs nightly (looser — <45 min budget).
   - What's unclear: exact fork/warmup/measurement counts.
   - RESOLVED: `@Fork(1) @Warmup(iterations = 3, time = 2, timeUnit = SECONDS) @Measurement(iterations = 5, time = 3, timeUnit = SECONDS)` chosen in plan 00-04 Task 3 as the default on every bench class. Plan 00-04 Task 5 documents actual wall-clock in the plan SUMMARY; tuning gate at Phase 1 if 1M budget is exceeded.

4. **Contributor-facing OSS files (CONTRIBUTING.md, templates, CODE_OF_CONDUCT.md) in Phase 0 or trailing?**
   - What we know: Planner discretion per CONTEXT.
   - RESOLVED: `LICENSE`, `NOTICE`, and a minimal `CONTRIBUTING.md` ship in plan 00-01 Task 3. `CODE_OF_CONDUCT.md`, GitHub issue templates, and PR templates are deferred to a Phase 0 follow-up task (not blocking FOUND-01..06).

## Sources

### Primary (HIGH confidence)
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/STACK.md` — version locks for Spring Boot 3.5.13, AGE 1.6.0, Testcontainers 1.20.4, Flyway 10, Jena 5.2.0, ArchUnit 1.3, JaCoCo 0.8.12+, Palantir Spotless pattern, Testcontainers-AGE recipe
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/PITFALLS.md` — CRIT-1 (`pg_upgrade` blocked), CRIT-2 (AGE cadence), CRIT-3 (aggregation cliff), MIN-1 (`agtype` param binding), MIN-2 (no default indexes)
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/ARCHITECTURE.md` — five-module layout, upward dependency rule, `TenantContext` as explicit parameter, GraphService choke point (Phase 1)
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/SUMMARY.md` §Phase 0 — rationale for instrumenting AGE risks before feature work
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/ROADMAP.md` §Phase 0 — success criteria (5 items)
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/REQUIREMENTS.md` §Foundations — FOUND-01..06 text
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/phases/00-foundations-risk-burndown/00-CONTEXT.md` — the 16 locked decisions

### Secondary (MEDIUM confidence — cited in STACK/PITFALLS)
- Apache AGE GitHub releases: https://github.com/apache/age/releases
- Apache AGE FAQ (`pg_upgrade` limitation): https://age.apache.org/faq/
- Apache AGE issue #2194 (aggregation cliff): https://github.com/apache/age/issues/2194
- Apache AGE issue #2111 (PG17 gap), #2229 (PG18 tracking)
- Crunchy Data: Postgres 17.1 ABI change near-miss: https://www.crunchydata.com/blog/a-change-to-relresultinfo-a-near-miss-with-postgres-17-1
- Spring Boot 3.5.13 release blog (2026-03-26)
- Spring Boot endoflife.date (3.4.x EOL)
- Testcontainers Postgres module (custom image substitute pattern): https://java.testcontainers.org/modules/databases/postgres/
- JMH official: https://github.com/openjdk/jmh

### Tertiary (LOW confidence — verify before use)
- Exact current `jmh-core` / `license-maven-plugin` / `palantir-java-format` patch versions (A1, A2, A3)
- Current `apache/age:PG16_latest` sha256 digest (A4)

## Metadata

**Confidence breakdown:**
- Standard stack: **HIGH** — all versions inherited from the already-completed research track; nothing new to discover
- Architecture: **HIGH** — five-module layout is locked; ArchUnit scope explicitly narrowed (D-15); TenantContext shape specified (D-16)
- Pitfalls: **HIGH** — CRIT-1/2/3 are well-documented upstream; MIN-1/2 are known AGE quirks; mitigation strategy for each is prescriptive
- Validation architecture: **HIGH** — Maven conventions (Surefire/Failsafe) are standard; every test file is explicitly listed for Wave 0
- Environment: **HIGH** — toolchain confirmed present per CLAUDE.md environment section

**Research date:** 2026-04-13
**Valid until:** 2026-05-13 (30 days — stable stack; refresh the JMH / Palantir / license-plugin patch versions and the `apache/age` digest if re-entering Phase 0 after this window)

## RESEARCH COMPLETE
