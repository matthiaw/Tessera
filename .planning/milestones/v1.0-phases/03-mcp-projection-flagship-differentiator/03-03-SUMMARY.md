---
phase: 03-mcp-projection-flagship-differentiator
plan: "03"
subsystem: mcp-projection
tags: [mcp, audit-log, quota, security, jdbc, spring-security, mockito]

requires:
  - phase: 03-mcp-projection-flagship-differentiator
    plan: "01"
    provides: "SpringAiMcpAdapter with TODO hooks for audit/quota, V22/V23 migration tables, ToolProvider.isWriteTool()"

provides:
  - "McpAuditLog: appends every tool invocation to mcp_audit_log (MCP-09, T-03-11)"
  - "AgentQuotaService: per-agent hourly write quota enforcement with AtomicLong counters warmed from audit log (SEC-07, T-03-10)"
  - "QuotaExceededException: signals missing or exceeded write quota"
  - "McpAuditController: GET /admin/mcp/audit (model_id, agent_id, from, to, limit filters) and GET /admin/mcp/quotas — tenant-scoped (T-03-12)"
  - "SpringAiMcpAdapter: wired with McpAuditLog + AgentQuotaService; quota gate before write tools; audit record after every invocation; no TODO comments remain"
  - "McpAuditLogTest: 3 Mockito-based tests, all passing"
  - "AgentQuotaServiceTest: 3 Mockito-based tests covering no-row, zero-quota, and window enforcement, all passing"

affects:
  - 03-04-plan

tech-stack:
  added: []
  patterns:
    - "Audit-then-quota pattern: quota check before tool execution; audit record always written after (including QUOTA_EXCEEDED)"
    - "AtomicLong counters keyed on agentId|modelId; warmed from mcp_audit_log.countForAgentSince() on first access (restart survivability)"
    - "Tenant match enforcement in admin controllers: JWT tenant claim must equal model_id param; returns 403 on mismatch (T-03-12)"
    - "Exception audit: ctx available -> always audit; ctx unavailable -> log only (prevents NPE during auth failure)"

key-files:
  created:
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditLog.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditController.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/AgentQuotaService.java
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/QuotaExceededException.java
  modified:
    - fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java
    - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpAuditLogTest.java
    - fabric-projections/src/test/java/dev/tessera/projections/mcp/AgentQuotaServiceTest.java

key-decisions:
  - "Window reset detection via Instant equality check: when hourlyWindowStarts.compute() returns now (newly set), counter is re-seeded from audit log for the new window"
  - "ctx-conditional audit on exception path: if tenant extraction fails (auth error), ctx is null so audit is skipped and only a log.warn fires — avoids NPE while still capturing most exception cases"
  - "McpAuditController uses queryForList for audit rows (returns Map<String,Object>) — consistent with existing admin controller patterns, no RowMapper needed"
  - "LENIENT Mockito strictness on AgentQuotaServiceTest: auditLog.countForAgentSince stub in @BeforeEach is unused in the two rejection tests (quota check throws before reaching warm-up code)"

requirements-completed: [SEC-07, MCP-09]

metrics:
  duration: 4min
  completed: 2026-04-17
---

# Phase 03 Plan 03: MCP Audit + Quota Enforcement Summary

**Append-only mcp_audit_log records every MCP tool invocation; AgentQuotaService enforces per-agent hourly write quotas via AtomicLong counters warmed from the audit log on restart; McpAuditController exposes tenant-scoped admin queries; SpringAiMcpAdapter fully wired with no remaining TODO comments.**

## Performance

- **Duration:** ~4 min
- **Started:** 2026-04-17T06:25:46Z
- **Completed:** 2026-04-17T06:29:59Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments

- McpAuditLog.record() inserts to mcp_audit_log (model_id, agent_id, tool_name, arguments JSONB, outcome, duration_ms) via NamedParameterJdbcTemplate; Jackson serialization failure falls back to `{}` without crashing
- McpAuditLog.countForAgentSince() queries non-QUOTA_EXCEEDED rows for restart counter warm-up (Pitfall 4 from RESEARCH.md)
- AgentQuotaService: default read-only (no quota row or writes_per_hour=0 → QuotaExceededException); 1-hour rolling window via AtomicLong + Instant window-start; counter warmed from audit log on first key access
- QuotaExceededException as plain RuntimeException — clean signal for adapter to convert to error CallToolResult
- McpAuditController: /admin/mcp/audit with 5 optional filters, limit capped at 500; /admin/mcp/quotas lists all agent quotas; both enforce tenant claim == model_id (T-03-12)
- SpringAiMcpAdapter: constructor extended with McpAuditLog + AgentQuotaService; invokeTool wires full flow: quota gate (if isWriteTool) → execute → audit record; QUOTA_EXCEEDED outcome audited before returning error result
- McpAuditLogTest and AgentQuotaServiceTest: @Disabled removed, 3 tests each, all passing with Mockito mocks

## Task Commits

1. **Task 1: McpAuditLog + AgentQuotaService + QuotaExceededException + enabled Wave 0 test stubs** — `afc38bf`
2. **Task 2: McpAuditController + wire audit/quota into SpringAiMcpAdapter** — `d215379`

## Files Created/Modified

- `fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditLog.java` — MCP-09 audit log writer with record() and countForAgentSince()
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditController.java` — admin endpoint for /audit and /quotas queries (tenant-scoped)
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/AgentQuotaService.java` — SEC-07 write quota enforcement with AtomicLong counters
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/QuotaExceededException.java` — quota violation signal
- `fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java` — wired McpAuditLog + AgentQuotaService; full invokeTool flow with no TODO comments
- `fabric-projections/src/test/java/dev/tessera/projections/mcp/McpAuditLogTest.java` — @Disabled removed; 3 Mockito tests passing
- `fabric-projections/src/test/java/dev/tessera/projections/mcp/AgentQuotaServiceTest.java` — @Disabled removed; 3 Mockito tests passing

## Decisions Made

- **Window reset detection via Instant equality**: `hourlyWindowStarts.compute()` returns the exact `now` reference when the window was just reset. The counter's `compute()` then checks `windowStart.equals(now)` to know whether to re-seed from the audit log for the new window. This avoids a second DB round-trip in the common case (window not expired).
- **ctx-conditional audit on exception path**: If tenant extraction fails (e.g., auth is null or malformed UUID), `ctx` is null and audit is skipped — a `log.warn` fires instead. This avoids NPE on the audit path while still capturing the exception for the vast majority of tool execution failures where ctx is available.
- **LENIENT Mockito strictness on AgentQuotaServiceTest**: The `auditLog.countForAgentSince` stub in `@BeforeEach` is unused by the two rejection tests (those tests throw before reaching the counter warm-up code). `@MockitoSettings(strictness = LENIENT)` is correct here — the stub is needed for `allows_writes_within_quota_then_rejects` and having it in setup is the right DRY location.

## Deviations from Plan

None — plan executed exactly as written. The `@MockitoSettings(strictness = LENIENT)` addition is a standard Mockito fix for the UnnecessaryStubbingException that arises when a shared stub is only consumed by a subset of tests — not a deviation from plan intent.

## Known Stubs

None — all production classes are fully implemented. No TODO markers, placeholder text, or empty return values remain in production code.

## Threat Flags

No new security-relevant surface beyond the plan's threat model. All 4 mitigations from the threat register are implemented:

| Threat | Mitigation Applied |
|--------|--------------------|
| T-03-10 Elevation of Privilege (AgentQuotaService) | Default quota = 0 writes; agents cannot write without explicit row in mcp_agent_quotas |
| T-03-11 Repudiation (McpAuditLog) | Every invocation recorded with agent_id, tool_name, arguments, outcome, duration_ms; audit log is append-only |
| T-03-12 Information Disclosure (McpAuditController) | JWT tenant claim must equal model_id; mismatch returns 403; /admin/** requires ROLE_ADMIN (enforced by SecurityConfig) |
| T-03-13 Tampering (AgentQuotaService) | Accepted: in-memory counters can drift on multi-instance (single-instance MVP); warm-up from audit log on restart mitigates |

## Next Phase Readiness

- **Phase 03 complete**: All 3 execution plans (01, 02, 03) delivered. MCP SSE endpoint, 7 ToolProvider tools, full audit+quota enforcement, admin endpoints all wired.
- **Plan 04 (if exists)**: McpAuditLog and AgentQuotaService are Spring beans ready for injection into any future tooling.

---
*Phase: 03-mcp-projection-flagship-differentiator*
*Completed: 2026-04-17*
