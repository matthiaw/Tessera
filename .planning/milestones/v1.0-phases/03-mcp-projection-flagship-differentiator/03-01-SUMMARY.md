---
phase: 03-mcp-projection-flagship-differentiator
plan: "01"
subsystem: mcp-projection
tags: [mcp, spring-ai, graph-repository, flyway, security, tool-provider, prompt-injection]

requires:
  - phase: 03-mcp-projection-flagship-differentiator
    plan: "00"
    provides: "FindShortestPathSpikeIT (Assumption A3 CONFIRMED), ToolResponseWrapperTest stub, McpIsolationArchTest active"

provides:
  - "ToolProvider interface: Tessera-owned tool contract, zero Spring AI imports (D-A2)"
  - "ToolResponse record: ok/error factories"
  - "SpringAiMcpAdapter: sole Spring AI import point; registers all ToolProviders at startup; wraps every response"
  - "ToolResponseWrapper: <data>...</data> prompt injection mitigation (SEC-08)"
  - "McpProjectionConfig: marker config enabling mcp package component scan"
  - "GraphRepository.executeTenantCypher(): read-only Cypher with mutation keyword blocklist (T-03-02)"
  - "GraphRepository.findShortestPath(): AGE shortestPath() with WHERE ALL cross-tenant filter (T-03-03)"
  - "V22__mcp_audit_log.sql: mcp_audit_log table (D-C3)"
  - "V23__mcp_agent_quotas.sql: mcp_agent_quotas table (D-C2)"
  - "MCP SSE endpoint at /mcp/sse with tool-change-notification enabled"
  - "ToolResponseWrapperTest: 7 test methods, 10 executions, all passing"

affects:
  - 03-02-plan
  - 03-03-plan

tech-stack:
  added:
    - "spring-ai-starter-mcp-server-webmvc 1.0.5 (via Spring AI BOM) added to fabric-projections"
  patterns:
    - "D-A2 isolation: only SpringAiMcpAdapter imports Spring AI MCP types; all ToolProvider implementors stay Spring-AI-free"
    - "ApplicationRunner for MCP tool registration (avoids Springdoc lifecycle Pitfall 6)"
    - "Mutation keyword blocklist in GraphSession.executeTenantCypher() for T-03-02 mitigation"
    - "WHERE ALL(n IN nodes(path) WHERE n.model_id = tenant) in findShortestPath() for T-03-03 mitigation"

key-files:
  created:
    - fabric-app/src/main/resources/db/migration/V22__mcp_audit_log.sql
    - fabric-app/src/main/resources/db/migration/V23__mcp_agent_quotas.sql
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolProvider.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolResponse.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/interceptor/ToolResponseWrapper.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/McpProjectionConfig.java
  modified:
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphRepositoryImpl.java
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
    - fabric-projections/pom.xml
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
    - fabric-app/src/main/resources/application.yml
    - fabric-projections/src/test/java/dev/tessera/projections/mcp/ToolResponseWrapperTest.java

key-decisions:
  - "D-A2 boundary: SpringAiMcpAdapter is the sole class importing Spring AI MCP types; ToolProvider implementors never touch Spring AI"
  - "isWriteTool() on ToolProvider interface (not private adapter method): testable, allows future write tools to override without adapter changes"
  - "No ToolRequest carrier record: ToolProvider.execute() takes direct parameters (TenantContext, agentId, Map) — carrier adds indirection with no benefit"
  - "McpSyncServer provided by spring-ai-starter autoconfiguration; no explicit @Bean needed in McpProjectionConfig"
  - "SchemaChangeEvent listener deferred: event type does not yet exist in fabric-core; notifySchemaChanged() is a public method on SpringAiMcpAdapter for wiring in a later plan"
  - "ApplicationRunner for tool registration avoids Springdoc lifecycle issue (Pitfall 6 from RESEARCH.md)"

metrics:
  duration: 14min
  tasks: 2
  files: 12
  completed: 2026-04-17
---

# Phase 03 Plan 01: MCP Infrastructure Bootstrap Summary

**Spring AI MCP SSE endpoint wired at /mcp/sse; ToolProvider interface isolates all tools from Spring AI; SpringAiMcpAdapter bridges to McpSyncServer with SEC-08 response wrapping; GraphRepository extended with read-only Cypher and shortest-path queries using spike-confirmed AGE patterns.**

## Performance

- **Duration:** ~14 min
- **Started:** 2026-04-17T06:02:00Z
- **Completed:** 2026-04-17T06:16:28Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments

- Flyway V22 (mcp_audit_log) and V23 (mcp_agent_quotas) tables created — ready for Plan 03 audit and quota wiring
- GraphRepository extended with `executeTenantCypher()` (mutation keyword blocklist, T-03-02 mitigation) and `findShortestPath()` (AGE shortestPath with WHERE ALL cross-tenant filter, T-03-03 mitigation)
- ToolProvider interface with zero Spring AI imports (D-A2 isolation boundary); isWriteTool() default method for quota dispatch
- ToolResponse record with ok/error factories
- ToolResponseWrapper applying <data>...</data> markers to all tool responses (SEC-08)
- SpringAiMcpAdapter: registers all ToolProvider beans at startup via ApplicationRunner; extracts tenant+agent from JWT; wraps all responses; TODO hooks for Plan 03 audit log and quota service
- McpProjectionConfig: marker config; McpSyncServer auto-configured by spring-ai-starter
- SecurityConfig: confirmed anyRequest().authenticated() covers /mcp/** for ROLE_AGENT JWTs (T-03-04)
- application.yml: MCP SSE at /mcp/sse, message at /mcp/message, tool-change-notification enabled
- ToolResponseWrapperTest: @Disabled removed, 7 test methods (10 executions incl. parameterized), all passing

## Task Commits

1. **Task 1: Migrations + GraphRepository + ToolProvider contracts** — `4e920d3`
2. **Task 2: SpringAiMcpAdapter + ToolResponseWrapper + config + test** — `7881d50`

## Files Created/Modified

- `fabric-app/src/main/resources/db/migration/V22__mcp_audit_log.sql` — mcp_audit_log table with model_id/agent_id/tool_name/outcome/duration_ms + 2 indexes
- `fabric-app/src/main/resources/db/migration/V23__mcp_agent_quotas.sql` — mcp_agent_quotas keyed by (agent_id, model_id)
- `fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java` — added executeTenantCypher() and findShortestPath()
- `fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphRepositoryImpl.java` — delegates to GraphSession
- `fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` — implements executeTenantCypher() (mutation blocklist + agtype result parsing) and findShortestPath() (AGE shortestPath + WHERE ALL tenant filter + agtype array parsing)
- `fabric-projections/pom.xml` — added spring-ai-starter-mcp-server-webmvc dependency
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolProvider.java` — Tessera tool interface (zero Spring AI imports)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolResponse.java` — tool result carrier record
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/interceptor/ToolResponseWrapper.java` — <data>...</data> wrapper
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java` — D-A2 bridge to McpSyncServer
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/McpProjectionConfig.java` — marker config
- `fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java` — added MCP agent comment
- `fabric-app/src/main/resources/application.yml` — MCP SSE config block added
- `fabric-projections/src/test/java/dev/tessera/projections/mcp/ToolResponseWrapperTest.java` — @Disabled removed, full test suite

## Decisions Made

- **D-A2 isolation enforced**: SpringAiMcpAdapter is the sole Spring AI import point. McpIsolationArchTest (created in Plan 00) is already active and will enforce this from the first Plan 02 tool onward.
- **No ToolRequest record**: `ToolProvider.execute(TenantContext, String, Map)` takes direct parameters. A carrier record would add indirection with no benefit since the adapter already unpacks tenant and agentId before calling execute().
- **isWriteTool() on the interface**: Placing the method on ToolProvider (not as private adapter logic) makes it testable and lets future write tools override without touching the adapter.
- **SchemaChangeEvent listener deferred**: The event type does not exist yet in fabric-core. `SpringAiMcpAdapter.notifySchemaChanged()` is a public method ready to be wired when the event is created.

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written. The only deviation is the SchemaChangeEvent listener not being implemented as `ApplicationListener<SchemaChangeEvent>` because the event type does not exist yet in fabric-core. This was handled by exposing `notifySchemaChanged()` as a public method with a TODO comment, consistent with the plan's intent.

## Known Stubs

None — all production classes for this plan are fully implemented. The TODO Plan 03 comments in SpringAiMcpAdapter (`mcpAuditLog.record(...)` and `agentQuotaService.checkWriteQuota(...)`) are intentional stubs pointing to Plan 03 work, not stubs that block this plan's goal.

## Threat Flags

No new security-relevant surface beyond what is in the plan's threat model. The mutation keyword blocklist (T-03-02) and WHERE ALL path filter (T-03-03) are implemented as specified. The /mcp/sse and /mcp/message endpoints are covered by the existing `anyRequest().authenticated()` rule (T-03-04).

## Next Phase Readiness

- **Plan 02 (MCP tools)**: ToolProvider interface stable; SpringAiMcpAdapter ready to pick up new ToolProvider beans automatically; GraphRepository extended with executeTenantCypher() and findShortestPath() for TraverseTool and FindPathTool; McpIsolationArchTest and McpSchemaAllowlistArchTest are live
- **Plan 03 (Audit + quota)**: V22/V23 migration tables ready; TODO hooks in SpringAiMcpAdapter at exact integration points; McpAuditLogTest and AgentQuotaServiceTest stubs from Plan 00 are ready to be enabled

---
*Phase: 03-mcp-projection-flagship-differentiator*
*Completed: 2026-04-17*

## Self-Check: PASSED

- V22__mcp_audit_log.sql: present at fabric-app/src/main/resources/db/migration/V22__mcp_audit_log.sql
- V23__mcp_agent_quotas.sql: present at fabric-app/src/main/resources/db/migration/V23__mcp_agent_quotas.sql
- ToolProvider.java: present at fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolProvider.java
- ToolResponse.java: present at fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolResponse.java
- ToolResponseWrapper.java: present at fabric-projections/src/main/java/dev/tessera/projections/mcp/interceptor/ToolResponseWrapper.java
- SpringAiMcpAdapter.java: present at fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java
- McpProjectionConfig.java: present at fabric-projections/src/main/java/dev/tessera/projections/mcp/McpProjectionConfig.java
- Commits 4e920d3 and 7881d50: verified in git log
- ToolResponseWrapperTest: 10 tests passing (0 @Disabled)
