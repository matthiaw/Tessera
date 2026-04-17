---
phase: 07-schema-change-event-infrastructure
reviewed: 2026-04-17T12:00:00Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - fabric-core/src/main/java/dev/tessera/core/schema/SchemaChangeEvent.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaRegistryEventPublishingTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/sql/SchemaChangeEventWiringTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/SchemaChangeMcpWiringTest.java
  - fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
  - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java
  - fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewSchemaChangeIT.java
findings:
  critical: 2
  warning: 3
  info: 2
  total: 7
status: issues_found
---

# Phase 07: Code Review Report

**Reviewed:** 2026-04-17T12:00:00Z
**Depth:** standard
**Files Reviewed:** 8
**Status:** issues_found

## Summary

The SchemaChangeEvent infrastructure is well-structured overall. The event record is clean, the `@TransactionalEventListener(AFTER_COMMIT)` pattern in both `SqlViewProjection` and `SpringAiMcpAdapter` is correct and avoids the transaction-abort pitfall documented in comments. Tests cover all 8 mutating methods and verify exception swallowing in the SQL projection listener.

However, there are two critical SQL injection vectors in `SqlViewProjection` where user-controlled slug values are interpolated into DDL without parameterization or allowlist validation, and a JSON injection issue in `SchemaRegistry` payload construction.

## Critical Issues

### CR-01: SQL Injection via unquoted view name in DDL

**File:** `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java:284`
**Issue:** The `viewName` is interpolated directly into `CREATE OR REPLACE VIEW` DDL. While `SqlViewNameResolver` lowercases and replaces hyphens with underscores, it does not reject or escape SQL metacharacters (semicolons, quotes, parentheses) in the `typeSlug`. A crafted slug like `x; DROP TABLE users--` would pass through `SqlViewNameResolver` (it becomes `x;_drop_table_users--`) and be injected into the DDL string at line 284. Since `CreateNodeTypeSpec` has no slug validation (no `@Pattern`, no allowlist), this is exploitable if a user can call `createNodeType` with an arbitrary slug.
**Fix:** Add slug validation at the `CreateNodeTypeSpec` / `AddPropertySpec` / `CreateEdgeTypeSpec` record level to enforce an allowlist pattern (e.g., `^[a-z][a-z0-9_]{0,62}$`). Additionally, double-quote the view name in the DDL:
```java
// In CreateNodeTypeSpec (compact record validation):
public CreateNodeTypeSpec {
    if (slug == null || !slug.matches("^[a-z][a-z0-9_]{0,62}$")) {
        throw new IllegalArgumentException("slug must match ^[a-z][a-z0-9_]{0,62}$");
    }
}

// In SqlViewProjection.buildViewDdl line 284:
return "CREATE OR REPLACE VIEW \"" + viewName + "\" AS\n" + ...
```

### CR-02: SQL Injection via property slug in column expression

**File:** `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java:301`
**Issue:** `buildColumnExpression` interpolates `prop.slug()` into `(properties::jsonb)->>'slug'` using single-quote wrapping with no escaping. A property slug containing `'` (e.g., `color'; DROP TABLE x--`) would break out of the string literal. The `safeAlias` on line 275 strips double-quotes but does not address the single-quoted jsonb key path on line 301.
**Fix:** Validate property slugs at creation time (same allowlist as CR-01), and/or escape single quotes in `buildColumnExpression`:
```java
private static String buildColumnExpression(PropertyDescriptor prop) {
    String slug = prop.slug().replace("'", "''"); // escape single quotes
    String base = "(properties::jsonb)->>'" + slug + "'";
    // ...
}
```

## Warnings

### WR-01: JSON injection in SchemaRegistry payload construction

**File:** `fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java:74-75`
**Issue:** All 8 mutating methods build JSON payloads via string concatenation with user-provided values (`spec.slug()`, `typeSlug`, `propertySlug`). A slug containing `"` would produce malformed JSON or allow field injection in the `schema_change_event` row. This affects lines 74, 89-93, 101-104, 115-116, 129-133, 144-148, 201-205, 251-255.
**Fix:** Use Jackson `ObjectMapper` or at minimum escape the values:
```java
// Preferred: use ObjectMapper
ObjectNode payload = objectMapper.createObjectNode()
    .put("changeType", "CREATE_TYPE")
    .put("typeSlug", spec.slug());
String json = objectMapper.writeValueAsString(payload);
```

### WR-02: changeType is a raw String -- no compile-time safety

**File:** `fabric-core/src/main/java/dev/tessera/core/schema/SchemaChangeEvent.java:36`
**Issue:** The `changeType` field is a plain `String`. The Javadoc lists 8 valid values, but nothing prevents passing an invalid value (e.g., typo `"CREAT_TYPE"`). Both producers (`SchemaRegistry`) and consumers (`SqlViewProjection.onSchemaChange`, `SpringAiMcpAdapter.onSchemaChange`) would silently accept invalid values. If a consumer later switches on `changeType` for selective regeneration, typos would cause silent no-ops.
**Fix:** Extract an enum:
```java
public enum SchemaChangeType {
    CREATE_TYPE, UPDATE_TYPE, DEPRECATE_TYPE,
    ADD_PROPERTY, DEPRECATE_PROPERTY, REMOVE_PROPERTY,
    RENAME_PROPERTY, CREATE_EDGE_TYPE
}

public record SchemaChangeEvent(UUID modelId, SchemaChangeType changeType, String typeSlug) {}
```

### WR-03: Event published inside @Transactional but listener is AFTER_COMMIT

**File:** `fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java:77`
**Issue:** `publishEvent` is called inside the `@Transactional` method, but the listener uses `@TransactionalEventListener(phase = AFTER_COMMIT)`. If the transaction rolls back after `publishEvent` (e.g., the `repo.findNodeType` call on line 78 throws), the event is still enqueued and Spring will discard it (correct AFTER_COMMIT behavior). However, if a future developer adds a `@EventListener` (non-transactional) subscriber, it would fire immediately inside the transaction before the mutation is committed -- receiving an event for a change that might roll back. This is not a current bug but a latent risk worth documenting with a code comment.
**Fix:** Add a comment near each `publishEvent` call:
```java
// IMPORTANT: This event uses @TransactionalEventListener(AFTER_COMMIT) listeners.
// Do NOT add plain @EventListener subscribers — they would fire before commit.
publisher.publishEvent(new SchemaChangeEvent(ctx.modelId(), "CREATE_TYPE", spec.slug()));
```

## Info

### IN-01: Fully-qualified class names used inline instead of imports

**File:** `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java:168,327,344-357`
**Issue:** `java.util.Map`, `java.util.HashMap`, and `java.util.regex.Matcher`/`Pattern` are used with fully-qualified names instead of imports. This reduces readability.
**Fix:** Add standard imports at the top of the file:
```java
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

### IN-02: SchemaChangeEvent Javadoc lists changeType values that could drift

**File:** `fabric-core/src/main/java/dev/tessera/core/schema/SchemaChangeEvent.java:32-33`
**Issue:** The Javadoc enumerates 8 valid `changeType` values. If a new mutation method is added to `SchemaRegistry`, the Javadoc must be manually updated. This is a documentation-drift risk (related to WR-02).
**Fix:** If an enum is adopted per WR-02, this issue resolves itself. Otherwise, reference the `SchemaRegistry` class in the Javadoc rather than enumerating values.

---

_Reviewed: 2026-04-17T12:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
