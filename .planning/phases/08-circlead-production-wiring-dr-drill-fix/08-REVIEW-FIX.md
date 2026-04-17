---
phase: 08-circlead-production-wiring-dr-drill-fix
fixed_at: 2026-04-17T00:00:00Z
review_path: .planning/phases/08-circlead-production-wiring-dr-drill-fix/08-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 08: Code Review Fix Report

**Fixed at:** 2026-04-17
**Source review:** .planning/phases/08-circlead-production-wiring-dr-drill-fix/08-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4
- Fixed: 4
- Skipped: 0

## Fixed Issues

### WR-01: DR drill invokes `failsafe:integration-test` without `failsafe:verify`

**Files modified:** `scripts/dr_drill.sh`
**Commit:** 0ed34ef
**Applied fix:** Added `failsafe:verify` as a second goal after `failsafe:integration-test` so Maven Failsafe reads the result XML and fails the build on assertion errors. Also removed the redundant `-Dit.test=CircleadDrillSmokeIT` flag (the class is picked up by the default `**/*IT.java` naming convention in failsafe-plugin 3.5.x).

---

### WR-02: `ON CONFLICT DO NOTHING` silently ignores connector config updates

**Files modified:** `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java`
**Commit:** 6c54ce7
**Applied fix:** Applied option A — added a clear Javadoc paragraph to `registerCircleadConnectors()` documenting the first-registration semantics: connector properties are only applied at first registration and subsequent changes in `application.yml` require deleting the row or using the admin CRUD API.

---

### WR-03: `@DependsOn("circleadConnectorConfig")` uses an unvalidated string literal

**Files modified:** `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java`
**Commit:** 2e70e1d
**Applied fix:** Added a four-line comment immediately above the `@DependsOn` annotation warning that the string must match the Spring-derived bean name of `CircleadConnectorConfig`, that there is no compile-time validation, and that a mismatch causes a silent startup ordering race.

---

### WR-04: `ConnectorRegistry.loadRow` does not guard against `null` for `mapping_def`

**Files modified:** `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java`
**Commit:** e684e27
**Applied fix:** Extracted `row.get("mapping_def")` into a local `mappingObj` variable with an explicit null check that logs `"Connector {} has null mapping_def, skipping"` and returns early, before calling `.toString()`. This produces a clear diagnostic message instead of a misleading NPE caught by the outer catch block.

---

_Fixed: 2026-04-17_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
