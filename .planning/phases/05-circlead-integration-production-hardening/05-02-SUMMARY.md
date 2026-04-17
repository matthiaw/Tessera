---
phase: 05-circlead-integration-production-hardening
plan: "02"
subsystem: connectors
tags: [circlead, wiremock, rest-connector, mapping-definition, integration-test, documentation]
dependency_graph:
  requires:
    - nyquist-test-stubs-phase5
  provides:
    - circlead-role-mapping-json
    - circlead-circle-mapping-json
    - circlead-activity-mapping-json
    - circlead-connector-config-bean
    - circlead-connector-integration-tests
    - circlead-mapping-documentation
  affects:
    - fabric-connectors
    - docs
tech_stack:
  added: []
  patterns:
    - MappingDefinition JSON classpath resources loaded via @Value Resource + ObjectMapper.readValue() in @Configuration
    - WireMock urlPathEqualTo + withQueryParam for stub matching query-parameterized circlead endpoints
    - Anonymous GraphRepository with findShortestPath + executeTenantCypher no-ops for test isolation
key_files:
  created:
    - fabric-connectors/src/main/resources/connectors/circlead-role-mapping.json
    - fabric-connectors/src/main/resources/connectors/circlead-circle-mapping.json
    - fabric-connectors/src/main/resources/connectors/circlead-activity-mapping.json
    - fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java
    - docs/circlead-mapping.md
  modified:
    - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java
    - fabric-connectors/src/test/java/dev/tessera/connectors/rest/RestPollingConnectorIT.java
    - fabric-connectors/src/test/java/dev/tessera/connectors/rest/EtagDeltaDetectionIT.java
    - fabric-connectors/src/test/java/dev/tessera/connectors/rest/ConnectorAdminCrudIT.java
    - fabric-connectors/src/test/java/dev/tessera/connectors/review/ExtractionReviewControllerIT.java
key_decisions:
  - "JSON field key for FieldMapping second parameter is sourcePath (camelCase), not source — patterns doc had the wrong key; verified against FieldMapping record definition"
  - "urlPathEqualTo + withQueryParam used in tests rather than urlEqualTo with full query string — WireMock query param matching is more robust against parameter ordering"
  - "findShortestPath and executeTenantCypher no-op implementations added to all anonymous GraphRepository instances — GraphRepository interface gained these abstract methods in a prior phase; pre-existing test-compile was broken before this plan"
patterns_established:
  - "Pattern: @Value(classpath:connectors/...) Resource + ObjectMapper.readValue() for loading MappingDefinition beans from JSON classpath resources"
  - "Pattern: WireMock urlPathEqualTo + withQueryParam for matching circlead-style query-parameterized endpoints"
requirements_completed: [CIRC-01, CIRC-02, CIRC-03]
duration: 7min
completed: "2026-04-17"
---

# Phase 5 Plan 02: Circlead Integration Summary

**3 MappingDefinition JSONs (Role/Circle/Activity with circlead_id identity), CircleadConnectorConfig @Configuration bean, 4 WireMock integration tests proving GenericRestPollerConnector polls circlead endpoints correctly, and circlead-mapping.md documenting field mappings, edge types, connector config, and Spring Retry graceful degradation.**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-17T10:50:55Z
- **Completed:** 2026-04-17T10:57:49Z
- **Tasks:** 2
- **Files created:** 5
- **Files modified:** 5

## Accomplishments

- 3 JSON classpath resources define field-level mappings for circlead Role, Circle, and Activity entities with `circlead_id` as the identity field for delta dedup
- `CircleadConnectorConfig` wires the 3 mappings as named Spring beans (`circleadRoleMapping`, `circleadCircleMapping`, `circleadActivityMapping`) by reading classpath resources via `ObjectMapper.readValue()`
- 4 `CircleadConnectorIT` tests verify the connector polls WireMock-stubbed circlead endpoints and produces correct `CandidateMutation` properties (title, circlead_id, abbreviation, status) including graceful empty-response handling
- `docs/circlead-mapping.md` documents all entity mappings, edge types, REST endpoints, connector configuration, Spring Retry circuit breaker pattern, and round-trip verification steps

## Task Commits

1. **Task 1: Circlead mapping JSONs + CircleadConnectorConfig + CircleadConnectorIT** - `c2abddf` (feat)
2. **Task 2: circlead-mapping.md documentation** - `f7baaf8` (docs)

## Files Created/Modified

- `fabric-connectors/src/main/resources/connectors/circlead-role-mapping.json` — Role MappingDefinition JSON (6 field mappings, circlead_id identity)
- `fabric-connectors/src/main/resources/connectors/circlead-circle-mapping.json` — Circle MappingDefinition JSON (5 field mappings, circlead_id identity)
- `fabric-connectors/src/main/resources/connectors/circlead-activity-mapping.json` — Activity MappingDefinition JSON (5 field mappings, circlead_id identity)
- `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java` — @Configuration with 3 @Bean methods loading mapping JSONs from classpath
- `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` — 4 WireMock tests (role, circle, activity, empty response)
- `docs/circlead-mapping.md` — Full integration reference: field tables, edge types, endpoints, connector config, graceful degradation, round-trip verification
- `fabric-connectors/src/test/java/dev/tessera/connectors/rest/RestPollingConnectorIT.java` — Added `findShortestPath` + `executeTenantCypher` to 2 anonymous GraphRepository impls (bug fix)
- `fabric-connectors/src/test/java/dev/tessera/connectors/rest/EtagDeltaDetectionIT.java` — Same bug fix (1 impl)
- `fabric-connectors/src/test/java/dev/tessera/connectors/rest/ConnectorAdminCrudIT.java` — Same bug fix (1 impl)
- `fabric-connectors/src/test/java/dev/tessera/connectors/review/ExtractionReviewControllerIT.java` — Same bug fix (1 impl)

## Decisions Made

- JSON field key for `FieldMapping`'s second constructor parameter is `sourcePath` (camelCase), not `source` — the patterns doc example used `"source"` which would silently fail JSON deserialization; verified against the `FieldMapping` record definition and `MappingDefinitionValidationTest`
- `urlPathEqualTo` + `withQueryParam` matcher used in WireMock stubs rather than `urlEqualTo` with the full query string — more robust against query parameter ordering differences
- `findShortestPath` and `executeTenantCypher` are interface-level abstract methods added to `GraphRepository` in Phase 3; all anonymous test implementations were missing them, causing a pre-existing broken test-compile on master that needed fixing as part of this plan

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Added `findShortestPath` and `executeTenantCypher` to anonymous GraphRepository implementations in 4 existing test files**

- **Found during:** Task 1 (first test-compile attempt)
- **Issue:** `GraphRepository` interface gained `findShortestPath(TenantContext, UUID, UUID)` and `executeTenantCypher(TenantContext, String)` as abstract methods during Phase 3 MCP work. All anonymous `GraphRepository` implementations in `fabric-connectors` test files were missing these methods, causing a compilation failure. The test-compile was already broken on master before this plan started.
- **Fix:** Added `findShortestPath` returning `List.of()` and `executeTenantCypher` returning `List.of()` to all 5 affected anonymous implementations (1 in `CircleadConnectorIT`, 2 in `RestPollingConnectorIT`, 1 in `EtagDeltaDetectionIT`, 1 in `ConnectorAdminCrudIT`, 1 in `ExtractionReviewControllerIT`).
- **Files modified:** `CircleadConnectorIT.java`, `RestPollingConnectorIT.java`, `EtagDeltaDetectionIT.java`, `ConnectorAdminCrudIT.java`, `ExtractionReviewControllerIT.java`
- **Verification:** `./mvnw test -pl fabric-connectors -Dtest=CircleadConnectorIT` — BUILD SUCCESS, 4 tests run, 0 failures
- **Committed in:** `c2abddf` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug)
**Impact on plan:** Fix was necessary to compile and run the tests. No scope creep.

## Issues Encountered

None beyond the pre-existing broken test-compile documented above.

## Known Stubs

None — all deliverables are fully implemented with real logic.

## Threat Surface

No new network endpoints introduced. The 3 JSON mapping files are classpath read-only resources (T-05-02-02: accepted — changes require rebuild+deploy). `CircleadConnectorConfig` exposes no new REST endpoints. Bearer token authentication for the circlead API must be stored in Vault (T-05-02-01 mitigation documented in `circlead-mapping.md` section 4.4).

## Next Phase Readiness

- 3 circlead entity types (Role, Circle, Activity) are ready for graph ingestion via `GenericRestPollerConnector`
- Edge type schemas (BELONGS_TO, RESPONSIBLE_FOR, PARENT_OF) require Schema Registry configuration before edge creation can proceed
- Plans 03 and 04 can proceed independently (EventRetentionJob + EventSnapshotService, DR drill)

## Self-Check: PASSED
