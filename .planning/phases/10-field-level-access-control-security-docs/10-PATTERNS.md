# Phase 10: Field-Level Access Control & Security Docs - Pattern Map

**Mapped:** 2026-04-17
**Files analyzed:** 13 (5 new, 8 modified)
**Analogs found:** 13 / 13

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `fabric-core/.../security/AclFilterService.java` | service | transform | `fabric-core/.../security/EncryptionStartupGuard.java` | role-match |
| `fabric-core/.../security/AclPropertyCache.java` | utility | transform | `fabric-core/.../schema/internal/SchemaDescriptorCache.java` | exact |
| `fabric-core/.../schema/PropertyDescriptor.java` | model | -- | self (backwards-compat constructor pattern) | exact |
| `fabric-core/.../schema/NodeTypeDescriptor.java` | model | -- | self (backwards-compat constructor pattern) | exact |
| `fabric-core/.../schema/internal/SchemaRepository.java` | service | CRUD | self (listProperties RowMapper pattern) | exact |
| `fabric-projections/.../rest/GenericEntityController.java` | controller | request-response | self (nodeToMap + JWT extraction) | exact |
| `fabric-projections/.../rest/EntityDispatcher.java` | service | request-response | self (requireReadEnabled pattern) | exact |
| `fabric-projections/.../mcp/tools/ToolNodeSerializer.java` | utility | transform | self (toMap pattern) | exact |
| `fabric-projections/.../mcp/adapter/SpringAiMcpAdapter.java` | adapter | request-response | self (SecurityContextHolder extraction) | exact |
| `fabric-app/.../db/migration/V29__acl_role_columns.sql` | migration | -- | `V13__schema_rest_exposure_and_encryption_flags.sql` | exact |
| `docs/ops/tde-deployment-runbook.md` | config | -- | (no analog -- ops doc) | none |
| `fabric-core/src/test/.../security/AclFilterServiceTest.java` | test | -- | `SchemaCacheInvalidationTest.java` | role-match |
| `fabric-projections/src/test/.../rest/AclFilterRestIT.java` | test | -- | `DenyAllExposureIT.java` | exact |

## Pattern Assignments

### `fabric-core/src/main/java/dev/tessera/core/security/AclFilterService.java` (NEW, service, transform)

**Analog:** `fabric-core/src/main/java/dev/tessera/core/security/EncryptionStartupGuard.java`

**Imports pattern** (lines 16-21):
```java
package dev.tessera.core.security;

import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.graph.NodeState;
import org.springframework.stereotype.Component;
```

**Component annotation pattern** (lines 39-40):
```java
@Component
public class EncryptionStartupGuard {
```

**Constructor injection pattern** (lines 45-48):
```java
public EncryptionStartupGuard(
        NamedParameterJdbcTemplate jdbc,
        @Value("${tessera.security.field-encryption.enabled:false}") boolean fieldEncryptionEnabled) {
```

**Note:** `AclFilterService` follows the same `@Component` + constructor injection style. Takes `AclPropertyCache` as dependency. Core method `filterProperties(NodeState, NodeTypeDescriptor, Set<String> callerRoles)` returns `Map<String, Object>`. Per D-07, returns empty map (never null) when all properties are redacted.

---

### `fabric-core/src/main/java/dev/tessera/core/security/AclPropertyCache.java` (NEW, utility, transform)

**Analog:** `fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaDescriptorCache.java`

**Imports pattern** (lines 18-24):
```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tessera.core.schema.NodeTypeDescriptor;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
```

**Caffeine cache initialization pattern** (lines 35-39):
```java
private final Cache<DescriptorKey, NodeTypeDescriptor> cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofHours(1))
        .recordStats()
        .build();
```

**Cache get pattern** (lines 41-43):
```java
public NodeTypeDescriptor get(DescriptorKey key, Function<DescriptorKey, NodeTypeDescriptor> loader) {
    return cache.get(key, loader);
}
```

**Invalidation pattern** (lines 49-51):
```java
public void invalidateAll(UUID modelId) {
    cache.asMap().keySet().removeIf(k -> k.modelId().equals(modelId));
}
```

**Cache key record pattern** (line 65):
```java
public record DescriptorKey(UUID modelId, String typeSlug, long schemaVersion) {}
```

**Note:** `AclPropertyCache` uses a similar record key but with `(UUID modelId, String typeSlug, String canonicalRoleSet)` where canonicalRoleSet is a sorted, comma-joined string to avoid cache misses from set ordering differences (Pitfall 1). Returns `Set<String>` of allowed property slugs.

---

### `fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java` (MODIFY, model)

**Analog:** Self -- the backwards-compatible constructor pattern from Phase 2

**Record extension pattern** (lines 29-69):
```java
public record PropertyDescriptor(
        String slug,
        String name,
        String dataType,
        boolean required,
        String defaultValue,
        String validationRules,
        String enumValues,
        String referenceTarget,
        Instant deprecatedAt,
        boolean encrypted,
        String encryptedAlg) {

    /**
     * Backwards-compatible constructor -- defaults the Wave 1 encryption flags
     * to {@code false} / {@code null}.
     */
    public PropertyDescriptor(
            String slug,
            String name,
            String dataType,
            boolean required,
            String defaultValue,
            String validationRules,
            String enumValues,
            String referenceTarget,
            Instant deprecatedAt) {
        this(slug, name, dataType, required, defaultValue, validationRules,
             enumValues, referenceTarget, deprecatedAt, false, null);
    }
}
```

**Pattern to follow:** Add `List<String> readRoles, List<String> writeRoles` after `encryptedAlg` in canonical constructor. Add a new backwards-compatible 11-arg constructor that defaults both to `List.of()`. Preserve existing 9-arg constructor by chaining to the new canonical via `List.of(), List.of()`.

---

### `fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java` (MODIFY, model)

**Analog:** Self -- identical backwards-compatible constructor pattern

**Record extension pattern** (lines 32-59):
```java
public record NodeTypeDescriptor(
        UUID modelId,
        String slug,
        String name,
        String label,
        String description,
        long schemaVersion,
        List<PropertyDescriptor> properties,
        Instant deprecatedAt,
        boolean restReadEnabled,
        boolean restWriteEnabled) {

    public NodeTypeDescriptor(
            UUID modelId,
            String slug,
            String name,
            String label,
            String description,
            long schemaVersion,
            List<PropertyDescriptor> properties,
            Instant deprecatedAt) {
        this(modelId, slug, name, label, description, schemaVersion, properties, deprecatedAt, false, false);
    }
}
```

**Pattern to follow:** Add `List<String> readRoles, List<String> writeRoles` after `restWriteEnabled`. Add backwards-compatible 10-arg constructor defaulting both to `List.of()`. Preserve existing 8-arg constructor.

---

### `fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaRepository.java` (MODIFY, service, CRUD)

**Analog:** Self -- `listProperties` RowMapper and `nodeTypeMapper` patterns

**Property RowMapper pattern** (lines 164-186):
```java
return jdbc.query(
        "SELECT slug, name, data_type, required, default_value, validation_rules, enum_values,"
                + " reference_target, deprecated_at, property_encrypted, property_encrypted_alg"
                + " FROM schema_properties WHERE model_id = :model_id::uuid AND type_slug = :type_slug"
                + " ORDER BY slug",
        p,
        (rs, i) -> new PropertyDescriptor(
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("data_type"),
                rs.getBoolean("required"),
                rs.getString("default_value"),
                rs.getString("validation_rules"),
                rs.getString("enum_values"),
                rs.getString("reference_target"),
                toInstant(rs.getTimestamp("deprecated_at")),
                rs.getBoolean("property_encrypted"),
                rs.getString("property_encrypted_alg")));
```

**Pattern to follow:** Add `, read_roles, write_roles` to SELECT. In RowMapper, use `java.sql.Array readArr = rs.getArray("read_roles"); List<String> readRoles = readArr != null ? List.of((String[]) readArr.getArray()) : List.of();` for each. Pass to new 13-arg constructor.

**NodeType RowMapper pattern** (lines 287-311):
```java
private RowMapper<NodeTypeRow> nodeTypeMapper() {
    return (rs, i) -> new NodeTypeRow(
            UUID.fromString(rs.getString("model_id")),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("label"),
            rs.getString("description"),
            toInstant(rs.getTimestamp("deprecated_at")),
            rs.getBoolean("rest_read_enabled"),
            rs.getBoolean("rest_write_enabled"));
}

private record NodeTypeRow(
        UUID modelId, String slug, String name, String label,
        String description, Instant deprecatedAt,
        boolean restReadEnabled, boolean restWriteEnabled) {}
```

**Pattern to follow:** Add `read_roles, write_roles` to all SELECT statements for `schema_node_types` (lines 77-78, 103-105, 217-218). Extend `NodeTypeRow` record with `List<String> readRoles, List<String> writeRoles`. Use same `rs.getArray()` approach. Pass through to `NodeTypeDescriptor` constructor.

---

### `fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java` (MODIFY, controller, request-response)

**Analog:** Self

**nodeToMap serialization pattern** (lines 200-213):
```java
private static Map<String, Object> nodeToMap(NodeState node) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("uuid", node.uuid().toString());
    map.put("type", node.typeSlug());
    map.put("seq", node.seq());
    if (node.createdAt() != null) {
        map.put("created_at", node.createdAt().toString());
    }
    if (node.updatedAt() != null) {
        map.put("updated_at", node.updatedAt().toString());
    }
    map.putAll(node.properties());
    return map;
}
```

**JWT access pattern** (lines 106, 27):
```java
@AuthenticationPrincipal Jwt jwt
```

**Tenant enforcement pattern** (lines 182-189):
```java
private static void enforceTenantMatch(Jwt jwt, String model) {
    if (jwt == null) {
        throw new CrossTenantException();
    }
    String tenant = jwt.getClaimAsString("tenant");
    if (tenant == null || !tenant.equals(model)) {
        throw new CrossTenantException();
    }
}
```

**Pattern to follow:** Change `nodeToMap` from `static` to instance method (needs `AclFilterService` dependency). Inject `AclFilterService` into constructor. Extract caller roles from `jwt.getClaimAsStringList("roles")` and pass to `AclFilterService.filterProperties()`. Replace `map.putAll(node.properties())` with `map.putAll(aclFilterService.filterProperties(node, descriptor, callerRoles))`. The `dispatcher.list/getById` calls must now also return or accept the `NodeTypeDescriptor` so it can be passed to the filter.

---

### `fabric-projections/src/main/java/dev/tessera/projections/rest/EntityDispatcher.java` (MODIFY, service, request-response)

**Analog:** Self

**requireReadEnabled pattern** (lines 117-123):
```java
private NodeTypeDescriptor requireReadEnabled(TenantContext ctx, String typeSlug) {
    NodeTypeDescriptor desc = loadOrThrow(ctx, typeSlug);
    if (!desc.restReadEnabled()) {
        throw new NotFoundException("Type '" + typeSlug + "' is not exposed for read");
    }
    return desc;
}
```

**requireWriteEnabled pattern** (lines 125-131):
```java
private NodeTypeDescriptor requireWriteEnabled(TenantContext ctx, String typeSlug) {
    NodeTypeDescriptor desc = loadOrThrow(ctx, typeSlug);
    if (!desc.restWriteEnabled()) {
        throw new NotFoundException("Type '" + typeSlug + "' is not exposed for write");
    }
    return desc;
}
```

**Pattern to follow:** Add `Set<String> callerRoles` parameter to `requireReadEnabled` and `requireWriteEnabled`. After exposure flag check, add type-level role check: `if (desc.readRoles() != null && !desc.readRoles().isEmpty() && callerRoles.stream().noneMatch(desc.readRoles()::contains)) throw new NotFoundException(...)`. For write path (`create`/`update`), add property-level write-role validation: iterate payload keys, check each against `desc.properties()` write_roles, reject if caller lacks required write role.

---

### `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ToolNodeSerializer.java` (MODIFY, utility, transform)

**Analog:** Self

**toMap pattern** (lines 38-46):
```java
static Map<String, Object> toMap(NodeState node) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("uuid", node.uuid() != null ? node.uuid().toString() : null);
    m.put("type", node.typeSlug());
    m.put("properties", node.properties());
    m.put("created_at", node.createdAt() != null ? node.createdAt().toString() : null);
    m.put("updated_at", node.updatedAt() != null ? node.updatedAt().toString() : null);
    return m;
}
```

**Pattern to follow:** Add overloaded `toMap(NodeState, AclFilterService, NodeTypeDescriptor, Set<String> callerRoles)` that replaces `node.properties()` with `aclFilterService.filterProperties(node, descriptor, callerRoles)`. The class is currently `package-private` and stateless -- keep it that way, pass dependencies as parameters. Callers (MCP tool implementations) must obtain roles from `SecurityContextHolder`.

---

### `fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java` (MODIFY, adapter, request-response)

**Analog:** Self

**SecurityContextHolder extraction pattern** (lines 132-136):
```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
UUID modelId = UUID.fromString(auth.getName());
ctx = TenantContext.of(modelId);
agentId = extractAgentId(auth);
```

**JwtAuthenticationToken extraction pattern** (lines 175-181):
```java
private static String extractAgentId(Authentication auth) {
    if (auth instanceof JwtAuthenticationToken jwtToken) {
        String sub = jwtToken.getToken().getSubject();
        return sub != null ? sub : auth.getName();
    }
    return auth.getName();
}
```

**Pattern to follow:** Extract roles from `auth.getAuthorities()` in `invokeTool()` (same location as tenant extraction, line 133). Strip `ROLE_` prefix: `auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toSet())`. Pass `callerRoles` to tool implementations or make available for `ToolNodeSerializer` calls.

---

### `fabric-app/src/main/resources/db/migration/V29__acl_role_columns.sql` (NEW, migration)

**Analog:** `fabric-app/src/main/resources/db/migration/V13__schema_rest_exposure_and_encryption_flags.sql`

**Migration header pattern** (lines 1-15):
```sql
-- Phase 2 / Wave 1 / 02-W1-01: Schema Registry exposure + encryption flags
-- (CONTEXT Decisions 2, 5).
--
-- Decision 5: REST exposure is deny-all by default. ...
```

**ALTER TABLE column addition pattern** (lines 17-23):
```sql
ALTER TABLE schema_node_types
    ADD COLUMN rest_read_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rest_write_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE schema_properties
    ADD COLUMN property_encrypted     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN property_encrypted_alg TEXT NULL;
```

**Pattern to follow:** Same ALTER TABLE style but with `TEXT[] NULL` (not BOOLEAN). Both columns default to NULL (per D-02, NULL = visible to all). Optional: GIN partial indexes for `WHERE read_roles IS NOT NULL`.

---

### `fabric-core/src/test/java/dev/tessera/core/security/AclFilterServiceTest.java` (NEW, test)

**Analog:** `fabric-core/src/test/java/dev/tessera/core/schema/SchemaCacheInvalidationTest.java`

**Test class structure** (lines 16-31):
```java
package dev.tessera.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.schema.internal.SchemaDescriptorCache;
import dev.tessera.core.schema.internal.SchemaDescriptorCache.DescriptorKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * SCHEMA-06 -- unit test (no DB). ...
 */
class SchemaCacheInvalidationTest {
```

**Pattern to follow:** Plain JUnit 5 + AssertJ, no Spring context, no Testcontainers. Package-private class. Test scenarios: empty read_roles = all properties visible; populated read_roles with matching caller role = visible; populated read_roles with non-matching role = filtered; all properties filtered = empty map (D-07); null vs empty list semantics (Pitfall 2).

---

### `fabric-projections/src/test/java/dev/tessera/projections/rest/AclFilterRestIT.java` (NEW, test)

**Analog:** `fabric-projections/src/test/java/dev/tessera/projections/rest/DenyAllExposureIT.java`

**IT class structure** (lines 44-58):
```java
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class DenyAllExposureIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera")
            .withReuse(true);
```

**DynamicPropertySource pattern** (lines 60-65):
```java
@DynamicPropertySource
static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
}
```

**JWT minting for specific roles** (line 86):
```java
String token = JwtTestHelper.mint(modelId.toString(), java.util.List.of("ADMIN"));
```

**REST-assured request pattern** (lines 92-97):
```java
given().port(port)
        .header("Authorization", "Bearer " + token)
        .when()
        .get("/api/v1/" + modelId + "/entities/Widget")
        .then()
        .statusCode(404);
```

**Direct JDBC setup pattern** (lines 99-103):
```java
jdbc.update(
        "UPDATE schema_node_types SET rest_read_enabled = TRUE"
                + " WHERE model_id = ?::uuid AND slug = ?",
        modelId.toString(), "Widget");
```

**Pattern to follow:** Same Testcontainers + REST-assured structure. Mint JWTs with different roles (`ADMIN`, `AGENT`, `TOKEN_ISSUER`) using `JwtTestHelper.mint()`. Insert properties with `read_roles` via direct JDBC. Assert that a restricted-role JWT sees fewer properties than an authorized-role JWT. Assert type-level role gating returns 404 for unauthorized role.

---

## Shared Patterns

### Apache 2.0 License Header
**Source:** Every Java file in the codebase (e.g., `EncryptionStartupGuard.java` lines 1-15)
**Apply to:** All new Java files
```java
/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
```

### JWT Role Extraction (REST path)
**Source:** `GenericEntityController.java` line 27 (`@AuthenticationPrincipal Jwt jwt`) + `SecurityConfig.java` lines 79-87
**Apply to:** `GenericEntityController` modifications
```java
// Extract raw role names from JWT claims (matches D-03 direct role matching)
List<String> roles = jwt.getClaimAsStringList("roles");
Set<String> callerRoles = roles != null ? Set.copyOf(roles) : Set.of();
```

### JWT Role Extraction (MCP path)
**Source:** `SpringAiMcpAdapter.java` lines 132-136 + `SecurityConfig.java` lines 80-82
**Apply to:** `SpringAiMcpAdapter` modifications, any MCP tool that needs roles
```java
// Spring Security adds ROLE_ prefix; strip it to get raw role names matching D-03
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
Set<String> callerRoles = auth.getAuthorities().stream()
    .map(GrantedAuthority::getAuthority)
    .filter(a -> a.startsWith("ROLE_"))
    .map(a -> a.substring(5))
    .collect(Collectors.toSet());
```

### Caffeine Cache Pattern
**Source:** `SchemaDescriptorCache.java` lines 33-66
**Apply to:** `AclPropertyCache`
```java
@Component
public class SchemaDescriptorCache {
    private final Cache<DescriptorKey, NodeTypeDescriptor> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .recordStats()
            .build();

    public record DescriptorKey(UUID modelId, String typeSlug, long schemaVersion) {}
}
```

### Deny-All = 404 Pattern
**Source:** `EntityDispatcher.java` lines 117-123
**Apply to:** Type-level role check in `EntityDispatcher`
```java
// Consistent with Phase 2 Decision 11: denied = 404, not 403
if (!desc.restReadEnabled()) {
    throw new NotFoundException("Type '" + typeSlug + "' is not exposed for read");
}
```

### TEXT[] JDBC Handling
**Source:** Standard PostgreSQL JDBC pattern (new to codebase, verified in RESEARCH.md)
**Apply to:** `SchemaRepository` RowMapper modifications
```java
java.sql.Array readArr = rs.getArray("read_roles");
List<String> readRoles = readArr != null
    ? List.of((String[]) readArr.getArray()) : List.of();
```

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `docs/ops/tde-deployment-runbook.md` | config | -- | First operational runbook in the codebase; no existing docs/ops pattern. Use RESEARCH.md D-09 requirements for section structure. |

## Metadata

**Analog search scope:** `fabric-core/`, `fabric-projections/`, `fabric-app/`
**Files scanned:** 13
**Pattern extraction date:** 2026-04-17
