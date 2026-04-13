# Phase 0: Foundations & Risk Burndown - Context

**Gathered:** 2026-04-13
**Status:** Ready for planning

<domain>
## Phase Boundary

Establish a reproducible, pinned Java 21 / Spring Boot 3.5.13 / PostgreSQL 16 + Apache AGE 1.6 environment with a Maven multi-module scaffold, a benchmark harness, and a dump/restore rehearsal — so that Phase 1 feature work starts on proven ground with the two highest Apache AGE risks (aggregation cliff and blocked `pg_upgrade`) already instrumented.

Scope anchor: requirements FOUND-01 through FOUND-06. No graph core, no schema registry, no projections, no connectors — those belong to Phase 1+.
</domain>

<decisions>
## Implementation Decisions

### Benchmark Harness (FOUND-04)

- **D-01:** Driver is **JMH microbenchmarks** living in a dedicated benchmark source set under `fabric-core` (tests-adjacent, not a new module). Same JMH infrastructure will be reused in Phase 1 for SHACL validation perf.
- **D-02:** Dataset is built by a **deterministic seed generator** (Java, fixed RNG seed) producing both the 100k and 1M node datasets with graph-shaped edges (few edges per node, multi-label sample). No Git LFS fixture dump, no Flyway-loop data generation.
- **D-03:** Query shapes covered: point-lookup by uuid, 2-hop traversal, aggregate (count/group-by-label), ordered pagination (cursor-style). These are the four shapes FOUND-04 requires.
- **D-04:** CI execution model: **100k runs on every push, 1M runs nightly.** Results are written as JSON to `.planning/benchmarks/{iso-timestamp}-{dataset}.json` so later phases can diff. A regression threshold (to be set during plan-phase, tentatively +25% p95 vs last green nightly) hard-fails the nightly build.

### Dump/Restore Rehearsal (FOUND-05)

- **D-05:** Seed data **reuses the 100k benchmark dataset**. One generator feeds both the benchmark harness and the rehearsal; keeps surface small and guarantees the rehearsal runs against realistic AGE label-table shapes.
- **D-06:** Verification is a **fixed query suite + row-count diff**: run point-lookup, traversal, aggregate, count-by-label before `pg_dump`; run the same suite after `pg_restore` on a fresh container; any result divergence hard-fails. No whole-graph hash — too fragile to ordering, too expensive on 100k nodes.
- **D-07:** Cadence: **nightly CI job, hard-fail on mismatch.** A broken DR runbook must be visible by next morning, not slipped through as report-only.

### Docker Compose & Image Pinning (FOUND-02, FOUND-03, FOUND-06)

- **D-08:** **Single `docker-compose.yml` with PostgreSQL+AGE only.** Vault is **deferred to Phase 2** per SEC-02 (Phase 2 is where connector credentials start to matter). Phase 0 keeps the local loop minimal.
- **D-09:** AGE image is **upstream `apache/age` pinned by sha256 digest** (not by the `PG16_latest` moving tag). The digest is set in (a) `docker-compose.yml`, (b) Testcontainers wiring, (c) a README snippet. One place to bump — no silent upstream retag. Self-built image is rejected; we do not want to own a container build pipeline in Phase 0.
- **D-10:** AGE session init split responsibility:
  - **Flyway V1 baseline** runs the one-time `CREATE EXTENSION IF NOT EXISTS age` DDL.
  - **HikariCP `connectionInitSql`** (`LOAD 'age'; SET search_path = ag_catalog, "$user", public;`) owns per-session priming for every pooled connection, including the connection Flyway itself uses.
  - Init SQL mounted into the container image is rejected — it hides setup from Flyway's migration history.

### CI, Build Hygiene, Tenancy Scope (FOUND-01)

- **D-11:** CI platform is **GitHub Actions**, public repo workflows. Matches the "open to contributors from day one" posture in PROJECT.md. Self-hosted IONOS runners are deferred — a solo dev does not need to own a runner lifecycle now.
- **D-12:** Hygiene plugins landing in Phase 0 (all **hard-fail** the build, not warn):
  - `spotless-maven-plugin` — Palantir Java Format (preferred over Google for less-noisy diffs)
  - `license-maven-plugin` (mycila) — Apache 2.0 headers on every `.java` file (required for an Apache-licensed OSS repo)
  - `jacoco-maven-plugin` 0.8.12+ — coverage report (no threshold gate yet; threshold lands with Phase 1 when there's real code to measure)
  - **SpotBugs is deferred to Phase 1.** On a mostly-empty scaffold it produces little signal and will just add noise to early feature commits.
- **D-13:** Maven enforcer plugin bans cycles and enforces Maven ≥ 3.9 + Java 21 toolchain. Maven Wrapper (`mvnw`) is committed so contributors get a pinned Maven.
- **D-14:** Multi-module layout is exactly the five modules mandated by the research/ROADMAP — `fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app` — under a parent POM with a dependency-management BOM. Dependency direction is strictly upward from `fabric-core`.
- **D-15:** ArchUnit test in Phase 0 covers **module dependency direction only** (upward-only, no cycles, `fabric-connectors` does not depend on `fabric-projections`, etc.). The **raw-Cypher ban (CORE-02) is deferred to Phase 1** where `graph.internal` actually exists — inventing a placeholder package in Phase 0 just to anchor the test is Phase 1 work leaking back.
- **D-16:** `TenantContext` **is shipped in Phase 0** as a structural primitive in `fabric-core` — a record with `model_id` (UUID) plus helpers, designed to be a mandatory method parameter (never a `ThreadLocal`). No consumers yet; this is the type Phase 1 will require everywhere.

### Claude's Discretion

The planner has flexibility on:
- Exact Maven parent POM shape and BOM coordinates (as long as it imports `spring-boot-dependencies` and pins Java 21 via `maven-compiler-plugin` 3.13+ with `<release>21</release>` and `-parameters`).
- Maven Surefire/Failsafe separation (unit vs `*IT.java` integration tests) — expected but not negotiated here.
- JVM args for JaCoCo + JUnit 5 on Java 21 (Mockito agent setup).
- Benchmark regression threshold exact value and warmup/iteration counts — pick defaults that keep nightly CI under a reasonable wall-clock budget.
- Testcontainers reuse strategy (`TESTCONTAINERS_REUSE_ENABLE`) and whether singletons are used; optimize for local dev loop speed.
- Specific file layout under `.github/workflows/` (one workflow vs split push-vs-nightly).
- Contributor files required for OSS posture: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue templates, PR template, `NOTICE`, `LICENSE` — planner decides whether they ship with Phase 0 or trail slightly.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level specs
- `.planning/PROJECT.md` — Vision, constraints, ADR summary (ADR-1..6), OSS posture
- `.planning/REQUIREMENTS.md` §Foundations (FOUND-01..06) — Exact acceptance criteria for Phase 0
- `.planning/ROADMAP.md` §"Phase 0: Foundations & Risk Burndown" — Goal, depends, success criteria

### Research (authoritative for Phase 0 stack decisions)
- `.planning/research/SUMMARY.md` §"Phase 0: Foundations & Risk-Burndown" — What must land in Phase 0 and what must NOT slip
- `.planning/research/STACK.md` — Full version lock file (Spring Boot 3.5.13, AGE 1.6.0 PG16 branch, Testcontainers 1.20.4, Flyway 10.x, Jena 5.2.0, ArchUnit 1.3, ShedLock 5.16+, Spring Cloud Vault 4.2.x)
- `.planning/research/PITFALLS.md` — Critical risks CRIT-1 (AGE blocks `pg_upgrade`), CRIT-2 (AGE lags PG), CRIT-3 (aggregation cliff) — all three are Phase 0's to instrument
- `.planning/research/ARCHITECTURE.md` — Module layout, `GraphService`/`GraphSession` choke point, `TenantContext` as mandatory parameter
- `.planning/research/FEATURES.md` — Table-stakes vs differentiator framing (context only for Phase 0)

### External (read for Phase 0)
- Apache AGE release page — confirm the sha256 digest for `apache/age:PG16_latest` at the time of pinning
- Testcontainers Postgres docs — "Using an alternative Postgres image" pattern (required because the default image does not bundle AGE)
- Spring Boot 3.5.13 release notes — baseline patch
- HikariCP docs §`connectionInitSql` — semantics on pooled connections

No Tessera ADRs exist yet as standalone files — ADR-1..6 are currently summarized inline in `PROJECT.md`. Planner should propose creating `.planning/adr/` in Phase 0 if it wants ADRs as first-class docs.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **None.** Phase 0 is greenfield scaffolding. The only files currently in the repo are `README.md`, `LICENSE`, `CLAUDE.md`, and `.planning/`.

### Established Patterns
- **None in code yet.** The patterns to establish are set by `.planning/research/` and will become the reference for all later phases.

### Integration Points
- The parent POM and Maven Wrapper are the first integration surface — every later phase adds modules under it.
- `docker-compose.yml` at repo root is the dev entry point; every later phase reads `application.yml` pointing at it.
- `.github/workflows/` becomes the enforcement point for benchmark + dump/restore jobs.

</code_context>

<specifics>
## Specific Ideas

- **"The graph is the truth; everything else is a projection"** (from PROJECT.md Core Value) is the anchor — Phase 0 is explicitly about proving the truth-store substrate (PG16+AGE) behaves before any projection or feature work.
- **Palantir Java Format** is the preferred Spotless formatter (less-noisy diffs than Google Java Format).
- **Digest-pin everywhere**: the apache/age sha256 must appear in `docker-compose.yml`, Testcontainers wiring, and a README snippet — one logical value, three enforcement sites.
- **JMH benchmarks must be reusable in Phase 1** — do not build a Phase-0-only harness; design for the SHACL perf work that's already flagged as a Phase 1 research gap.
- **Benchmark results live in `.planning/benchmarks/`** so they become part of the same repo of record as roadmap and context, not a detached CI artifact.

</specifics>

<deferred>
## Deferred Ideas

- **Vault container in dev compose** — deferred to Phase 2 when SEC-02 actually requires connector credentials. Adding it now is pre-work without a payoff.
- **`graph.internal` raw-Cypher ban (ArchUnit)** — deferred to Phase 1 where `GraphSession` actually exists. Phase 0 ArchUnit only enforces module dependency direction.
- **SpotBugs** — deferred to Phase 1 when there's real code to analyze.
- **JaCoCo coverage threshold** — plugin lands in Phase 0 (report only); a pass/fail threshold is a Phase 1 decision.
- **Self-built `apache/age` image under our control** — deferred indefinitely. Only revisited if upstream AGE release cadence breaks or forces us to patch.
- **Postgres RLS on AGE label tables as a belt-and-braces tenant filter** — research flag for Phase 1; not a Phase 0 concern.
- **ADR directory (`.planning/adr/`) as first-class docs** — noted; planner may propose in Phase 0 or leave in PROJECT.md.
- **Field-level encryption** — all-or-nothing per research CRIT-7/CRIT-8; handled by Phase 2 SEC-06 decision, not Phase 0.

</deferred>

---

*Phase: 00-foundations-risk-burndown*
*Context gathered: 2026-04-13*
