---
phase: 00-foundations-risk-burndown
plan: 01
subsystem: build
tags: [maven, parent-pom, hygiene, oss-posture, java21]
requirements: [FOUND-01]
dependency_graph:
  requires: []
  provides:
    - tessera-parent
    - fabric-core
    - fabric-rules
    - fabric-projections
    - fabric-connectors
    - fabric-app
    - maven-wrapper
  affects: [all-future-plans]
tech_stack:
  added:
    - spring-boot-starter-parent:3.5.13
    - spring-cloud-dependencies:2024.0.1
    - spring-ai-bom:1.0.5
    - testcontainers-bom:1.20.4
    - jena-shacl:5.2.0
    - postgresql-jdbc:42.7.5
    - archunit-junit5:1.3.0
    - jmh-core:1.37
    - shedlock-spring:5.16.0
    - springdoc-openapi:2.8.6
    - spotless-maven-plugin:2.44.1 (palantirJavaFormat 2.50.0)
    - license-maven-plugin:4.5 (mycila)
    - jacoco-maven-plugin:0.8.12
    - maven-enforcer-plugin:3.5.0
    - extra-enforcer-rules:1.9.0
    - build-helper-maven-plugin:3.6.0
    - flyway-maven-plugin:10.20.1
    - maven-wrapper:3.3.2 (apache-maven 3.9.9)
  patterns:
    - "Hard-fail at validate phase: spotless, license, enforcer all bound to validate"
    - "Strict upward module dependency direction from fabric-core (D-14)"
    - "fabric-connectors must NOT depend on fabric-projections (D-15)"
    - "JMH lives in fabric-core test source set under src/jmh/java (D-01)"
key_files:
  created:
    - pom.xml
    - fabric-core/pom.xml
    - fabric-rules/pom.xml
    - fabric-projections/pom.xml
    - fabric-connectors/pom.xml
    - fabric-app/pom.xml
    - mvnw
    - mvnw.cmd
    - .mvn/wrapper/maven-wrapper.properties
    - .mvn/wrapper/maven-wrapper.jar
    - .mvn/license-header.txt
    - NOTICE
    - CONTRIBUTING.md
  modified:
    - .gitignore
decisions:
  - "Spring Boot 3.5.13 parent inherits dependencyManagement; tessera-parent imports spring-cloud, spring-ai, testcontainers BOMs on top"
  - "banCircularDependencies sourced from extra-enforcer-rules:1.9.0 (D-13). Standard maven-enforcer-plugin has no first-class cycle rule. Wired with explicit implementation= class to bypass the deprecated short-name registry"
  - "spring-boot-maven-plugin repackage skipped in fabric-app until @SpringBootApplication main class lands (Phase 1+). The plugin remains active so the binding is in place"
  - "openjdk.jdk symlink in repo root added to .gitignore (developer-machine artifact, not project content)"
metrics:
  duration: ~10 min
  completed: 2026-04-13
  tasks: 3
  files_created: 13
  files_modified: 1
---

# Phase 0 Plan 01: Maven Multi-Module Foundation Summary

Stand up the Tessera Maven multi-module skeleton with parent POM, five `fabric-*` modules, Maven Wrapper, and hygiene plugins (Spotless / license-maven-plugin / JaCoCo / enforcer with banCircularDependencies) all hard-failing at the `validate` phase, plus Apache 2.0 OSS posture files (LICENSE already present, NOTICE, CONTRIBUTING.md).

## What landed

### Task 1 — Parent POM, Maven Wrapper, license header (commit `e92f7df`)

- `pom.xml` (`dev.tessera:tessera-parent:0.1.0-SNAPSHOT`, packaging `pom`) inheriting from `spring-boot-starter-parent:3.5.13`.
- BOM imports: `spring-cloud-dependencies:2024.0.1`, `spring-ai-bom:1.0.5`, `testcontainers-bom:1.20.4`.
- Standalone version-pinned deps in `dependencyManagement`: `jena-shacl 5.2.0`, `postgresql 42.7.5`, `archunit-junit5 1.3.0`, `jmh-core/jmh-generator-annprocess 1.37`, `shedlock-spring 5.16.0`, `springdoc-openapi-starter-webmvc-ui 2.8.6`.
- Plugin management with all hard-fail hygiene plugins:
  - `maven-compiler-plugin 3.13.0` — `<release>21</release>`, `-parameters`.
  - `maven-surefire-plugin 3.5.2` — `*Test.java` only, `-XX:+EnableDynamicAgentLoading` for Mockito on JDK 21+.
  - `maven-failsafe-plugin 3.5.2` — `*IT.java`, bound to `integration-test` + `verify`.
  - `maven-enforcer-plugin 3.5.0` + `extra-enforcer-rules 1.9.0`. Rules: `requireMavenVersion [3.9,)`, `requireJavaVersion 21`, `dependencyConvergence`, `requireUpperBoundDeps`, `banDuplicatePomDependencyVersions`, `banCircularDependencies` (D-13). Bound to `validate`.
  - `spotless-maven-plugin 2.44.1` with `palantirJavaFormat 2.50.0` and license-header tied to `.mvn/license-header.txt`. Bound to `validate`.
  - `license-maven-plugin 4.5` (mycila) with the same header. Bound to `validate`.
  - `jacoco-maven-plugin 0.8.12` — `prepare-agent` at `initialize`, `report` at `verify`. **No threshold gate** (D-12).
  - `maven-jar-plugin 3.4.2`, `maven-source-plugin 3.3.1` (attach-sources), `flyway-maven-plugin 10.20.1`, `build-helper-maven-plugin 3.6.0` (pluginManagement only, used by fabric-core).
- Active in parent `<build><plugins>`: enforcer, spotless, license, jacoco — every module inherits automatically.
- **SpotBugs intentionally absent** (D-12 — deferred to Phase 1).
- Maven Wrapper 3.3.2 pinned to `apache-maven 3.9.9`. `mvnw` executable, `mvnw.cmd`, `maven-wrapper.properties`, and `maven-wrapper.jar` all committed for offline reproducibility.
- `.mvn/license-header.txt` — Apache 2.0 short header, literal (no template variables).
- `.gitignore` updated for `target/`, `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`, `*.log`, `.claude/`, plus `openjdk.jdk` (developer-machine symlink).

**Verify:** `./mvnw -N validate` → `BUILD SUCCESS`, all six enforcer rules pass, spotless and license check run (no Java files yet → trivially green).

### Task 2 — Five fabric-* module POMs (commit `c653f66`)

| Module | Intra-project deps | Notes |
|---|---|---|
| `fabric-core` | none | bottom of graph; `spring-boot-starter`, `spring-boot-starter-jdbc`, `postgresql`, `flyway-core`, `flyway-database-postgresql`, test scope: testcontainers, JMH; `build-helper-maven-plugin` adds `src/jmh/java` test source root (D-01) |
| `fabric-rules` | `fabric-core` | minimal — rule engine module |
| `fabric-projections` | `fabric-core`, `fabric-rules` | structural placeholder only |
| `fabric-connectors` | `fabric-core` | **does NOT depend on fabric-projections** (D-15); to be enforced at runtime by ArchUnit in plan 00-03 |
| `fabric-app` | all four siblings | `spring-boot-starter-web`, `spring-boot-starter-actuator`, test: `archunit-junit5`, testcontainers; `spring-boot-maven-plugin` configured but `repackage` skipped until a `@SpringBootApplication` main class exists |

**Verify:** `./mvnw -B -DskipTests verify` — full reactor (parent + 5 modules) all `SUCCESS` in ~3s on warm cache.

### Task 3 — OSS posture files (commit `38fa571`)

- `LICENSE` — full Apache 2.0 text was already present (11346 bytes), satisfies the `>= 10000 bytes` and `Apache License, Version 2.0` checks. Untouched.
- `NOTICE` — minimal Apache NOTICE file with `Tessera Contributors` copyright and ASF reference.
- `CONTRIBUTING.md` — concise contributor guide (~60 lines) covering: Apache 2.0 + DCO, `./mvnw -B verify` build contract, Spotless / Palantir Java Format, license headers, `*Test.java` vs `*IT.java` split, conventional commits without `Co-Authored-By`, module direction with the dependency arrow diagram, GSD workflow entry points per project `CLAUDE.md`.

## Decisions Made

- **Pinned Spring Boot 3.5.13 as the parent** — current 3.5.x line per stack pin; 3.4.x is EOL.
- **`banCircularDependencies` via `extra-enforcer-rules:1.9.0`** — wired with explicit `implementation="org.codehaus.mojo.extraenforcer.dependencies.BanCircularDependencies"` to avoid relying on the deprecated short-name rule registry. Satisfies D-13 at the POM-coordinate level. Package-level cycle defense (ArchUnit `modules_should_be_free_of_cycles`) is the complementary check landing in plan 00-03 Task 4.
- **`spring-boot-maven-plugin` `<skip>true</skip>` in fabric-app** — Spring Boot's `repackage` goal needs a discoverable main class; Phase 0 has zero source files so a clean reactor build would fail. The skip is documented in the POM with a removal trigger ("when fabric-app gains a `@SpringBootApplication`"). Pragmatic Rule 3 unblock — the plugin binding is still in place.
- **Wrapper jar committed (`.mvn/wrapper/maven-wrapper.jar`)** — standard practice for fully reproducible offline builds and matches the "open to contributors from day one" posture.
- **`openjdk.jdk` ignored, not deleted** — the symlink was created during the Java-install checkpoint and points to the Homebrew JDK 21 install. Adding it to `.gitignore` keeps the repo clean without touching the developer's machine state.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] `spring-boot-maven-plugin:repackage` failed on `fabric-app` (no main class)**
- **Found during:** Task 2 verify (`./mvnw -B -DskipTests verify`)
- **Issue:** `Failed to execute goal org.springframework.boot:spring-boot-maven-plugin:3.5.13:repackage ... Unable to find main class`. Phase 0 ships zero Java source, so Spring Boot's repackage cannot find a `@SpringBootApplication`.
- **Fix:** Added `<configuration><skip>true</skip></configuration>` to the `spring-boot-maven-plugin` declaration in `fabric-app/pom.xml`, with an inline comment marking it for removal once the application class lands. The plugin is still on the classpath and bound — only the repackage execution is suppressed.
- **Files modified:** `fabric-app/pom.xml`
- **Commit:** `c653f66` (folded into Task 2 commit since the issue surfaced during Task 2 verification)

**2. [Rule 2 — Critical hygiene] `openjdk.jdk` developer-machine symlink leaking into repo root**
- **Found during:** Task 1 pre-commit `git status`
- **Issue:** The Java-install checkpoint left an `openjdk.jdk` symlink at the repo root (`-> /opt/homebrew/opt/openjdk@21/...`). Without ignoring it, the next contributor's `git status` would surface a confusing untracked entry.
- **Fix:** Added `openjdk.jdk` to `.gitignore`.
- **Files modified:** `.gitignore`
- **Commit:** `e92f7df`

## Acceptance Criteria Status

### Task 1
- [x] `pom.xml` exists with all required literal strings (`spring-boot-starter-parent`, `3.5.13`, `<release>21</release>`, `palantirJavaFormat`, `license-maven-plugin`, `jacoco-maven-plugin`, `maven-enforcer-plugin`, `requireMavenVersion`, `requireJavaVersion`, `2024.0.1`, `1.0.5`, `5.2.0`, `42.7.5`, `1.20.4`)
- [x] `pom.xml` contains `extra-enforcer-rules` and `banCircularDependencies` (D-13 cycle ban)
- [x] `<modules>` block lists exactly the five `fabric-*` modules
- [x] `pom.xml` does NOT contain `spotbugs` (D-12 defer)
- [x] `mvnw` is executable; `.mvn/wrapper/maven-wrapper.properties` references `apache-maven/3.9.9`
- [x] `.mvn/license-header.txt` contains `Apache License, Version 2.0`
- [x] `./mvnw -N validate` exits 0 (BUILD SUCCESS, 6/6 enforcer rules pass)
- [x] `./mvnw spotless:check` exits 0

### Task 2
- [x] All five `fabric-*/pom.xml` files exist
- [x] `fabric-connectors/pom.xml` does NOT reference `fabric-projections` (D-15)
- [x] Four other modules depend on `fabric-core`
- [x] `fabric-core/pom.xml` has zero `dev.tessera` `<dependency>` entries
- [x] `fabric-core/pom.xml` contains `build-helper-maven-plugin` and `src/jmh/java`
- [x] `fabric-app/pom.xml` contains `archunit-junit5` and `spring-boot-maven-plugin`
- [x] `./mvnw -B -DskipTests verify` exits 0 (all 6 reactor projects SUCCESS)

### Task 3
- [x] `LICENSE` is the full Apache 2.0 text (11346 bytes, > 10000)
- [x] `NOTICE` contains `Tessera Contributors` and `Apache Software Foundation`
- [x] `CONTRIBUTING.md` contains `Apache 2.0`, `./mvnw -B verify`, `Palantir Java Format`, `Co-Authored-By`, `fabric-core`, `GSD`

## Verification Summary

```
$ ./mvnw -B -DskipTests verify
[INFO] Reactor Summary for Tessera Parent 0.1.0-SNAPSHOT:
[INFO] Tessera Parent ..................................... SUCCESS
[INFO] Tessera :: fabric-core ............................. SUCCESS
[INFO] Tessera :: fabric-rules ............................ SUCCESS
[INFO] Tessera :: fabric-projections ...................... SUCCESS
[INFO] Tessera :: fabric-connectors ....................... SUCCESS
[INFO] Tessera :: fabric-app .............................. SUCCESS
[INFO] BUILD SUCCESS
```

FOUND-01 satisfied: Maven multi-module skeleton with strict upward dependency direction is in place and a fresh clone can run `./mvnw -B verify` green with hygiene plugins (spotless, license, enforcer with banCircularDependencies, jacoco) enforced at the `validate` phase on every module.

## Threat Flags

None. All threats from the plan's `<threat_model>` (T-00-01 supply-chain via pinned BOMs + `requireUpperBoundDeps`, T-00-02 wrapper pinning, T-00-03 license headers via mycila check, T-00-04 hygiene-at-validate) are mitigated as planned. T-00-05 (plugin code execution at build time) remains accepted per the plan.

## Self-Check: PASSED

Files verified present:
- pom.xml — FOUND
- fabric-core/pom.xml, fabric-rules/pom.xml, fabric-projections/pom.xml, fabric-connectors/pom.xml, fabric-app/pom.xml — FOUND (all five)
- mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties, .mvn/wrapper/maven-wrapper.jar, .mvn/license-header.txt — FOUND
- LICENSE, NOTICE, CONTRIBUTING.md — FOUND

Commits verified:
- e92f7df (chore(00-01): parent POM and wrapper) — FOUND
- c653f66 (feat(00-01): five fabric-* module POMs) — FOUND
- 38fa571 (docs(00-01): NOTICE and CONTRIBUTING.md) — FOUND
