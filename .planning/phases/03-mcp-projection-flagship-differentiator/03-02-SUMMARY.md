---
phase: 03-mcp-projection-flagship-differentiator
plan: "02"
subsystem: mcp-projection
tags: [mcp, tool-provider, graph-repository, schema-registry, event-log, cursor-pagination, cypher, archunit]

requires:
  - phase: 03-mcp-projection-flagship-differentiator
    plan: "01"
    provides: "ToolProvider interface, ToolResponse record, SpringAiMcpAdapter, GraphRepository.executeTenantCypher, GraphRepository.findShortestPath, McpIsolationArchTest, McpSchemaAllowlistArchTest"

provides:
  - "ListEntityTypesTool: list_entity_types queries SchemaRegistry.listNodeTypes() (MCP-02)"
  - "DescribeTypeTool: describe_type returns full property schema + REFERENCE-based edge types (MCP-02)"
  - "QueryEntitiesTool: query_entities with CursorCodec pagination, in-memory filter, limit capped at 100 (MCP-03)"
  - "GetEntityTool: get_entity with depth 0-3 neighbor expansion via executeTenantCypher, depth clamped at 3 (MCP-04, D-B4)"
  - "TraverseTool: traverse() tenant-scoped read-only Cypher with mutation keyword catch (MCP-05)"
  - "FindPathTool: find_path() delegates to findShortestPath with AGE WHERE ALL cross-tenant filter (MCP-06)"
  - "GetStateAtTool: get_state_at() delegates to EventLog.replayToState for temporal reconstruction (MCP-07)"
  - "ToolNodeSerializer: shared package-private NodeState -> Map helper for consistent JSON keys"

affects:
  - 03-03-plan

tech-stack:
  added: []
  patterns:
    - "ToolProvider pattern: @Component, inject services, validate tenant, delegate, return JSON string (zero Spring AI imports)"
    - "T-03-08 DoS mitigation: GetEntityTool.depth clamped to [0,3]"
    - "T-03-09 DoS mitigation: QueryEntitiesTool.limit capped at 100"
    - "T-03-05/T-03-06: TraverseTool catches IllegalArgumentException from executeTenantCypher mutation blocklist"
    - "T-03-07: FindPathTool delegates to findShortestPath which uses WHERE ALL cross-tenant Cypher filter"
    - "In-memory filter pattern for QueryEntitiesTool: applied after queryAllAfter to support property filtering without Cypher injection"

key-files:
  created:
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ListEntityTypesTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/DescribeTypeTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/QueryEntitiesTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetEntityTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/TraverseTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/FindPathTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetStateAtTool.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ToolNodeSerializer.java

key-decisions:
  - "ToolNodeSerializer as package-private helper: shared serialization convention (uuid, type, properties, created_at, updated_at) without leaking outside tools package"
  - "GetEntityTool neighbor expansion via executeTenantCypher: reuses existing mutation-blocklisted, tenant-scoped path rather than raw JDBC; non-fatal on traversal error (returns neighbors: [])"
  - "DescribeTypeTool edge type derivation: uses REFERENCE dataType on PropertyDescriptor as edge signal (no separate EdgeTypeDescriptor lookup API for listing all edge types)"
  - "QueryEntitiesTool in-memory filter: applied post-queryAllAfter to avoid Cypher injection risk; acceptable for MVP query shapes"

metrics:
  duration: 8min
  tasks: 2
  files: 8
  completed: 2026-04-17
---

# Phase 03 Plan 02: MCP Tool Implementations Summary

**All 7 MCP tools implemented as ToolProvider beans: schema tools query SchemaRegistry, entity tools use CursorCodec pagination and depth-clamped neighbor expansion, graph tools delegate to tenant-scoped executeTenantCypher and findShortestPath, temporal tool replays EventLog state — all with zero Spring AI imports.**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-04-17T06:15:00Z
- **Completed:** 2026-04-17T06:23:16Z
- **Tasks:** 2
- **Files created:** 8

## Accomplishments

- ListEntityTypesTool: queries `SchemaRegistry.listNodeTypes()`, returns slug/name/description/schema_version JSON array
- DescribeTypeTool: calls `SchemaRegistry.loadFor()`, returns full property schema + REFERENCE-property-derived edge type list
- QueryEntitiesTool: cursor pagination via `CursorCodec.encode/decode`, in-memory property filter, limit default 20 capped at 100 (T-03-09)
- GetEntityTool: depth [0,3] clamped (T-03-08), depth>0 expands neighbors via `executeTenantCypher`, non-fatal on traversal error
- TraverseTool: passes Cypher to `executeTenantCypher`, catches `IllegalArgumentException` for mutation blocklist (T-03-05/T-03-06)
- FindPathTool: delegates to `findShortestPath` (AGE shortestPath + WHERE ALL model_id filter, T-03-07, A3 confirmed in Wave 0)
- GetStateAtTool: parses ISO-8601 timestamp, delegates to `EventLog.replayToState` (EVENT-06, MCP-07)
- ToolNodeSerializer: shared package-private helper for consistent NodeState → Map<String,Object> representation
- All 8 classes compile cleanly; `McpIsolationArchTest` and `McpSchemaAllowlistArchTest` both pass

## Task Commits

1. **Task 1: Schema + Entity tools (ListEntityTypes, DescribeType, QueryEntities, GetEntity)** — `ccbccd6`
2. **Task 2: Graph tools (Traverse, FindPath, GetStateAt)** — `38ad652`

## Files Created/Modified

- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ListEntityTypesTool.java` — list_entity_types (MCP-02)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/DescribeTypeTool.java` — describe_type (MCP-02)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/QueryEntitiesTool.java` — query_entities with CursorCodec (MCP-03)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetEntityTool.java` — get_entity depth 0-3 (MCP-04, D-B4)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/TraverseTool.java` — traverse read-only Cypher (MCP-05)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/FindPathTool.java` — find_path via AGE shortestPath (MCP-06)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetStateAtTool.java` — get_state_at via EventLog.replayToState (MCP-07)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ToolNodeSerializer.java` — shared NodeState serialization helper

## Decisions Made

- **ToolNodeSerializer as package-private helper**: Consistent JSON keys (uuid, type, properties, created_at, updated_at) shared across all tools without creating a public API surface.
- **GetEntityTool neighbor expansion via executeTenantCypher**: Reuses the mutation-blocklisted, model_id-injected path rather than raw JDBC. Non-fatal error handling returns `neighbors: []` with `neighbors_error` on traversal failure to avoid breaking entity retrieval.
- **DescribeTypeTool edge type derivation via REFERENCE properties**: `SchemaRegistry` has no `listEdgeTypes()` API. The tool derives edge relationships from `PropertyDescriptor` entries with `dataType=REFERENCE` and non-blank `referenceTarget`. This is correct for the current schema model.
- **QueryEntitiesTool in-memory filter**: Post-query in-memory filtering avoids Cypher injection risk from agent-provided filter keys/values. Acceptable for MVP query shapes; a native Cypher WHERE approach can be added later when the parameter API is more tightly typed.

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written.

The only note: Task 1 verification command (`mvn compile` then `McpIsolationArchTest`) required reinstalling `fabric-core` first (`mvn install -pl fabric-core`) because `executeTenantCypher` was added in Plan 01 and the local repo cache needed refreshing. This is a standard Maven reactor rebuild step, not a deviation.

## Known Stubs

None — all 7 tool classes are fully implemented. No stub values, placeholder text, or TODO markers in production code paths.

## Threat Flags

No new security-relevant surface beyond the plan's threat model. All 5 mitigations from the threat register are implemented:

| Threat | Mitigation Applied |
|--------|--------------------|
| T-03-05 Tampering (TraverseTool) | `executeTenantCypher` mutation keyword blocklist; `IllegalArgumentException` caught and returned as error |
| T-03-06 Info Disclosure (TraverseTool) | `model_id` WHERE clause injected server-side by `GraphRepositoryImpl` |
| T-03-07 Info Disclosure (FindPathTool) | `findShortestPath` uses `WHERE ALL(n IN nodes(path) WHERE n.model_id = tenant)` in AGE Cypher |
| T-03-08 DoS (GetEntityTool) | depth clamped to [0,3] before any query |
| T-03-09 DoS (QueryEntitiesTool) | limit capped at 100; cursor pagination prevents full table scan |

## Next Phase Readiness

- **Plan 03 (Audit + quota)**: All 7 tools wired to `SpringAiMcpAdapter` automatically (it collects all `ToolProvider` beans at startup). V22/V23 migration tables ready. TODO hooks in `SpringAiMcpAdapter` at exact `mcpAuditLog.record(...)` and `agentQuotaService.checkWriteQuota(...)` integration points.

---
*Phase: 03-mcp-projection-flagship-differentiator*
*Completed: 2026-04-17*

## Self-Check: PASSED

- ListEntityTypesTool.java: FOUND
- DescribeTypeTool.java: FOUND
- QueryEntitiesTool.java: FOUND
- GetEntityTool.java: FOUND
- TraverseTool.java: FOUND
- FindPathTool.java: FOUND
- GetStateAtTool.java: FOUND
- 03-02-SUMMARY.md: FOUND
- Commit ccbccd6 (Task 1): verified in git log
- Commit 38ad652 (Task 2): verified in git log
