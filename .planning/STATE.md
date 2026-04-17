---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Completed 04-sql-view-kafka-projections-hash-chained-audit plan 03 (Debezium/Kafka CDC Fan-out)
last_updated: "2026-04-17T09:49:30.898Z"
progress:
  total_phases: 7
  completed_phases: 6
  total_plans: 27
  completed_plans: 27
  percent: 100
---

# State: Tessera

**Last updated:** 2026-04-13

## Project Reference

**Core Value:** The graph is the truth; everything else is a projection.
**Current Focus:** Phase 04 — SQL View + Kafka Projections, Hash-Chained Audit
**Granularity:** standard
**Mode:** yolo, parallel execution enabled, research + plan-check + verifier all on.

## Current Position

Phase: 04 (SQL View + Kafka Projections, Hash-Chained Audit) — EXECUTING
Plan: 2 of 4

- **Milestone:** 1
- **Phase:** 4
- **Plan:** Not started
- **Status:** Executing Phase 04
- **Progress:** [██████████] 100%

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
| Phase 03-mcp-projection-flagship-differentiator P03 | 4 | 2 tasks | 7 files |
| Phase 03-mcp-projection-flagship-differentiator P04 | 7 | 2 tasks | 15 files |
| Phase 04-sql-view-kafka-projections-hash-chained-audit P00 | 4 | 1 tasks | 7 files |
| Phase 04-sql-view-kafka-projections-hash-chained-audit P01 | 6 | 2 tasks | 13 files |
| Phase 04-sql-view-kafka-projections-hash-chained-audit P02 | 60 | 2 tasks | 7 files |
| Phase 04-sql-view-kafka-projections-hash-chained-audit P03 | 5 | 2 tasks | 9 files |

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

### Decisions (Phase 04 Plan 03 — Debezium/Kafka CDC Fan-out)

- `ApplicationContextRunner.withUserConfiguration(OutboxPoller.class)` required (not `.withBean`) in `OutboxPollerConditionalIT` — `withBean` bypasses Spring Boot condition evaluation; `withUserConfiguration` treats the class as a component candidate so `@ConditionalOnProperty` is evaluated during context refresh
- `aggregatetype` in `GraphServiceImpl.apply()` changed to `modelId + "." + typeSlug` for Debezium EventRouter topic routing to `tessera.{model_id}.{type_slug}`; dot separator is safe for Kafka topic names
- `bitnami/kafka:3.9` chosen for KRaft first-class support; `ImagePinningTest` only enforces `apache/age` digest pinning — Kafka/Debezium images are not subject to the same constraint
- `spring-boot-starter-actuator` added to `fabric-projections/pom.xml` — required for `AbstractHealthIndicator`; was missing from the module's dependencies

### Decisions (Phase 04 Plan 02 — Hash-Chained Audit Verification)

- RowCallbackHandler (streaming) used in AuditVerificationService to avoid OOM on large tenant event logs; aborts on first broken link
- recompactJson() via Jackson + TreeMap normalizes Postgres JSONB (adds spaces after : and ,) back to compact sorted-key form matching hash-time JsonMaps.toJson() output — required for correct hash verification
- Per-tenant JVM synchronized lock in appendWithHashChain() spans predecessor read AND INSERT; correct for single-JVM MVP; multi-instance requires distributed lock (deferred)
- Concurrency IT verifies no-null prev_hash safety property (not strict chain linearity); strict linearity requires cross-JVM serialization beyond MVP scope
- fabric-projections IT V16/V17 test migrations replaced with no-ops; apache/age image has no pgvector extension

### Decisions (Phase 04 Plan 01 — SQL View Projection)

- `SqlViewProjection` uses `(properties::jsonb)->>'key'` cast throughout view DDL — agtype is NOT jsonb (Pitfall 1); explicit cast required to avoid runtime type errors when BI tools query views
- View DDL embeds `/* schema_version:N model_id:X type:Y */` comment for D-D3 staleness detection; `pg_get_viewdef` + regex parse skips regeneration when version matches
- `regenerateAll()` enumerates tenants via `schemaRegistry.listDistinctExposedModels()` — reuses existing fabric-core API rather than introducing a separate tenant registry table
- V26 test migration uses `SELECT 1` no-op (not `ALTER SYSTEM`) for Testcontainers container reuse safety; production V26 in `fabric-app` is authoritative

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

### Decisions (Phase 03 Plan 03 — MCP Audit + Quota)

- Window reset detection via Instant equality in AgentQuotaService: hourlyWindowStarts.compute() returns now when newly set; counter re-seeded only on reset, avoiding redundant DB round-trips in steady state
- ctx-conditional audit on exception path in SpringAiMcpAdapter: audit skipped if ctx is null (auth failure before tenant extraction); log.warn fires instead to avoid NPE
- LENIENT Mockito strictness on AgentQuotaServiceTest: auditLog.countForAgentSince stub in @BeforeEach is unused by rejection tests (quota throws before warm-up); lenient is correct DRY placement

### Decisions (Phase 03 Plan 04 — MCP Security Test Suite)

- ArchUnit DescribedPredicate<JavaCall<?>> for callMethodWhere in McpMutationAllowlistTest: checks owner class isEquivalentTo(SchemaRegistry.class) plus method name prefix to block mutation verbs; placed in arch package alongside ProjectionsModuleDependencyTest
- MockWriteTool dispatch simulation in McpQuotaEnforcementIT: mirrors SpringAiMcpAdapter.invokeTool() inline (not @SpyBean); proves SEC-07 at full dispatch layer via isWriteTool()=true override with QUOTA_EXCEEDED audit row verified
- V14-V23 migrations copied to fabric-projections test resources: ITs need mcp_audit_log and mcp_agent_quotas tables; intermediate migrations included for Flyway baseline integrity

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

**Last session:** 2026-04-17T09:49:30.886Z
**Stopped at:** Completed 04-sql-view-kafka-projections-hash-chained-audit plan 03 (Debezium/Kafka CDC Fan-out)

**Next action on resume:** Execute plan 04-03 (next plan in phase 04).

**Files of record:**

- `.planning/PROJECT.md` — core value, constraints, decisions
- `.planning/REQUIREMENTS.md` — 87 v1 requirements with traceability to phases 0–5
- `.planning/ROADMAP.md` — 6-phase milestone 1 plan with success criteria
- `.planning/research/SUMMARY.md` — research synthesis and phase rationale
- `.planning/research/STACK.md`, `ARCHITECTURE.md`, `PITFALLS.md`, `FEATURES.md`

---
*State initialized: 2026-04-13*
