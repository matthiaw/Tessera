---
gsd_artifact: patterns
phase: "03"
phase_name: "MCP Projection (Flagship Differentiator)"
created: 2026-04-17
---

# Phase 3 — Code Patterns

**Purpose:** Concrete codebase evidence for the planner. Every table entry and excerpt is drawn from actual files in the repo as of this session. The planner models new MCP files directly after these.

---

## 1. Files to Create / Modify

### New files — `fabric-projections/src/main/java/dev/tessera/projections/mcp/`

| File Path | Role | Data Flow | Closest Analog | Notes |
|-----------|------|-----------|----------------|-------|
| `mcp/api/ToolProvider.java` | Tessera-owned tool interface (Spring AI isolation boundary) | Receives `TenantContext` + `Map<String,Object>` args → returns `String` content | No direct analog — designed like `EntityDispatcher` is to `GenericEntityController`: the boundary layer | D-A2 |
| `mcp/api/ToolRequest.java` | Request carrier DTO (tenant + args) | Inbound from `SpringAiMcpAdapter` to `ToolProvider.execute()` | `GraphMutation` record in `fabric-core` (request DTO pattern) | May be a Java `record` |
| `mcp/api/ToolResponse.java` | Response carrier (raw content before wrapping) | `ToolProvider` → `ToolResponseWrapper` → `SpringAiMcpAdapter` | `NodeState` record (read-side DTO) | May be a Java `record` |
| `mcp/adapter/SpringAiMcpAdapter.java` | Bridges `ToolProvider` list → Spring AI `McpSyncServer`; calls `ToolResponseWrapper.wrap()` | Tool invocation dispatched through here; also listens for schema change events | `OpenApiSchemaCustomizer` (schema-driven dynamic registration at startup) | Only class that imports Spring AI |
| `mcp/tools/ListEntityTypesTool.java` | MCP-02: returns tenant's node type slugs/names/descriptions | `SecurityContextHolder` → `TenantContext` → `SchemaRegistry.listNodeTypes()` → JSON | `EntityDispatcher.list()` (schema lookup + tenant filter + delegate) | |
| `mcp/tools/DescribeTypeTool.java` | MCP-02: returns full property schema + edge types for a slug | `SchemaRegistry.loadFor(ctx, slug)` → `NodeTypeDescriptor` → JSON | `EntityDispatcher.loadOrThrow()` | |
| `mcp/tools/QueryEntitiesTool.java` | MCP-03: cursor-paginated entity query with property filters | `CursorCodec.decode()` → `GraphRepository.queryAllAfter()` → `CursorCodec.encode()` | `GenericEntityController.list()` + `EntityDispatcher.list()` | Reuses `CursorCodec` directly |
| `mcp/tools/GetEntityTool.java` | MCP-04: single entity + configurable relation depth (1–3) | `GraphRepository.queryById()` + neighbor Cypher via `GraphSession` | `GenericEntityController.getById()` + `EntityDispatcher.getById()` | Depth cap at 3, default 1 |
| `mcp/tools/TraverseTool.java` | MCP-05: raw tenant-scoped Cypher execution | Validates `model_id` injection → delegates to `GraphSession.queryAllNodes()` or raw JDBC path | `GraphSession.queryAllNodes()` (model_id-WHERE pattern) | Must not bypass GraphSession |
| `mcp/tools/FindPathTool.java` | MCP-06: shortest path between two node UUIDs | AGE `shortestPath()` Cypher via JDBC, tenant-scoped | `GraphSession` raw Cypher pattern (see `cypher()` calls in GraphSession) | Uses `GRAPH_NAME = "tessera_main"` constant |
| `mcp/tools/GetStateAtTool.java` | MCP-07: temporal state reconstruction | `EventLog.replayToState(ctx, nodeUuid, Instant)` → JSON | `EventLog.replayToState()` (already implemented, thin adapter only) | |
| `mcp/interceptor/ToolResponseWrapper.java` | SEC-08: wraps every tool response in `<data>…</data>` | Called by `SpringAiMcpAdapter` for ALL tools | No direct analog — static utility, similar to `CursorCodec` (pure stateless encoder) | |
| `mcp/quota/AgentQuotaService.java` | D-C2: in-memory `AtomicLong` counters + `mcp_agent_quotas` DB lookup | `McpAuditLog` invocation → quota check → allow/reject | `CircuitBreakerPort` / `WriteRateCircuitBreaker` pattern (rolling window + trip) | Default quota = 0 writes |
| `mcp/quota/QuotaExceededException.java` | Signals write quota violation | Thrown by `AgentQuotaService`; caught in `SpringAiMcpAdapter` | `CircuitBreakerTrippedException` | `RuntimeException` subclass |
| `mcp/audit/McpAuditLog.java` | D-C3: writes one row per tool invocation to `mcp_audit_log` | Called by `SpringAiMcpAdapter` after every tool call | `EventLog.append()` (NamedParameterJdbcTemplate INSERT pattern) | |
| `mcp/audit/McpAuditController.java` | D-C3: `GET /admin/mcp/audit` with tenant+agent+time filters | HTTP request → JWT tenant extraction → JDBC query → JSON response | `TokenIssueController` (`@RestController @RequestMapping("/admin/...")` pattern) | |
| `mcp/McpProjectionConfig.java` | Spring Boot `@Configuration` that wires `McpSyncServer` + adapter | Declares beans: `McpSyncServer`, registers tools via `SpringAiMcpAdapter` | `OpenApiSchemaCustomizer` (`@Configuration(proxyBeanMethods = false)` pattern) | Only file that `@Bean`-declares Spring AI types |

### Modified files

| File Path | Change | Reason |
|-----------|--------|--------|
| `fabric-projections/pom.xml` | Add `spring-ai-starter-mcp-server-webmvc` dependency | MCP transport |
| `fabric-projections/src/main/resources/application.yml` | Add `spring.ai.mcp.server.*` properties block | SSE endpoint, server name, tool-change-notification |
| `fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java` | Add `ROLE_AGENT` to `anyRequest().authenticated()` path; add `/mcp/sse` and `/mcp/message` to the permit/require rules | D-C1: agents use the same JWT chain |
| `fabric-app/src/main/resources/db/migration/V22__mcp_audit_log.sql` | New Flyway migration: `mcp_audit_log` table + indexes | D-C3 |
| `fabric-app/src/main/resources/db/migration/V23__mcp_agent_quotas.sql` | New Flyway migration: `mcp_agent_quotas` table | D-C2 |

### New test files

| File Path | Role | Closest Analog |
|-----------|------|----------------|
| `src/test/java/dev/tessera/projections/mcp/ToolResponseWrapperTest.java` | Unit: wrapper applied on every tool response | `CursorCodecTest` |
| `src/test/java/dev/tessera/projections/mcp/McpPromptInjectionIT.java` | Integration: adversarial-seed + `<data>` wrapper assertion (D-D2) | `CrossTenantLeakPropertyIT` (property test pattern) |
| `src/test/java/dev/tessera/projections/mcp/McpCrossTenantIT.java` | Integration: cross-tenant access via MCP returns no data | `CrossTenantLeakPropertyIT` |
| `src/test/java/dev/tessera/projections/mcp/McpAuditLogIT.java` | Integration: audit row written per invocation | `AuditHistoryIT` in fabric-core |
| `src/test/java/dev/tessera/projections/mcp/McpQuotaEnforcementIT.java` | Integration: write rejected when quota = 0 | `NodeLifecycleIT` (mutation path tests) |
| `src/test/java/dev/tessera/projections/arch/McpMutationAllowlistTest.java` | ArchUnit: no MCP tool imports `SchemaRegistry.create*` etc. | `ProjectionsModuleDependencyTest` |

---

## 2. Pattern Excerpts

### 2.1 Service layer with schema lookup + tenant filter — `EntityDispatcher`

Model: `mcp/tools/*.java` implement the same pattern: load schema, check access, delegate to graph.

```java
// fabric-projections/src/main/java/dev/tessera/projections/rest/EntityDispatcher.java
@Service
public class EntityDispatcher {
    private final SchemaRegistry schemaRegistry;
    private final GraphRepository graphRepository;
    private final GraphService graphService;

    public EntityDispatcher(SchemaRegistry schemaRegistry, GraphRepository graphRepository,
                            GraphService graphService) { ... }

    public List<NodeState> list(TenantContext ctx, String typeSlug, long afterSeq, int limit) {
        requireReadEnabled(ctx, typeSlug);
        return graphRepository.queryAllAfter(ctx, typeSlug, afterSeq, limit);
    }

    private NodeTypeDescriptor loadOrThrow(TenantContext ctx, String typeSlug) {
        return schemaRegistry
                .loadFor(ctx, typeSlug)
                .orElseThrow(
                        () -> new NotFoundException("Type '" + typeSlug + "' not found in model " + ctx.modelId()));
    }
}
```

**MCP tool classes follow this pattern exactly.** Inject `SchemaRegistry`, `GraphRepository`, `EventLog`. Validate tenant. Delegate. Return String content.

---

### 2.2 Cursor pagination — `CursorCodec` + `GenericEntityController.list()`

Model: `QueryEntitiesTool.java` reuses `CursorCodec` without modification.

```java
// fabric-projections/src/main/java/dev/tessera/projections/rest/CursorCodec.java
public final class CursorCodec {
    // Wire format: modelId|typeSlug|lastSeq|lastNodeId → Base64
    public static String encode(UUID modelId, String typeSlug, long lastSeq, UUID lastNodeId) { ... }
    public static CursorPosition decode(String cursor) { ... }
    public record CursorPosition(UUID modelId, String typeSlug, long lastSeq, UUID lastNodeId) {}
}
```

```java
// In GenericEntityController.list() — the pagination pattern MCP QueryEntitiesTool models:
long afterSeq = 0;
if (cursor != null && !cursor.isBlank()) {
    CursorCodec.CursorPosition pos = CursorCodec.decode(cursor);
    afterSeq = pos.lastSeq();
}
List<NodeState> nodes = dispatcher.list(ctx, typeSlug, afterSeq, effectiveLimit + 1);
boolean hasMore = nodes.size() > effectiveLimit;
List<NodeState> page = hasMore ? nodes.subList(0, effectiveLimit) : nodes;
String nextCursor = null;
if (hasMore && !page.isEmpty()) {
    NodeState last = page.get(page.size() - 1);
    nextCursor = CursorCodec.encode(modelId, typeSlug, last.seq(), last.uuid());
}
```

---

### 2.3 JWT tenant extraction — `GenericEntityController.enforceTenantMatch()`

Model: MCP tools extract tenant the same way from `SecurityContextHolder`.

```java
// fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java
private static void enforceTenantMatch(Jwt jwt, String model) {
    if (jwt == null) {
        throw new CrossTenantException();
    }
    String tenant = jwt.getClaimAsString("tenant");
    if (tenant == null || !tenant.equals(model)) {
        throw new CrossTenantException();
    }
}
// ...
@GetMapping
public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, ...) {
    // jwt.getClaimAsString("tenant") is the modelId UUID
    UUID modelId = parseModelId(model);
    enforceTenantMatch(jwt, model);
    TenantContext ctx = TenantContext.of(modelId);
    // ...
}
```

In MCP tools, the same claim is available via `SecurityContextHolder`:
```java
// Pattern for MCP tool tenant extraction (from RESEARCH.md §Pattern 4):
// SecurityConfig.jwtAuthenticationConverter() sets setPrincipalClaimName("tenant")
// so auth.getName() returns the tenant UUID string
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
UUID modelId = UUID.fromString(auth.getName());
TenantContext ctx = TenantContext.of(modelId);
// agentId = JWT sub claim:
String agentId = ((JwtAuthenticationToken) auth).getToken().getSubject();
```

---

### 2.4 Security filter chain — `SecurityConfig`

Model: Phase 3 adds `ROLE_AGENT` and `/mcp/**` paths to the existing chain. No new `SecurityFilterChain` bean needed.

```java
// fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(TesseraAuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, RotatableJwtDecoder jwtDecoder) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth -> oauth.jwt(
                jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter())))
            // ... HSTS config ...
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html", "/admin/tokens/issue")
                .permitAll()
                .requestMatchers("/admin/**")
                .hasAnyRole("ADMIN", "TOKEN_ISSUER")
                .anyRequest()
                .authenticated());
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedConverter = new JwtGrantedAuthoritiesConverter();
        grantedConverter.setAuthoritiesClaimName("roles");
        grantedConverter.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedConverter);
        converter.setPrincipalClaimName("tenant");   // auth.getName() == tenant UUID
        return converter;
    }
}
```

**Phase 3 change:** `ROLE_AGENT` agents hit `anyRequest().authenticated()` — they don't need a new rule, just a JWT with any valid role. The `/mcp/sse` and `/mcp/message` endpoints are already caught by `anyRequest().authenticated()`. No structural change needed — just ensure `ROLE_AGENT` is issued by `TokenIssueController`.

---

### 2.5 Admin controller pattern — `TokenIssueController`

Model: `McpAuditController` follows this exact shape (JDBC query instead of JWT mint).

```java
// fabric-projections/src/main/java/dev/tessera/projections/rest/admin/TokenIssueController.java
@RestController
@RequestMapping("/admin/tokens")
public class TokenIssueController {
    private final TesseraAuthProperties authProperties;

    @PostMapping("/issue")
    public ResponseEntity<Map<String, Object>> issueToken(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Tessera-Bootstrap", required = false) String bootstrapHeader) {
        // validation → logic → ResponseEntity.ok(Map.of(...))
    }
}
```

**`McpAuditController` template:**
```java
@RestController
@RequestMapping("/admin/mcp")
public class McpAuditController {
    private final NamedParameterJdbcTemplate jdbc;

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> getAuditLog(
            @RequestParam("model_id") UUID modelId,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @AuthenticationPrincipal Jwt jwt) {
        // tenant check, JDBC query, ResponseEntity.ok(...)
    }
}
```

---

### 2.6 JDBC INSERT audit pattern — `EventLog.append()`

Model: `McpAuditLog.append()` follows this NamedParameterJdbcTemplate INSERT pattern.

```java
// fabric-core/src/main/java/dev/tessera/core/events/EventLog.java
private static final String INSERT = """
    INSERT INTO graph_events (...) VALUES (...) RETURNING id
    """;

public Appended append(TenantContext ctx, GraphMutation mutation, NodeState state,
                       String eventType, Map<String, Object> previousState) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("model_id", ctx.modelId().toString());
    p.addValue("sequence_nr", seq);
    // ... other params ...
    UUID id = jdbc.queryForObject(INSERT, p, UUID.class);
    return new Appended(id, seq);
}

public record Appended(UUID eventId, long sequenceNr) {}
```

**`McpAuditLog` INSERT template:**
```java
private static final String INSERT = """
    INSERT INTO mcp_audit_log
        (id, model_id, agent_id, tool_name, arguments, outcome, duration_ms, created_at)
    VALUES
        (gen_random_uuid(), :model_id::uuid, :agent_id, :tool_name,
         :arguments::jsonb, :outcome, :duration_ms, clock_timestamp())
    """;
```

---

### 2.7 EventLog temporal replay — `EventLog.replayToState()`

Model: `GetStateAtTool.execute()` is a direct delegation to this method. No new logic needed.

```java
// fabric-core/src/main/java/dev/tessera/core/events/EventLog.java
public Optional<Map<String, Object>> replayToState(TenantContext ctx, UUID nodeUuid, Instant at) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("model_id", ctx.modelId().toString());
    p.addValue("node_uuid", nodeUuid.toString());
    p.addValue("at", java.sql.Timestamp.from(at));
    List<Map<String, Object>> folded = jdbc.query("""
            SELECT event_type, payload
              FROM graph_events
             WHERE model_id = :model_id::uuid
               AND node_uuid = :node_uuid::uuid
               AND event_time <= :at
             ORDER BY sequence_nr ASC
            """, p, (rs, rowNum) -> { ... });
    if (folded.isEmpty()) return Optional.empty();
    return Optional.of(folded.get(folded.size() - 1));
}
```

---

### 2.8 Raw Cypher execution — `GraphSession`

Model: `FindPathTool` and `TraverseTool` use the same JDBC pattern GraphSession uses. The `GRAPH_NAME` constant and the `executeCypher` idiom must be replicated.

```java
// fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
public static final String GRAPH_NAME = "tessera_main";
private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

// All Cypher runs through this shape:
String cypher = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$"
    + " MATCH (n:" + label + ")"
    + " WHERE n.model_id = \"" + ctx.modelId() + "\" AND n._seq > " + afterSeq
    + " RETURN n ORDER BY n._seq LIMIT " + limit
    + " $$) AS (n agtype)";
List<String> rows = jdbc.query(cypher, (rs, i) -> rs.getString(1));
return rows.stream().map(r -> toNodeState(label, r)).toList();
```

**FindPath Cypher (from RESEARCH.md §Pattern 5):**
```sql
SELECT * FROM cypher('tessera_main', $$
  MATCH (a {model_id: "{{modelId}}", uuid: "{{fromUuid}}"}),
        (b {model_id: "{{modelId}}", uuid: "{{toUuid}}"})
  MATCH path = shortestPath((a)-[*1..10]-(b))
  WHERE ALL(n IN nodes(path) WHERE n.model_id = "{{modelId}}")
  RETURN path
$$) AS (path agtype);
```

**Critical:** `FindPathTool` must NOT import `GraphSession` directly (it is in `dev.tessera.core.graph.internal` — blocked by ArchUnit). It must go through `GraphRepository` or a new public port on `GraphRepository` interface, or through a thin `GraphSession`-delegating method added to `GraphRepositoryImpl`. This is the key integration design decision for the planner.

---

### 2.9 AtomicLong in-memory counter — `CircuitBreakerPort` shape

Model: `AgentQuotaService` follows the same port pattern (interface in `fabric-core`, implementation in projection layer).

```java
// fabric-core/src/main/java/dev/tessera/core/circuit/CircuitBreakerPort.java
public interface CircuitBreakerPort {
    /**
     * Record a mutation and evaluate tripping state.
     * Throw CircuitBreakerTrippedException if over threshold.
     */
    void recordAndCheck(TenantContext ctx, String connectorId) throws CircuitBreakerTrippedException;
}
```

**`AgentQuotaService` analogy:**
```java
// dev.tessera.projections.mcp.quota.AgentQuotaService
@Service
public class AgentQuotaService {
    // ConcurrentHashMap<(agentId, modelId), AtomicLong> — same pattern as WriteRateCircuitBreaker
    private final ConcurrentHashMap<String, AtomicLong> hourlyCounters = new ConcurrentHashMap<>();
    private final NamedParameterJdbcTemplate jdbc;

    // key = agentId + "|" + modelId
    public void checkWriteQuota(TenantContext ctx, String agentId) throws QuotaExceededException {
        int limit = loadQuotaLimit(ctx, agentId);  // from mcp_agent_quotas
        if (limit == 0) throw new QuotaExceededException("Agent " + agentId + " has no write quota");
        long count = hourlyCounters
            .computeIfAbsent(agentId + "|" + ctx.modelId(), k -> new AtomicLong(0))
            .incrementAndGet();
        if (count > limit) throw new QuotaExceededException("Write quota exceeded");
    }
}
```

---

### 2.10 ArchUnit test — `ProjectionsModuleDependencyTest`

Model: `McpMutationAllowlistTest` extends this with MCP-specific rule.

```java
// fabric-projections/src/test/java/dev/tessera/projections/arch/ProjectionsModuleDependencyTest.java
@AnalyzeClasses(
        packages = "dev.tessera.projections",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class ProjectionsModuleDependencyTest {

    @ArchTest
    static final ArchRule projections_must_not_import_graph_internal = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.tessera.core.graph.internal..");
}
```

**Phase 3 addition (new `@ArchTest` in same class or new class):**
```java
@ArchTest
static final ArchRule mcp_tools_must_not_call_schema_mutations = noClasses()
        .that()
        .resideInAPackage("dev.tessera.projections.mcp.tools..")
        .should()
        .callMethodWhere(JavaCall.Predicates.target(
            ArchConditions.owner(assignableTo(SchemaRegistry.class))
            .and(ArchConditions.method(nameMatching("create.*|update.*|delete.*|deprecate.*|rename.*"))))
        );
```

---

### 2.11 Integration test harness — `CrossTenantLeakPropertyIT`

Model: `McpPromptInjectionIT` uses the same `@SpringBootTest + @Testcontainers + RestAssured` shape, seeding adversarial node data.

```java
// fabric-projections/src/test/java/dev/tessera/projections/rest/CrossTenantLeakPropertyIT.java
@SpringBootTest(classes = ProjectionItApplication.class,
                webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class CrossTenantLeakPropertyIT {

    private static final String AGE_IMAGE =
        "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("tessera").withUsername("tessera").withPassword("tessera").withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @LocalServerPort int port;

    @Test
    void cross_tenant_always_returns_404_with_identical_body() {
        for (int i = 0; i < 100; i++) {
            UUID tenantA = UUID.randomUUID();
            String tokenA = JwtTestHelper.mint(tenantA.toString(), List.of("ADMIN"));
            given().port(port).header("Authorization", "Bearer " + tokenA)
                .when().get("/api/v1/" + UUID.randomUUID() + "/entities/type-x")
                .then().statusCode(404).contentType("application/problem+json");
        }
    }
}
```

---

### 2.12 JWT test helper — `JwtTestHelper`

Model: MCP ITs mint `AGENT` role JWTs using the same helper; just pass `List.of("AGENT")`.

```java
// fabric-projections/src/test/java/dev/tessera/projections/rest/JwtTestHelper.java
public static final String TEST_SIGNING_KEY = "dGVzc2VyYS10ZXN0LWtleS0xMjM0NTY3ODkwYWJjZGVm";

public static String mint(String tenant, List<String> roles) { ... }

// MCP ITs call:
String agentToken = JwtTestHelper.mint(tenantId.toString(), List.of("AGENT"));
```

---

### 2.13 Flyway migration structure — V8 and V13 as templates

Last migration is V21. Phase 3 starts at V22. Each migration is a single `ALTER TABLE` or `CREATE TABLE` block with an inline comment header.

```sql
-- V8__connector_limits_and_dlq.sql (template shape)
-- Phase 1 / Wave 0 / connector_limits_and_dlq
CREATE TABLE connector_limits (
    model_id UUID NOT NULL,
    connector_id TEXT NOT NULL,
    ...
    PRIMARY KEY (model_id, connector_id)
);
CREATE INDEX idx_connector_dlq_model_connector_created
    ON connector_dlq (model_id, connector_id, created_at DESC);
```

**Phase 3 migrations:**

```sql
-- V22__mcp_audit_log.sql
-- Phase 3 / MCP Projection / D-C3: per-invocation audit log
CREATE TABLE mcp_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id    UUID NOT NULL,
    agent_id    TEXT NOT NULL,
    tool_name   TEXT NOT NULL,
    arguments   JSONB NOT NULL DEFAULT '{}',
    outcome     TEXT NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_mcp_audit_model_created
    ON mcp_audit_log (model_id, created_at DESC);
CREATE INDEX idx_mcp_audit_model_agent_created
    ON mcp_audit_log (model_id, agent_id, created_at DESC);
```

```sql
-- V23__mcp_agent_quotas.sql
-- Phase 3 / MCP Projection / D-C2: per-agent write quota table
CREATE TABLE mcp_agent_quotas (
    agent_id        TEXT NOT NULL,
    model_id        UUID NOT NULL,
    writes_per_hour INT  NOT NULL DEFAULT 0,
    writes_per_day  INT  NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (agent_id, model_id)
);
```

---

### 2.14 Spring AI MCP YAML configuration

```yaml
# application.yml addition (from RESEARCH.md §Pattern 7, VERIFIED via Spring AI 1.0.x docs)
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

The `/mcp/sse` and `/mcp/message` paths are MVC endpoints protected by `SecurityConfig.anyRequest().authenticated()` — no additional security configuration required.

---

### 2.15 SchemaRegistry API surface used by MCP tools

```java
// fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
// Methods MCP tools call (all public, non-mutating):
List<NodeTypeDescriptor> listNodeTypes(TenantContext ctx);
Optional<NodeTypeDescriptor> loadFor(TenantContext ctx, String typeSlug);
Optional<EdgeTypeDescriptor> findEdgeType(TenantContext ctx, String slug);

// NodeTypeDescriptor record fields:
// UUID modelId, String slug, String name, String label, String description,
// long schemaVersion, List<PropertyDescriptor> properties, Instant deprecatedAt,
// boolean restReadEnabled, boolean restWriteEnabled

// PropertyDescriptor fields (from PropertyDescriptor.java):
// String slug, String dataType, boolean required, boolean deprecated
```

**`DescribeTypeTool` uses `loadFor()` to get properties + then `findEdgeType()` calls for each edge type slug.**

---

## 3. Integration Points

### 3.1 Phase 1 core services consumed by MCP tools

| MCP File | Calls | Via |
|----------|-------|-----|
| `ListEntityTypesTool` | `SchemaRegistry.listNodeTypes(ctx)` | Direct injection |
| `DescribeTypeTool` | `SchemaRegistry.loadFor(ctx, slug)`, `SchemaRegistry.findEdgeType(ctx, slug)` | Direct injection |
| `QueryEntitiesTool` | `GraphRepository.queryAllAfter(ctx, typeSlug, afterSeq, limit)`, `CursorCodec.encode/decode()` | Direct injection |
| `GetEntityTool` | `GraphRepository.queryById(ctx, typeSlug, uuid)` + neighbor query | Direct injection |
| `TraverseTool` | Tenant-scoped Cypher via `GraphRepository.queryAll()` or new `GraphRepository.executeCypher()` port | Requires new method on `GraphRepository` interface |
| `FindPathTool` | AGE `shortestPath()` Cypher via JDBC — needs a new `GraphRepository.findPath()` method | Requires new method on `GraphRepository` interface + `GraphRepositoryImpl` implementation |
| `GetStateAtTool` | `EventLog.replayToState(ctx, nodeUuid, at)` | Direct injection |
| `McpAuditLog` | `NamedParameterJdbcTemplate` INSERT to `mcp_audit_log` | Direct injection |
| `AgentQuotaService` | `NamedParameterJdbcTemplate` SELECT from `mcp_agent_quotas` | Direct injection |

### 3.2 Phase 2 security infrastructure reused by MCP

| MCP Need | Existing Asset | Where |
|----------|---------------|--------|
| JWT decode + tenant extraction | `RotatableJwtDecoder` (bean) | `SecurityConfig` wires it; `SpringAiMcpAdapter` / tools get tenant from `SecurityContextHolder` |
| `ROLE_AGENT` JWT issuance | `TokenIssueController` — already accepts any `roles` list | Pass `["AGENT"]` in request body |
| `/admin/mcp/audit` access control | `SecurityConfig` `hasAnyRole("ADMIN", "TOKEN_ISSUER")` on `/admin/**` | No change needed |
| SSE endpoint JWT protection | `anyRequest().authenticated()` in `SecurityConfig` | No change needed |

### 3.3 New `GraphRepository` methods needed

`TraverseTool` and `FindPathTool` need Cypher access that `GraphRepository` currently does not expose. Two options the planner must choose:

**Option A (preferred per CONTEXT.md D-A3 principle):** Add two new methods to `GraphRepository` interface + implement in `GraphRepositoryImpl`:
```java
// New method on GraphRepository
List<Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher);
Optional<List<NodeState>> findShortestPath(TenantContext ctx, UUID from, UUID to);
```

**Option B:** Add a `GraphSession`-typed bean to `GraphCoreConfig` and expose it via `GraphRepository`. This exposes less surface but requires `GraphSession` to be a Spring bean (it currently is, via `GraphCoreConfig`).

The ArchUnit test already blocks `dev.tessera.projections..` from reaching `dev.tessera.core.graph.internal..`, so `FindPathTool` cannot call `GraphSession` directly regardless.

### 3.4 `SpringAiMcpAdapter` bean wiring

```
McpSyncServer (Spring AI auto-configured bean)
    ↑ registered via addTool()
SpringAiMcpAdapter (@Component, ApplicationListener<SchemaChangeEvent>)
    ↑ injects
List<ToolProvider> (Spring-collected all @Component ToolProvider implementations)
    ↑ each implements
{ListEntityTypesTool, DescribeTypeTool, QueryEntitiesTool, GetEntityTool,
 TraverseTool, FindPathTool, GetStateAtTool}
    ↑ each injects
{SchemaRegistry, GraphRepository, EventLog, NamedParameterJdbcTemplate}
```

`ToolResponseWrapper.wrap()` is called inside `SpringAiMcpAdapter.invokeTool()` — never inside individual tool classes.

`McpAuditLog.record()` is also called inside `SpringAiMcpAdapter.invokeTool()` — one audit row per tool call, regardless of outcome.

### 3.5 Tenant context flow through Spring AI MVC

```
HTTP POST /mcp/message (Bearer JWT)
  → Spring Security filter: validates JWT, sets SecurityContextHolder
  → Tomcat thread (same thread throughout — WebMVC, not WebFlux)
  → Spring AI MCP handler routes to SpringAiMcpAdapter.invokeTool()
  → SpringAiMcpAdapter extracts tenant from SecurityContextHolder.getContext().getAuthentication()
  → TenantContext ctx = TenantContext.of(UUID.fromString(auth.getName()))
  → ToolProvider.execute(ctx, args)
  → tool delegates to SchemaRegistry/GraphRepository/EventLog with ctx
```

`TenantContext` is NEVER stored in a ThreadLocal — it is extracted from the JWT and passed as an explicit method parameter on every downstream call (CORE-03 mandate).

---

## Key Design Decision for Planner

**`TraverseTool` and `FindPathTool` Cypher access:** The planner MUST decide whether to:
1. Add `executeTenantCypher()` and `findShortestPath()` to the `GraphRepository` interface (preferred — keeps the boundary clean and testable), or
2. Create a thin `GraphQueryPort` interface in `fabric-core`'s public API that `GraphSession` implements, following the `CircuitBreakerPort` SPI pattern.

Either approach must be reflected in `ProjectionsModuleDependencyTest` (or a new ArchUnit rule) to prevent future direct GraphSession access from MCP tools.
