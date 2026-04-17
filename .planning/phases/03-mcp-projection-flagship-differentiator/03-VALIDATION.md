---
phase: 3
slug: mcp-projection-flagship-differentiator
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `fabric-projections/src/test/resources/application-test.yml` |
| **Quick run command** | `mvn test -pl fabric-projections -Dtest=Mcp*Test` |
| **Full suite command** | `mvn verify -pl fabric-projections` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl fabric-projections -Dtest=Mcp*Test`
- **After every plan wave:** Run `mvn verify -pl fabric-projections`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | MCP-01 | — | N/A | unit | `mvn test -pl fabric-projections -Dtest=McpToolRegistrationTest` | ❌ W0 | ⬜ pending |
| 03-01-02 | 01 | 1 | MCP-02 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpToolSurfaceIT` | ❌ W0 | ⬜ pending |
| 03-02-01 | 02 | 1 | MCP-03, MCP-04, MCP-05 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpQueryToolsIT` | ❌ W0 | ⬜ pending |
| 03-02-02 | 02 | 1 | MCP-06 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpTraverseToolsIT` | ❌ W0 | ⬜ pending |
| 03-02-03 | 02 | 1 | MCP-07 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpTemporalToolIT` | ❌ W0 | ⬜ pending |
| 03-03-01 | 03 | 2 | SEC-07 | T-03-01 | Read-only by default; write rejected without quota | integration | `mvn test -pl fabric-projections -Dtest=McpReadOnlyEnforcementIT` | ❌ W0 | ⬜ pending |
| 03-03-02 | 03 | 2 | SEC-08 | T-03-02 | Prompt injection blocked by data wrapper | integration | `mvn test -pl fabric-projections -Dtest=McpPromptInjectionIT` | ❌ W0 | ⬜ pending |
| 03-04-01 | 04 | 2 | MCP-08 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpDynamicRegistrationIT` | ❌ W0 | ⬜ pending |
| 03-05-01 | 05 | 2 | MCP-09 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpAuditLogIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `McpToolRegistrationTest.java` — stubs for MCP-01 tool registration
- [ ] `McpToolSurfaceIT.java` — integration test for MCP-02 tool surface
- [ ] `McpQueryToolsIT.java` — integration test for query tools (MCP-03..05)
- [ ] `McpTraverseToolsIT.java` — integration test for traverse/find_path (MCP-06)
- [ ] `McpTemporalToolIT.java` — integration test for get_state_at (MCP-07)
- [ ] `McpDynamicRegistrationIT.java` — integration test for dynamic registration (MCP-08)
- [ ] `McpAuditLogIT.java` — integration test for audit logging (MCP-09)
- [ ] `McpReadOnlyEnforcementIT.java` — security test for read-only enforcement (SEC-07)
- [ ] `McpPromptInjectionIT.java` — security test for prompt injection defense (SEC-08)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| MCP client end-to-end connection | MCP-01 | Requires real MCP client (Claude Desktop / agent SDK) | 1. Start Tessera with MCP enabled 2. Connect Claude Desktop 3. Verify tool list appears 4. Invoke list_entity_types |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
