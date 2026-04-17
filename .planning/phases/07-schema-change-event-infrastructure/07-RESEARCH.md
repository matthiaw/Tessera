# Phase 7: SchemaChangeEvent Infrastructure - Research

**Researched:** 2026-04-17
**Domain:** Spring ApplicationEvent publishing from SchemaRegistry + @EventListener wiring in SqlViewProjection and SpringAiMcpAdapter
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SQL-02 | Views bypass Cypher for aggregate queries, reading AGE label tables directly — views are regenerated when schema changes and survive restart | SqlViewProjection already has `regenerateForTenant(TenantContext)` and `regenerateAll()`. The missing wiring is: SchemaRegistry must publish an event after each mutation, and SqlViewProjection must listen and call `regenerateForTenant`. |
| MCP-08 | MCP tool set is dynamically registered from the Schema Registry — adding a type surfaces new tools without redeploy (fallback: restart on schema change if Spring AI doesn't support runtime registration) | SpringAiMcpAdapter.notifySchemaChanged() already calls `mcpServer.notifyToolsListChanged()`. The missing wiring is: SchemaRegistry must publish the event, and SpringAiMcpAdapter must listen and call `notifySchemaChanged()`. |

</phase_requirements>

---

## Summary

This phase closes a single integration gap: SchemaRegistry mutates schema but never notifies projections. The SQL view projection and MCP adapter both have the receiver-side code already written — they just lack the trigger. The work is a pure wiring exercise: create one new event record type in `fabric-core`, publish it from every schema-mutating method in `SchemaRegistry`, and add `@EventListener` methods in `SqlViewProjection` (already in `fabric-projections`) and `SpringAiMcpAdapter` (also in `fabric-projections`).

No new libraries are required. Spring's `ApplicationEventPublisher` is already used in the codebase (`OutboxPoller`, `ConnectorRegistry`) and is available in `fabric-core` via `spring-boot-starter`. The existing `GraphEventPublished` record in `fabric-core.events` is the canonical pattern to follow for the new `SchemaChangeEvent` record. The module dependency direction is: `fabric-core` publishes the event; `fabric-projections` (which already depends on `fabric-core`) listens to it.

The only design decision of substance is the event payload: the minimal viable payload is `(UUID modelId, String changeType, String typeSlug)` — enough for `SqlViewProjection` to call `regenerateForTenant` for the right tenant and log the event type. `SpringAiMcpAdapter` ignores the payload entirely (it calls `mcpServer.notifyToolsListChanged()` unconditionally).

**Primary recommendation:** Define `SchemaChangeEvent` as a Java `record` in `dev.tessera.core.schema` (same package as `SchemaRegistry`), inject `ApplicationEventPublisher` into `SchemaRegistry`, publish after each mutation method before returning, and add `@EventListener` methods in both `SqlViewProjection` and `SpringAiMcpAdapter`.

---

## Standard Stack

### Core (all already on classpath — no new dependencies)

| Library | Version | Purpose | Module |
|---------|---------|---------|--------|
| `spring-context` (via `spring-boot-starter`) | Spring Boot 3.5.x BOM | `ApplicationEventPublisher`, `@EventListener` | `fabric-core` (transitive), `fabric-projections` (transitive) |
| `spring-boot-starter` | 3.5.13 | Already in `fabric-core/pom.xml` | Direct |

[VERIFIED: fabric-core/pom.xml — `spring-boot-starter` is a direct dependency, giving `ApplicationEventPublisher`]
[VERIFIED: fabric-projections/pom.xml — `spring-boot-starter-web` brings Spring context, `@EventListener` available]

**No new POM entries required.** Spring's event infrastructure is part of `spring-context`, which is the foundation of every Spring Boot module already in the project.

### Existing Event Precedents in Codebase

| Event Class | Location | Pattern Used |
|-------------|----------|--------------|
| `GraphEventPublished` | `fabric-core/src/main/java/dev/tessera/core/events/` | Java record, published via `ApplicationEventPublisher.publishEvent()` |
| `ConnectorMutatedEvent` | `fabric-connectors/src/main/java/dev/tessera/connectors/` | Java record, `@EventListener` in `ConnectorRegistry` |

[VERIFIED: Both files read in this session]

---

## Architecture Patterns

### Module Dependency Constraints (ArchUnit-enforced)

```
fabric-core        ← cannot depend on fabric-rules, fabric-projections, fabric-connectors, fabric-app
fabric-projections → fabric-core, fabric-rules (CANNOT depend on fabric-connectors, fabric-app)
```

The event wiring respects this completely:
- `SchemaChangeEvent` lives in `fabric-core` (no upstream deps)
- `SchemaRegistry` (in `fabric-core`) publishes the event
- `SqlViewProjection` and `SpringAiMcpAdapter` (both in `fabric-projections`) listen to it

[VERIFIED: ModuleDependencyTest.java — fabric_core_should_not_depend_on_others, fabric_projections_should_not_depend_on_connectors_or_app]

### Recommended Project Structure (additions only)

```
fabric-core/src/main/java/dev/tessera/core/schema/
├── SchemaChangeEvent.java     ← NEW: event record
└── SchemaRegistry.java        ← MODIFY: inject publisher, publish after mutations

fabric-projections/src/main/java/dev/tessera/projections/sql/
└── SqlViewProjection.java     ← MODIFY: add @EventListener method

fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/
└── SpringAiMcpAdapter.java    ← MODIFY: add @EventListener method

fabric-projections/src/test/java/dev/tessera/projections/sql/
└── SqlViewSchemaChangeIT.java ← MODIFY: implement @Disabled stub tests
```

### Pattern 1: SchemaChangeEvent Record

Follows the `GraphEventPublished` and `ConnectorMutatedEvent` precedents exactly.

```java
// Source: codebase pattern — GraphEventPublished.java [VERIFIED]
// Place: fabric-core/src/main/java/dev/tessera/core/schema/SchemaChangeEvent.java
package dev.tessera.core.schema;

import java.util.UUID;

/**
 * Published via ApplicationEventPublisher when SchemaRegistry mutates
 * a node type, property, or edge type. Listeners in fabric-projections
 * use this to regenerate SQL views and notify MCP clients.
 *
 * @param modelId     tenant scope of the schema change
 * @param changeType  one of: CREATE_TYPE, UPDATE_TYPE, DEPRECATE_TYPE,
 *                    ADD_PROPERTY, DEPRECATE_PROPERTY, REMOVE_PROPERTY,
 *                    RENAME_PROPERTY, CREATE_EDGE_TYPE
 * @param typeSlug    the node type or edge type affected
 */
public record SchemaChangeEvent(UUID modelId, String changeType, String typeSlug) {}
```

### Pattern 2: SchemaRegistry publishes events

Inject `ApplicationEventPublisher` into `SchemaRegistry` via constructor. Publish after the `cache.invalidateAll()` call in each mutating method (post-invalidation ensures listeners see the fresh schema).

```java
// Source: current SchemaRegistry constructor [VERIFIED]
// Modified constructor signature:
public SchemaRegistry(
        SchemaRepository repo,
        SchemaVersionService versions,
        SchemaAliasService aliases,
        SchemaDescriptorCache cache,
        SchemaChangeReplayer replayer,
        ApplicationEventPublisher publisher) {   // ADD this parameter
    this.repo = repo;
    this.versions = versions;
    this.aliases = aliases;
    this.cache = cache;
    this.replayer = replayer;
    this.publisher = publisher;               // ADD this field
}
```

Publish call at end of each mutating method (example for `createNodeType`):

```java
@Transactional(propagation = Propagation.REQUIRED)
public NodeTypeDescriptor createNodeType(TenantContext ctx, CreateNodeTypeSpec spec) {
    repo.insertNodeType(ctx, spec);
    String payload = ...;
    versions.applyChange(ctx, "CREATE_TYPE", payload, "schema-registry");
    cache.invalidateAll(ctx.modelId());
    publisher.publishEvent(new SchemaChangeEvent(ctx.modelId(), "CREATE_TYPE", spec.slug())); // ADD
    return repo.findNodeType(...)...;
}
```

**Every mutating method needs the publish call:**
1. `createNodeType` — changeType: `CREATE_TYPE`, typeSlug: `spec.slug()`
2. `updateNodeTypeDescription` — changeType: `UPDATE_TYPE`, typeSlug: parameter
3. `deprecateNodeType` — changeType: `DEPRECATE_TYPE`, typeSlug: parameter
4. `addProperty` — changeType: `ADD_PROPERTY`, typeSlug: parameter
5. `deprecateProperty` — changeType: `DEPRECATE_PROPERTY`, typeSlug: parameter
6. `createEdgeType` — changeType: `CREATE_EDGE_TYPE`, typeSlug: `spec.slug()`
7. `renameProperty` — changeType: `RENAME_PROPERTY`, typeSlug: parameter
8. `removeRequiredPropertyOrReject` — changeType: `REMOVE_PROPERTY`, typeSlug: parameter

[VERIFIED: All 8 methods enumerated by reading SchemaRegistry.java in this session]

### Pattern 3: SqlViewProjection @EventListener

`SqlViewProjection` already implements `ApplicationRunner` for startup regeneration. Add a `@EventListener` method:

```java
// Source: SqlViewProjection.java [VERIFIED] + ConnectorRegistry @EventListener pattern [VERIFIED]
// Add to SqlViewProjection:
import dev.tessera.core.schema.SchemaChangeEvent;
import org.springframework.context.event.EventListener;

@EventListener
public void onSchemaChange(SchemaChangeEvent event) {
    log.info("SqlViewProjection: schema change {}/{} — regenerating views for model_id={}",
             event.changeType(), event.typeSlug(), event.modelId());
    try {
        regenerateForTenant(TenantContext.of(event.modelId()));
    } catch (Exception e) {
        log.warn("SqlViewProjection: regeneration failed for model_id={} after schema change: {}",
                 event.modelId(), e.getMessage(), e);
    }
}
```

**Why not regenerateAll():** `regenerateAll()` scans all tenants which is expensive. The event carries `modelId` directly — only the affected tenant needs regeneration.

### Pattern 4: SpringAiMcpAdapter @EventListener

`SpringAiMcpAdapter` already has `notifySchemaChanged()` with a TODO comment pointing to exactly this phase.

```java
// Source: SpringAiMcpAdapter.java line 99 [VERIFIED] — existing TODO:
// "TODO Plan 03: wire to SchemaChangeEvent via ApplicationListener once
//  the event type is defined in fabric-core."

// Add to SpringAiMcpAdapter:
import dev.tessera.core.schema.SchemaChangeEvent;
import org.springframework.context.event.EventListener;

@EventListener
public void onSchemaChange(SchemaChangeEvent event) {
    log.debug("SpringAiMcpAdapter: schema change {}/{} — notifying MCP clients",
              event.changeType(), event.typeSlug());
    notifySchemaChanged();
}
```

The TODO in SpringAiMcpAdapter is explicit evidence that this wiring was intentionally deferred to this phase. [VERIFIED: SpringAiMcpAdapter.java lines 95-102]

### Anti-Patterns to Avoid

- **Publishing inside the transaction (before commit):** `@EventListener` is synchronous by default and will execute during the open transaction. This is acceptable here — SqlViewProjection's `regenerateForTenant` executes DDL (`CREATE OR REPLACE VIEW`) which is auto-committed in Postgres (DDL cannot be in the same TX as the schema change). However, if the SchemaRegistry transaction rolls back, the listener has already run. Use `@TransactionalEventListener(phase = AFTER_COMMIT)` if strict transactional consistency is required.
- **@TransactionalEventListener pitfall in tests:** `@TransactionalEventListener` does not fire in tests that use `@Transactional` rollback on test methods — the test transaction never commits. If integration tests use `@Transactional`, use plain `@EventListener` or restructure tests to commit before asserting.
- **Async listeners (`@Async @EventListener`):** Do not use `@Async` for these listeners. SQL view DDL must complete synchronously before the schema change API returns to the caller (success criterion 2: "within seconds"). Async introduces non-determinism in tests.
- **Importing `fabric-projections` types in `fabric-core`:** `SchemaChangeEvent` must have no dependency on projections. Keep it a simple data record.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Cross-module notification | Custom observer interface, callback list, or direct method calls from `fabric-core` to `fabric-projections` | `ApplicationEventPublisher` + `@EventListener` | ApplicationEventPublisher respects Spring's component scanning and keeps `fabric-core` ignorant of listeners; direct calls would violate the ArchUnit dependency direction |
| Event queue / message broker for schema changes | Kafka topic, database poll loop | In-process Spring events | Schema changes are low-frequency (human-driven API calls), sub-second latency is trivially achievable in-process; no broker needed for MVP |
| Custom event hierarchy / marker interfaces | Abstract `TesseraEvent` parent class | Simple Java record | Records are canonical for Spring events since Spring 6 / Boot 3 |

---

## Common Pitfalls

### Pitfall 1: @TransactionalEventListener vs @EventListener test incompatibility

**What goes wrong:** Developer uses `@TransactionalEventListener(phase = AFTER_COMMIT)` for correctness. Integration tests that annotate test methods with `@Transactional` never see the listener fire because the test transaction is rolled back, not committed.
**Why it happens:** `@TransactionalEventListener` binds to the commit phase. A test-method-level `@Transactional` marks the TX for rollback, so the commit phase never triggers.
**How to avoid:** Use plain `@EventListener` for this phase — the use case is DDL regeneration, not data integrity. If strict AFTER_COMMIT behavior is required in production, restructure ITs to not use test-method `@Transactional` (e.g., manually clean up via `@AfterEach` DELETE).
**Warning signs:** Listener method never called in ITs; `assertThat(activeViews).isNotEmpty()` fails even after schema change.

### Pitfall 2: View DDL already exists — staleness check skips regeneration

**What goes wrong:** Test creates a node type, calls `regenerateForTenant`, asserts view exists. Then modifies schema, publishes event, expects view to be updated — but the view is NOT updated because `viewIsCurrentVersion()` returns true (version didn't bump yet in the same TX).
**Why it happens:** `SchemaVersionService.currentVersion()` reads from DB. If the schema change that bumped the version and the event listener's `regenerateForTenant()` call run within the same TX before the version row is flushed, the listener sees the old version.
**How to avoid:** Publish the event AFTER `cache.invalidateAll()` and AFTER `versions.applyChange()` has been called — already the natural ordering if the publish call is placed at the very end of the mutating method. With `@EventListener` (synchronous in same TX), the version row is already written by the time `regenerateForTenant` calls `schemaVersionService.currentVersion()` — JPA/JDBC within the same TX will see the dirty write. Verify with an integration test that checks the view DDL contains the updated schema version after the event fires.
**Warning signs:** `viewIsCurrentVersion` always returns true in integration tests; view DDL does not reflect the latest property after a schema change.

### Pitfall 3: SchemaRegistry constructor breaks existing test fixtures

**What goes wrong:** Adding `ApplicationEventPublisher publisher` as a constructor parameter breaks all existing tests that construct `SchemaRegistry` directly (e.g., `SchemaNodeTypeCrudIT`, `SchemaPropertyCrudIT`). Compilation fails.
**Why it happens:** Constructor injection is used; Spring auto-injects `ApplicationEventPublisher` in production but test fixtures constructing the class manually miss the new parameter.
**How to avoid:** In integration tests (`SchemaNodeTypeCrudIT` etc.) that use `@SpringBootTest`, Spring auto-injects `ApplicationEventPublisher` — these tests are safe. Only unit tests that `new SchemaRegistry(...)` manually would break. Check for manual construction: grep shows only `@SpringBootTest`-backed ITs using `@Autowired SchemaRegistry` — no manual construction found.
**Warning signs:** Compilation errors like `constructor SchemaRegistry(SchemaRepository, SchemaVersionService, SchemaAliasService, SchemaDescriptorCache, SchemaChangeReplayer) is not applicable`.

[VERIFIED: All SchemaRegistry test files use @SpringBootTest + @Autowired, not manual construction]

### Pitfall 4: Event published from within @Transactional method — listener runs mid-TX

**What goes wrong:** `SqlViewProjection.onSchemaChange()` calls `regenerateForTenant()` which calls `jdbc.getJdbcTemplate().execute(ddl)` — executing DDL inside the still-open schema-change transaction. In Postgres, DDL inside an open transaction is allowed but may cause locking issues if any other session holds a lock on the view being replaced.
**Why it happens:** Spring's default `@EventListener` runs synchronously in the calling thread and shares the transaction of the publisher.
**How to avoid:** DDL `CREATE OR REPLACE VIEW` in Postgres is safe to run mid-transaction (no conflicting locks expected since the view did not exist before). The `viewIsCurrentVersion` staleness check provides idempotency. For robustness in high-concurrency production scenarios, the planner may opt for `@TransactionalEventListener(AFTER_COMMIT)` and accept the test restructuring cost.

### Pitfall 5: Missing import in SqlViewProjection causes a silent no-op

**What goes wrong:** Listener method compiles but never fires because the event class is `dev.tessera.core.schema.SchemaChangeEvent` but the import resolves to a different class (e.g., if a stub/placeholder class was accidentally created elsewhere).
**Why it happens:** Java event listener matching is by exact class type — Spring will not dispatch a `SchemaChangeEvent` to a listener declared for a differently-named type.
**How to avoid:** Use the fully-qualified class name in the import. Verify with an IT that the listener method fires after a `createNodeType` call.

---

## Code Examples

### SchemaChangeEvent record

```java
// Source: codebase pattern — GraphEventPublished.java [VERIFIED]
// File: fabric-core/src/main/java/dev/tessera/core/schema/SchemaChangeEvent.java
package dev.tessera.core.schema;

import java.util.UUID;

/**
 * Published via ApplicationEventPublisher when SchemaRegistry mutates
 * a node type, property, or edge type for a given tenant.
 * Consumed by SqlViewProjection (view regeneration) and
 * SpringAiMcpAdapter (MCP client notification).
 *
 * @param modelId    tenant UUID
 * @param changeType CREATE_TYPE | UPDATE_TYPE | DEPRECATE_TYPE |
 *                   ADD_PROPERTY | DEPRECATE_PROPERTY | REMOVE_PROPERTY |
 *                   RENAME_PROPERTY | CREATE_EDGE_TYPE
 * @param typeSlug   affected node/edge type slug
 */
public record SchemaChangeEvent(UUID modelId, String changeType, String typeSlug) {}
```

### SchemaRegistry constructor addition

```java
// Source: SchemaRegistry.java constructor [VERIFIED]
private final ApplicationEventPublisher publisher; // ADD field

public SchemaRegistry(
        SchemaRepository repo,
        SchemaVersionService versions,
        SchemaAliasService aliases,
        SchemaDescriptorCache cache,
        SchemaChangeReplayer replayer,
        ApplicationEventPublisher publisher) { // ADD parameter
    this.repo = repo;
    this.versions = versions;
    this.aliases = aliases;
    this.cache = cache;
    this.replayer = replayer;
    this.publisher = publisher;               // ADD assignment
}
```

### SchemaRegistry publish call (example — replicate in all 8 mutating methods)

```java
// Source: SchemaRegistry.createNodeType [VERIFIED] — add publish at end of method body
@Transactional(propagation = Propagation.REQUIRED)
public NodeTypeDescriptor createNodeType(TenantContext ctx, CreateNodeTypeSpec spec) {
    repo.insertNodeType(ctx, spec);
    String payload = "{\"changeType\":\"CREATE_TYPE\",\"typeSlug\":\"" + spec.slug() + "\"}";
    versions.applyChange(ctx, "CREATE_TYPE", payload, "schema-registry");
    cache.invalidateAll(ctx.modelId());
    // ADD: publish event after all mutations are applied
    publisher.publishEvent(new SchemaChangeEvent(ctx.modelId(), "CREATE_TYPE", spec.slug()));
    return repo.findNodeType(ctx, spec.slug(), versions.currentVersion(ctx))
            .orElseThrow(() -> new IllegalStateException("createNodeType: inserted but not found"));
}
```

### SqlViewProjection @EventListener

```java
// Source: SqlViewProjection.java [VERIFIED] + ConnectorRegistry pattern [VERIFIED]
// Add to SqlViewProjection.java:
import dev.tessera.core.schema.SchemaChangeEvent;
import org.springframework.context.event.EventListener;

/**
 * SQL-02: Regenerate SQL views for the affected tenant when the schema changes.
 * Runs synchronously in the publisher's thread (SchemaRegistry method call).
 */
@EventListener
public void onSchemaChange(SchemaChangeEvent event) {
    log.info("SqlViewProjection: regenerating views for model_id={} after {} on type '{}'",
             event.modelId(), event.changeType(), event.typeSlug());
    try {
        regenerateForTenant(TenantContext.of(event.modelId()));
    } catch (Exception e) {
        log.warn("SqlViewProjection: regeneration failed for model_id={}: {}",
                 event.modelId(), e.getMessage(), e);
    }
}
```

### SpringAiMcpAdapter @EventListener

```java
// Source: SpringAiMcpAdapter.java line 99 [VERIFIED] — explicit TODO left for this phase
// Add to SpringAiMcpAdapter.java:
import dev.tessera.core.schema.SchemaChangeEvent;
import org.springframework.context.event.EventListener;

/**
 * MCP-08: Notify connected MCP clients that the tools list may have changed
 * after a schema mutation. Resolves the TODO in notifySchemaChanged().
 */
@EventListener
public void onSchemaChange(SchemaChangeEvent event) {
    log.debug("SpringAiMcpAdapter: schema change {}/{} — notifying MCP clients (model_id={})",
              event.changeType(), event.typeSlug(), event.modelId());
    notifySchemaChanged();
}
```

### SqlViewSchemaChangeIT — implement disabled tests

The `SqlViewSchemaChangeIT` test class exists but is `@Disabled` with stubs for `viewRegeneratedOnSchemaChange` and `viewsSurviveApplicationRestart`. This phase implements them.

```java
// Source: SqlViewSchemaChangeIT.java [VERIFIED] — @Disabled stub with fail("Not yet implemented")
// Enable and implement:

@Test
void viewRegeneratedOnSchemaChange() {
    // 1. Register type
    schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("widget", "Widget", "widget", "test"));
    sqlViewProjection.regenerateForTenant(ctx);

    // 2. Add property — this triggers SchemaChangeEvent → onSchemaChange → regenerateForTenant
    schemaRegistry.addProperty(ctx, "widget",
            new AddPropertySpec("color", "Color", "STRING", false));
    // Event fires synchronously — view is already regenerated by the time we assert

    // 3. Assert new column in view DDL
    String viewName = SqlViewNameResolver.resolve(modelId, "widget");
    String viewDef = jdbc.queryForObject(
            "SELECT pg_get_viewdef(:name::regclass, true)",
            new MapSqlParameterSource("name", viewName),
            String.class);
    assertThat(viewDef).contains("color");
}
```

---

## Runtime State Inventory

Step 2.5: SKIPPED — this is a greenfield wiring phase, not a rename/refactor/migration phase. No stored data, live service config, OS-registered state, secrets, or build artifacts embed any string being changed.

---

## Environment Availability

Step 2.6: No external dependencies. All required infrastructure (Spring context, ApplicationEventPublisher, JDBC, Testcontainers) is already available.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Spring `ApplicationEventPublisher` | SchemaRegistry publish | Yes (spring-boot-starter) | Spring Boot 3.5.x BOM | — |
| Spring `@EventListener` | SqlViewProjection, SpringAiMcpAdapter | Yes (spring-context in spring-boot-starter) | Spring Boot 3.5.x BOM | — |
| Testcontainers AGE | SqlViewSchemaChangeIT | Yes (fabric-projections test scope) | 1.20.x | — |

No missing dependencies.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + AssertJ |
| Config file | `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewProjectionIT.java` (existing) |
| Quick run command | `./mvnw test -pl fabric-projections -Dtest=SqlViewProjectionIT,SqlViewSchemaChangeIT -DfailIfNoTests=false` |
| Full suite command | `./mvnw verify -pl fabric-core,fabric-projections` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SQL-02 | SchemaRegistry.addProperty publishes SchemaChangeEvent; SqlViewProjection.onSchemaChange fires and view is regenerated within same synchronous call | integration | `./mvnw test -pl fabric-projections -Dtest=SqlViewSchemaChangeIT#viewRegeneratedOnSchemaChange` | Exists (disabled stub) — enable in Wave 0 |
| SQL-02 | ApplicationRunner regenerates views on startup (survive restart) | integration | `./mvnw test -pl fabric-projections -Dtest=SqlViewSchemaChangeIT#viewsSurviveApplicationRestart` | Exists (disabled stub) — enable in Wave 0 |
| MCP-08 | SchemaRegistry.createNodeType triggers SpringAiMcpAdapter.notifySchemaChanged() call | unit | `./mvnw test -pl fabric-projections -Dtest=SchemaChangeEventWiringTest` | ❌ Wave 0: new test |
| SCHEMA publish | SchemaChangeEvent is published by SchemaRegistry after each of the 8 mutating methods | unit | `./mvnw test -pl fabric-core -Dtest=SchemaRegistryEventPublishingTest` | ❌ Wave 0: new test |

### Sampling Rate

- **Per task commit:** `./mvnw test -pl fabric-core -Dtest=SchemaRegistryEventPublishingTest -DfailIfNoTests=false`
- **Per wave merge:** `./mvnw verify -pl fabric-core,fabric-projections`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `fabric-core/src/test/java/dev/tessera/core/schema/SchemaRegistryEventPublishingTest.java` — unit test: mock `ApplicationEventPublisher`, verify `SchemaChangeEvent` is published for each of the 8 SchemaRegistry mutating methods
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/sql/SchemaChangeEventWiringTest.java` — unit test: verify `SqlViewProjection.onSchemaChange(event)` calls `regenerateForTenant` with the correct `TenantContext`
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/mcp/SchemaChangeMcpWiringTest.java` — unit test: verify `SpringAiMcpAdapter.onSchemaChange(event)` calls `notifySchemaChanged()` (spy on `mcpServer.notifyToolsListChanged()`)
- [ ] Enable `SqlViewSchemaChangeIT` (remove `@Disabled`, implement both test methods)

---

## Security Domain

This phase adds no new endpoints, no new authentication paths, no new data storage, and no credentials. The `SchemaChangeEvent` is an internal in-process Spring event — it never crosses a network boundary and carries only `(modelId, changeType, typeSlug)`, which are already visible to any code that has a reference to `SchemaRegistry`. No new ASVS categories apply.

The ArchUnit isolation boundary (MCP tools must not call schema mutations) is not affected — `onSchemaChange` is on the adapter, not on a ToolProvider implementation.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Synchronous `@EventListener` (not `@TransactionalEventListener`) is the correct choice — view DDL runs in the same TX | Architecture Patterns | Low-medium: if the schema change TX rolls back after DDL has run, the view and schema are inconsistent. For MVP (solo development, human-driven schema changes) this is acceptable. If strict consistency is required, switch to `@TransactionalEventListener(AFTER_COMMIT)` and restructure ITs. |
| A2 | All SchemaRegistry integration tests use `@SpringBootTest` + `@Autowired` — no test manually `new`s a `SchemaRegistry` | Pitfalls | Low: if a manual constructor call exists in a test not caught by this research, adding the `publisher` parameter will cause a compile error that is easy to fix. |
| A3 | `notifySchemaChanged()` calling `mcpServer.notifyToolsListChanged()` is sufficient for MCP-08 — Spring AI MCP client will re-query tool list after this notification | Architecture Patterns | Low: the existing implementation is already in place with exactly this call, and a prior decision in Phase 3 confirmed it. If Spring AI does not support runtime tool list change notifications in the version used (1.0.x), the accepted fallback is a documented restart procedure — the notify call is a no-op rather than an error. |

---

## Open Questions (RESOLVED)

1. **@EventListener vs @TransactionalEventListener — which to use?**
   - What we know: `@EventListener` fires synchronously mid-TX; `@TransactionalEventListener(AFTER_COMMIT)` fires after commit but does not fire in `@Transactional` test methods.
   - What's unclear: whether the schema change TX rolling back after the view has been regenerated is a real concern for this project at MVP scale.
   - Recommendation: Use `@EventListener` (simpler, works with integration tests as-is). Document as a known limitation. If production rollback scenarios become a concern, the switch to `@TransactionalEventListener(AFTER_COMMIT)` is a one-line change plus IT restructuring.

2. **Event payload granularity — is `typeSlug` sufficient for SqlViewProjection?**
   - What we know: `regenerateForTenant(TenantContext)` regenerates ALL types for a tenant. It does not have a per-type regeneration method.
   - What's unclear: whether it would be worth optimizing to regenerate only the changed type's view.
   - Recommendation: Use `regenerateForTenant` as-is. Schema changes are rare and human-driven; regenerating all types for a tenant is cheap (a handful of `CREATE OR REPLACE VIEW` statements). No per-type optimization needed for MVP.

---

## Sources

### Primary (HIGH confidence)

- [VERIFIED: fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java] — all 8 mutating methods enumerated; constructor signature confirmed
- [VERIFIED: fabric-core/src/main/java/dev/tessera/core/events/GraphEventPublished.java] — canonical event record pattern
- [VERIFIED: fabric-connectors/src/main/java/dev/tessera/connectors/ConnectorMutatedEvent.java] — canonical event + @EventListener pattern
- [VERIFIED: fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java] — `regenerateForTenant` API confirmed; `ApplicationRunner` pattern confirmed
- [VERIFIED: fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java] — `notifySchemaChanged()` method confirmed; TODO comment pointing to this phase confirmed
- [VERIFIED: fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewSchemaChangeIT.java] — `@Disabled` stubs confirmed
- [VERIFIED: fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java] — ArchUnit dependency rules confirmed
- [VERIFIED: fabric-core/pom.xml] — `spring-boot-starter` is direct dependency (ApplicationEventPublisher available)
- [VERIFIED: fabric-projections/pom.xml] — `spring-boot-starter-web` is direct dependency (@EventListener available)
- [VERIFIED: .planning/config.json] — `workflow.nyquist_validation: true`

### Tertiary (LOW confidence — not needed, no unknowns require external lookup)

No external sources consulted. All facts are directly verifiable from the codebase.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries verified in pom.xml; no new dependencies needed
- Architecture: HIGH — dependency rules verified; event pattern verified from two existing codebase examples; TODO comment in SpringAiMcpAdapter makes the intent explicit
- Pitfalls: HIGH — all derived from direct code inspection; @TransactionalEventListener pitfall is a well-known Spring pattern [ASSUMED from training knowledge, but the risk is low and the workaround is documented]

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (Spring Boot 3.5.x stable; codebase patterns stable)
