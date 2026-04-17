---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Completed 03-mcp-projection-flagship-differentiator plan 02 (MCP tool implementations)
last_updated: "2026-04-17T06:24:35.873Z"
progress:
  total_phases: 7
  completed_phases: 4
  total_plans: 23
  completed_plans: 21
  percent: 91
---

# State: Tessera

**Last updated:** 2026-04-13

## Project Reference

**Core Value:** The graph is the truth; everything else is a projection.
**Current Focus:** Phase 03 — MCP Projection (Flagship Differentiator)
**Granularity:** standard
**Mode:** yolo, parallel execution enabled, research + plan-check + verifier all on.

## Current Position

Phase: 03 (MCP Projection (Flagship Differentiator)) — EXECUTING
Plan: 1 of 5

- **Milestone:** 1
- **Phase:** 0 (Foundations & Risk Burndown) — not started
- **Plan:** none
- **Status:** Executing Phase 03
- **Progress:** [█████████░] 91%

## Performance Metrics

| Metric | Value |
|--------|-------|
| Phases total | 6 |
| Phases complete | 0 |
| v1 requirements | 87 |
| Requirements mapped | 87 (100%) |
| Phase 01 PW4 | 21m | 4 tasks | 5 files |
| Phase 02 PW0 | 35m | 2 tasks | 9 files |
| Phase 02 PW1 | 55 | 2 tasks | 19 files |
| Phase 02 PW2 | 48m | 1 tasks | 21 files |
| Phase 02 PW2 | 19 | 1 tasks | 6 files |
| Phase 02 PW3 | 160m | 2 tasks | 40 files |
| Phase 02.5 P01 | 14m | 2 tasks | 16 files |
| Phase 02.5 P02 | 10m | 2 tasks | 14 files |
| Phase 02.5 P03 | 26m | 2 tasks | 15 files |
| Phase 02.5 P04 | 30m | 2 tasks | 14 files |
| Phase 03-mcp-projection-flagship-differentiator P00 | 28 | 2 tasks | 7 files |
| Phase 03-mcp-projection-flagship-differentiator P01 | 14 | 2 tasks | 14 files |
| Phase 03-mcp-projection-flagship-differentiator P02 | 8 | 2 tasks | 8 files |

## Accumulated Context

### Decisions (from PROJECT.md, ADRs 1–6)

- PostgreSQL 16 + Apache AGE 1.6 as primary store (ADR-1)
- SHACL (Apache Jena) for schema validation; OWL deferred (ADR-2)
- Custom chain-of-responsibility rule engine; Drools deferred (ADR-3)
- Event log in Postgres; Kafka is a downstream projection (ADR-4)
- Schema registry co-located with graph DB (ADR-5)
- circlead stays standalone and consumes Tessera in parallel (ADR-6)
- First connector: generic REST polling, read-only
- Self-hosted on IONOS VPS; Apache 2.0 license; open to contributors from day one

### Decisions (Phase 03 Plan 00 — Wave 0 Spike)

- Assumption A3 CONFIRMED: AGE shortestPath() nodes(path) returns parseable vertex array; WHERE ALL model_id filter enforces cross-tenant isolation mid-traversal (FindPathTool de-risked)
- FindPathTool must apply `WHERE ALL(n IN nodes(path) WHERE n.model_id = tenant)` in Cypher, not post-filter in Java, to prevent T-03-S1 information disclosure
- commons-text pinned in parent dependencyManagement to fix installed fabric-rules POM version resolution (pre-existing build bug)

### Decisions (Phase 03 Plan 01 — MCP Infrastructure Bootstrap)

- D-A2 isolation: SpringAiMcpAdapter is the sole Spring AI import point; ToolProvider implementors never import Spring AI types (enforced by McpIsolationArchTest)
- isWriteTool() placed on ToolProvider interface (not private adapter logic) for testability and to let future write tools override without touching the adapter
- No ToolRequest carrier record: ToolProvider.execute() takes direct parameters (TenantContext, agentId, Map); carrier adds indirection with no benefit
- McpSyncServer auto-configured by spring-ai-starter-mcp-server-webmvc; no explicit @Bean needed in McpProjectionConfig
- SchemaChangeEvent listener deferred: event type does not exist yet in fabric-core; SpringAiMcpAdapter.notifySchemaChanged() is a public method ready for wiring

### Decisions (Phase 03 Plan 02 — MCP Tool Implementations)

- ToolNodeSerializer as package-private helper: shared NodeState serialization convention (uuid, type, properties, created_at, updated_at) in tools package without leaking a public API
- GetEntityTool neighbors via executeTenantCypher with non-fatal error fallback: traversal errors return empty neighbors list rather than failing the entire entity fetch
- QueryEntitiesTool in-memory filter applied post-queryAllAfter: avoids Cypher injection risk from agent-provided filter key/value pairs; acceptable for MVP query shapes
- DescribeTypeTool edge type derivation via REFERENCE PropertyDescriptor: SchemaRegistry has no listEdgeTypes() API; REFERENCE dataType with referenceTarget is the correct schema model signal

### Open Todos

- Kick off Phase 0 via `/gsd-plan-phase 0`
- Revisit research flags during plan-phase for Phases 1, 2, 3, 4 (see ROADMAP.md Notes)
- Decide tenant model granularity (per-customer vs per-domain) before Phase 1 schema freeze
- Decide field-level encryption disposition before Phase 2 (ship fully or feature-flag off)

### Blockers

None.

### Key Risks (carried from research/PITFALLS.md)

- **Apache AGE operational risk** — `-rc0` tagging, `pg_upgrade` blocked, ~15× aggregation cliff, Postgres minor lag. Mitigation is Phase 0 work.
- **Multi-tenant `model_id` leakage** — engineered defenses: central `GraphSession`, ArchUnit ban on raw Cypher, mandatory `TenantContext` parameter, property-based fuzz tests.
- **Reconciliation conflict storm** — write-rate circuit breaker + origin tracking + read-only first connector.
- **Field-level encryption all-or-nothing** — half-built is security theater.

## Session Continuity

**Last session:** 2026-04-17T06:24:35.866Z
**Stopped at:** Completed 03-mcp-projection-flagship-differentiator plan 02 (MCP tool implementations)

**Next action on resume:** Transition Phase 02.5 or start Phase 0 via `/gsd-plan-phase 0`.

**Files of record:**

- `.planning/PROJECT.md` — core value, constraints, decisions
- `.planning/REQUIREMENTS.md` — 87 v1 requirements with traceability to phases 0–5
- `.planning/ROADMAP.md` — 6-phase milestone 1 plan with success criteria
- `.planning/research/SUMMARY.md` — research synthesis and phase rationale
- `.planning/research/STACK.md`, `ARCHITECTURE.md`, `PITFALLS.md`, `FEATURES.md`

---
*State initialized: 2026-04-13*
