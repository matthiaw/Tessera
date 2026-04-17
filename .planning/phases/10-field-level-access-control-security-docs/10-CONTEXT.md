---
gsd_artifact: context
phase: "10"
phase_name: "Field-Level Access Control & Security Docs"
created: 2026-04-17
requirements: [SEC-05, REST-07, SEC-04, SEC-03]
---

# Phase 10: Field-Level Access Control & Security Docs - Context

**Gathered:** 2026-04-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement per-property read/write role ACL in the projection engine so REST and MCP responses are filtered by caller role (beyond tenant isolation), add role-gated node type visibility, and produce the Postgres TDE deployment runbook for IONOS VPS. This is gap closure for SEC-03, SEC-04, SEC-05, and REST-07 — all deferred from Phase 2 (Decision 10: "row-level access control is tenant-only for Phase 2").

</domain>

<decisions>
## Implementation Decisions

### ACL Storage Model
- **D-01:** Per-property role ACLs stored as `read_roles TEXT[]` and `write_roles TEXT[]` columns on the existing `schema_properties` table. Flyway migration adds both columns.
- **D-02:** Empty/NULL `read_roles` = visible to all authenticated callers. Only properties with explicit role lists are restricted. Same semantics for `write_roles`.
- **D-03:** Role names in ACL arrays match JWT `roles` claim values directly — no mapping layer, no hierarchy. Existing roles: `ADMIN`, `AGENT`, `TOKEN_ISSUER`.

### Row-Level Role Filtering
- **D-04:** Role-gated node types — add `read_roles TEXT[]` and `write_roles TEXT[]` to `schema_node_types` table (same pattern as properties). Caller without required role gets 404 for that type, consistent with Phase 2 Decision 11 (deny-all = 404).
- **D-05:** Edge types do NOT get their own ACL. Edges inherit visibility from their endpoint nodes — if you can't see the node, you can't see its edges.

### Filtering Enforcement
- **D-06:** A single shared `AclFilterService` (in `fabric-projections` or `fabric-core`) takes `NodeState` + caller roles and returns a filtered property map. Both `GenericEntityController.nodeToMap()` and `ToolNodeSerializer` call it. One place to test, one place to audit.
- **D-07:** When ALL properties of a node are redacted, return the node with empty properties `{ uuid, type, properties: {} }`. Caller knows the node exists but sees no data. Pagination counts remain consistent.
- **D-08:** Cache the allowed-property-set per `(model_id, type_slug, role_set)` in Caffeine. Schema changes invalidate the cache. Consistent with Phase 1's Caffeine shape cache pattern.

### TDE Deployment Runbook
- **D-09:** Full operational runbook covering: LUKS/dm-crypt setup on IONOS VPS, Postgres data directory encryption, key rotation procedure, CMK-encrypted `pg_dump` backups, DR restore from encrypted backup, and monitoring (disk health, LUKS status).
- **D-10:** Runbook lives at `docs/ops/tde-deployment-runbook.md` — versioned with the codebase, matching OSS posture.

### Claude's Discretion
- Module placement of `AclFilterService` (fabric-core vs fabric-projections) — choose based on dependency graph
- Caffeine cache sizing and TTL for ACL property sets
- Runbook formatting and section ordering

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Security Architecture
- `.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-CONTEXT.md` — Phase 2 security decisions (JWT RBAC, tenant isolation, deny-all=404, field encryption feature flag)
- `.planning/REQUIREMENTS.md` §Security — SEC-03, SEC-04, SEC-05 requirement definitions

### Existing ACL-Adjacent Code
- `fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java` — Spring Security filter chain, role definitions
- `fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java` — REST serialization via `nodeToMap()`, tenant enforcement
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ToolNodeSerializer.java` — MCP node serialization
- `fabric-projections/src/main/java/dev/tessera/projections/rest/EntityDispatcher.java` — exposure flag checks before dispatch
- `fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java` — property model (has encrypted fields, needs ACL fields)
- `fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaRepository.java` — JDBC queries for schema metadata
- `fabric-core/src/main/java/dev/tessera/core/security/EncryptionStartupGuard.java` — fail-closed pattern reference

### Schema & Migrations
- `fabric-core/src/main/resources/db/migration/` — existing Flyway migrations for schema_properties and schema_node_types tables

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PropertyDescriptor` record: extend with `readRoles` and `writeRoles` fields (List<String>)
- `SchemaRepository`: already queries schema_properties with typed columns — add read_roles/write_roles to SELECT
- Caffeine cache in schema layer: established pattern for `(model_id, schema_version)` keyed caches — reuse for ACL cache
- `SecurityConfig` + `JwtAuthenticationConverter`: roles extraction from JWT already works

### Established Patterns
- Typed columns on schema tables (not JSONB) for queryable, indexable metadata
- Caffeine caching with schema-version-based invalidation
- Fail-closed startup guards (`EncryptionStartupGuard` pattern)
- `EntityDispatcher` as the pre-dispatch check layer — type-level ACL check fits here
- 404-for-deny pattern across all projections

### Integration Points
- `GenericEntityController.nodeToMap()` — call AclFilterService before returning response
- `ToolNodeSerializer` — call AclFilterService before returning MCP tool result
- `EntityDispatcher` — add type-level role check alongside existing exposure flag check
- Flyway migrations — new V-migration for read_roles/write_roles on both tables

</code_context>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 10-field-level-access-control-security-docs*
*Context gathered: 2026-04-17*
