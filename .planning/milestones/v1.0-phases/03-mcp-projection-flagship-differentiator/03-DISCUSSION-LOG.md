# Phase 3: MCP Projection (Flagship Differentiator) - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-17
**Phase:** 03-mcp-projection-flagship-differentiator
**Areas discussed:** MCP transport & Spring AI isolation, Tool surface & dynamic registration, Agent identity write quotas & audit, Prompt injection hardening

---

## MCP Transport & Spring AI Isolation

### Transport Protocol

| Option | Description | Selected |
|--------|-------------|----------|
| SSE (HTTP) only | Spring AI MCP Server WebMVC starter, reuses Tomcat + SecurityConfig | ✓ |
| Stdio only | Separate process, can't share security stack | |
| Both SSE + stdio | Two code paths, more maintenance | |

**User's choice:** SSE (HTTP) only
**Notes:** Simplest path — reuses existing Tomcat server and security infrastructure.

### Spring AI Coupling

| Option | Description | Selected |
|--------|-------------|----------|
| Interface-isolated | Tessera-owned ToolProvider interface, Spring AI adapter behind it | ✓ |
| Direct Spring AI dependency | Use Spring AI beans directly throughout | |

**User's choice:** Interface-isolated
**Notes:** Matches MCP-01 requirement. Swappable to raw SDK if Spring AI breaks.

### Module Placement

| Option | Description | Selected |
|--------|-------------|----------|
| Inside fabric-projections | Package dev.tessera.projections.mcp, shares security infra | ✓ |
| New fabric-mcp module | Separate module, cleaner isolation but adds 6th Maven module | |

**User's choice:** Inside fabric-projections
**Notes:** MCP is a projection — same module makes architectural sense.

---

## Tool Surface & Dynamic Registration

### Traverse Query Input

| Option | Description | Selected |
|--------|-------------|----------|
| Cypher only (tenant-scoped) | Agent sends Cypher, server injects model_id filter | ✓ |
| Cypher with NL fallback | Accept both, LLM translates NL to Cypher if needed | |
| Natural language only | All queries translated via LLM | |

**User's choice:** Cypher only (tenant-scoped)
**Notes:** Predictable, testable, no LLM-in-the-loop overhead. NL deferred.

### Dynamic Registration Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Restart-on-schema-change | Tool list built at startup, restart on change | ✓ |
| Runtime hot-reload | Attempt runtime registration via Spring AI internals | |
| You decide | Let researcher investigate | |

**User's choice:** Restart-on-schema-change
**Notes:** Safe baseline. Researcher should confirm Spring AI 1.0.5 capabilities.

### Tool Shape

| Option | Description | Selected |
|--------|-------------|----------|
| Generic tools (7 total) | Type slug as parameter, constant tool count | ✓ |
| Type-specific tools | One tool per type per operation, grows with schema | |
| Hybrid | Mix of schema-aware + generic operational | |

**User's choice:** Generic tools (7 total)
**Notes:** Matches MCP-02..07 wording. Manageable for agents with limited tool budgets.

### Relation Depth

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, depth parameter | depth=1 default, max 3 | ✓ |
| Fixed depth = 1 | Always direct neighbors only | |
| You decide | Let planner pick | |

**User's choice:** Yes, depth parameter (default 1, max 3)
**Notes:** Satisfies MCP-04 "configurable relation depth".

---

## Agent Identity, Write Quotas & Audit

### Agent Identity

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse JWT with agent role | Same JWT mechanism, ROLE_AGENT claim | ✓ |
| Separate API key per agent | Unique keys in Vault, new auth path | |
| MCP protocol-level auth | Tie to MCP transport auth negotiation | |

**User's choice:** Reuse JWT with ROLE_AGENT
**Notes:** Reuses existing SecurityConfig + Vault HMAC. No new auth system.

### Write Quota Mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Config table + in-memory counter | mcp_agent_quotas table + AtomicLong, default 0 | ✓ |
| JWT claim-based | Embed quota in JWT payload | |
| You decide | Let planner pick | |

**User's choice:** Config table + in-memory counter
**Notes:** Default: 0 writes (read-only). Operators explicitly grant capacity.

### Audit Log Location

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated mcp_audit_log table | Separate from graph_events, queryable per tenant | ✓ |
| Reuse graph_events table | Add as special event_type | |
| Application log only | Structured JSON logging, no DB table | |

**User's choice:** Dedicated mcp_audit_log table
**Notes:** Clean separation — tool invocations are operational metadata, not graph state.

---

## Prompt Injection Hardening

### Data Wrapper Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Response interceptor | Single ToolResponseWrapper at ToolProvider interface level | ✓ |
| Per-tool wrapping | Each tool wraps its own response | |
| You decide | Let planner pick | |

**User's choice:** Response interceptor
**Notes:** Defense-in-depth — even if a tool omits wrapping, the interceptor catches it.

### Prompt Injection Test Suite

| Option | Description | Selected |
|--------|-------------|----------|
| Seeded adversarial data | Deterministic CI tests with embedded instructions in graph data | ✓ |
| LLM-in-the-loop red team | Actual LLM attacks, non-deterministic | |
| Both | Seeded for CI + optional LLM red team nightly | |

**User's choice:** Seeded adversarial data
**Notes:** Deterministic, CI-runnable, no LLM needed in test loop.

### Schema Mutation Prevention

| Option | Description | Selected |
|--------|-------------|----------|
| Allowlist + ArchUnit | 7 read tools in explicit allowlist + compile-time test | ✓ |
| Role-based at runtime | Tools exist but gated behind ROLE_SCHEMA_ADMIN | |
| You decide | Let planner pick | |

**User's choice:** Allowlist + ArchUnit
**Notes:** Belt and suspenders: runtime allowlist + compile-time architectural test.

---

## Claude's Discretion

- Exact Spring AI MCP starter configuration
- ToolProvider interface shape and return types
- Cursor pagination reuse for MCP query_entities
- find_path algorithm (AGE built-in vs custom)
- get_state_at implementation (Java replay vs Cypher temporal)
- Flyway migration numbering (continue from Phase 2.5)
- MCP admin endpoint query parameters
- In-memory quota counter reset strategy

## Deferred Ideas

- Natural-language-to-Cypher translation for traverse()
- Type-specific MCP tools
- Runtime hot-reload of tools on schema change
- Stdio MCP transport
- MCP write tools (dedicated mutation tools)
- Agent-to-agent communication via MCP
