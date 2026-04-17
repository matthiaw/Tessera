# Phase 10: Field-Level Access Control & Security Docs - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-17
**Phase:** 10-field-level-access-control-security-docs
**Areas discussed:** ACL storage model, Row-level role filtering, Filtering enforcement, TDE runbook scope

---

## ACL Storage Model

### Q1: How should per-property role ACLs be stored?

| Option | Description | Selected |
|--------|-------------|----------|
| Columns on schema_properties | Add read_roles and write_roles TEXT[] columns to the existing schema_properties table. Simple, transactional with schema changes, queryable. | ✓ |
| Separate ACL table | New acl_rules table with (property_id, role, permission) rows. More flexible but adds JOIN overhead. | |
| JSONB in PropertyDescriptor | Store ACL as a JSONB blob on the existing property row. Flexible schema but harder to query/index. | |

**User's choice:** Columns on schema_properties
**Notes:** Fits the existing pattern where exposure flags live on schema tables.

### Q2: How should empty ACL arrays be interpreted?

| Option | Description | Selected |
|--------|-------------|----------|
| Empty = visible to all | NULL or empty read_roles means no restriction. Only properties with explicit roles are filtered. | ✓ |
| Empty = hidden from all | NULL or empty read_roles means no one can see the property unless explicitly granted. | |

**User's choice:** Empty = visible to all
**Notes:** Matches deny-all-at-type-level, allow-all-at-property-level pattern.

### Q3: Should role names in ACL arrays match JWT roles exactly, or use a mapping layer?

| Option | Description | Selected |
|--------|-------------|----------|
| Direct match | read_roles contains literal role strings matching JWT roles claim. Simple, auditable. | ✓ |
| Role hierarchy with mapping | Define a role hierarchy in config. ACL checks walk the hierarchy. | |

**User's choice:** Direct match
**Notes:** Existing roles: ADMIN, AGENT, TOKEN_ISSUER.

---

## Row-Level Role Filtering

### Q1: What row-level predicates should exist beyond tenant isolation?

| Option | Description | Selected |
|--------|-------------|----------|
| Role-gated node types | Add read_roles/write_roles TEXT[] to schema_node_types. Caller without required role gets 404. | ✓ |
| Owner-based visibility | Nodes have an owner field; only the owner or admins can see them. | |
| Property-based row predicates | Filter rows based on property values. | |
| No row-level beyond tenant | Keep Phase 2's tenant-only model. | |

**User's choice:** Role-gated node types
**Notes:** Same pattern as property ACL. Consistent with 404-for-deny.

### Q2: Should edge types also get role ACLs?

| Option | Description | Selected |
|--------|-------------|----------|
| Nodes and properties only | Edges inherit visibility from endpoint nodes. | ✓ |
| Edges get their own ACL | Add read_roles to schema_edge_types too. | |

**User's choice:** Nodes and properties only
**Notes:** Simpler, covers SEC-04/SEC-05 requirements.

---

## Filtering Enforcement

### Q1: Where should field-level filtering happen in the pipeline?

| Option | Description | Selected |
|--------|-------------|----------|
| Shared filter service | Single AclFilterService called by both REST and MCP serialization. | ✓ |
| Separate per-projection | REST and MCP each implement own filtering. | |
| Cypher-level filtering | Push ACL predicates into Cypher queries. | |

**User's choice:** Shared filter service
**Notes:** One place to test, one place to audit.

### Q2: What happens when ALL properties of a node are redacted?

| Option | Description | Selected |
|--------|-------------|----------|
| Return node with empty properties | Return { uuid, type, properties: {} }. Pagination stays consistent. | ✓ |
| Omit the node entirely | Don't include fully-redacted nodes in results. | |
| You decide | Let Claude pick during implementation. | |

**User's choice:** Return node with empty properties
**Notes:** Caller knows node exists but sees no data.

### Q3: Should ACL filter result be cached?

| Option | Description | Selected |
|--------|-------------|----------|
| Cache allowed-property-set | Caffeine cache per (model_id, type_slug, role_set). Schema changes invalidate. | ✓ |
| Compute per request | No caching, read ACL arrays on each serialization. | |
| You decide | Let Claude pick based on performance. | |

**User's choice:** Cache allowed-property-set
**Notes:** Consistent with Phase 1 Caffeine cache pattern.

---

## TDE Runbook Scope

### Q1: How deep should the TDE deployment runbook go?

| Option | Description | Selected |
|--------|-------------|----------|
| Full operational runbook | LUKS setup, key rotation, CMK backups, DR restore, monitoring. | ✓ |
| Setup-only guide | Just LUKS partition setup and Postgres data directory encryption. | |
| Reference doc with pointers | High-level architecture + links to official docs. | |

**User's choice:** Full operational runbook
**Notes:** Covers SEC-03 completely, production-ready.

### Q2: Where should the runbook live?

| Option | Description | Selected |
|--------|-------------|----------|
| docs/ops/ in the repo | Versioned with code at docs/ops/tde-deployment-runbook.md. | ✓ |
| Separate ops wiki | External wiki or docs site. | |
| You decide | Let Claude pick the location. | |

**User's choice:** docs/ops/ in the repo
**Notes:** Matches OSS posture from PROJECT.md.

---

## Claude's Discretion

- Module placement of AclFilterService (fabric-core vs fabric-projections)
- Caffeine cache sizing and TTL for ACL property sets
- Runbook formatting and section ordering

## Deferred Ideas

None — discussion stayed within phase scope
