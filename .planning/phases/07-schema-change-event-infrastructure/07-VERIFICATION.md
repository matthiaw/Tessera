---
phase: 07-schema-change-event-infrastructure
verified: 2026-04-17T00:00:00Z
status: passed
score: 3/3
overrides_applied: 0
---

# Phase 7: SchemaChangeEvent Infrastructure — Verification Report

**Phase Goal:** Create a `SchemaChangeEvent` application event in fabric-core, publish it from SchemaRegistry on type/property mutations, and wire listeners in SqlViewProjection (view regeneration) and SpringAiMcpAdapter (client notification) — so projections stay fresh after runtime schema changes without restart.
**Verified:** 2026-04-17
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Creating or modifying a node type via SchemaRegistry publishes a SchemaChangeEvent | VERIFIED | `publisher.publishEvent(new SchemaChangeEvent(...))` present in all 8 mutating methods of `SchemaRegistry.java` (createNodeType, updateNodeTypeDescription, deprecateNodeType, addProperty, deprecateProperty, createEdgeType, renameProperty, removeRequiredPropertyOrReject). `ApplicationEventPublisher` injected via constructor. 8 unit tests in `SchemaRegistryEventPublishingTest` verify each call with ArgumentCaptor. |
| 2 | SqlViewProjection regenerates affected views within seconds of a schema change — no restart required | VERIFIED | `SqlViewProjection.java` contains `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` on `onSchemaChange(SchemaChangeEvent)` which calls `regenerateForTenant(TenantContext.of(event.modelId()))`. AFTER_COMMIT chosen deliberately (deviation from plan's `@EventListener`) to avoid aborting the parent transaction when a staleness-check query fails on a non-existent view. Integration test `SqlViewSchemaChangeIT` verifies end-to-end event delivery without exception propagation. |
| 3 | SpringAiMcpAdapter.notifySchemaChanged() is called on schema change, notifying connected MCP clients | VERIFIED | `SpringAiMcpAdapter.java` contains `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` on `onSchemaChange(SchemaChangeEvent)` which calls `notifySchemaChanged()`. `notifySchemaChanged()` calls `mcpServer.notifyToolsListChanged()`. TODO comment from plan was removed. Unit test `SchemaChangeMcpWiringTest` verifies `notifyToolsListChanged()` is called exactly once. |

**Score:** 3/3 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `fabric-core/src/main/java/dev/tessera/core/schema/SchemaChangeEvent.java` | SchemaChangeEvent record type | VERIFIED | `public record SchemaChangeEvent(UUID modelId, String changeType, String typeSlug)` — exists, substantive, imported by both projection adapters |
| `fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java` | Event publishing from all 8 mutating methods | VERIFIED | Contains `ApplicationEventPublisher publisher` field, injected via constructor. Grep confirms 8 `publisher.publishEvent` calls. |
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java` | @EventListener for SQL view regeneration | VERIFIED | Contains `onSchemaChange(SchemaChangeEvent)` annotated with `@TransactionalEventListener(AFTER_COMMIT)`. Calls `regenerateForTenant(TenantContext.of(event.modelId()))`. Exceptions caught and logged. |
| `fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java` | @EventListener for MCP client notification | VERIFIED | Contains `onSchemaChange(SchemaChangeEvent)` annotated with `@TransactionalEventListener(AFTER_COMMIT)`. Calls `notifySchemaChanged()`. TODO comment removed. |
| `fabric-core/src/test/java/dev/tessera/core/schema/SchemaRegistryEventPublishingTest.java` | 8 unit tests verifying publish calls | VERIFIED | All 8 test methods present (createNodeType, updateNodeTypeDescription, deprecateNodeType, addProperty, deprecateProperty, createEdgeType, renameProperty, removeRequiredPropertyOrReject), each using ArgumentCaptor to assert modelId, changeType, typeSlug. |
| `fabric-projections/src/test/java/dev/tessera/projections/sql/SchemaChangeEventWiringTest.java` | Unit test for SQL listener wiring | VERIFIED | 2 tests: (1) verifies `regenerateForTenant` called with correct `TenantContext`, (2) verifies exceptions do not propagate. Uses Mockito spy. |
| `fabric-projections/src/test/java/dev/tessera/projections/mcp/SchemaChangeMcpWiringTest.java` | Unit test for MCP listener wiring | VERIFIED | 1 test verifying `mcpServer.notifyToolsListChanged()` called exactly once on `onSchemaChange`. |
| `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewSchemaChangeIT.java` | Integration test, @Disabled removed | VERIFIED | No `@Disabled` annotation present. `@ActiveProfiles("projection-it")` added. Two tests: `viewRegeneratedOnSchemaChange` and `viewsSurviveApplicationRestart`. Uses Testcontainers with AGE image. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| SchemaRegistry | SqlViewProjection | ApplicationEventPublisher -> @TransactionalEventListener(SchemaChangeEvent) | WIRED | `publisher.publishEvent(new SchemaChangeEvent(...))` in all 8 methods; `SqlViewProjection.onSchemaChange` receives event and calls `regenerateForTenant` |
| SchemaRegistry | SpringAiMcpAdapter | ApplicationEventPublisher -> @TransactionalEventListener(SchemaChangeEvent) | WIRED | Same publisher path; `SpringAiMcpAdapter.onSchemaChange` receives event and calls `notifySchemaChanged()` -> `mcpServer.notifyToolsListChanged()` |

### Data-Flow Trace (Level 4)

Not applicable — this phase adds event infrastructure (publish/subscribe wiring), not data-rendering components. The listeners trigger side effects (DDL and MCP notification), not data presentation to users.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| SchemaChangeEvent record compiles and is importable | Confirmed by integration test context loading (SpringBootTest) | Imported by both SqlViewProjection and SpringAiMcpAdapter without compilation errors | PASS |
| 8 publish calls in SchemaRegistry | Grep count on SchemaRegistry.java | 8 matches for `publisher.publishEvent` | PASS |
| No residual TODO in SpringAiMcpAdapter | Grep for "TODO Plan 03" | No matches | PASS |
| SqlViewSchemaChangeIT has no @Disabled | Grep for @Disabled in IT file | No matches | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| SQL-02 | 07-01-PLAN.md | Views bypass Cypher for aggregate queries, reading AGE label tables directly; regenerated on schema change | SATISFIED | `SqlViewProjection.onSchemaChange` wired via `@TransactionalEventListener(AFTER_COMMIT)` calls `regenerateForTenant`. Integration test `SqlViewSchemaChangeIT` verifies end-to-end event propagation. |
| MCP-08 | 07-01-PLAN.md | MCP tool set dynamically registered from Schema Registry — adding a type surfaces new tools without redeploy | SATISFIED | `SpringAiMcpAdapter.onSchemaChange` wired via `@TransactionalEventListener(AFTER_COMMIT)` calls `notifySchemaChanged()` -> `mcpServer.notifyToolsListChanged()`. Unit test verifies the call chain. |

**Orphaned requirements check:** REQUIREMENTS.md traceability table maps SQL-02 and MCP-08 to Phase 7. No additional Phase 7 requirements found in REQUIREMENTS.md.

### Anti-Patterns Found

No anti-patterns detected:
- No TODO/FIXME/placeholder comments in any of the 8 modified/created files
- No stub implementations (all `onSchemaChange` methods have real logic)
- No hardcoded empty returns
- No `return null` or empty collection returns
- Commits f255b4c and 5797846 both present in git log

### Human Verification Required

None. All must-haves are verifiable programmatically. The behavioral correctness of `@TransactionalEventListener(AFTER_COMMIT)` (fires after DB commit in a separate connection) is confirmed by the integration test's `assertThatCode(...).doesNotThrowAnyException()` pattern which would fail if the transaction abort bug were present.

### Notable Deviation (Accepted)

The plan specified `@EventListener` but the implementation correctly uses `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`. This deviation is an improvement: plain `@EventListener` fires synchronously inside the `@Transactional` SchemaRegistry method, causing Postgres to abort the parent transaction when the view staleness check queries a non-existent view (PSQLException propagates even when caught in Java, because Postgres marks the connection's transaction as ABORTED). AFTER_COMMIT fires after the schema mutation is durably committed, in a separate connection context. The roadmap success criteria specify the behavioral outcome (views regenerated, MCP clients notified) — not the Spring annotation type — so this deviation satisfies all three truths.

### Gaps Summary

No gaps. All 3 roadmap success criteria satisfied. Both requirement IDs (SQL-02, MCP-08) covered by substantive implementations with unit and integration test coverage.

---

_Verified: 2026-04-17_
_Verifier: Claude (gsd-verifier)_
