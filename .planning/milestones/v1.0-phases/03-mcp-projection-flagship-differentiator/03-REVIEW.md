---
phase: 03-mcp-projection-flagship-differentiator
reviewed: 2026-04-17T00:00:00Z
depth: standard
files_reviewed: 32
files_reviewed_list:
  - fabric-app/src/main/resources/db/migration/V22__mcp_audit_log.sql
  - fabric-app/src/main/resources/db/migration/V23__mcp_agent_quotas.sql
  - fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphRepositoryImpl.java
  - fabric-core/src/test/java/dev/tessera/core/graph/FindShortestPathSpikeIT.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolProvider.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/api/ToolResponse.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditController.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditLog.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/interceptor/ToolResponseWrapper.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/McpProjectionConfig.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/AgentQuotaService.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/QuotaExceededException.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/DescribeTypeTool.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/FindPathTool.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetEntityTool.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetStateAtTool.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ListEntityTypesTool.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/QueryEntitiesTool.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/ToolNodeSerializer.java
  - fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/TraverseTool.java
  - fabric-projections/src/test/java/dev/tessera/projections/arch/McpMutationAllowlistTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/AgentQuotaServiceTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpAuditLogIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpAuditLogTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpCrossTenantIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpIsolationArchTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpPromptInjectionIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpQuotaEnforcementIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/McpSchemaAllowlistArchTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/mcp/ToolResponseWrapperTest.java
findings:
  critical: 3
  warning: 6
  info: 4
  total: 13
status: issues_found
---

# Phase 03: Code Review Report

**Reviewed:** 2026-04-17T00:00:00Z
**Depth:** standard
**Files Reviewed:** 32
**Status:** issues_found

## Summary

This phase delivers the MCP projection layer: seven read-only tools, a per-invocation audit log, per-agent write quotas, a `<data>` wrapper for prompt injection mitigation, and an admin REST endpoint. The architecture is well-thought-out — the Spring AI isolation boundary (`SpringAiMcpAdapter` as the single SDK importer) is clean, ArchUnit guards are comprehensive, and the multi-layer quota enforcement test is solid.

Three critical issues require attention before this code ships:

1. **Authentication failure does not audit**: when `auth.getName()` throws a `NullPointerException` or returns an unparseable value the exception propagates to the top-level catch block where `ctx == null`, so no audit row is written. Combined with the fact that the exception message (which may contain raw JWT data) is returned to the caller, this creates an information-disclosure vector.
2. **`ToolResponseWrapper` does not escape content**: the `<data>` wrap provides only positional containment — it does not escape `</data>` sequences in the payload. An adversarial node whose property literally contains `</data>` can terminate the outer wrapper early. The existing test (`wrapper_isolates_injected_closing_tags_in_data`) documents this behaviour as acceptable, but it is actually wrong: the structural invariant "everything between the first `<data>` and the last `</data>` is trusted data" does not hold once adversarial content contains `</data>`.
3. **`AgentQuotaService` window-reset logic races**: the `hourlyWindowStarts.compute` and `hourlyCounters.compute` run as two separate `ConcurrentHashMap.compute` calls. Between them, a concurrent thread can observe `windowStart == now` in the second compute (meaning "reset"), re-seed the counter from the audit log, and increment past the limit before the first thread's increment completes. On single-instance MVP this is low probability, but the documented accepted risk (T-03-13) only covers count drift across instances, not within-instance races.

Six warnings and four informational items follow.

---

## Critical Issues

### CR-01: Unauthenticated / malformed JWT causes silent audit gap and information disclosure

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/adapter/SpringAiMcpAdapter.java:119`

**Issue:** `auth.getName()` is called without a null-check on `auth`. If the security context has no authentication (unauthenticated request that somehow reached this code path), `auth` is `null` and the call throws `NullPointerException`. The catch block at line 149 fires with `ctx == null`, so `mcpAuditLog.record()` is skipped entirely — the attempted invocation leaves no trace. Additionally, `ex.getMessage()` is returned verbatim to the MCP caller at line 156, and for a `NullPointerException` or `IllegalArgumentException` from `UUID.fromString`, this message may contain fragments of the raw JWT `name` claim.

**Fix:**
```java
// At the top of invokeTool(), before accessing auth:
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth == null || !auth.isAuthenticated()) {
    // Audit with a synthetic "unauthenticated" context is not possible without model_id.
    // Return a generic error — do NOT expose auth internals.
    log.warn("MCP tool {} invoked without authenticated principal", tool.toolName());
    return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(ToolResponseWrapper.wrap("Authentication required"))), true);
}
UUID modelId;
try {
    modelId = UUID.fromString(auth.getName());
} catch (IllegalArgumentException e) {
    log.warn("MCP tool {} invoked with non-UUID principal: {}", tool.toolName(), auth.getName());
    return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(ToolResponseWrapper.wrap("Authentication required"))), true);
}
```

Also replace line 156 generic message to avoid leaking `ex.getMessage()` for auth-related failures:
```java
String wrapped = ToolResponseWrapper.wrap("Tool execution failed");  // no ex.getMessage()
```

---

### CR-02: `ToolResponseWrapper` does not escape `</data>` — prompt injection escape is possible

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/interceptor/ToolResponseWrapper.java:35-40`

**Issue:** `wrap()` concatenates raw content between `<data>` and `</data>` without escaping. If `rawContent` contains the literal string `</data>`, the wrapper is structurally broken: the result `<data>safe</data><injected/><data>rest</data>` has the closing tag in the wrong place. An LLM agent that uses the first `</data>` as the boundary will treat `<injected/>` as outside the data context. The test at `McpPromptInjectionIT.java:212` acknowledges this but incorrectly characterises it as "neutralized" — it is not, because the outer boundary ends at the first `</data>` occurrence, not the last. Any sophisticated parser or parser-exploiting prompt will break out.

**Fix:**
```java
public static String wrap(String rawContent) {
    if (rawContent == null) {
        return OPEN + CLOSE;
    }
    // Escape any </data> sequences in the payload so the closing tag cannot be faked.
    String escaped = rawContent.replace("</data>", "<\\/data>");
    return OPEN + escaped + CLOSE;
}
```

Update `ToolResponseWrapperTest` to assert that `</data>` inside content is escaped, and update `McpPromptInjectionIT.wrapper_isolates_injected_closing_tags_in_data` to match.

---

### CR-03: `AgentQuotaService.checkWriteQuota` has a TOCTOU race between window-reset and counter-reset

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/AgentQuotaService.java:90-119`

**Issue:** The method performs two independent `ConcurrentHashMap.compute` calls (lines 90-95 and 98-113). Thread A may see `windowStart == now` (window was just reset) in the `hourlyWindowStarts.compute`, but between that call returning and the `hourlyCounters.compute` being entered, Thread B can reset and re-seed the counter. Thread A's `compute` then overwrites that count with a fresh audit-log seed. More concretely:

- Both threads enter `hourlyWindowStarts.compute` concurrently; one resets to `now`, the other returns the new value. So far fine.
- Both threads then enter `hourlyCounters.compute`; both see `windowStart.equals(now)` (since `now` is the same reference in both threads' stacks due to the lambda capture), both call `auditLog.countForAgentSince` and `existing.set(historical)`, then both `incrementAndGet`. The counter ends up at `historical + 2` instead of `historical + 1`. Under normal load this is harmless but under a burst it permits one extra write past the quota boundary.

The root fix is to combine both operations in a single synchronized block or to use a single `ConcurrentHashMap<String, WindowState>` record that holds both the window start and the counter atomically.

**Fix:**
```java
// Replace the two separate ConcurrentHashMap entries with a single holder:
private record WindowState(Instant windowStart, AtomicLong counter) {}
private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();

public void checkWriteQuota(TenantContext ctx, String agentId) throws QuotaExceededException {
    String key = agentId + "|" + ctx.modelId();
    int limit = loadQuotaLimit(ctx, agentId);
    if (limit <= 0) {
        throw new QuotaExceededException("Agent '" + agentId + "' has no write quota for model " + ctx.modelId());
    }
    Instant now = Instant.now();
    WindowState ws = windows.compute(key, (k, existing) -> {
        if (existing == null || now.isAfter(existing.windowStart().plus(1, ChronoUnit.HOURS))) {
            long historical = auditLog.countForAgentSince(ctx, agentId, now);
            return new WindowState(now, new AtomicLong(historical));
        }
        return existing;
    });
    long count = ws.counter().incrementAndGet();
    if (count > limit) {
        throw new QuotaExceededException(
                "Write quota exceeded: " + count + "/" + limit + " writes/hour for agent '" + agentId + "'");
    }
}
```

---

## Warnings

### WR-01: `GetEntityTool` builds Cypher with string interpolation — injection risk if `executeTenantCypher` filter is incomplete

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/GetEntityTool.java:117-119`

**Issue:** The neighbor expansion Cypher is built with `String.format` interpolating `entityId` (a `UUID`) and `depth` (an integer clamped to 0-3). Both values are controlled by Tessera code, not raw user input, so this is not directly exploitable — but it creates a maintenance trap: if a future refactor passes the raw `idStr` string instead of the already-parsed `entityId`, or passes an unclamped depth, the query becomes injectable. The UUID is already validated, but the pattern of building Cypher strings with format should be consistently avoided in favor of parameterized queries.

**Fix:** Pass the UUID and depth as named Cypher parameters through `GraphSession`'s parameterized interface rather than embedding them in the query string directly.

---

### WR-02: `McpAuditController.getAuditLog` appends user-supplied ISO-8601 strings directly into SQL via named params — but validates nothing about format

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditController.java:93-99`

**Issue:** The `from` and `to` query parameters are accepted as raw strings and appended as `:from::timestamptz` and `:to::timestamptz` in the SQL. While `NamedParameterJdbcTemplate` prevents SQL injection (the values are bound), the Postgres `::timestamptz` cast will throw an exception for any non-ISO-8601 string, surfacing a Postgres error message to the caller that may leak schema or query structure. There is no input validation before the SQL execution.

**Fix:**
```java
if (from != null && !from.isBlank()) {
    try {
        Instant.parse(from); // validate format
    } catch (DateTimeParseException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid 'from' timestamp format"));
    }
    sql.append(" AND created_at >= :from::timestamptz");
    p.addValue("from", from);
}
// Same pattern for `to`
```

---

### WR-03: `AgentQuotaService` calls `auditLog.countForAgentSince` inside a `ConcurrentHashMap.compute` lambda

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/quota/AgentQuotaService.java:99-113`

**Issue:** `ConcurrentHashMap.compute` holds an internal lock on the bucket for the duration of the lambda. Calling a JDBC-backed method (`auditLog.countForAgentSince`) from inside that lambda means a database round-trip happens while the map segment is locked. Under connection pool contention, this can block all other threads trying to compute on the same hash bucket for the duration of the query. The JDK docs explicitly warn against calling blocking operations inside `compute`.

**Fix:** Perform the audit log query outside the `compute` call and pass the result in via a local variable. Since this is a warm-up path (first access only), it can safely run before the `compute` with a double-check pattern:

```java
// Outside compute: speculatively fetch the audit count
long warmupCount = 0;
if (!hourlyCounters.containsKey(key)) {
    warmupCount = auditLog.countForAgentSince(ctx, agentId, now);
}
final long capturedWarmup = warmupCount;
AtomicLong counter = hourlyCounters.compute(key, (k, existing) -> {
    if (existing == null) {
        return new AtomicLong(capturedWarmup);
    }
    if (windowStart.equals(now)) {
        existing.set(capturedWarmup); // reset with pre-fetched value
    }
    return existing;
});
```

Note: this also eliminates the CR-03 risk if combined with the single-WindowState fix.

---

### WR-04: `McpCrossTenantIT.find_path_between_cross_tenant_nodes_returns_empty` uses wrong parameter keys

**File:** `fabric-projections/src/test/java/dev/tessera/projections/mcp/McpCrossTenantIT.java:233-234`

**Issue:** `FindPathTool.inputSchemaJson()` declares parameters named `from` and `to`. The test passes `from_id` and `to_id` (line 234), which will not match. `arguments.get("from")` in `FindPathTool.execute()` will return `null`, causing an immediate `ToolResponse.error("from parameter is required")`. The test then hits the `if (response.success())` branch (false) and checks `doesNotContain("TENANT_B_MARKER")` on the error message — which will always pass vacuously. The cross-tenant isolation for `find_path` is therefore untested.

**Fix:**
```java
// Change line 234 in McpCrossTenantIT.java:
ToolResponse response = tool.execute(
        ctxA, AGENT_ID, Map.of("from", fakeFromId.toString(), "to", tenantBNodeId.toString()));
```

---

### WR-05: `McpAuditLogIT.audit_log_records_successful_invocation` test does not prove end-to-end adapter behaviour

**File:** `fabric-projections/src/test/java/dev/tessera/projections/mcp/McpAuditLogIT.java:107-124`

**Issue:** The test manually calls `tool.execute()` then manually calls `auditLog.record()` (line 113). This does not exercise `SpringAiMcpAdapter.invokeTool()` — the code path that actually calls `auditLog.record()` in production. If the adapter were removed or its recording removed, this test would still pass. The test title ("proves every MCP tool invocation produces a row") is misleading.

**Fix:** Either drive the invocation via the full adapter (`invokeToolViaDispatch` as done in `McpQuotaEnforcementIT`) or rename and scope the test to "proves audit log service writes correct rows given explicit record() calls", and add a separate test that drives the adapter end-to-end.

---

### WR-06: `QueryEntitiesTool` applies in-memory filter AFTER fetching `limit+1` from the database

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/tools/QueryEntitiesTool.java:108-128`

**Issue:** The tool fetches `limit+1` nodes from the database, then applies the property filter in-memory (lines 114-124), then checks `filtered.size() > limit` to determine `hasMore`. If the filter eliminates rows, a page with fewer than `limit` results may exist even though more data is available, and `hasMore` will be `false` even when it should be `true`. This means the `next_cursor` is never emitted for filtered queries that have more results beyond the current `limit+1` window. Agents using filter + cursor pagination will silently see truncated results.

**Fix:** Either push filters into the Cypher query (preferred — avoids over-fetching) or document the limitation clearly in the tool description. For an in-memory filter fix, fetch enough rows to guarantee coverage — but this requires fetching all matching rows up to the cursor, which is unbounded without a push-down.

---

## Info

### IN-01: `ToolResponseWrapper.wrap` does not handle empty string consistently with null

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/interceptor/ToolResponseWrapper.java:35-40`

**Issue:** `wrap(null)` returns `<data></data>` (guarded path), but `wrap("")` falls through to `OPEN + "" + CLOSE` which produces the same result via a different code path. The test covers both, so there is no bug, but the comment "A null input yields..." suggests null is the only edge case. A future reader may not realize empty string is effectively equivalent. Minor clarity issue only.

**Fix:** Add a comment or collapse the two cases:
```java
if (rawContent == null || rawContent.isEmpty()) {
    return OPEN + CLOSE;
}
```

---

### IN-02: `McpAuditLog` uses a static `ObjectMapper` instance

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditLog.java:57`

**Issue:** `MAPPER` is declared `private static final`. In the Spring context, `McpAuditLog` is a `@Service` singleton, and `ObjectMapper` is thread-safe, so this is not incorrect. However, the convention in the rest of the codebase (see `DescribeTypeTool`, `FindPathTool`, etc.) is to inject `ObjectMapper` from the Spring context, which allows configuration (e.g., custom serializers, `@JsonComponent` registrations) to apply consistently. The static instance bypasses any Spring-managed `ObjectMapper` customizations.

**Fix:** Inject `ObjectMapper` via constructor:
```java
public McpAuditLog(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
}
```

---

### IN-03: `FindShortestPathSpikeIT` uses hardcoded string UUIDs that could collide across test runs in a shared container

**File:** `fabric-core/src/test/java/dev/tessera/core/graph/FindShortestPathSpikeIT.java:76-81`

**Issue:** `UUID_A` through `UUID_D` are compile-time constants. With `@Container` using `withReuse(true)` (as in the IT tests), if the container is reused across test suite runs the `CREATE` statements in `seedGraph()` will conflict with previously created nodes, causing duplicate vertex errors or stale data. The spike uses hardcoded UUIDs rather than generated ones. Note: this file does not use `withReuse(true)` — it uses a standard `@Container` — so this is lower risk, but it will fail if the test class is run twice without a container restart.

**Fix:** Generate the UUIDs in `seedGraph()` using `UUID.randomUUID()` and store them as instance fields, or use a unique label per test run (e.g., append a random suffix to `"SpikeNode"`).

---

### IN-04: `McpAuditLog.COUNT_SINCE` query excludes `QUOTA_EXCEEDED` outcomes but the quota service's warm-up should probably also exclude `EXCEPTION` outcomes

**File:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditLog.java:51-55`

**Issue:** The `COUNT_SINCE` query (used to warm the quota counter on restart) filters out `QUOTA_EXCEEDED` but not `EXCEPTION` outcomes. An `EXCEPTION` outcome means the tool call failed before completing — counting it toward the quota is debatable. If an agent's tool invocations frequently fail with exceptions (e.g., due to database unavailability), the warm-up count will over-report actual write consumption, causing legitimate quota headroom to be lost after a restart.

**Fix:** Decide on the intended semantics and document them in the query. If only successful writes should count, change the filter to:
```sql
AND outcome = 'SUCCESS'
```
If all attempted writes (including errors) should count, the current filter (excluding only QUOTA_EXCEEDED) is correct but should include a comment explaining the intent.

---

_Reviewed: 2026-04-17T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
