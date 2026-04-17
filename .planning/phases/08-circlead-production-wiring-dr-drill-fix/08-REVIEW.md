---
phase: 08-circlead-production-wiring-dr-drill-fix
reviewed: 2026-04-17T00:00:00Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - fabric-app/src/main/resources/application.yml
  - fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadDrillSmokeIT.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadPlaceholderResolutionTest.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadSchedulerWiringIT.java
  - scripts/dr_drill.sh
findings:
  critical: 0
  warning: 4
  info: 3
  total: 7
status: issues_found
---

# Phase 08: Code Review Report

**Reviewed:** 2026-04-17
**Depth:** standard
**Files Reviewed:** 7
**Status:** issues_found

## Summary

This phase wires the three circlead connector types (Role, Circle, Activity) into the Spring application context, ensures startup ordering between `CircleadConnectorConfig` and `ConnectorRegistry`, resolves `${...}` placeholders in `sourceUrl` before passing to `URI.create()`, and extends the DR drill script with a circlead consumer smoke test.

The implementation is solid overall. No security vulnerabilities or data-loss defects were found. Four warnings are raised: two relate to subtle correctness risks in the DR drill script (missing `failsafe:verify` goal that can silently pass even on test failure; `ON CONFLICT DO NOTHING` losing config updates on connector re-registration across restarts), one to the `@DependsOn` string literal that is not validated at compile time, and one to the `it.test` property name which is incorrect for failsafe. Three informational items cover hardcoded credentials in a dev-only script, an unused import, and a missing null-guard in `ConnectorRegistry.loadRow`.

---

## Warnings

### WR-01: DR drill invokes `failsafe:integration-test` without `failsafe:verify` — test failures silently pass

**File:** `scripts/dr_drill.sh:218`
**Issue:** The drill script runs `failsafe:integration-test` but not `failsafe:verify`. Maven Failsafe is designed in two goals: `integration-test` executes the tests and captures results to `surefire-reports/`, while `verify` reads those results and fails the build if any test failed. Running only `integration-test` means a failing `CircleadDrillSmokeIT` will produce a non-zero exit only if the JVM itself crashes; an assertion failure or test error is recorded in the XML report but the Maven process still exits 0. The drill therefore gives a false PASS.

Additionally, `-Dit.test=CircleadDrillSmokeIT` is the Surefire property for selecting tests; the Failsafe-native property is `-Dtest=CircleadDrillSmokeIT` (when `includes` override is used) or the Failsafe-specific `-Dgroups` / `-Dit.test` depending on plugin version. The `maven-failsafe-plugin 3.5.2` in the parent POM uses `**/*IT.java` as the default include, so the class will be picked up by naming convention without `-Dit.test`, making the flag redundant but not harmful. The missing `verify` goal is the critical part.

**Fix:**
```bash
./mvnw -B -ntp -pl fabric-connectors failsafe:integration-test failsafe:verify \
  -Dsurefire.skip=true \
  -Dfailsafe.useFile=false
```

---

### WR-02: `ON CONFLICT DO NOTHING` in `registerCircleadConnectors` silently ignores connector config updates

**File:** `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java:124-136`
**Issue:** The upsert uses `ON CONFLICT DO NOTHING`. This is idempotent across restarts but also means any change to `credentials_ref`, `poll_interval_seconds`, or `mapping_def` between deployments is silently ignored — the row already exists, so the new values are never applied. The Javadoc says "idempotent across restarts" but the implication is that connector configuration can never be updated this way.

The connector rows have no natural unique key shown in the snippet — if the conflict target is `(id)` (the PK), then a redeploy with changed `circleadCredentialsRef` will leave the old credentials in the DB. If the intent is "register once and let admins update via the CRUD API" that should be documented. If the intent is "always reflect application.yml", an `ON CONFLICT ... DO UPDATE SET ...` is required.

**Fix (option A — silent skip is intentional, document it):**
Add a comment in the Javadoc: "Connector properties set here are only applied at first registration. Subsequent changes require either manually deleting the row or the admin connector CRUD API."

**Fix (option B — reflect config on every restart):**
```sql
INSERT INTO connectors (model_id, type, mapping_def, auth_type, credentials_ref,
                        poll_interval_seconds, enabled)
VALUES (:modelId::uuid, 'rest-poll', :mappingJson::jsonb, 'BEARER',
        :credentialsRef, :interval, true)
ON CONFLICT (model_id, type, source_entity_type) DO UPDATE
  SET mapping_def           = EXCLUDED.mapping_def,
      credentials_ref       = EXCLUDED.credentials_ref,
      poll_interval_seconds = EXCLUDED.poll_interval_seconds
```
(Adjust conflict columns to match the actual unique constraint on `connectors`.)

---

### WR-03: `@DependsOn("circleadConnectorConfig")` uses an unvalidated string literal

**File:** `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java:48`
**Issue:** Spring derives the default bean name for a `@Configuration` class from the class name with the first letter lowercased: `CircleadConnectorConfig` → `"circleadConnectorConfig"`. This is correct and matches the annotation. However, if the class is ever renamed (e.g., to `CircleadConfig`) the `@DependsOn` string will silently refer to a non-existent bean name, and Spring will either not enforce the ordering or throw at startup. There is no compile-time validation.

This is a low-probability risk but can cause a subtle startup race where connector rows are missing when `loadAll()` runs, resulting in zero registered connectors at boot with no error logged.

**Fix:** Add a constant or use a well-documented convention, and add a comment warning about the coupling:
```java
// IMPORTANT: must match the Spring-derived bean name of CircleadConnectorConfig.
// If that class is renamed, update this string too.
@DependsOn("circleadConnectorConfig")
```
A longer-term fix is to introduce a shared interface `CircleadConnectorRegistrar` implemented by `CircleadConnectorConfig` and depend on the interface type, or use `@DependsOn` with a `BeanDefinitionRegistryPostProcessor` constant. For now the comment is sufficient.

---

### WR-04: `ConnectorRegistry.loadRow` does not guard against `null` for `mapping_def` column

**File:** `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java:146`
**Issue:** `row.get("mapping_def").toString()` will throw a `NullPointerException` if the `mapping_def` column is `NULL` in the database (e.g., a manually inserted row during testing or a failed migration). The outer `try/catch` on line 158 catches the resulting NPE and logs a warning, so this is not a crash, but the error message will be misleading ("Failed to parse mapping for connector X: null") rather than "mapping_def is null".

**Fix:**
```java
Object mappingObj = row.get("mapping_def");
if (mappingObj == null) {
    LOG.warn("Connector {} has null mapping_def, skipping", id);
    return;
}
String mappingJson = mappingObj.toString();
```

---

## Info

### IN-01: Hardcoded `PGPASSWORD=tessera` and credentials in `dr_drill.sh`

**File:** `scripts/dr_drill.sh:67,78,79,155,175`
**Issue:** The password `tessera` is hardcoded in multiple places in the drill script. This is a dev-only Docker drill script (not production), and the password matches the `application.yml` dev default, so this is not a production secret exposure. However, it creates a coupling: if the dev default password is ever changed in `docker-compose.yml` or `application.yml`, the drill script breaks silently.

**Fix:** Extract to a variable at the top of the script:
```bash
DB_PASSWORD="${DR_DB_PASSWORD:-tessera}"
```
Then replace all `PGPASSWORD=tessera` occurrences with `PGPASSWORD=$DB_PASSWORD`.

---

### IN-02: `CircleadDrillSmokeIT` imports `Map` from `java.util` but uses an anonymous class — minor unused path

**File:** `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadDrillSmokeIT.java:74`
**Issue:** The `executeTenantCypher` override uses `java.util.Map` in its return type (`List<java.util.Map<String, Object>>`). The import at line 33 (`import java.util.Map;`) and the fully-qualified name at line 74 (`java.util.Map`) are redundant — the imported `Map` could be used directly. This is a minor inconsistency.

**Fix:** Remove the fully-qualified path on line 74; use the already-imported `Map`:
```java
public List<Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher) {
```

---

### IN-03: `application.yml` exposes bootstrap token with empty default — boot guard needed

**File:** `fabric-app/src/main/resources/application.yml:43`
**Issue:** `bootstrap-token: "${TESSERA_BOOTSTRAP_TOKEN:}"` resolves to an empty string when the environment variable is absent. Depending on how the bootstrap token is validated (whether an empty string is treated as "disabled" vs "any caller passes"), an empty token could be a security gap. This is informational because the actual guard logic is in the application code (not reviewed here), but the pattern is worth flagging for awareness.

**Fix:** Verify that the bootstrap token handler rejects or ignores empty-string tokens, or change the default to something that explicitly means "disabled":
```yaml
bootstrap-token: "${TESSERA_BOOTSTRAP_TOKEN:DISABLED}"
```
and guard on the literal value `"DISABLED"` in the handler.

---

_Reviewed: 2026-04-17_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
