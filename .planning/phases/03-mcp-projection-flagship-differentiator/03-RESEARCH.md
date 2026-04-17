---
gsd_artifact: research
phase: "03"
phase_name: "MCP Projection (Flagship Differentiator)"
created: 2026-04-17
requirements: [SEC-07, SEC-08, MCP-01, MCP-02, MCP-03, MCP-04, MCP-05, MCP-06, MCP-07, MCP-08, MCP-09]
---

# Phase 3: MCP Projection (Flagship Differentiator) — Research

**Researched:** 2026-04-17
**Domain:** Spring AI MCP Server, Apache AGE path queries, prompt injection hardening, audit log design
**Confidence:** HIGH (core Spring AI API verified via official docs + Maven; AGE path syntax verified via official docs; security patterns verified via Spring Security docs)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**A. MCP Transport & Spring AI Isolation**
- D-A1: SSE (HTTP) transport only via `spring-ai-starter-mcp-server-webmvc`. Reuses existing Tomcat server and `SecurityConfig`.
- D-A2: Spring AI MCP is interface-isolated. `ToolProvider` interface in `dev.tessera.projections.mcp`; `SpringAiMcpAdapter` bridges to Spring AI beans.
- D-A3: MCP code inside `fabric-projections` under `dev.tessera.projections.mcp`.

**B. Tool Surface & Dynamic Registration**
- D-B1: 7 generic tools: `list_entity_types`, `describe_type`, `traverse`, `get_entity`, `query_entities`, `find_path`, `get_state_at`
- D-B2: Restart-on-schema-change model as baseline (research to confirm whether runtime registration is supported — if yes, prefer it)
- D-B3: Constant 7 tools regardless of schema size
- D-B4: `get_entity` depth parameter (default 1, max 3)

**C. Agent Identity, Write Quotas & Audit**
- D-C1: JWT auth with `ROLE_AGENT` claim; reuses `SecurityConfig` + `RotatableJwtDecoder`
- D-C2: `mcp_agent_quotas` table + in-memory `AtomicLong` counter; default quota = 0 writes
- D-C3: `mcp_audit_log` table with `(id, model_id, agent_id, tool_name, arguments JSONB, outcome, duration_ms, created_at)`; admin endpoint `GET /admin/mcp/audit`

**D. Prompt Injection Hardening**
- D-D1: `ToolResponseWrapper` interceptor at `ToolProvider` level; single enforcement point
- D-D2: Adversarial-seed CI test suite
- D-D3: Allowlist + ArchUnit test for schema-mutation prevention

### Claude's Discretion

- Exact Spring AI MCP starter `application.yml` properties
- `ToolProvider` interface shape (return types, signatures)
- Cursor pagination for `query_entities` (reuse `CursorCodec` or MCP-specific variant)
- `find_path` algorithm (AGE built-in shortestPath vs custom BFS)
- `get_state_at` implementation (event replay in Java vs Cypher temporal query)
- Flyway migration numbering (continue from V21)
- MCP admin endpoint query parameters
- In-memory quota counter reset strategy (sliding vs fixed window)

### Deferred Ideas (OUT OF SCOPE)

- NL-to-Cypher translation for `traverse()`
- Type-specific MCP tools
- Runtime hot-reload without restart (now potentially NOT deferred — see runtime registration finding)
- Stdio MCP transport
- GraphQL projection
- LLM-assisted Cypher generation inside Tessera
- Dedicated write tools (create_entity, update_entity)
- Agent-to-agent communication
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SEC-07 | MCP agents read-only by default; writes require per-agent quota; no schema mutation tools | D-C2 quota table design verified; allowlist pattern established |
| SEC-08 | MCP tool responses wrap source-system content in `<data>...</data>` markers | ToolResponseWrapper interceptor pattern; MCP text content type confirmed |
| MCP-01 | Spring AI MCP Server isolated behind interface for swappability | `io.modelcontextprotocol.sdk:mcp` fallback confirmed on Maven Central |
| MCP-02 | `list_entity_types()` and `describe_type(slug)` from Schema Registry | SchemaRegistry.listNodeTypes/loadFor API confirmed usable |
| MCP-03 | `query_entities(type, filter)` with cursor pagination | CursorCodec reusable; GraphRepository.queryAllAfter available |
| MCP-04 | `get_entity(type, id)` with configurable relation depth | GraphSession.findNode + neighbor query pattern established |
| MCP-05 | `traverse(query)` accepting Cypher (tenant-scoped) | GraphSession is the sole Cypher executor; model_id injection pattern confirmed |
| MCP-06 | `find_path(from, to)` shortest path between two nodes | AGE shortestPath/allShortestPaths confirmed with WHERE filter support |
| MCP-07 | `get_state_at(entity_id, timestamp)` from event log | EventLog.replayToState() already implemented and available |
| MCP-08 | MCP tool set driven by Schema Registry; adding type surfaces without redeploy (or restart fallback) | Runtime addTool() confirmed available in Spring AI 1.0.5 — runtime path IS viable |
| MCP-09 | Audit log: every invocation records agent identity, tool, arguments, outcome | Table design and admin endpoint pattern established |
</phase_requirements>

---

## Summary

Phase 3 delivers the MCP projection layer inside `fabric-projections`. The research confirms the core Spring AI MCP Server API is available and compatible: `spring-ai-starter-mcp-server-webmvc` is already in the Spring AI 1.0.5 BOM (already in parent POM), and `McpSyncServer` exposes `addTool()`/`removeTool()`/`notifyToolsListChanged()` for runtime tool management. This eliminates the restart-on-schema-change fallback — **runtime registration is viable** and should be the primary implementation path.

The 7 generic tools map cleanly onto existing `fabric-core` APIs. `get_state_at` delegates directly to `EventLog.replayToState()` which already exists. `find_path` uses AGE's built-in `shortestPath()` Cypher function with `model_id` WHERE filter injection via `GraphSession`. `traverse()` uses `GraphSession` as the sole Cypher executor with mandatory tenant scoping.

One significant security finding: the Spring AI community `mcp-security` module (which provides `McpServerOAuth2Configurer`) is 1.1.x-only and explicitly states "SSE transport not supported". However, this module is irrelevant — Tessera uses its own `SecurityFilterChain` (JWT HMAC resource server) and the SSE `/sse` endpoint is just another MVC endpoint protected by it. Spring Security standard `oauth2ResourceServer().jwt()` protects all MVC endpoints including the SSE path. No Spring AI-specific security module is needed.

**Primary recommendation:** Use `McpSyncServer.addTool()` for runtime schema-driven registration (schema change event listener triggers `addTool()`/`removeTool()` + `notifyToolsListChanged()`). Build 7 concrete `ToolProvider` implementations behind a single interface. Wrap all tool responses through `ToolResponseWrapper` interceptor before returning. Audit every invocation in `mcp_audit_log`.

---

## Standard Stack

### Core — Already in Parent POM

| Library | Version | Purpose | Status |
|---------|---------|---------|--------|
| `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` | 1.0.5 (via BOM) | MCP server over SSE / Spring MVC | Add to `fabric-projections/pom.xml` [VERIFIED: Maven registry + official docs] |
| `spring-boot-starter-web` | 3.5.13 (BOM) | Tomcat + Spring MVC (already present) | Already in `fabric-projections/pom.xml` |
| `spring-boot-starter-oauth2-resource-server` | 3.5.13 (BOM) | JWT security (already present) | Already in `fabric-projections/pom.xml` |
| `spring-boot-starter-jdbc` | 3.5.13 (BOM) | `NamedParameterJdbcTemplate` for audit log | Add to `fabric-projections/pom.xml` [VERIFIED: codebase] |
| `spring-boot-starter-data-jpa` | 3.5.13 (BOM) | `mcp_agent_quotas` JPA entity | Add if not already transitive |

### Fallback (MCP-01 isolation escape hatch)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.modelcontextprotocol.sdk:mcp` | 0.9.0+ | Raw MCP Java SDK | If Spring AI MCP starter breaks on upgrade [VERIFIED: Maven registry, 0.9.0 available] |

**Note on fallback SDK coordinates:** The raw Java SDK uses groupId `io.modelcontextprotocol.sdk`, artifactId `mcp` (not `io.modelcontextprotocol:sdk-java` — that coordinates does not exist on Maven Central). [VERIFIED: Maven registry via `mvn dependency:get`]

### Installation (add to `fabric-projections/pom.xml`)

```xml
<!-- Spring AI MCP Server (BOM already manages version) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>

<!-- JDBC template for mcp_audit_log writes (may already be transitive) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

---

## Architecture Patterns

### Recommended Package Structure

```
fabric-projections/src/main/java/dev/tessera/projections/
├── rest/                              (existing — do not modify)
│   ├── security/                      (SecurityConfig, RotatableJwtDecoder — reuse)
│   └── ...
└── mcp/
    ├── api/
    │   ├── ToolProvider.java          (D-A2: Tessera-owned interface; Spring AI isolation)
    │   ├── ToolRequest.java           (request DTO: tenantCtx + parameters)
    │   └── ToolResponse.java          (response DTO: content String, wrapped by interceptor)
    ├── adapter/
    │   └── SpringAiMcpAdapter.java    (D-A2: bridges ToolProvider -> Spring AI McpSyncServer)
    ├── tools/
    │   ├── ListEntityTypesTool.java   (MCP-02)
    │   ├── DescribeTypeTool.java      (MCP-02)
    │   ├── QueryEntitiesTool.java     (MCP-03)
    │   ├── GetEntityTool.java         (MCP-04)
    │   ├── TraverseTool.java          (MCP-05)
    │   ├── FindPathTool.java          (MCP-06)
    │   └── GetStateAtTool.java        (MCP-07)
    ├── interceptor/
    │   └── ToolResponseWrapper.java   (D-D1: wraps every response in <data>...</data>)
    ├── quota/
    │   ├── AgentQuotaService.java     (D-C2: AtomicLong counters + mcp_agent_quotas table)
    │   └── QuotaExceededException.java
    ├── audit/
    │   ├── McpAuditLog.java           (D-C3: insert to mcp_audit_log)
    │   └── McpAuditController.java    (GET /admin/mcp/audit)
    └── McpProjectionConfig.java       (Spring AI autoconfiguration wiring)
```

### Pattern 1: Tool Provider Interface (MCP-01 isolation)

```java
// Source: CONTEXT.md D-A2
package dev.tessera.projections.mcp.api;

/**
 * Tessera-owned tool contract. Spring AI is never imported here.
 * All tool classes implement this interface. The SpringAiMcpAdapter
 * bridges to McpSyncServer.
 */
public interface ToolProvider {
    String toolName();
    String toolDescription();
    // JSON Schema string for the tool's inputSchema
    String inputSchemaJson();
    // Execute and return raw content; ToolResponseWrapper wraps result
    String execute(TenantContext tenant, Map<String, Object> arguments);
}
```

**Why this matters:** If Spring AI MCP minor version breaks (API churn observed 2025-2026 [VERIFIED: CLAUDE.md]), only `SpringAiMcpAdapter` changes. All 7 tool classes stay untouched.

### Pattern 2: Runtime Tool Registration via McpSyncServer

```java
// Source: Spring AI 1.0.5 docs + spring.io/blog/2025/05/04 (MEDIUM confidence — blog confirmed,
//         addTool() method confirmed via search; exact signature from McpSyncServer API)
@Component
public class SpringAiMcpAdapter implements ApplicationListener<SchemaChangeEvent> {

    private final McpSyncServer mcpServer;
    private final List<ToolProvider> tools;

    @Override
    public void onApplicationEvent(SchemaChangeEvent event) {
        // Schema changed — re-register tools (tenant-specific schema reflected
        // through SchemaRegistry calls in tool.execute(), not in registration)
        // For 7 static tools, notifying clients is sufficient:
        mcpServer.notifyToolsListChanged();
        // If actual tool specs change, use addTool()/removeTool() first
    }
}
```

**Critical finding (MCP-08):** `McpSyncServer.addTool()`, `removeTool()`, and `notifyToolsListChanged()` ARE supported in Spring AI 1.0.5. [VERIFIED: spring.io/blog/2025/05/04/spring-ai-dynamic-tool-updates-with-mcp/ — confirmed via WebSearch, medium confidence for exact method names; official Spring AI MCP docs confirm runtime registration via CommandLineRunner pattern]

Since Tessera uses 7 **static** tools (schema info is fetched dynamically inside each tool via `SchemaRegistry.loadFor()`), the restart-on-schema-change fallback may not be needed at all. Schema changes affect tool *behavior*, not tool *registration*. The `list_entity_types` tool always returns the current schema by querying at invocation time — no re-registration needed.

### Pattern 3: ToolResponseWrapper Interceptor (SEC-08, D-D1)

```java
// Source: CONTEXT.md D-D1
public final class ToolResponseWrapper {

    private static final String OPEN = "<data>";
    private static final String CLOSE = "</data>";

    /** Wrap every tool response payload. Applied by SpringAiMcpAdapter for
     *  all tools — individual tools must NOT wrap their own output. */
    public static String wrap(String rawContent) {
        if (rawContent == null) return OPEN + CLOSE;
        return OPEN + rawContent + CLOSE;
    }
}
```

**Enforcement path:** `SpringAiMcpAdapter` calls `ToolResponseWrapper.wrap(tool.execute(...))` before building the `CallToolResult`. An ArchUnit test verifies no tool class directly calls `ToolResponseWrapper`.

### Pattern 4: Tenant Context Extraction from JWT in MCP Tools

```java
// Source: SecurityConfig.java (existing code) + Spring AI MCP ToolContext docs
// McpToolUtils.getMcpExchange(toolContext) provides exchange;
// JWT principal is on SecurityContextHolder (Spring MVC, same thread)
@Tool(description = "...")
public String someToolMethod(ToolContext toolContext, ...) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    // auth.getName() == JWT 'tenant' claim (setPrincipalClaimName("tenant") in SecurityConfig)
    UUID modelId = UUID.fromString(auth.getName());
    TenantContext tenant = TenantContext.of(modelId);
    // agent identity = JWT 'sub' claim
    String agentId = ...; // cast to JwtAuthenticationToken and get sub
}
```

**Why it works:** Spring AI WebMVC variant runs tools on the same Tomcat thread as the HTTP request, so `SecurityContextHolder` is populated. [VERIFIED: Spring AI docs note "full thread local support, enabling seamless integration with Spring Security method-level annotations" for WebMVC variant — cited above in security search]

### Pattern 5: find_path Using AGE shortestPath

```cypher
-- Source: Apache AGE official docs + multiple community examples [MEDIUM confidence]
-- Tenant-scoped shortest path between two nodes by UUID
SELECT * FROM cypher('tessera_main', $$
  MATCH (a {model_id: "{{modelId}}", uuid: "{{fromUuid}}"}),
        (b {model_id: "{{modelId}}", uuid: "{{toUuid}}"})
  MATCH path = shortestPath((a)-[*1..10]-(b))
  WHERE ALL(n IN nodes(path) WHERE n.model_id = "{{modelId}}")
  RETURN path
$$) AS (path agtype);
```

**Caveats:**
- The `model_id` filter must appear in both node lookups AND the path restriction to prevent cross-tenant hops. [ASSUMED — the double-filter pattern is standard tenant isolation practice, but AGE-specific behavior of `ALL(n IN nodes(path) WHERE ...)` filtering mid-traversal needs testing]
- `allShortestPaths()` is also supported if all shortest paths are needed
- Variable-length `*1..10` is the fallback if `shortestPath()` has issues in AGE 1.6.0 [ASSUMED — based on community examples]

### Pattern 6: get_state_at Delegation (EVENT-06)

```java
// Source: EventLog.java (existing codebase)
// EventLog.replayToState() already implements EVENT-06 exactly
public String execute(TenantContext tenant, Map<String, Object> arguments) {
    UUID entityId = UUID.fromString((String) arguments.get("entity_id"));
    Instant at = Instant.parse((String) arguments.get("timestamp"));
    return eventLog.replayToState(tenant, entityId, at)
        .map(state -> toJson(state))
        .orElse("{\"error\": \"no state found at timestamp\"}");
}
```

**This is free:** `EventLog.replayToState()` is already implemented and verified in Phase 1. The `get_state_at` tool is a thin adapter.

### Pattern 7: SSE Endpoint Configuration

```yaml
# Source: Spring AI 1.0.x docs [VERIFIED via WebFetch]
spring:
  ai:
    mcp:
      server:
        name: tessera-mcp
        version: 1.0.0
        type: SYNC
        sse-endpoint: /mcp/sse
        sse-message-endpoint: /mcp/message
        tool-change-notification: true
```

The SSE endpoint `/mcp/sse` is a standard Spring MVC endpoint and is protected by the existing `SecurityConfig` JWT filter chain — no additional MCP-specific security configuration is needed. [VERIFIED: Spring Security `oauth2ResourceServer().jwt()` applies to ALL MVC endpoints by default via `anyRequest().authenticated()`]

### Anti-Patterns to Avoid

- **Importing `SchemaRegistry.create*` / `update*` / `delete*` from any MCP tool class** — blocked by ArchUnit allowlist test (D-D3)
- **Wrapping responses inside individual tool implementations** — wrapping belongs only in `ToolResponseWrapper` (D-D1). Redundant wrapping creates `<data><data>...</data></data>` output that breaks agents.
- **Using Spring AI's `@Tool` annotation directly on tool classes** — this couples tool classes to Spring AI. Only `SpringAiMcpAdapter` uses Spring AI annotations; tool classes use Tessera's `ToolProvider` interface.
- **Running path queries without injecting `model_id`** — the `traverse()` tool must run Cypher through `GraphSession` or a delegating method that auto-injects `model_id`, not raw JDBC.
- **Using ThreadLocal for `TenantContext`** — CORE-03 mandate. Extract from JWT, pass explicitly.
- **Allowing depth > 3 in `get_entity`** — graph explosion risk on densely connected tenants (D-B4).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MCP protocol wire format | Custom SSE/JSON-RPC implementation | `spring-ai-starter-mcp-server-webmvc` | Tool schema, SSE framing, JSON-RPC 2.0, capabilities negotiation — 100s of edge cases |
| Event replay for temporal query | Custom SQL aggregation | `EventLog.replayToState()` | Already implemented, tested, handles TOMBSTONE markers |
| Cursor pagination encoding | New cursor format | `CursorCodec` (existing) | Wire format `modelId|typeSlug|lastSeq|lastNodeId` already defined, tested |
| Shortest path algorithm | Custom BFS/Dijkstra in Java | AGE `shortestPath()` Cypher | Graph DB handles this natively with native indexing |
| JSON Schema generation for tool params | Manual JSON string building | Spring AI auto-schema from Java method params | Annotation-driven, survives refactoring, standard |
| Audit log timestamp precision | Java System.currentTimeMillis() | `TIMESTAMPTZ` + Postgres `now()` | Tessera owns timestamps (CORE-08); TIMESTAMPTZ has microsecond precision |

**Key insight:** The most valuable "don't hand-roll" in this phase is the MCP protocol itself. The wire format (JSON-RPC 2.0 over SSE), capability negotiation, tool schema format, and error response shapes are all handled by the Spring AI starter.

---

## Common Pitfalls

### Pitfall 1: SSE Security — "Deprecated for MCP Security Module" Confusion

**What goes wrong:** Developer reads Spring AI 1.1.x MCP security docs saying "SSE transport not supported" and concludes SSE cannot be secured.
**Why it happens:** The statement refers specifically to the `spring-ai-community/mcp-security` module (a 1.1.x-only community add-on for OAuth2 authorization servers). Tessera does NOT use this module.
**How to avoid:** Tessera uses its own `SecurityFilterChain` with `oauth2ResourceServer().jwt()` which protects ALL MVC endpoints including `/mcp/sse` and `/mcp/message`. No MCP-specific security module is needed.
**Warning signs:** If you see `McpServerOAuth2Configurer` in the code — that's the wrong path for Tessera 1.0.5 + HMAC-JWT setup.

### Pitfall 2: Tenant Isolation in Cypher traverse()

**What goes wrong:** Agent passes a Cypher query like `MATCH (n) RETURN n` that matches all nodes across all tenants.
**Why it happens:** Raw Cypher does not automatically filter by `model_id`.
**How to avoid:** `TraverseTool` must parse the incoming Cypher and inject `WHERE n.model_id = "..."` or use `GraphSession`'s internal model_id injection. The safest approach: wrap the user's Cypher as a subquery with a mandatory model_id filter on the outer pattern. Add a validation step that rejects queries containing `DELETE`, `CREATE`, `MERGE`, `SET`, `REMOVE`, `DROP`.
**Warning signs:** Integration test `CrossTenantLeakPropertyIT` pattern should be replicated for MCP traverse — seed two tenants, verify tenant A cannot see tenant B data via `traverse()`.

### Pitfall 3: ToolResponseWrapper Double-Wrapping

**What goes wrong:** Tool implementation AND adapter both apply `<data>` wrapper → `<data><data>content</data></data>`.
**Why it happens:** Defense-in-depth logic applied too eagerly.
**How to avoid:** Wrapper is ONLY applied in `SpringAiMcpAdapter`. ArchUnit rule: no class in `dev.tessera.projections.mcp.tools` calls `ToolResponseWrapper`.

### Pitfall 4: Quota Counter Accuracy After Restart

**What goes wrong:** In-memory `AtomicLong` counter resets to 0 on restart, allowing quota bypass if the service restarts frequently.
**Why it happens:** JVM state is not persistent.
**How to avoid:** On startup, initialize each agent's counter from the `mcp_audit_log` table (count rows for the agent within the current hour/day window). This is one SQL query per known agent at startup — acceptable cost.
**Warning signs:** If quota is easily exceeded in tests only after a cold start, the initialization read is missing.

### Pitfall 5: AGE shortestPath Across Tenant Boundary

**What goes wrong:** `shortestPath((a)-[*]-(b))` may traverse through nodes belonging to other tenants if the graph is not tenant-partitioned at the label level.
**Why it happens:** AGE stores all tenants in the same graph (partitioned by `model_id` property, not by separate graphs). A path can hop through any node with any label.
**How to avoid:** Add `ALL(n IN nodes(path) WHERE n.model_id = "{{modelId}}")` to the path query (or test if AGE 1.6.0 supports this mid-traversal filter). Fallback: validate source and target node ownership before running path query; reject if either node is not in tenant's graph.
**Warning signs:** An integration test seeding two tenants with overlapping node labels detects this.

### Pitfall 6: Spring AI Tool Registration Timing

**What goes wrong:** `McpSyncServer.addTool()` called before the server is fully initialized → NPE or tools not visible.
**Why it happens:** Spring Boot `@PostConstruct` vs `ApplicationRunner` ordering.
**How to avoid:** Register initial tools in an `ApplicationRunner` (runs after full context refresh) not in `@PostConstruct`. Schema-change-triggered re-registration happens via `ApplicationEventPublisher` event.

### Pitfall 7: `get_entity` Depth Query N+1 Problem

**What goes wrong:** Fetching neighbors at depth 2–3 triggers one Cypher query per hop level, making response time unacceptable for agents.
**Why it happens:** Naive implementation calls `findNode` recursively.
**How to avoid:** Use a single variable-length Cypher path query: `MATCH path = (n {uuid: "..."})-[*0..{{depth}}]-(m) WHERE n.model_id = "..."` to fetch all nodes in one round-trip.

---

## Code Examples

### Spring AI MCP Server Configuration (application.yml addition)

```yaml
# Source: Spring AI 1.0.x reference docs [VERIFIED via WebFetch]
spring:
  ai:
    mcp:
      server:
        name: tessera-mcp
        version: 1.0.0
        type: SYNC
        sse-endpoint: /mcp/sse
        sse-message-endpoint: /mcp/message
        tool-change-notification: true
```

### McpSyncServer Runtime Tool Addition

```java
// Source: spring.io blog 2025-05-04 pattern (MEDIUM confidence — blog confirmed,
//         exact method names inferred from Java SDK docs)
@Component
public class SpringAiMcpAdapter {
    private final McpSyncServer mcpServer;

    @EventListener
    public void onSchemaChange(SchemaChangedEvent event) {
        // 7 static tools — schema info fetched inside each tool at runtime.
        // Just notify clients their tool list has been semantically updated.
        mcpServer.notifyToolsListChanged();
    }
}
```

### MCP Tool Response (text content + data wrapper)

```json
// Source: MCP specification 2024-11-05 [VERIFIED via modelcontextprotocol.io]
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<data>{\"uuid\": \"...\", \"name\": \"Acme Corp\"}</data>"
      }
    ],
    "isError": false
  }
}
```

### AGE shortestPath Query with Tenant Isolation

```java
// Source: Apache AGE community docs + CONTEXT.md Cypher injection prevention pattern
// [MEDIUM confidence — shortestPath syntax confirmed; model_id filtering approach is ASSUMED to work]
String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
    + " MATCH (a {model_id: \"" + ctx.modelId() + "\", uuid: \"" + fromUuid + "\"}),"
    + "       (b {model_id: \"" + ctx.modelId() + "\", uuid: \"" + toUuid + "\"})"
    + " MATCH path = shortestPath((a)-[*1..10]-(b))"
    + " RETURN path"
    + " $$) AS (path agtype)";
```

**Note:** The `RETURN path` gives an agtype path object. Serializing this to JSON for the MCP response requires converting nodes and edges in the path into a readable structure. Consider a helper that extracts `nodes(path)` and `relationships(path)` into a JSON array. [ASSUMED — agtype path serialization needs testing; no official Java example found for path agtype]

### Flyway Migration V22 (mcp_audit_log)

```sql
-- V22__mcp_audit_log.sql
-- Source: CONTEXT.md D-C3 + Tessera migration conventions
CREATE TABLE mcp_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id    UUID NOT NULL,
    agent_id    TEXT NOT NULL,
    tool_name   TEXT NOT NULL,
    arguments   JSONB NOT NULL DEFAULT '{}',
    outcome     TEXT NOT NULL,  -- 'SUCCESS', 'QUOTA_EXCEEDED', 'ERROR', 'REJECTED'
    duration_ms BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX mcp_audit_by_tenant_time  ON mcp_audit_log (model_id, created_at DESC);
CREATE INDEX mcp_audit_by_agent_time   ON mcp_audit_log (model_id, agent_id, created_at DESC);
```

### Flyway Migration V23 (mcp_agent_quotas)

```sql
-- V23__mcp_agent_quotas.sql
-- Source: CONTEXT.md D-C2
CREATE TABLE mcp_agent_quotas (
    agent_id        TEXT NOT NULL,
    model_id        UUID NOT NULL,
    writes_per_hour INT  NOT NULL DEFAULT 0,
    writes_per_day  INT  NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, model_id)
);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| MCP stdio transport | SSE (HTTP) transport for remote agents | MCP spec 2024+ | SSE enables multi-tenant server with shared auth |
| Spring AI tool annotation `@Tool` | Spring AI 1.1.x also supports `@McpTool` for annotations, but 1.0.x uses `@Tool` | Spring AI 1.1.0 | Tessera 1.0.5 uses `@Tool` or programmatic `SyncToolSpecification` |
| Restart to update tool list | `McpSyncServer.addTool()` + `notifyToolsListChanged()` | Spring AI ~1.0 GA | Runtime tool updates without restart now viable |
| SSE for MCP | MCP spec 2025-11-xx adding Streamable HTTP | 2025 | SSE remains valid for 1.0.x; Streamable HTTP is future path |

**Deprecated/outdated:**
- `bootstrap.yml` for Vault config: deprecated since Spring Cloud Vault 3.0 (already handled in Phase 2)
- MCP `notifications/tools/list_changed` still the standard mechanism for notifying clients of tool list changes [VERIFIED: MCP spec 2024-11-05]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | AGE `ALL(n IN nodes(path) WHERE n.model_id = "...")` correctly filters mid-path nodes in AGE 1.6.0 | Architecture Pattern 5 (find_path) | Cross-tenant path traversal possible if filter is silently ignored; integration test required |
| A2 | `shortestPath()` variable-length `*1..10` avoids graph explosion for Tessera-scale graphs | Architecture Pattern 5 | Performance issue if tenant graph is very dense; may need configurable max-hops |
| A3 | Agtype `path` object returned by `shortestPath()` can be serialized to a useful JSON representation via `nodes(path)` and `relationships(path)` in AGE 1.6.0 | Code Examples (find_path) | If path agtype serialization is non-trivial, may need to fall back to variable-length MATCH with RETURN collect(n) |
| A4 | `McpSyncServer.addTool()` exact method signature accepts `McpServerFeatures.SyncToolSpecification` in Spring AI 1.0.5 | Architecture Pattern 2 | Minor code change; API shape confirmed from SDK docs but exact 1.0.5 overloads not verified from source |
| A5 | Quota counter initialization from `mcp_audit_log` at startup is cheap enough (few agents, sparse writes) | Pitfall 4 | If tenants have many agents with high write history, startup could be slow; add timeout/fallback |
| A6 | The `traverse()` tool can reject DML Cypher (CREATE/DELETE/MERGE) via a simple keyword allowlist without needing a full Cypher parser | Architecture Pattern — traverse() | Determined adversary might obfuscate DML keywords; a proper Cypher parser would be safer. Risk: medium (labeled data, read-only AGE session would catch it anyway if connection is configured read-only) |

**If this table is empty:** All claims in this research were verified or cited.

---

## Open Questions

1. **AGE path agtype serialization**
   - What we know: `shortestPath()` returns an `agtype` path object; `nodes(path)` and `relationships(path)` functions extract collections
   - What's unclear: Whether AGE 1.6.0's `toNodeState()` pattern in `GraphSession` can parse path agtypes, or whether a separate parser is needed
   - Recommendation: The planner should include a spike task (Wave 0 or Wave 1) to test `shortestPath()` return value serialization with the existing `AgtypeJsonParser`. If it fails, add a `PathAgtypeParser` helper.

2. **`McpSyncServer` bean injection in Spring Boot 1.0.5**
   - What we know: `McpServerAutoConfiguration` creates the `McpSyncServer` bean; `type=SYNC` is default
   - What's unclear: Whether `McpSyncServer` can be `@Autowired` directly or requires `McpServer.sync(...)` factory
   - Recommendation: Inject via `@Autowired McpSyncServer mcpServer`; add a smoke test to fail early if the bean is absent

3. **`traverse()` Cypher injection defense depth**
   - What we know: `GraphSession` uses regex IDENT validation for labels/keys; raw Cypher is passed directly from agents for `traverse()`
   - What's unclear: Whether a keyword blocklist (`CREATE`, `DELETE`, `MERGE`, `SET`, `REMOVE`) is sufficient or if a Cypher parser dependency is warranted
   - Recommendation: Start with keyword blocklist + mandatory `RETURN` clause verification. An integration test with adversarial Cypher inputs confirms coverage.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | Testcontainers (integration tests) | Yes | 27.4.0 | — |
| Apache AGE image | `AgePostgresContainer` (existing) | Yes (pinned digest) | PG16/v1.6.0-rc0 | — |
| Java 21 | All | Yes | OpenJDK Corretto 23 (Corretto 21 compatible) | — |
| Maven 3.9 | Build | Yes | 3.9.x (from `mvnw`) | — |
| `spring-ai-starter-mcp-server-webmvc:1.0.5` | MCP server | Yes | Verified in Maven Central | `io.modelcontextprotocol.sdk:mcp:0.9.0` |
| `io.modelcontextprotocol.sdk:mcp:0.9.0` | MCP-01 fallback | Yes | Verified in Maven Central | — |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Spring Boot 3.5.13 BOM) |
| Config file | `fabric-projections/pom.xml` (surefire + failsafe already configured) |
| Quick run command | `./mvnw test -pl fabric-projections -Dtest=McpAuditLogTest,AgentQuotaServiceTest,ToolResponseWrapperTest -DskipITs` |
| Full suite command | `./mvnw verify -pl fabric-projections` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SEC-07 | Agent with no quota rejected on write attempt | unit | `./mvnw test -pl fabric-projections -Dtest=AgentQuotaServiceTest` | Wave 0 |
| SEC-07 | Schema mutation tools absent from tool list | ArchUnit | `./mvnw test -pl fabric-projections -Dtest=McpSchemaAllowlistArchTest` | Wave 0 |
| SEC-08 | Every tool response wrapped in `<data>...</data>` | unit | `./mvnw test -pl fabric-projections -Dtest=ToolResponseWrapperTest` | Wave 0 |
| SEC-08 | Adversarial payloads do not alter tool behavior | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpPromptInjectionIT` | Wave 0 |
| MCP-01 | `SpringAiMcpAdapter` is only class importing Spring AI MCP types | ArchUnit | `./mvnw test -pl fabric-projections -Dtest=McpIsolationArchTest` | Wave 0 |
| MCP-02 | `list_entity_types` returns tenant schema | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpSchemaToolsIT` | Wave 0 |
| MCP-03 | `query_entities` cursor pagination works | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpQueryEntitiesIT` | Wave 0 |
| MCP-04 | `get_entity` depth=1 returns direct neighbors | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpGetEntityIT` | Wave 0 |
| MCP-05 | `traverse` tenant-scoped Cypher returns correct results | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpTraverseIT` | Wave 0 |
| MCP-06 | `find_path` returns shortest path within tenant | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpFindPathIT` | Wave 0 |
| MCP-07 | `get_state_at` returns correct historical state | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpGetStateAtIT` | Wave 0 |
| MCP-08 | Schema change reflected without restart (or documented fallback) | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpSchemaChangeIT` | Wave 0 |
| MCP-09 | Every tool invocation logged in `mcp_audit_log` | integration | `./mvnw verify -pl fabric-projections -Dit.test=McpAuditIT` | Wave 0 |

### Sampling Rate

- **Per task commit:** `./mvnw test -pl fabric-projections -Dtest=*McpTest,*McpSpec -DskipITs`
- **Per wave merge:** `./mvnw verify -pl fabric-projections`
- **Phase gate:** Full suite green before `/gsd-verify-work` (also runs `fabric-app` ArchUnit tests)

### Wave 0 Gaps

- [ ] `McpAuditLogTest.java` — unit test for `McpAuditLog.record()` method
- [ ] `AgentQuotaServiceTest.java` — unit tests for quota enforcement and initialization from DB
- [ ] `ToolResponseWrapperTest.java` — null input, empty string, nested data tags
- [ ] `McpSchemaAllowlistArchTest.java` — ArchUnit: no tool class imports `SchemaRegistry.create*`/`update*`/`delete*`
- [ ] `McpIsolationArchTest.java` — ArchUnit: only `SpringAiMcpAdapter` imports `org.springframework.ai.mcp`
- [ ] `McpPromptInjectionIT.java` — adversarial seed integration test (SEC-08)
- [ ] Integration test classes for each of the 7 tools (7 new `*IT.java` files)
- [ ] Test migrations for `mcp_audit_log` and `mcp_agent_quotas` in `fabric-projections/src/test/resources/db/migration/`

*(Existing `AgePostgresContainer` and `ProjectionItApplication` are reusable for all integration tests)*

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | Yes | JWT HMAC (existing `RotatableJwtDecoder`) + `ROLE_AGENT` claim |
| V3 Session Management | No | Stateless JWT; no sessions |
| V4 Access Control | Yes | Agent write quota enforcement; schema-mutation tool allowlist |
| V5 Input Validation | Yes | Cypher DML keyword blocklist in `traverse()`; depth parameter clamping |
| V6 Cryptography | No | JWT signing key managed by Vault (Phase 2, already done) |
| V7 Error Handling | Yes | `TesseraProblemHandler` reuse; no graph data in error responses |
| V9 Communications | Yes | TLS inherited from existing Tomcat config (SEC-01 Phase 2) |

### Known Threat Patterns for MCP Server

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Prompt injection via source-system data | Tampering / Spoofing | `<data>...</data>` wrapper (SEC-08); adversarial CI tests |
| Schema mutation via `traverse()` Cypher | Tampering | DML keyword blocklist + read-only schema in tool allowlist |
| Cross-tenant data access via `traverse()` | Information Disclosure | Mandatory `model_id` injection in `GraphSession`; tenant isolation integration test |
| Write quota bypass via restart | Elevation of Privilege | Counter initialization from `mcp_audit_log` on startup |
| Tool enumeration / schema discovery by unauthorized agent | Information Disclosure | All `/mcp/**` endpoints require valid JWT (`anyRequest().authenticated()`) |
| Denial of service via deep `get_entity` (depth=10+) | DoS | Depth parameter clamped to max=3 (D-B4) |
| Denial of service via expensive Cypher in `traverse()` | DoS | Query timeout via JDBC statement timeout; length/complexity limits (ASSUMED) |

---

## Sources

### Primary (HIGH confidence)

- `fabric-projections/pom.xml` + `pom.xml (root)` — confirmed Spring AI 1.0.5 BOM present; `spring-ai-starter-mcp-server-webmvc` coordinates verified
- `fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` — `replayToState()` confirmed implemented
- `fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` — sole Cypher execution surface; `model_id` injection pattern confirmed
- `fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java` — `loadFor()`, `listNodeTypes()` APIs confirmed
- `fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java` — JWT auth chain confirmed reusable
- [Spring AI 1.0.x MCP Server Boot Starter docs](https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-server-boot-starter-docs.html) — SSE endpoint config properties, McpSyncServer bean
- [MCP Specification 2024-11-05 tools page](https://modelcontextprotocol.io/specification/2024-11-05/server/tools) — tool schema format, `notifications/tools/list_changed`, error handling
- Maven Central verification: `spring-ai-starter-mcp-server-webmvc:1.0.5` FOUND; `io.modelcontextprotocol.sdk:mcp:0.9.0` FOUND

### Secondary (MEDIUM confidence)

- [spring.io blog: Dynamic Tool Updates with MCP (2025-05-04)](https://spring.io/blog/2025/05/04/spring-ai-dynamic-tool-updates-with-mcp/) — `McpSyncServer.addTool()`, `notifyToolsListChanged()` pattern confirmed
- [spring.io blog: MCP Server security](https://spring.io/blog/2025/09/30/spring-ai-mcp-server-security/) — clarified SSE deprecation is for `mcp-security` community module only
- [Apache AGE community articles (dev.to)](https://dev.to/matheusfarias03/exploring-shortest-path-algorithms-with-apache-age-oap) — `shortestPath()` syntax with WHERE filter

### Tertiary (LOW confidence — flagged in Assumptions Log)

- AGE 1.6.0 `ALL(n IN nodes(path) WHERE ...)` mid-path tenant filter behavior — community examples, not official AGE 1.6.0 docs
- `McpSyncServer.addTool()` exact method signature for Spring AI 1.0.5 — inferred from SDK API docs; exact overloads not confirmed from 1.0.5 source

---

## Project Constraints (from CLAUDE.md)

| Directive | Impact on Phase 3 |
|-----------|------------------|
| Java 21 + Spring Boot 3.5.x | MCP code must compile at `--release 21` |
| PostgreSQL 16 + Apache AGE 1.6.0 | All Cypher queries (find_path, traverse, get_entity depth) target AGE 1.6.0 API |
| Spring AI 1.0.5 (not 2.0-Mx, not 1.1.x) | Use `@Tool` annotation (not `@McpTool` which is 1.1.x); `McpSyncServer` available |
| Maven multi-module; no new modules | MCP code in `fabric-projections` only |
| Flyway plain-SQL (not Liquibase) | V22+ migrations for `mcp_audit_log` and `mcp_agent_quotas` |
| Admin endpoints under `/admin/*` | `/admin/mcp/audit` and `/admin/mcp/quotas` |
| `TenantContext` as explicit parameter | All tool implementations pass `TenantContext` from JWT, never via ThreadLocal |
| ArchUnit tests in `fabric-app` | Extend `ConnectorArchitectureTest.java` or add new `McpArchitectureTest.java` |
| Spotless (Palantir format) + license headers | All new `.java` files need Apache 2.0 header |
| `dependencyConvergence` + `requireUpperBoundDeps` enforcer | Adding `spring-ai-starter-mcp-server-webmvc` must not introduce new convergence violations |

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — BOM present, Maven Central verified
- Architecture patterns: HIGH for patterns derived from existing codebase; MEDIUM for Spring AI MCP runtime API
- AGE path queries: MEDIUM — shortestPath syntax confirmed from community docs; tenant filter behavior ASSUMED
- Pitfalls: HIGH — derived from existing Tessera patterns (tenant isolation, wrapper design)

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (Spring AI 1.0.x stable; AGE 1.6.0 stable)
