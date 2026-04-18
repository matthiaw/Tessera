# Phase 10: Field-Level Access Control & Security Docs - Research

**Researched:** 2026-04-17
**Domain:** Authorization / ACL filtering in projection engine + operational security documentation
**Confidence:** HIGH

## Summary

Phase 10 closes the access control gap deferred from Phase 2 (Decision 10). The scope is narrow and well-defined: add `read_roles` and `write_roles` columns to `schema_properties` and `schema_node_types`, build a single `AclFilterService` that filters node properties and type visibility based on caller roles, integrate it into both the REST and MCP projection paths, cache allowed-property-sets in Caffeine, and produce a LUKS/dm-crypt TDE runbook for the IONOS VPS deployment.

The codebase is well-prepared for this work. The Schema Registry already has typed columns on `schema_properties` and `schema_node_types` (V4, V13 migrations), `EntityDispatcher` already checks exposure flags before dispatching, `SecurityConfig` already extracts JWT roles via `JwtGrantedAuthoritiesConverter`, and Caffeine caching is an established pattern in `SchemaDescriptorCache`. The primary engineering challenge is threading caller roles through the MCP path, which currently passes only `TenantContext` and `agentId` to `ToolProvider.execute()` -- roles must be extracted from the `SecurityContextHolder` at the adapter layer and forwarded.

**Primary recommendation:** Build `AclFilterService` in `fabric-core` (not `fabric-projections`) because `PropertyDescriptor` and `NodeTypeDescriptor` already live there, and the service needs no projection-layer dependencies. The next Flyway migration is V29.

<user_constraints>

## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Per-property role ACLs stored as `read_roles TEXT[]` and `write_roles TEXT[]` columns on the existing `schema_properties` table. Flyway migration adds both columns.
- **D-02:** Empty/NULL `read_roles` = visible to all authenticated callers. Only properties with explicit role lists are restricted. Same semantics for `write_roles`.
- **D-03:** Role names in ACL arrays match JWT `roles` claim values directly -- no mapping layer, no hierarchy. Existing roles: `ADMIN`, `AGENT`, `TOKEN_ISSUER`.
- **D-04:** Role-gated node types -- add `read_roles TEXT[]` and `write_roles TEXT[]` to `schema_node_types` table (same pattern as properties). Caller without required role gets 404 for that type, consistent with Phase 2 Decision 11 (deny-all = 404).
- **D-05:** Edge types do NOT get their own ACL. Edges inherit visibility from their endpoint nodes -- if you can't see the node, you can't see its edges.
- **D-06:** A single shared `AclFilterService` (in `fabric-projections` or `fabric-core`) takes `NodeState` + caller roles and returns a filtered property map. Both `GenericEntityController.nodeToMap()` and `ToolNodeSerializer` call it. One place to test, one place to audit.
- **D-07:** When ALL properties of a node are redacted, return the node with empty properties `{ uuid, type, properties: {} }`. Caller knows the node exists but sees no data. Pagination counts remain consistent.
- **D-08:** Cache the allowed-property-set per `(model_id, type_slug, role_set)` in Caffeine. Schema changes invalidate the cache. Consistent with Phase 1's Caffeine shape cache pattern.
- **D-09:** Full operational runbook covering: LUKS/dm-crypt setup on IONOS VPS, Postgres data directory encryption, key rotation procedure, CMK-encrypted `pg_dump` backups, DR restore from encrypted backup, and monitoring (disk health, LUKS status).
- **D-10:** Runbook lives at `docs/ops/tde-deployment-runbook.md` -- versioned with the codebase, matching OSS posture.

### Claude's Discretion
- Module placement of `AclFilterService` (fabric-core vs fabric-projections) -- choose based on dependency graph
- Caffeine cache sizing and TTL for ACL property sets
- Runbook formatting and section ordering

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope

</user_constraints>

<phase_requirements>

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SEC-05 | Field-level access control (read/write roles per `schema_property`), enforced in the Projection Engine | D-01 through D-08 define the ACL model; `AclFilterService` filters properties; integration into `nodeToMap()` and `ToolNodeSerializer.toMap()` |
| REST-07 | Row-level and field-level access control filters response payloads based on caller role | Type-level ACL on `schema_node_types` (D-04) gives row-level; property ACL (D-01) gives field-level; `EntityDispatcher` checks type-level before dispatch |
| SEC-04 | Row-level access control based on caller role, enforced in the Projection Engine before response serialization | `EntityDispatcher.requireReadEnabled()` extended with type-level role check (D-04); 404 on denied types consistent with Phase 2 Decision 11 |
| SEC-03 | Postgres Transparent Data Encryption at rest (LUKS/dm-crypt self-hosted) with CMK-encrypted backups | TDE deployment runbook (D-09, D-10) at `docs/ops/tde-deployment-runbook.md` |

</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| ACL metadata storage (read_roles/write_roles) | Database / Storage | -- | Schema columns on `schema_properties` and `schema_node_types` tables |
| ACL filtering logic | API / Backend (fabric-core) | -- | `AclFilterService` operates on `NodeState` + roles at the service layer |
| REST response filtering | API / Backend (fabric-projections) | -- | `GenericEntityController.nodeToMap()` calls `AclFilterService` |
| MCP response filtering | API / Backend (fabric-projections) | -- | `ToolNodeSerializer.toMap()` calls `AclFilterService` |
| Type-level role gating | API / Backend (fabric-projections) | -- | `EntityDispatcher.requireReadEnabled()` extended with role check |
| ACL cache | API / Backend (fabric-core) | -- | Caffeine cache keyed by `(model_id, type_slug, role_set)` |
| TDE deployment | Infrastructure / Ops | -- | LUKS/dm-crypt on IONOS VPS, documented as runbook |

## Standard Stack

### Core
No new libraries required. All capabilities are built with existing dependencies.

| Library | Version | Purpose | Already in POM |
|---------|---------|---------|----------------|
| Caffeine | 3.1.8+ | ACL property-set cache | Yes (fabric-core) |
| Spring Security | 6.x (via Spring Boot 3.5.x BOM) | JWT role extraction | Yes (fabric-projections) |
| Flyway | 10.x (via Spring Boot BOM) | V29 migration for ACL columns | Yes (fabric-app) |
| PostgreSQL | 16.x | `TEXT[]` array columns for role lists | Yes |

[VERIFIED: existing pom.xml dependencies]

### Supporting
No additional supporting libraries needed. The phase is purely Java code + SQL migration + documentation.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `TEXT[]` columns | JSONB array | TEXT[] has native `@>` operator for containment checks, simpler indexing with GIN; JSONB adds parsing overhead for no benefit |
| Custom Caffeine cache | Spring `@Cacheable` | `@Cacheable` lacks the `(model_id, type_slug, role_set)` composite key ergonomics; direct Caffeine API matches existing `SchemaDescriptorCache` pattern |

## Architecture Patterns

### System Architecture Diagram

```
JWT (roles claim)
       |
       v
[SecurityConfig] -- extracts roles via JwtGrantedAuthoritiesConverter
       |
       +-- REST path -----> [GenericEntityController]
       |                         |
       |                   [EntityDispatcher]
       |                    |          |
       |              type ACL     exposure check
       |              (D-04)        (existing)
       |                    |
       |               [nodeToMap()]
       |                    |
       |              [AclFilterService.filterProperties()]
       |                    |
       |              [Caffeine ACL cache]
       |                    |
       |              filtered response
       |
       +-- MCP path ------> [SpringAiMcpAdapter]
                                  |
                           [ToolProvider.execute()]
                                  |
                           [ToolNodeSerializer.toMap()]
                                  |
                           [AclFilterService.filterProperties()]
                                  |
                           filtered response
```

### Recommended Project Structure

```
fabric-core/
  src/main/java/dev/tessera/core/
    schema/
      PropertyDescriptor.java         # EXTEND: add readRoles, writeRoles
      NodeTypeDescriptor.java         # EXTEND: add readRoles, writeRoles
      internal/
        SchemaRepository.java         # EXTEND: SELECT read_roles, write_roles
    security/
      AclFilterService.java           # NEW
      AclPropertyCache.java           # NEW

fabric-app/
  src/main/resources/db/migration/
    V29__acl_role_columns.sql         # NEW

fabric-projections/
  src/main/java/dev/tessera/projections/
    rest/
      GenericEntityController.java    # MODIFY: call AclFilterService in nodeToMap()
      EntityDispatcher.java           # MODIFY: add type-level role check
    mcp/
      tools/
        ToolNodeSerializer.java       # MODIFY: call AclFilterService
      adapter/
        SpringAiMcpAdapter.java       # MODIFY: extract and forward caller roles

docs/ops/
  tde-deployment-runbook.md           # NEW
```

### Pattern 1: ACL Filter Service

**What:** Centralized service that takes a `NodeState`, a `NodeTypeDescriptor` (with property ACL metadata), and caller roles, then returns a filtered property map.

**When to use:** Every time a node is serialized for external consumption (REST or MCP).

**Example:**
```java
// Source: project design based on D-06, D-07
public class AclFilterService {

    private final AclPropertyCache cache;

    /**
     * Returns filtered property map. Properties with non-empty readRoles
     * that do not intersect with callerRoles are excluded.
     * Per D-07: if all properties are filtered, returns empty map (not null).
     */
    public Map<String, Object> filterProperties(
            NodeState node,
            NodeTypeDescriptor descriptor,
            Set<String> callerRoles) {

        Set<String> allowedSlugs = cache.getAllowedPropertySlugs(
                descriptor.modelId(), descriptor.slug(),
                callerRoles, descriptor::properties);

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.properties().entrySet()) {
            if (allowedSlugs.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered; // empty map if all redacted (D-07)
    }
}
```

[ASSUMED]

### Pattern 2: Type-Level Role Check in EntityDispatcher

**What:** Extend `EntityDispatcher.requireReadEnabled()` to also check type-level `readRoles` against caller roles. Denied types return 404 (D-04, Phase 2 Decision 11).

**When to use:** Every dispatch call in `EntityDispatcher`.

**Example:**
```java
// Source: project design based on D-04
private NodeTypeDescriptor requireReadEnabled(TenantContext ctx, String typeSlug, Set<String> callerRoles) {
    NodeTypeDescriptor desc = loadOrThrow(ctx, typeSlug);
    if (!desc.restReadEnabled()) {
        throw new NotFoundException("Type '" + typeSlug + "' is not exposed for read");
    }
    // D-04: type-level role ACL
    if (desc.readRoles() != null && !desc.readRoles().isEmpty()) {
        if (callerRoles.stream().noneMatch(desc.readRoles()::contains)) {
            throw new NotFoundException("Type '" + typeSlug + "' not found");
        }
    }
    return desc;
}
```

[ASSUMED]

### Pattern 3: Extracting Roles from JWT in MCP Path

**What:** The MCP `SpringAiMcpAdapter.invokeTool()` already accesses `SecurityContextHolder.getContext().getAuthentication()` to extract `modelId`. Roles must be extracted from the same JWT and passed through to `AclFilterService`.

**Key insight:** `ToolProvider.execute()` signature currently takes `(TenantContext, String agentId, Map<String, Object> arguments)`. Roles can be extracted from `SecurityContextHolder` at the point where `AclFilterService` is called (inside `ToolNodeSerializer`) rather than changing the `ToolProvider` interface. Alternatively, roles can be added to a `SecurityContext` holder or passed through `TenantContext`.

**Recommended approach:** Extract roles in `SpringAiMcpAdapter.invokeTool()` and store them in a request-scoped bean or `ThreadLocal`-based `CallerContext` that `AclFilterService` reads. This avoids changing the `ToolProvider` interface signature which would break all 7 existing tools.

```java
// Source: project design
// In SpringAiMcpAdapter.invokeTool():
Set<String> callerRoles = extractCallerRoles(auth);
CallerContext.set(callerRoles);
try {
    ToolResponse response = tool.execute(ctx, agentId, params);
    // ...
} finally {
    CallerContext.clear();
}
```

**Alternative:** Pass roles explicitly through `AclFilterService` parameters at each call site. This is simpler but requires `GenericEntityController` and each MCP tool to obtain and pass roles. Since `GenericEntityController` has `@AuthenticationPrincipal Jwt jwt` directly, and MCP tools can read from `SecurityContextHolder`, explicit passing at each call site is actually the cleaner approach -- no ThreadLocal needed.

**Recommended: Explicit parameter passing.** Both REST (`Jwt` parameter) and MCP (`SecurityContextHolder`) have access to roles at the call site. Pass `Set<String> callerRoles` to `AclFilterService.filterProperties()`. No new `CallerContext` ThreadLocal needed.

[ASSUMED]

### Anti-Patterns to Avoid

- **ACL check after serialization:** Never serialize full properties then filter -- always filter before building the response map. Prevents accidental logging or caching of unfiltered data.
- **Role hierarchy in ACL layer:** D-03 explicitly forbids role hierarchy or mapping layers. `ADMIN` in the JWT must literally match `ADMIN` in the `read_roles` array. Do not add implicit ADMIN-sees-everything logic unless explicitly stored in `read_roles`.
- **Changing ToolProvider interface:** Adding a `roles` parameter to `execute()` would break all 7 existing tool implementations. Use explicit `SecurityContextHolder` access or pass roles at the serialization layer instead.
- **Filtering at the Cypher/SQL level:** ACL filtering is at the projection layer, not the query layer. Nodes are always fetched fully from the graph; filtering happens before response serialization. This keeps the graph query layer clean and makes the ACL boundary auditable.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| PostgreSQL array containment | Custom array parsing | `TEXT[]` with `@>` operator / `= ANY()` | Native Postgres array operators are fast and GIN-indexable |
| Cache invalidation | Manual invalidation tracking | Caffeine with `SchemaChangeEvent` listener | Existing pattern from `SchemaDescriptorCache` |
| JWT role extraction | Custom JWT parser | `JwtGrantedAuthoritiesConverter` (already configured) | Spring Security handles claim extraction, prefix stripping |
| LUKS disk encryption | Custom encryption layer | `cryptsetup` / `dm-crypt` (OS-level) | Industry-standard, battle-tested, kernel-level |

## Common Pitfalls

### Pitfall 1: Role Set Ordering in Cache Key
**What goes wrong:** Caffeine cache misses on equivalent role sets presented in different order (e.g., `{ADMIN, AGENT}` vs `{AGENT, ADMIN}`).
**Why it happens:** Using `List<String>` or raw `Set` with identity-based hashing.
**How to avoid:** Normalize the role set before cache lookup: sort and use `TreeSet` or create a canonical `String` key like `"ADMIN,AGENT"`.
**Warning signs:** Cache hit rate much lower than expected; repeated DB queries for same effective permissions.

### Pitfall 2: NULL vs Empty Array Semantics
**What goes wrong:** Treating `NULL` read_roles and `'{}'` (empty array) differently when they should both mean "visible to all" per D-02.
**Why it happens:** Postgres `NULL` and `'{}'` have different SQL comparison semantics. `NULL @> ARRAY['ADMIN']` returns NULL (not false).
**How to avoid:** In the Java layer, treat both NULL and empty list as "no restriction." In SQL migration, default to NULL (not empty array) for consistency. In `PropertyDescriptor`, map both to `List.of()` or `null` consistently.
**Warning signs:** Properties with empty-array ACLs being hidden from all callers.

### Pitfall 3: Backwards-Compatible Record Extension
**What goes wrong:** Adding `readRoles` and `writeRoles` to `PropertyDescriptor` record breaks all existing constructors across the codebase.
**Why it happens:** Java records are positional; adding fields changes the canonical constructor.
**How to avoid:** Follow the existing pattern from Phase 2 -- `PropertyDescriptor` already has a backwards-compatible constructor that defaults `encrypted`/`encryptedAlg`. Add a similar constructor that defaults `readRoles`/`writeRoles` to `null`/`List.of()`.
**Warning signs:** Compile errors across fabric-core, fabric-rules, fabric-projections, and fabric-connectors test fixtures.

### Pitfall 4: MCP Tools Not Getting Filtered
**What goes wrong:** REST responses are filtered but MCP tool responses leak unfiltered properties.
**Why it happens:** `ToolNodeSerializer` is `package-private` in `mcp.tools` and currently has no `AclFilterService` dependency. Easy to miss during implementation.
**How to avoid:** Integration test that issues the same query via REST and MCP with a restricted-role JWT and asserts identical filtered output.
**Warning signs:** MCP tools returning more properties than REST for the same entity and role.

### Pitfall 5: Forgetting Write-Role Checks on POST/PUT
**What goes wrong:** Read ACLs are enforced but write ACLs on properties are not, allowing any authenticated caller to write restricted fields.
**Why it happens:** Write path goes through `GraphService.apply()` which does not know about property-level ACLs.
**How to avoid:** Add write-role validation in `EntityDispatcher.create()` and `EntityDispatcher.update()` -- check that every property in the payload is either unrestricted or the caller has the required `write_roles`.
**Warning signs:** Restricted properties being written by callers without the write role.

### Pitfall 6: Test Migration Copies
**What goes wrong:** V29 migration is added to `fabric-app/src/main/resources/db/migration/` but not copied to test resource directories in `fabric-core`, `fabric-rules`, `fabric-projections`, and `fabric-connectors`.
**Why it happens:** Each module has its own test migration directory for Testcontainers-based ITs.
**How to avoid:** Copy V29 to all four test migration directories as part of the migration task.
**Warning signs:** ITs failing with `relation "schema_properties" has no column "read_roles"`.

## Code Examples

### V29 Flyway Migration
```sql
-- Source: project design based on D-01, D-04
-- V29: Add role ACL columns to schema_properties and schema_node_types

ALTER TABLE schema_properties
    ADD COLUMN read_roles  TEXT[] NULL,
    ADD COLUMN write_roles TEXT[] NULL;

ALTER TABLE schema_node_types
    ADD COLUMN read_roles  TEXT[] NULL,
    ADD COLUMN write_roles TEXT[] NULL;

-- GIN indexes for efficient role containment queries (if needed for admin queries)
CREATE INDEX idx_schema_properties_read_roles ON schema_properties USING GIN (read_roles)
    WHERE read_roles IS NOT NULL;
CREATE INDEX idx_schema_node_types_read_roles ON schema_node_types USING GIN (read_roles)
    WHERE read_roles IS NOT NULL;
```
[ASSUMED -- indexes are optional; evaluate need based on admin query patterns]

### PropertyDescriptor Extension
```java
// Source: existing pattern from PropertyDescriptor Phase 2 backwards-compat constructor
public record PropertyDescriptor(
        String slug, String name, String dataType, boolean required,
        String defaultValue, String validationRules, String enumValues,
        String referenceTarget, Instant deprecatedAt,
        boolean encrypted, String encryptedAlg,
        List<String> readRoles, List<String> writeRoles) {

    // Backwards-compatible: existing 11-arg constructor
    public PropertyDescriptor(
            String slug, String name, String dataType, boolean required,
            String defaultValue, String validationRules, String enumValues,
            String referenceTarget, Instant deprecatedAt,
            boolean encrypted, String encryptedAlg) {
        this(slug, name, dataType, required, defaultValue, validationRules,
             enumValues, referenceTarget, deprecatedAt, encrypted, encryptedAlg,
             List.of(), List.of());
    }

    // Backwards-compatible: original 9-arg constructor
    public PropertyDescriptor(
            String slug, String name, String dataType, boolean required,
            String defaultValue, String validationRules, String enumValues,
            String referenceTarget, Instant deprecatedAt) {
        this(slug, name, dataType, required, defaultValue, validationRules,
             enumValues, referenceTarget, deprecatedAt, false, null,
             List.of(), List.of());
    }
}
```
[ASSUMED]

### SchemaRepository Query Extension
```java
// Source: existing SchemaRepository.listProperties() pattern
// Add read_roles and write_roles to SELECT + RowMapper
"SELECT slug, name, data_type, required, default_value, validation_rules, enum_values,"
    + " reference_target, deprecated_at, property_encrypted, property_encrypted_alg,"
    + " read_roles, write_roles"
    + " FROM schema_properties WHERE model_id = :model_id::uuid AND type_slug = :type_slug"
    + " ORDER BY slug",
p,
(rs, i) -> {
    // Convert TEXT[] to List<String>
    java.sql.Array readArr = rs.getArray("read_roles");
    List<String> readRoles = readArr != null
        ? List.of((String[]) readArr.getArray()) : List.of();
    java.sql.Array writeArr = rs.getArray("write_roles");
    List<String> writeRoles = writeArr != null
        ? List.of((String[]) writeArr.getArray()) : List.of();
    return new PropertyDescriptor(
        rs.getString("slug"), rs.getString("name"), rs.getString("data_type"),
        rs.getBoolean("required"), rs.getString("default_value"),
        rs.getString("validation_rules"), rs.getString("enum_values"),
        rs.getString("reference_target"), toInstant(rs.getTimestamp("deprecated_at")),
        rs.getBoolean("property_encrypted"), rs.getString("property_encrypted_alg"),
        readRoles, writeRoles);
})
```
[VERIFIED: `java.sql.Array` is the correct JDBC approach for PostgreSQL TEXT[] columns]

### Extracting Caller Roles from JWT (REST Path)
```java
// Source: existing SecurityConfig.jwtAuthenticationConverter()
// In GenericEntityController, extract roles from Jwt:
private static Set<String> extractCallerRoles(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    return roles != null ? Set.copyOf(roles) : Set.of();
}
```
[VERIFIED: `SecurityConfig` maps `roles` claim with `ROLE_` prefix for Spring Security; raw claim values available via `jwt.getClaimAsStringList("roles")`]

### Extracting Caller Roles from JWT (MCP Path)
```java
// Source: existing SpringAiMcpAdapter.invokeTool() pattern
// In MCP tools or ToolNodeSerializer, roles from SecurityContextHolder:
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
Set<String> callerRoles = auth.getAuthorities().stream()
    .map(GrantedAuthority::getAuthority)
    .filter(a -> a.startsWith("ROLE_"))
    .map(a -> a.substring(5)) // strip "ROLE_" prefix
    .collect(Collectors.toSet());
```
[VERIFIED: `SecurityConfig` adds `ROLE_` prefix via `JwtGrantedAuthoritiesConverter.setAuthorityPrefix("ROLE_")`; stripping it back gives raw role names matching D-03]

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Tenant-only access control (Phase 2) | Tenant + role-based type and property ACL (Phase 10) | Phase 10 | REST and MCP responses now filtered by caller role, not just tenant |
| `nodeToMap()` serializes all properties | `nodeToMap()` calls `AclFilterService` first | Phase 10 | Breaking: code that calls `nodeToMap()` must now supply caller roles |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `AclFilterService` should live in `fabric-core` based on dependency analysis | Architecture Patterns | LOW -- if placed in fabric-projections, MCP tools can still access it since they're in the same module |
| A2 | No change to `ToolProvider.execute()` interface; roles obtained at serialization call sites | Architecture Patterns | MEDIUM -- if roles can't be obtained at `ToolNodeSerializer` call sites, interface change needed affecting 7 tools |
| A3 | GIN indexes on `read_roles` columns are beneficial | Code Examples | LOW -- indexes are optional and can be added/removed independently |
| A4 | Caffeine cache size of 1000 entries with 30-minute TTL is appropriate for ACL cache | Open Questions | LOW -- easily tunable post-deployment |

## Open Questions

1. **Admin API for setting ACL roles on properties and types**
   - What we know: D-01 and D-04 define the storage model; schema admin endpoints exist for exposure flags
   - What's unclear: Whether a new admin endpoint is needed in Phase 10 or if ACL can be set via existing schema mutation endpoints
   - Recommendation: Extend existing schema property/type creation and update endpoints to accept `read_roles` and `write_roles` parameters. This is the simplest path and consistent with how `rest_read_enabled` was added in Phase 2.

2. **ADMIN role implicit access**
   - What we know: D-03 says "no hierarchy, no mapping layer." D-02 says "empty = visible to all."
   - What's unclear: Should ADMIN always see all properties regardless of ACL, or must ADMIN be explicitly listed in every `read_roles` array?
   - Recommendation: Follow D-03 literally -- ADMIN must be in `read_roles` to see a restricted property. This is the safest default and avoids implicit privilege escalation. Document this clearly.

3. **SQL View Projection ACL implications**
   - What we know: `SqlViewProjection` generates per-type SQL views that include all properties
   - What's unclear: Should SQL views respect property ACLs? SQL views have no notion of "caller role"
   - Recommendation: Out of scope for Phase 10. SQL views are used by BI tools (Metabase/Looker) that authenticate at the database level, not via JWT. Document this as a known limitation.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Maven | Build | Yes | 3.9.14 | -- |
| Java (OpenJDK) | Runtime | Yes | 23.0.2 | -- |
| Docker | Testcontainers ITs | Yes | 27.4.0 | -- |
| PostgreSQL (via Testcontainers) | IT with TEXT[] columns | Yes | apache/age image | -- |

No missing dependencies.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers |
| Config file | Each module's `pom.xml` with surefire/failsafe |
| Quick run command | `mvn test -pl fabric-core -Dtest=AclFilterServiceTest` |
| Full suite command | `mvn verify -pl fabric-core,fabric-projections` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SEC-05 | Properties with read_roles filtered from REST response when caller lacks role | integration | `mvn verify -pl fabric-projections -Dtest=AclFilterRestIT` | Wave 0 |
| SEC-05 | Properties with read_roles filtered from MCP response when caller lacks role | integration | `mvn verify -pl fabric-projections -Dtest=AclFilterMcpIT` | Wave 0 |
| SEC-04 | Node types with read_roles return 404 when caller lacks role | integration | `mvn verify -pl fabric-projections -Dtest=TypeRoleGatingIT` | Wave 0 |
| REST-07 | Same entity returns different fields for different roles | integration | `mvn verify -pl fabric-projections -Dtest=RoleBasedFieldFilterIT` | Wave 0 |
| SEC-05 | Write-role enforcement on POST/PUT rejects writes to restricted properties | integration | `mvn verify -pl fabric-projections -Dtest=WriteRoleEnforcementIT` | Wave 0 |
| SEC-05 | AclFilterService correctly filters with empty/null/populated read_roles | unit | `mvn test -pl fabric-core -Dtest=AclFilterServiceTest` | Wave 0 |
| SEC-05 | Caffeine ACL cache invalidates on schema change | unit | `mvn test -pl fabric-core -Dtest=AclPropertyCacheTest` | Wave 0 |
| SEC-03 | TDE runbook exists and covers required sections | manual | Review `docs/ops/tde-deployment-runbook.md` | Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl fabric-core -Dtest=AclFilterServiceTest`
- **Per wave merge:** `mvn verify -pl fabric-core,fabric-projections`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `fabric-core/src/test/java/.../security/AclFilterServiceTest.java` -- covers SEC-05 unit
- [ ] `fabric-core/src/test/java/.../security/AclPropertyCacheTest.java` -- covers cache behavior
- [ ] `fabric-projections/src/test/java/.../rest/AclFilterRestIT.java` -- covers SEC-05, REST-07
- [ ] `fabric-projections/src/test/java/.../rest/TypeRoleGatingIT.java` -- covers SEC-04
- [ ] V29 migration copied to all test resource directories

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No (already implemented in Phase 2) | JWT via Spring Security |
| V3 Session Management | No (stateless JWT) | Stateless |
| V4 Access Control | **Yes** -- primary focus | `AclFilterService` + type-level role gating |
| V5 Input Validation | Yes (ACL role values in admin API) | Validate role names against known set |
| V6 Cryptography | Yes (TDE runbook) | LUKS/dm-crypt for data at rest |

### Known Threat Patterns for This Phase

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Privilege escalation via missing ACL check on MCP path | Elevation of Privilege | Integration test asserting REST and MCP return identical filtered output for same role |
| ACL bypass via write path (POST/PUT restricted fields) | Tampering | Write-role validation in `EntityDispatcher.create()/update()` |
| Information disclosure via error messages revealing restricted property names | Information Disclosure | Error messages must not name specific redacted properties |
| Cache poisoning via stale ACL cache after schema change | Elevation of Privilege | `SchemaChangeEvent` listener invalidates ACL cache (same pattern as `SchemaDescriptorCache`) |
| Denial of service via cache key explosion (many unique role combinations) | Denial of Service | Caffeine `maximumSize` bound on ACL cache |

## Sources

### Primary (HIGH confidence)
- Codebase inspection: `PropertyDescriptor.java`, `NodeTypeDescriptor.java`, `SchemaRepository.java`, `EntityDispatcher.java`, `GenericEntityController.java`, `ToolNodeSerializer.java`, `SpringAiMcpAdapter.java`, `SecurityConfig.java`, `EncryptionStartupGuard.java`, `SchemaDescriptorCache.java`
- Flyway migrations V4 (schema_registry), V13 (exposure/encryption flags) -- verified column structure
- Phase 2 CONTEXT.md (02-CONTEXT.md) -- security decisions D-6, D-10, D-11

### Secondary (MEDIUM confidence)
- PostgreSQL TEXT[] array documentation -- standard JDBC Array handling [VERIFIED: JDBC `getArray()` returns `String[]` for TEXT[]]

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- no new libraries, all existing dependencies
- Architecture: HIGH -- well-understood codebase with clear extension points
- Pitfalls: HIGH -- common Java record extension and caching issues are well-documented
- TDE runbook: MEDIUM -- LUKS/dm-crypt is standard but IONOS VPS specifics may vary

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (stable domain, no fast-moving dependencies)
