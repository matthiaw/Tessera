# Phase 0: Foundations & Risk Burndown - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-13
**Phase:** 00-foundations-risk-burndown
**Areas discussed:** Benchmark harness shape, Dump/restore rehearsal, Docker Compose + AGE image pinning, CI + build hygiene + TenantContext scope

---

## Gray Area Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Benchmark harness shape | JMH vs custom driver; dataset generation; CI budget | ✓ |
| Dump/restore rehearsal | Seed data realism, validation queries, cadence | ✓ |
| Docker Compose + AGE image pinning | Compose shape, image digest strategy, AGE init location | ✓ |
| CI + build hygiene + TenantContext scope | CI platform, hygiene plugins, tenancy choke-point scope | ✓ |

**User's choice:** All four areas discussed.

---

## Benchmark Harness Shape

### Q1: What driver shape for the benchmark harness?

| Option | Description | Selected |
|--------|-------------|----------|
| JMH microbenchmarks (Recommended) | JMH module in fabric-core tests; precise timings, JSON output; reusable for Phase 1 SHACL perf work | ✓ |
| Plain JUnit + StopWatch | Cheapest to write; noisy; not suited for micro-level AGE tuning | |
| Standalone CLI driver module | New fabric-bench module; more flexibility but more maintenance | |

**User's choice:** JMH microbenchmarks
**Notes:** Same harness will be reused in Phase 1 for SHACL validation perf work.

### Q2: How to generate the 100k / 1M node datasets?

| Option | Description | Selected |
|--------|-------------|----------|
| Deterministic seed generator (Recommended) | Java/SQL generator, fixed RNG seed, reproducible, parameterized by N | ✓ |
| Checked-in fixture dump | pg_dump via Git LFS; fastest to load but opaque and bloats repo | |
| Synthetic via Cypher loops in Flyway | Mixes benchmark data with schema migrations | |

**User's choice:** Deterministic seed generator

### Q3: Where does the harness run, and how are results published?

| Option | Description | Selected |
|--------|-------------|----------|
| 100k in CI (push), 1M nightly (Recommended) | Bounded push latency, nightly publishes JSON results for phase-over-phase diff | ✓ |
| Both in CI on every push | Strongest signal but adds minutes to every build | |
| Local-only, no CI | Rejected — FOUND-04 explicitly requires CI execution | |

**User's choice:** 100k in CI (push), 1M nightly
**Notes:** Results written as JSON to `.planning/benchmarks/`.

---

## Dump/Restore Rehearsal

### Q1: What seed dataset should the dump/restore rehearsal use?

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse 100k benchmark dataset (Recommended) | One generator feeds both; small surface; realistic AGE label-table shape | ✓ |
| Dedicated small mixed dataset | Feature-coverage focused (tombstones, edges with properties, multi-label) | |
| Both | Strongest but two generators to maintain | |

**User's choice:** Reuse 100k benchmark dataset

### Q2: How does the rehearsal prove the restored graph is queryable?

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed query suite + row-count diff (Recommended) | Run 4 canonical queries pre-dump and post-restore; any divergence fails | ✓ |
| Cypher equality hash of whole graph | Strongest but fragile to ordering and expensive on 100k nodes | |
| Smoke query only | Cheapest, weakest — would not catch partial restore | |

**User's choice:** Fixed query suite + row-count diff

### Q3: How often and how strictly does the rehearsal run in CI?

| Option | Description | Selected |
|--------|-------------|----------|
| Nightly, hard-fail on mismatch (Recommended) | Runs with 1M benchmark; broken DR runbook visible next morning | ✓ |
| Every push, hard-fail | Maximum safety; overkill for solo dev early in project | |
| Nightly, report-only | Easy to ignore; silent DR regression risk | |

**User's choice:** Nightly, hard-fail on mismatch

---

## Docker Compose + AGE Image Pinning

### Q1: How should the dev stack be composed?

| Option | Description | Selected |
|--------|-------------|----------|
| Single docker-compose.yml, Postgres+AGE only (Recommended) | Minimal Phase 0 surface; Vault deferred to Phase 2 when SEC-02 needs it | ✓ |
| Compose with Postgres+AGE+Vault dev-mode | Secrets plumbing exercised early at cost of extra container | |
| Base compose + override files | Clean dev/test split but more ceremony for solo dev | |

**User's choice:** Single docker-compose.yml, Postgres+AGE only

### Q2: How is the apache/age image pinned?

| Option | Description | Selected |
|--------|-------------|----------|
| Upstream apache/age pinned by digest (Recommended) | sha256 pin in compose + Testcontainers + README; immune to upstream retag | ✓ |
| Self-built image from apache/age Dockerfile | Maximum control but own a build+registry pipeline for no payoff | |
| Upstream pinned by tag only | Silent retag risk — exactly what research flagged | |

**User's choice:** Upstream apache/age pinned by digest

### Q3: Where does AGE session init live?

| Option | Description | Selected |
|--------|-------------|----------|
| HikariCP connectionInitSql only (Recommended) | Single source of truth in application.yml for per-session priming | ✓ |
| Flyway baseline for CREATE EXTENSION; Hikari for session | Split responsibility; clearer but two places to remember | |
| Init SQL script mounted into container | Hides setup from Flyway migration history | |

**User's choice:** HikariCP connectionInitSql only
**Notes:** Captured in CONTEXT.md D-10 with clarification — Flyway V1 still runs the one-time `CREATE EXTENSION IF NOT EXISTS age` DDL, while HikariCP `connectionInitSql` owns the per-session `LOAD 'age'` + search_path priming.

---

## CI + Build Hygiene + TenantContext Scope

### Q1: CI platform for Tessera?

| Option | Description | Selected |
|--------|-------------|----------|
| GitHub Actions (Recommended) | Free for public repos; matches OSS/contributor posture from PROJECT.md | ✓ |
| Self-hosted runner on IONOS | Same infra as prod but solo dev owns runner lifecycle | |
| Defer CI to Phase 1 | Rejected — FOUND-04/05 require CI execution | |

**User's choice:** GitHub Actions

### Q2: Which build-hygiene plugins land in Phase 0, and how strict?

| Option | Description | Selected |
|--------|-------------|----------|
| Spotless + license-maven + JaCoCo, fail build (Recommended) | Minimal strict set; SpotBugs deferred to Phase 1 | ✓ |
| Full set: Spotless + SpotBugs + license + JaCoCo | Strongest but SpotBugs produces little signal on empty scaffold | |
| Spotless + license only, warn-but-don't-fail | Lowest friction; format drift lands on main silently | |

**User's choice:** Spotless + license-maven + JaCoCo, fail build
**Notes:** Palantir Java Format preferred over Google; JaCoCo report-only in Phase 0, threshold in Phase 1.

### Q3: How much of the tenancy choke-point lands in Phase 0 vs Phase 1?

| Option | Description | Selected |
|--------|-------------|----------|
| TenantContext type + ArchUnit module rules only (Recommended) | TenantContext record in fabric-core; ArchUnit enforces upward-only deps | ✓ |
| Everything: TenantContext + graph.internal placeholder + raw-Cypher ban | Feels like Phase 1 work leaking back | |
| Nothing — defer TenantContext | Rejected — research explicitly places TenantContext in Phase 0 | |

**User's choice:** TenantContext type + ArchUnit module rules only

---

## Claude's Discretion

Captured in CONTEXT.md:
- Exact Maven parent POM shape and BOM coordinates
- Maven Surefire/Failsafe layout
- JVM args for JaCoCo + JUnit 5 on Java 21
- JMH warmup/iteration counts and benchmark regression threshold value
- Testcontainers reuse strategy
- `.github/workflows/` file layout
- Timing of OSS contributor files (CONTRIBUTING.md, CODE_OF_CONDUCT.md, issue templates, PR template, NOTICE)

## Deferred Ideas

- Vault in dev compose → Phase 2 (SEC-02)
- `graph.internal` raw-Cypher ArchUnit ban → Phase 1
- SpotBugs → Phase 1
- JaCoCo coverage threshold → Phase 1
- Self-built apache/age image → deferred indefinitely
- Postgres RLS on AGE label tables → Phase 1 research flag
- `.planning/adr/` as first-class ADR docs → planner may propose in Phase 0
- Field-level encryption → Phase 2 SEC-06 (binary decision)
