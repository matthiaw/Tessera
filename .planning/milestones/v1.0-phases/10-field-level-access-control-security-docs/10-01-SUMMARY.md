---
phase: 10-field-level-access-control-security-docs
plan: 01
status: complete
started: 2026-04-17T21:40:00+02:00
completed: 2026-04-17T21:52:00+02:00
---

## Summary

ACL schema layer implemented: V29 Flyway migration adds read_roles and write_roles TEXT[] columns to schema_properties and schema_node_types tables. PropertyDescriptor (13-arg) and NodeTypeDescriptor (12-arg) records extended with backwards-compatible constructors. SchemaRepository reads and maps ACL columns via JDBC Array handling. AclFilterService provides centralized property filtering, type visibility checks, and write-role enforcement using Caffeine-cached allowed-property-set lookups with canonicalized role-set composite keys.

## Key Files

### Created
- `fabric-app/src/main/resources/db/migration/V29__acl_role_columns.sql` — ACL role columns migration
- `fabric-core/src/main/java/dev/tessera/core/security/AclFilterService.java` — Centralized ACL filtering logic
- `fabric-core/src/main/java/dev/tessera/core/security/AclPropertyCache.java` — Caffeine-cached allowed-property-set lookups
- `fabric-core/src/test/java/dev/tessera/core/security/AclFilterServiceTest.java` — 11 unit tests for ACL filtering
- `fabric-core/src/test/java/dev/tessera/core/security/AclPropertyCacheTest.java` — 4 unit tests for cache behavior

### Modified
- `fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java` — Added readRoles/writeRoles fields (13-arg canonical)
- `fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java` — Added readRoles/writeRoles fields (12-arg canonical)
- `fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaRepository.java` — Reads and maps ACL columns

## Self-Check: PASSED

- V29 migration in all 5 module locations
- PropertyDescriptor 13-arg canonical with 11-arg and 9-arg backwards-compat constructors
- NodeTypeDescriptor 12-arg canonical with 10-arg and 8-arg backwards-compat constructors
- SchemaRepository reads read_roles/write_roles via rs.getArray
- AclFilterService.filterProperties, isTypeVisible, checkWriteRoles all implemented
- AclPropertyCache uses Caffeine with canonicalized role-set keys
- 15/15 unit tests pass
- fabric-core compiles cleanly

## Deviations

None.
