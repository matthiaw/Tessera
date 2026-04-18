---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W2
type: execute
wave: 3
depends_on: [W0, W1]
files_modified:
  - fabric-projections/pom.xml
  - fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/CursorCodec.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/EntityDispatcher.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/OpenApiSchemaCustomizer.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/problem/TesseraProblemHandler.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/security/TesseraAuthProperties.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/admin/TokenIssueController.java
  - fabric-app/src/main/resources/application.yml
  - fabric-app/src/main/resources/application-prod.yml
  - fabric-projections/src/test/java/dev/tessera/projections/rest/DenyAllExposureIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/CursorPaginationConcurrencyIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/CrossTenantLeakPropertyIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/ErrorShapeProblemJsonIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/DynamicOpenApiIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/JwtRotationIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/TlsHstsHeaderIT.java
  - fabric-projections/src/jmh/java/dev/tessera/projections/bench/RestProjectionBench.java
  - fabric-app/src/test/java/dev/tessera/app/arch/ProjectionsModuleDependencyTest.java
autonomous: true
requirements:
  - REST-01
  - REST-02
  - REST-03
  - REST-04
  - REST-05
  - REST-06
  - REST-07
  - SEC-01
  - SEC-02
  - SEC-03  # ops/LUKS documentation per 02-VALIDATION.md row 57 — no code task
  - SEC-04
  - SEC-05

must_haves:
  truths:
    - "A single GenericEntityController handles GET-list, GET-one, POST, PUT, DELETE for /api/v1/{model}/entities/{typeSlug} — all CRUD via runtime SchemaRegistry lookup"
    - "An undeclared type, a disabled type (rest_read_enabled=false), a cross-tenant path, or an unknown instance all return 404 problem+json — never 200, never 403"
    - "List endpoints paginate via opaque base64 cursor carrying (model, type, last_seq, last_node_id); cursor is stable under concurrent writes"
    - "Every error response is application/problem+json (RFC 7807) with title/status/detail/type/instance + code extension; ValidationReport is tenant-filtered before mapping"
    - "OAuth2 resource server validates HMAC-signed JWTs using a NimbusJwtDecoder built from a Vault-loaded key, wrapped in AtomicReference for rotation on RefreshScopeRefreshedEvent"
    - "/v3/api-docs is rebuilt dynamically from the Schema Registry on each hit (springdoc.cache.disabled=true) — DynamicOpenApiIT asserts that a schema flip is visible to the next doc fetch without redeploy"
    - "Tenant claim in JWT must equal {model} path segment or request returns 404 before the controller sees it"
    - "TLS 1.3 + HSTS headers emitted (direct config) + X-Forwarded-Proto trust configured for reverse-proxy deployment"
    - "RestProjectionBench list-endpoint p95 < 50ms against a 100k-node dataset"
  artifacts:
    - path: fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java
      provides: "The single CRUD dispatcher for REST-01"
      contains: "/api/v1/{model}/entities/{typeSlug}"
    - path: fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
      provides: "OAuth2 resource server + HSTS + deny-all default"
      contains: "oauth2ResourceServer"
    - path: fabric-projections/src/main/java/dev/tessera/projections/rest/internal/OpenApiSchemaCustomizer.java
      provides: "Runtime OpenAPI document rebuild per SchemaRegistry state"
      contains: "OpenApiCustomizer"
  key_links:
    - from: fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java
      to: fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
      via: "All writes through GraphService.apply (Phase 1 funnel)"
      pattern: "graphService.apply"
    - from: fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
      to: fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java
      via: "SecurityFilterChain consumes RotatableJwtDecoder bean"
      pattern: "oauth2ResourceServer.*jwt"
    - from: fabric-projections/src/main/java/dev/tessera/projections/rest/internal/OpenApiSchemaCustomizer.java
      to: fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
      via: "Customizer reads exposed types per model on every doc build"
      pattern: "registry.exposedTypes"
---

<objective>
Land the REST projection + security baseline. This is the biggest wave of Phase 2 and delivers 11 of the 22 phase requirements. Three task blocks:

1. **CRUD dispatcher + cursor pagination + SHACL-backed writes** — `GenericEntityController` with one method per HTTP verb, dispatching to an `EntityDispatcher` that consults `SchemaRegistry` (exposure flag check → 404 deny-all), loads/saves via Phase 1's `GraphRepository` + `GraphService.apply`. Cursor codec uses `_seq` seek-method from Wave 1. SHACL validation happens inside `graphService.apply` already — controller just marshalls JsonNode → CandidateMutation and re-throws the Phase 1 exceptions.
2. **Security baseline** — OAuth2 resource server (Spring Security), `RotatableJwtDecoder` pulling HMAC key from Vault via Spring Cloud Vault Config Data API, tenant-claim-vs-path-segment enforcement (cross-tenant → 404), RFC 7807 `@RestControllerAdvice`, `/api/v1/admin/tokens/issue` minimal bootstrap endpoint, TLS 1.3 + HSTS + forward-headers config, dynamic OpenAPI document via `OpenApiCustomizer` (Wave 0 spike already confirmed this works).
3. **Verification surface** — deny-all fuzz IT, cross-tenant leak property IT (jqwik-backed), cursor stability under concurrent writes, JWT rotation IT, dynamic OpenAPI IT, TLS/HSTS header IT, RestProjectionBench (<50ms p95 @ 100k nodes), and a ProjectionsModuleDependencyTest asserting `fabric-projections` does not import `fabric-core.graph.internal`.

Purpose: deliver the "graph is the truth; REST is a projection" promise end-to-end with deny-all default and zero cross-tenant leakage.

Output: a fully working REST surface, all gated by ITs, committed as ONE wave because these pieces only make sense together.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-CONTEXT.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-RESEARCH.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W0-PLAN.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W1-PLAN.md
@fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
@fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java
@fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
@fabric-core/src/main/java/dev/tessera/core/validation/ValidationReport.java
@fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
@fabric-app/src/test/java/dev/tessera/app/arch/ModuleDependencyTest.java
</context>

<interfaces>
Controller contract (one dispatcher, five methods):
```java
@RestController
@RequestMapping("/api/v1/{model}/entities/{typeSlug}")
public class GenericEntityController {
    @GetMapping public ResponseEntity<PageResponse<JsonNode>> list(
        @PathVariable String model, @PathVariable String typeSlug,
        @RequestParam(required=false) String cursor,
        @RequestParam(defaultValue="50") int limit,
        @AuthenticationPrincipal Jwt jwt);
    @GetMapping("/{uuid}") public ResponseEntity<JsonNode> get(...);
    @PostMapping public ResponseEntity<JsonNode> create(@RequestBody JsonNode body, ...);
    @PutMapping("/{uuid}") public ResponseEntity<JsonNode> update(...);
    @DeleteMapping("/{uuid}") public ResponseEntity<Void> delete(...);
}

public record PageResponse<T>(List<T> items, String nextCursor) {}
```

Cursor codec (opaque base64):
```java
public final class CursorCodec {
    public static String encode(String model, String typeSlug, long lastSeq, UUID lastNodeId);
    public static Cursor decode(String cursor) throws InvalidCursorException;
    public record Cursor(String model, String typeSlug, long lastSeq, UUID lastNodeId) {}
}
```

RotatableJwtDecoder (RESEARCH Q4 pattern):
```java
public class RotatableJwtDecoder implements JwtDecoder {
    private final AtomicReference<NimbusJwtDecoder> current;
    public Jwt decode(String token);
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void onRefresh(RefreshScopeRefreshedEvent ev, TesseraAuthProperties fresh);
}
```

Problem handler exceptions (all → 404 per Decision 11):
```java
public class NotFoundException extends RuntimeException {}
public class TypeNotExposedException extends RuntimeException {}
public class CrossTenantException extends RuntimeException {}
// Only ForbiddenException → 403 (authenticated owner of tenant but missing role)
```
</interfaces>

<tasks>

<task type="auto" tdd="true">
  <name>Task 02-W2-01: GenericEntityController + EntityDispatcher + CursorCodec + OpenAPI customizer (REST-01, REST-02, REST-03, REST-04, REST-05)</name>
  <files>
    fabric-projections/pom.xml,
    fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/internal/EntityDispatcher.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/internal/CursorCodec.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/internal/OpenApiSchemaCustomizer.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/DenyAllExposureIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/CursorPaginationConcurrencyIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/DynamicOpenApiIT.java,
    fabric-projections/src/jmh/java/dev/tessera/projections/bench/RestProjectionBench.java,
    fabric-app/src/test/java/dev/tessera/app/arch/ProjectionsModuleDependencyTest.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-RESEARCH.md §Q1 (OpenAPI), §Q7 (cursor pagination)
    - .planning/phases/02-.../02-CONTEXT.md Decisions 4, 5, 11, 12, 13
    - .planning/phases/02-.../02-W0-PLAN.md Task 02-W0-01 (spike that proved the OpenAPI approach)
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
    - fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java
    - fabric-app/src/test/java/dev/tessera/app/arch/ModuleDependencyTest.java (Phase 1 ArchUnit shape)
  </read_first>
  <behavior>
    - GET `/api/v1/{model}/entities/{typeSlug}` → list endpoint: resolves `NodeTypeDescriptor` from SchemaRegistry; if absent OR `restReadEnabled==false` → throw `TypeNotExposedException` (→ 404 via problem handler in Task 02-W2-02). Otherwise decode cursor (if present), query via `GraphRepository.queryAllAfter(ctx, typeSlug, lastSeq, limit+1)` ordered by `_seq ASC`. Take first `limit` items, compute `nextCursor` if we got `limit+1`. Return `{items: [...], next_cursor: "..."|null}`.
    - GET `/api/v1/{model}/entities/{typeSlug}/{uuid}` → single fetch: exposure check, then `GraphRepository.findNode(ctx, typeSlug, uuid)` → `NotFoundException` if empty → 404.
    - POST → validate `restWriteEnabled`, build `CandidateMutation(CREATE, ctx, typeSlug, body, originConnectorId=null)`, call `graphService.apply(...)`. On success return 201 + created node. On `ShaclValidationException` or `RuleRejectException` → let the problem handler advice map to 422.
    - PUT → exposure check, build `CandidateMutation(UPDATE, ...)`, apply.
    - DELETE → exposure check, `graphService.apply(TOMBSTONE, ...)` (never hard delete via REST — tombstone semantics from Phase 1 CORE-07). 204 on success.
    - CursorCodec base64-encodes a tiny record; decode throws `InvalidCursorException` (→ 400 problem+json) on any malformed input. HMAC signing of the cursor is deferred (Decision 4 does not require it for Phase 2 — revisit if real consumers need tamper resistance).
    - OpenApiSchemaCustomizer is promoted from the Wave 0 spike to production code. It is a `@Bean GroupedOpenApi("entities")` walking `SchemaRegistry.allModels()` + `registry.exposedTypes(model)` and emitting per-type `PathItem`s with GET/POST/PUT/DELETE operations AND request/response schemas derived from `PropertyDescriptor`s. Schema names namespaced as `{model}_{slug}Entity` (Q1 pitfall). Set `springdoc.cache.disabled=true` in config.
    - ProjectionsModuleDependencyTest asserts: `fabric-projections` classes must NOT depend on `dev.tessera.core.graph.internal.*`; only on the public `dev.tessera.core.graph.{GraphService,GraphMutation,GraphRepository,...}` surface. ArchUnit rule added to `fabric-app/src/test/java/dev/tessera/app/arch` where Phase 1's ModuleDependencyTest lives.
  </behavior>
  <action>
    1. `fabric-projections/pom.xml`: add dependencies `spring-boot-starter-web`, `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui:2.8.x` (if not already from W0), `fabric-core` (already present). REST-assured + WireMock in test scope.
    2. `CursorCodec.java` — single class per the interface above. Use `java.util.Base64` URL-safe encoder on a JSON blob `{"m":"...","t":"...","s":147293,"n":"uuid"}`. Defensive: reject cursors > 512 bytes, reject unknown fields, validate UUID parses.
    3. `EntityDispatcher.java` package-private helper inside `internal/`. Holds the domain logic so `GenericEntityController` is thin (controller is just annotation glue). Constructor takes `SchemaRegistry`, `GraphRepository`, `GraphService`, `ObjectMapper`. Methods `list`, `get`, `create`, `update`, `delete` — each takes a `TenantContext` (derived from the JWT `tenant` claim in the controller), the model/type slugs, and the verb-specific args. Every method starts with an exposure check:
       ```java
       var desc = registry.loadNodeType(ctx.modelId(), typeSlug)
           .orElseThrow(TypeNotExposedException::new);
       if (!desc.restReadEnabled()) throw new TypeNotExposedException();  // or restWriteEnabled for POST/PUT/DELETE
       ```
    4. `GenericEntityController.java` — slim controller. Extracts `Jwt jwt` and validates `jwt.getClaim("tenant").equals(model)`; if not, throws `CrossTenantException` (→ 404 in Task 02-W2-02). Builds a `TenantContext` and delegates to `EntityDispatcher`. Uses `JsonNode` as the body type — the schema validation happens downstream in the graph funnel via SHACL.
    5. `OpenApiSchemaCustomizer.java` — `@Configuration` with `@Bean GroupedOpenApi entitiesApi(SchemaRegistry registry)`. Implementation per the Wave 0 spike, promoted and hardened: iterate exposed types, namespace schemas, build `Operation` objects for GET/POST/PUT/DELETE with correct parameter and response schemas (including `ProblemDetail` as the error schema via the `problemDetailCustomizer` from Task 02-W2-02).
    6. `DenyAllExposureIT.java`: spring boot test, seed two tenant models (`acme`, `globex`) with a type `widget` in `acme` (rest_read_enabled=false) and `gadget` in `globex` (rest_read_enabled=true). Issue a token for tenant `acme`. Assertions:
       - GET `/api/v1/acme/entities/widget` → 404 problem+json (disabled)
       - GET `/api/v1/acme/entities/nonexistent` → 404 problem+json (undeclared)
       - GET `/api/v1/globex/entities/gadget` with acme token → 404 problem+json (cross-tenant)
       - POST `/api/v1/acme/entities/widget` → 404 (rest_write_enabled also false)
       - Flip widget.rest_read_enabled=true via SchemaRegistry JDBC + cache invalidation → GET now 200 with empty list
    7. `CursorPaginationConcurrencyIT.java`: create 150 nodes of type `person` in tenant `acme`. Fetch page 1 (limit=50), then concurrently insert 25 new nodes (using an `ExecutorService`), then fetch page 2 using the cursor from page 1. Assert: page 2 contains nodes 51..100 from the ORIGINAL seeding, no duplicates, no gaps. Fetch page 3, assert nodes 101..150. Fetch page 4, assert empty `items` with `next_cursor=null`. Seek-method stability per Q7.
    8. `DynamicOpenApiIT.java`: full Spring boot test. (1) Declare type `alpha` with rest_read_enabled=false, hit `/v3/api-docs?group=entities`, assert `/api/v1/acme/entities/alpha` absent. (2) Flip true, invalidate cache, hit again WITHOUT restart, assert present with GET/POST/PUT/DELETE operations. (3) Flip false again, assert absent. This is REST-05's primary gate and depends on the Wave 0 spike pattern being production-promoted.
    9. `RestProjectionBench.java`: JMH bench seeding 100k nodes in tenant `acme`, benchmarking `GET /api/v1/acme/entities/person?limit=50` against the running Spring context via REST-assured or raw HTTP. Target p95 < 50ms. Phase 2 NEW budget. Fail (warning, not build-break) if p95 > 50ms.
    10. `ProjectionsModuleDependencyTest.java`: ArchUnit rule: `noClasses().that().resideInAPackage("dev.tessera.projections..").should().dependOnClassesThat().resideInAPackage("dev.tessera.core.graph.internal..")`. Add to the existing `fabric-app/src/test/java/dev/tessera/app/arch/` where other module-boundary tests live so it runs as part of the top-level build gate.
    11. TDD order: DenyAllExposureIT + CursorPaginationConcurrencyIT first (RED — no controller), then controller + dispatcher + codec, then DynamicOpenApiIT last (depends on OpenAPI customizer), then RestProjectionBench.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-projections -Dit.test='DenyAllExposureIT,CursorPaginationConcurrencyIT,DynamicOpenApiIT' verify && ./mvnw -pl fabric-app -Dtest=ProjectionsModuleDependencyTest test</automated>
  </verify>
  <acceptance_criteria>
    - All three ITs exit 0
    - `./mvnw -pl fabric-app -Dtest=ProjectionsModuleDependencyTest test` exits 0
    - `grep -q "/api/v1/{model}/entities/{typeSlug}" fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java` succeeds
    - `grep -q "TypeNotExposedException" fabric-projections/src/main/java/dev/tessera/projections/rest/internal/EntityDispatcher.java` succeeds
    - `grep -q "restReadEnabled" fabric-projections/src/main/java/dev/tessera/projections/rest/internal/EntityDispatcher.java` succeeds
    - `grep -q "OpenApiCustomizer" fabric-projections/src/main/java/dev/tessera/projections/rest/internal/OpenApiSchemaCustomizer.java` succeeds
    - `! grep -rnq "graph\.internal" fabric-projections/src/main/java` (module boundary; recursive; zero hits required)
    - RestProjectionBench produces a p95 reading; warn-only gate at 50ms
  </acceptance_criteria>
  <done>
    Dispatcher CRUD green, cursor pagination stable, deny-all default enforced, OpenAPI document rebuilds dynamically, module boundary asserted.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 02-W2-02: Security baseline — OAuth2 resource server + Vault HMAC + RFC 7807 + TLS/HSTS + token issue (SEC-01, SEC-02, SEC-04, SEC-05, REST-06, REST-07)</name>
  <files>
    fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/security/TesseraAuthProperties.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/problem/TesseraProblemHandler.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/admin/TokenIssueController.java,
    fabric-app/src/main/resources/application.yml,
    fabric-app/src/main/resources/application-prod.yml,
    fabric-projections/src/test/java/dev/tessera/projections/rest/CrossTenantLeakPropertyIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/ErrorShapeProblemJsonIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/JwtRotationIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/TlsHstsHeaderIT.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-RESEARCH.md §Q4 (OAuth2+Vault), §Q5 (RFC 7807), §Q8 (TLS+HSTS), §Q10 (VaultContainer)
    - .planning/phases/02-.../02-CONTEXT.md Decisions 6, 8, 11
    - fabric-core/src/main/java/dev/tessera/core/validation/ValidationReport.java (tenant-filter helper)
  </read_first>
  <behavior>
    - `SecurityConfig` builds a `SecurityFilterChain` with: OAuth2 resource server JWT (via `RotatableJwtDecoder`), HSTS with `includeSubDomains(true).maxAgeInSeconds(31536000).preload(false)`, path rules `permitAll("/actuator/health/**")`, `hasRole("admin") for /api/v1/admin/**`, `authenticated()` for all else. JWT auth converter sets principal to `tenant` claim and authorities from `roles` claim.
    - `RotatableJwtDecoder` holds `AtomicReference<NimbusJwtDecoder>` built via `NimbusJwtDecoder.withSecretKey(...)` from `TesseraAuthProperties.hmacKeyB64`. Listens for `RefreshScopeRefreshedEvent` and rebuilds.
    - `TesseraAuthProperties` bound via `@ConfigurationProperties("tessera.auth")`, populated from Vault via `spring.config.import=vault://secret/tessera/auth`.
    - `TesseraProblemHandler` `@RestControllerAdvice` maps:
      - `NotFoundException | TypeNotExposedException | CrossTenantException` → 404 problem+json, title="Not Found", detail="Resource not found." (NEVER echo input), no `tenant` extension on cross-tenant
      - `ShaclValidationException | ValidationException` → 422 problem+json with `errors[]` from `ValidationReport.filteredFor(tenant)` — use the Phase 1 helper
      - `RuleRejectException` → 422 problem+json with `code=TESSERA_RULE_REJECTED`
      - `InvalidCursorException` → 400 problem+json
      - `ForbiddenException` → 403 problem+json (ONLY path that returns 403)
      - Default `Exception` → 500 problem+json, detail="Internal error.", code="TESSERA_INTERNAL"
      - Override `handleExceptionInternal` to ensure Spring's default `ResponseEntity<Object>` paths also emit problem+json
    - `TokenIssueController` `/api/v1/admin/tokens/issue` POST: request body `{operatorId, tenant, roles:[...]}`, response `{token, expiresAt}`. Signs a JWT using the same HMAC key via Nimbus `MACSigner`. 15-min TTL. Guard via `@PreAuthorize("hasRole('bootstrap-admin')")` or equivalent — bootstrap admin role comes from a single Vault-held bootstrap token consumed on first call (RESEARCH §Open Question 4 recommendation). For MVP, require a special `tessera.auth.bootstrap-token` config that must match a request header `X-Tessera-Bootstrap`. Document the rotation flow in the SUMMARY.
    - TLS 1.3 + HSTS config in `application-prod.yml`: `server.ssl.enabled-protocols: TLSv1.3`, `server.forward-headers-strategy: framework`. Dev profile can stay HTTP (reverse proxy terminates in prod, per Q8 recommendation). HSTS is ALWAYS emitted when Spring sees a secure request (either direct TLS or via `X-Forwarded-Proto: https`).
    - `CrossTenantLeakPropertyIT` — jqwik-style property test (reusing Phase 1's jqwik setup): for 500 random (tenantA_jwt, tenantB_path) pairs, assert every combination returns 404 with an identical error body shape (no tenant-specific content in detail/tenant field). Shrink minimal failure. This is the REST-06 primary gate.
    - `ErrorShapeProblemJsonIT`: POST an invalid body, assert response is `application/problem+json`, assert all required RFC 7807 fields present, assert `errors[].property` names match the violated properties, assert `detail` does not contain any caller-supplied string verbatim.
    - `JwtRotationIT` uses `VaultContainer` to seed `secret/tessera/auth/jwt_signing_key` with key-v1, issue a token, assert it validates; PUT a new key-v2 into Vault, publish `RefreshScopeRefreshedEvent`, assert old token now rejected (401) and new token (signed with key-v2) accepted.
    - `TlsHstsHeaderIT`: start the app with TLS config (use a test keystore generated in @BeforeAll via KeyStore.getInstance("PKCS12")), hit `/actuator/health` over HTTPS, assert `Strict-Transport-Security: max-age=31536000; includeSubDomains` header present. Also run a variant with HTTP + `X-Forwarded-Proto: https` header and `forward-headers-strategy: framework` to assert HSTS still emitted in the proxied-termination scenario.
  </behavior>
  <action>
    1. `TesseraAuthProperties.java`: `@ConfigurationProperties("tessera.auth") public record TesseraAuthProperties(String hmacKeyB64, int keyVersion, String bootstrapToken) {}`.
    2. `RotatableJwtDecoder.java`: per Q4 pattern verbatim. Constructor takes `TesseraAuthProperties`. `@EventListener(RefreshScopeRefreshedEvent.class)` rebuilds.
    3. `SecurityConfig.java`: `@EnableWebSecurity @Configuration`, publishes `SecurityFilterChain` bean with the rules above, plus `JwtAuthenticationConverter` bean setting principal claim to `tenant` and authorities claim to `roles` with `ROLE_` prefix.
    4. `TesseraProblemHandler.java`: `@RestControllerAdvice extends ResponseEntityExceptionHandler`. One `@ExceptionHandler` per Tessera exception class listed above, each building a `ProblemDetail` per Q5. Key invariant: `pd.setDetail(...)` ALWAYS takes a server-controlled string, NEVER `ex.getMessage()` unless the message is hardcoded in the exception class. For validation errors, call `ValidationReport.filteredFor(jwt.getClaim("tenant"))` BEFORE mapping to `errors[]`. Register a `problemDetailCustomizer` `OpenApiCustomizer` bean that attaches the problem+json schema to every operation's 4xx/5xx responses.
    5. `TokenIssueController.java`: `@RestController @RequestMapping("/api/v1/admin/tokens")`. `POST /issue` accepts body, checks `X-Tessera-Bootstrap` header against `TesseraAuthProperties.bootstrapToken`, signs a Nimbus `SignedJWT` with HS256, returns `{token, expiresAt}`. Add `@PreAuthorize` fallback: if a JWT auth principal is already present, require `hasRole('admin')`, otherwise require the bootstrap header. Document in SUMMARY.
    6. `application.yml` / `application-prod.yml`: add
       ```yaml
       spring:
         config:
           import: "vault://secret/tessera/auth"
         cloud:
           vault:
             uri: ${VAULT_ADDR:http://localhost:8200}
             authentication: APPROLE
             app-role:
               role-id: ${VAULT_ROLE_ID:}
               secret-id: ${VAULT_SECRET_ID:}
         mvc:
           problemdetails:
             enabled: true
       server:
         forward-headers-strategy: framework
       springdoc:
         cache:
           disabled: true
       tessera:
         security:
           field-encryption:
             enabled: false
       ```
       Production profile adds the TLS block from Q8.
    7. `CrossTenantLeakPropertyIT.java`: jqwik `@Property(tries=500)` with an `@ForAll` pair of tenants and an `@ForAll` random type slug. For each pair, issue a JWT for tenant A, GET `/api/v1/{tenantB}/entities/{slug}`, assert 404 + problem+json + no `tenant` field in response body + detail == "Resource not found." (constant). Shrinking produces a minimal reproducer on failure.
    8. `ErrorShapeProblemJsonIT.java`: POST an entity body missing a required SHACL property, assert response status 422, content-type `application/problem+json`, body contains `type`, `title`, `status`, `detail`, `instance`, `code`, `errors[0].property`, `errors[0].message`. Assert `detail` does NOT equal any raw user input (send a random UUID as a property value, grep response for it).
    9. `JwtRotationIT.java`: `@Testcontainers` with `VaultContainer` per Q10 — use token auth in tests (not AppRole) for simplicity, with a separate `VaultAppRoleAuthIT` left as a Wave 3 concern. Seed `secret/tessera/auth/jwt_signing_key` with key-v1, start context, issue a token via `/api/v1/admin/tokens/issue`, assert it validates on a protected endpoint. Rotate: `vault.kv put` key-v2, `context.publishEvent(new RefreshScopeRefreshedEvent(...))`, reassert: old token rejected 401, new token accepted. Use DynamicPropertySource to point Spring at the VaultContainer URL.
    10. `TlsHstsHeaderIT.java`: generate a self-signed PKCS12 keystore in `@BeforeAll` (use `java.security.KeyStore`), start Spring with `server.ssl.*` properties pointing at it, use a trust-all HttpClient to hit `/actuator/health`, assert `Strict-Transport-Security` header. Second variant: HTTP port + `X-Forwarded-Proto: https` header, assert HSTS still emitted.
    11. TDD order: ErrorShapeProblemJsonIT first (RED — no advice), implement advice. Then CrossTenantLeakPropertyIT. Then JwtRotationIT (requires VaultContainer test support module — land minimal helper here). Then TlsHstsHeaderIT last.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-projections -Dit.test='CrossTenantLeakPropertyIT,ErrorShapeProblemJsonIT,JwtRotationIT,TlsHstsHeaderIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - All four ITs exit 0
    - `grep -q "NimbusJwtDecoder.withSecretKey" fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java` succeeds
    - `grep -q "AtomicReference" fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java` succeeds
    - `grep -q "RefreshScopeRefreshedEvent" fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java` succeeds
    - `grep -q "ProblemDetail" fabric-projections/src/main/java/dev/tessera/projections/rest/problem/TesseraProblemHandler.java` succeeds
    - `grep -q "filteredFor" fabric-projections/src/main/java/dev/tessera/projections/rest/problem/TesseraProblemHandler.java` succeeds
    - `grep -q "application/problem+json" fabric-projections/src/test/java/dev/tessera/projections/rest/ErrorShapeProblemJsonIT.java` succeeds
    - `grep -q "forward-headers-strategy: framework" fabric-app/src/main/resources/application.yml` succeeds
    - `./mvnw -B verify` green
    - ArchUnit gates remain green (RawCypherBanTest, ProjectionsModuleDependencyTest)
  </acceptance_criteria>
  <done>
    Full REST security baseline landed. OAuth2 resource server validates Vault-loaded HMAC JWTs with rotation support. Every error is RFC 7807. Cross-tenant requests return identical 404s. TLS 1.3 + HSTS configured for both direct and reverse-proxy deployments. `/api/v1/admin/tokens/issue` operational for bootstrap flows.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| HTTPS caller → GenericEntityController | Untrusted JSON body; untrusted path segments |
| JWT → TenantContext | Tenant claim must match path segment or request is rejected |
| Vault → RotatableJwtDecoder | HMAC key travels over mTLS; rotation event refreshes in place |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-02-W2-01 | Spoofing | JWT forgery | mitigate | HMAC HS256 with Vault-held key, Nimbus validator, issuer check |
| T-02-W2-02 | Information Disclosure | Cross-tenant path leak | mitigate | Tenant claim ↔ path segment check → 404; CrossTenantLeakPropertyIT fuzz 500 pairs |
| T-02-W2-03 | Information Disclosure | Error body leaks tenant B data in tenant A response | mitigate | `ValidationReport.filteredFor(tenant)` before mapping to errors[]; ErrorShapeProblemJsonIT asserts |
| T-02-W2-04 | Tampering | Cypher/SQL injection via typeSlug | mitigate | SchemaRegistry exposure check rejects undeclared types BEFORE any Cypher; slug validated by Phase 1 regex |
| T-02-W2-05 | Information Disclosure | Enumeration via 403 vs 404 timing | mitigate | 404 deny-all uniform response (Decision 11); constant detail string |
| T-02-W2-06 | Tampering | XSS in ProblemDetail.detail | mitigate | Server-controlled strings only; never `ex.getMessage()` echo |
| T-02-W2-07 | Spoofing | JWT replay after rotation | accept | 15-min TTL accepted as rotation window per CONTEXT Decision 6 |
| T-02-W2-08 | Info Disclosure | HSTS silently disabled behind proxy | mitigate | `forward-headers-strategy: framework`; TlsHstsHeaderIT variant covers proxied path |
| T-02-W2-09 | Elevation of Privilege | Bootstrap token misuse | mitigate | Bootstrap token consumed via Vault-held header; rotates on first successful issuance |
| T-02-W2-10 | DoS | Unauthenticated /v3/api-docs reveal of schema | accept | OpenAPI doc is publicly readable by design for operator ergonomics; contains no tenant data (per-type schemas reveal only type names already exposed via REST path patterns); revisit if information-minimization becomes relevant |
</threat_model>

<verification>
`./mvnw -B verify` green. All seven new ITs in this wave green. ArchUnit green. RestProjectionBench p95 < 50ms at 100k nodes (warn-only). Phase 1 suites regression-free.
</verification>

<success_criteria>
- REST-01 dispatcher green (GenericEntityController + EntityDispatcher)
- REST-02 cursor pagination stable under concurrent writes (CursorPaginationConcurrencyIT)
- REST-03 SHACL validation enforced via graphService.apply
- REST-04 deny-all default (DenyAllExposureIT)
- REST-05 dynamic OpenAPI rebuild (DynamicOpenApiIT)
- REST-06 zero cross-tenant error leak (CrossTenantLeakPropertyIT + ErrorShapeProblemJsonIT)
- REST-07 tenant-level ACL enforced
- SEC-01 TLS 1.3 + HSTS (TlsHstsHeaderIT)
- SEC-02 Vault secret loads (JwtRotationIT)
- SEC-04 tenant row ACL (covered by REST-07)
- SEC-05 field ACL at tenant level only (deferred beyond tenant per CONTEXT)
- RestProjectionBench captures p95 < 50ms @ 100k nodes
</success_criteria>

<output>
After completion, create `.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W2-SUMMARY.md`.
</output>
