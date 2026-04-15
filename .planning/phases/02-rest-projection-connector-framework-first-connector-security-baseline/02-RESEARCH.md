---
gsd_artifact: research
phase: "02"
phase_name: "REST Projection, Connector Framework, First Connector, Security Baseline"
created: 2026-04-15
requirements: [REST-01, REST-02, REST-03, REST-04, REST-05, REST-06, REST-07, CONN-01, CONN-02, CONN-03, CONN-04, CONN-05, CONN-06, CONN-07, CONN-08, SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, SEC-06]
confidence: MEDIUM-HIGH
---

# Phase 2 — Research

**Researched:** 2026-04-15
**Domain:** REST projection + connector framework + security baseline (Spring Boot 3.5 / Java 21 / AGE 1.6)
**Confidence:** HIGH for Spring Security / problem+json / ShedLock / Testcontainers-Vault. MEDIUM for SpringDoc dynamic lifecycle and AGE cursor pagination (both Tessera-specific shapes with limited prior art).

## Summary

This phase's stack is already locked by `.planning/research/STACK.md` and `02-CONTEXT.md`. This document answers the **HOW** for ten specific implementation questions flagged during discuss-phase. No alternatives are re-explored; no library substitutions are recommended.

**Primary recommendation:** Build one dispatcher controller (`GenericEntityController`) that delegates to a `SchemaRegistryBackedService`, publish a `GroupedOpenApi` + `OpenApiCustomizer` that reads the Schema Registry and rebuilds path+schema entries on schema-version change, and wrap the entire connector module behind a thin SPI (`Connector#poll`) so Phase 2.5's unstructured-text mode can share the same write funnel.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions (verbatim summary)

1. **`model_id` = per-customer** — URL `/api/v1/{model}/…` is tenant-identifying.
2. **Field-level encryption OFF** — feature flag + startup guard that refuses boot if any schema declares `property_encrypted=true`. Columns `property_encrypted` + `property_encrypted_alg` ship in Flyway migration. No encryption machinery in Phase 2.
3. **Bearer token only** — connector auth. Credentials at `secret/tessera/connectors/{id}/bearer_token`. `auth_type` column future-proof but only `BEARER` accepted.
4. **Opaque cursor pagination** — `{ items, next_cursor }`, base64 of `(model_id, type_slug, last_sequence, last_node_id)`. `limit` default 50, cap 500. No `offset`.
5. **Schema Registry `rest_read_enabled` / `rest_write_enabled` columns** — default false; missing-flag = 404.
6. **OAuth2 resource server + Vault-held HMAC key** — Spring Security JWT; HMAC key at `secret/tessera/auth/jwt_signing_key`. `tenant` + `roles` claims. Bootstrap `/admin/tokens/issue`. TTL 15 min.
7. **DB-backed connectors table + `/admin/connectors` hot-reload** — `ConnectorRegistry` in-memory cache reacts to `ApplicationEventPublisher`. `credentials_ref` is a Vault path.
8. **RFC 7807 problem+json** — global `@ControllerAdvice`; extensions `tenant`, `code`, `errors[]`.
9. **Fixed-interval scheduling** — `poll_interval_seconds` only. ShedLock. Single dispatcher bean (no thread-per-connector).
10. **Tenant-only row ACLs** — no per-property / per-row predicates.
11. **404 deny-all** — undeclared type, disabled type, cross-tenant path, unknown instance all → 404. 403 reserved for role-missing.

### Claude's Discretion

- Admin path prefix (recommendation below: `/api/v1/admin/*` — single filter chain, consistent versioning)
- Mapping DSL shape (Q3)
- Vault auth method for Spring Cloud Vault (recommendation: AppRole)
- ETag/Last-Modified granularity (Q6)
- Sync-status surface shape (recommendation: embedded in list + dedicated `/admin/connectors/{id}/status` for polling dashboards)
- OpenAPI lifecycle hook (Q1)
- Bootstrap token mechanism (recommendation: one-shot `/admin/tokens/issue` protected by a static bootstrap token pulled from Vault `secret/tessera/auth/bootstrap_token` on first run)

### Deferred Ideas (OUT OF SCOPE)

- GraphQL, row/property ACLs, full field-level encryption, OAuth2 client-credentials / Basic / API-key connector auth, cron scheduling, full OIDC, write-back connectors, bidirectional circlead.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| REST-01 | `GenericEntityController` via runtime schema lookup | Q1 (OpenAPI lifecycle), dispatcher pattern below |
| REST-02 | Filtering + cursor pagination + projection | Q7 (AGE cursor pagination) |
| REST-03 | Schema-validated POST/PUT bodies | Reuses Phase 1 Jena SHACL validator through `GraphService.apply` |
| REST-04 | Fail-closed (deny-all default) | CONTEXT Decision 5 — `rest_read_enabled` flag |
| REST-05 | SpringDoc dynamic doc | Q1 |
| REST-06 | No cross-tenant leak in errors | Q5 (RFC 7807 + `@ControllerAdvice`) |
| REST-07 | Row/field ACL | CONTEXT Decision 10 — tenant-only for Phase 2 |
| CONN-01 | `Connector` SPI | Q2 |
| CONN-02 | `MappingDefinition` JSON | Q3 |
| CONN-03 | `ConnectorRegistry` + `@Scheduled` + ShedLock | Q9 |
| CONN-04 | Retry + DLQ | Spring Retry + `connector_dlq` table pattern |
| CONN-05 | `_last_sync_at` + `_source_hash` | Q6 (ETag/delta) |
| CONN-06 | Sync status surface | CONTEXT discretion — recommendation below |
| CONN-07 | Generic REST poller | Q6 + JDK 21 `HttpClient` (STACK.md) |
| CONN-08 | Read-only | No write-back code path |
| SEC-01 | TLS 1.3 + HSTS | Q8 |
| SEC-02 | Vault via Config Data API | Q4 + Q10 |
| SEC-03 | TDE at rest (LUKS on IONOS) | Operational / infra — document in deployment runbook; no code |
| SEC-04 | Row ACL (tenant-only) | CONTEXT Decision 10 |
| SEC-05 | Field ACL | Deferred beyond tenant-level for Phase 2 |
| SEC-06 | Encryption gate | CONTEXT Decision 2 — startup guard only |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

- Java 21 + Maven multi-module (`fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`)
- ArchUnit `RawCypherBanTest` — connector module MUST NOT touch `graph.internal` or execute raw Cypher
- All writes through `GraphService.apply(GraphMutation, TenantContext)` — Phase 1 single write funnel
- Flyway plain-SQL migrations, Phase 2 starts at V10
- GSD workflow enforcement: changes go through `/gsd-execute-phase`

---

## Q1 — SpringDoc dynamic controller registration lifecycle

**Problem:** A single `@RequestMapping("/api/v1/{model}/entities/{typeSlug}")` dispatcher handles all types. SpringDoc 2.8.x at startup sees ONE method returning `Object` / `Map<String,Object>`, not per-type schemas. `/v3/api-docs` therefore shows a generic stub, not the per-type REST contract REQ REST-05 demands.

**Recommendation:** Do NOT try to register per-type `@Controller` beans dynamically. Instead, keep ONE dispatcher controller and inject schema-awareness into SpringDoc via an `OpenApiCustomizer` bean that reads the Schema Registry at the moment SpringDoc builds the doc.

**Mechanism:**

1. Single dispatcher: `GenericEntityController` with methods `list(model, typeSlug, …)`, `get(…)`, `create(…)`, `update(…)`, `delete(…)`. Each returns `ResponseEntity<JsonNode>` or a generic `EntityDto`.
2. Register one `GroupedOpenApi` per top-level group (e.g. `entities`).
3. Inject an `OpenApiCustomizer` that, for each `(model_id, typeSlug)` where `rest_read_enabled=true`:
   - Adds a `PathItem` under `/api/v1/{model}/entities/{typeSlug}` with the resolved model name (or uses path-item-level parameters if you want a single path with documented `{typeSlug}` enum).
   - Adds the request/response schemas derived from `schema_properties` (SpringDoc exposes `io.swagger.v3.oas.models.media.Schema`).
4. Cache invalidation: Springdoc caches the OpenAPI document per group. Use `springdoc.cache.disabled=true` in dev, and in prod bump a cache key by incrementing a schema-version counter — Springdoc 2.8.x exposes `SpringDocConfigProperties#getCache` and the doc is regenerated when group composition changes. The simplest working pattern is to **override `OpenApiResource` bean** and make it re-run the customizer when a schema-version atomic counter has advanced.

**Minimal skeleton (verified pattern from springdoc-openapi 2.6+; 2.8.x identical API surface — CITED: springdoc-openapi reference docs):**

```java
@Bean
public GroupedOpenApi entitiesApi(SchemaRegistry registry) {
    return GroupedOpenApi.builder()
        .group("entities")
        .pathsToMatch("/api/v1/*/entities/**")
        .addOpenApiCustomizer(openApi -> {
            Paths paths = openApi.getPaths() != null ? openApi.getPaths() : new Paths();
            for (ModelId model : registry.allModels()) {
                for (NodeTypeDescriptor type : registry.exposedTypes(model)) {
                    String path = "/api/v1/" + model.value() + "/entities/" + type.slug();
                    PathItem item = buildPathItem(type); // GET/POST/PUT/DELETE ops
                    paths.addPathItem(path, item);
                    openApi.schema(type.slug() + "Entity", buildSchema(type));
                    openApi.schema(type.slug() + "EntityList", buildListSchema(type));
                }
            }
            openApi.setPaths(paths);
        })
        .build();
}
```

**Lifecycle gotcha:** SpringDoc builds the OpenAPI document **on first GET /v3/api-docs**, not at startup. So the customizer runs lazily — schema mutations that happen BEFORE the first doc fetch are already reflected. For mutations AFTER the first fetch: invalidate by listening to your Phase 1 schema-version event and calling `springDocProviders.getWebMvcProvider().getActualGroups()` then forcing a rebuild. Simpler: set `springdoc.cache.disabled=true` globally and accept a ~50 ms re-walk per `/v3/api-docs` hit. REQ REST-05 does not require sub-millisecond doc latency.

**Pitfall — Springdoc introspection timing:** Springdoc scans `@RestController` beans during `onApplicationEvent(ContextRefreshedEvent)`. The `OpenApiCustomizer` approach above sidesteps that scan entirely — we don't rely on controller introspection, we rely on the customizer running at doc-build time. This is why this pattern is robust against runtime schema changes.

**Pitfall — schema definition collisions:** Two tenants declaring a type named `Person` with different property sets would collide on the schema name `PersonEntity`. Namespace by model: `{model}_{slug}Entity`.

**Sources:**
- `[CITED: https://springdoc.org/]` — `OpenApiCustomizer` and `GroupedOpenApi` builder pattern (Springdoc 2.x docs, applies to 2.8.x line).
- `[ASSUMED]` — lazy-build timing of `/v3/api-docs` — inferred from springdoc-openapi source behavior through 2.7.x; 2.8.x behavior is unchanged in the stable line but has not been re-verified in this session against 2.8.6 release notes. **Verification task for Wave 0:** spike `SchemaVersionBumpIT` that mutates the Schema Registry at runtime and asserts `/v3/api-docs` reflects the new type within one hit.

**Confidence:** MEDIUM. The `OpenApiCustomizer` pattern is HIGH confidence; the runtime invalidation semantics are MEDIUM — needs a Wave 0 spike before Wave 1 commits to this architecture.

---

## Q2 — Connector SPI shape

**Recommendation:**

```java
public interface Connector {
    /** Stable identifier of the source system type (e.g. "rest-poll", "csv-folder"). */
    String type();

    /** Static capabilities: supports delta detection? webhook? bulk? */
    ConnectorCapabilities capabilities();

    /**
     * Pull one batch from the source and return candidate mutations.
     * Called on the scheduler thread under a ShedLock.
     *
     * - clock: mutation timestamps come from here (CORE-08: Tessera-owned clocks).
     * - mapping: per-instance mapping definition, parsed once by ConnectorRegistry.
     * - state: cursor / ETag / last-modified from the previous call. Opaque to the runner.
     *
     * Returns a pollResult containing: the candidates, the next state to persist, and
     * any terminal error that should mark the sync as failed.
     */
    PollResult poll(Clock clock,
                    MappingDefinition mapping,
                    ConnectorState state,
                    TenantContext tenant);

    /** Optional webhook entry point. MVP: unsupported (CONN-01 keeps the hook for parity). */
    default PollResult onWebhook(byte[] payload, Map<String,String> headers,
                                 MappingDefinition mapping, TenantContext tenant) {
        throw new UnsupportedOperationException();
    }
}

public record PollResult(
    List<CandidateMutation> candidates,    // flow through GraphService.apply
    ConnectorState nextState,              // persisted by ConnectorRunner
    SyncOutcome outcome,                   // SUCCESS / PARTIAL / FAILED
    List<DlqEntry> dlq                     // rows that failed mapping, not connection
) {}

public record ConnectorState(String cursor, String etag, Instant lastModified,
                             long lastSequence) {}
```

**Why this shape:**

- `poll` takes a `Clock` — satisfies CORE-08 (source clocks are untrusted) without the connector needing to know it.
- `ConnectorState` is the Phase 2 equivalent of Kafka Connect's `offsets` map — opaque to the runner, typed by the connector. Serialized to JSONB in `connectors` or `connector_sync_status`.
- `CandidateMutation` — **reuse Phase 1's existing type**. Phase 2.5 (EXTR-01) requires that structured and unstructured connectors converge on the same shape; that commitment is already in the Rule Engine contract (ADR-7). Connector module must not define a parallel type.
- `poll` is synchronous and blocking. Virtual threads (Java 21, `spring.threads.virtual.enabled=true`) handle the concurrency; a reactive `Mono<PollResult>` is premature (STACK.md explicitly rejects WebFlux).
- `DlqEntry` is separate from `SyncOutcome`: a mapping failure on row 17 does NOT fail the whole sync — row 17 goes to DLQ, the other rows commit. This is the Kafka Connect dead-letter pattern.

**Prior art borrowed from:**

| System | Concept adopted |
|---|---|
| Kafka Connect | Opaque offsets (`ConnectorState`), dead-letter queue, source task vs. sink task separation |
| Airbyte | `CandidateMutation` as a normalized record type (equivalent of Airbyte's `AirbyteMessage`) |
| Debezium | "Connector is stateful about its source, stateless about its sink" — runner owns persistence |

**Explicitly NOT borrowed:**
- Kafka Connect's `SourceTask#poll` returning `List<SourceRecord>` directly — Tessera needs richer metadata for `CandidateMutation` and partial-failure DLQ.
- Airbyte's state JSON blob shape — too loose for a typed Java connector.

**Runner responsibilities (separate from the `Connector` interface):**

```java
@Component
public class ConnectorRunner {
    // Called by the @Scheduled bean; wraps one poll cycle.
    void runOnce(ConnectorInstance instance) {
        try (var lock = shedLock.acquire("connector-" + instance.id(), instance.intervalSeconds())) {
            if (!lock.isAcquired()) return;
            PollResult result = instance.connector().poll(clock, instance.mapping(),
                instance.state(), instance.tenant());
            for (CandidateMutation c : result.candidates()) {
                graphService.apply(c.toMutation(), instance.tenant()); // reuse Phase 1 funnel
            }
            writeDlq(result.dlq());
            updateSyncStatus(instance, result.outcome(), result.nextState());
        }
    }
}
```

**Pitfall:** The connector MUST NOT call `graphService.apply` directly — that's the runner's job. ArchUnit test: `classes().that().implement(Connector.class).should().notDependOn(GraphService.class)`. This separation makes the connector unit-testable without Spring.

**Pitfall:** `ConnectorState` serialization needs to survive a Jackson round-trip. Prefer a typed `record` with `@JsonProperty` over a `Map<String,Object>` for clarity and migration safety.

**Confidence:** HIGH for the shape. MEDIUM for the exact location of DLQ writes (could be in the runner or dispatched through `GraphService.apply` as a failed-mutation event — see CONN-04; planner to decide).

**Sources:**
- `[CITED: Kafka Connect SourceTask docs]` (training-data knowledge of Kafka Connect 3.x API).
- `[ASSUMED]` — Airbyte message normalization specifics — general architectural pattern, no specific version verified in this session.

---

## Q3 — Mapping DSL for `mapping_def` JSONB

**Recommendation: JSONPath (Jayway)** — `com.jayway.jsonpath:json-path:2.9.0`.

**Why not the others:**

| DSL | Java maturity | Expressivity | Verdict |
|---|---|---|---|
| **JSONPath (Jayway)** | Mature (2.9.0, 2024; used by Spring Boot, Rest-Assured, WireMock which are already on Tessera's classpath) | Sufficient for nested + array selection | **Pick this** |
| JMESPath | `io.burt:jmespath-jackson` works; niche in Java ecosystem | Slightly cleaner array transforms | Skip — adds a dep for marginal gain |
| JSONata | Only `com.dashjoin:jsonata` JVM port exists; maintained but small community | Most expressive (full functional language) | Too much surface area for Phase 2 scope |
| Custom DSL | Zero deps | Whatever you build | Violates "don't hand-roll" — you'd reinvent path traversal |

**Bonus argument for JSONPath:** Jayway is already a transitive dep of `rest-assured` 5.5.x (Tessera test scope per STACK.md). Moving it to main scope is a single `<scope>compile</scope>` flip.

**`MappingDefinition` record shape:**

```java
public record MappingDefinition(
    String sourceEntityType,          // logical name in the source system
    String targetNodeTypeSlug,        // Tessera Schema Registry slug
    String rootPath,                  // JSONPath to iterate rows: "$.data.items[*]"
    List<FieldMapping> fields,
    List<String> identityFields,      // Tessera node identity fields
    String syncStrategy,              // "poll" only in Phase 2
    int pollIntervalSeconds,
    String reconciliationChain        // default "AUTHORITY"
) {}

public record FieldMapping(
    String target,                    // Tessera property name
    String sourcePath,                // JSONPath: "$.attributes.email"
    @Nullable String transform,       // simple registry: "lowercase", "iso8601-date", "parse-int"
    boolean required
) {}
```

**Transforms as a closed registry, not a DSL:** Resist the urge to put expression language inside `FieldMapping.transform`. MVP is a finite enum of built-ins (`lowercase`, `uppercase`, `trim`, `iso8601-date`, `parse-int`, `parse-decimal`). Anything more complex belongs in a custom connector type, not a data-driven mapping. This is the single biggest maintainability lever — CDC/ETL tools that allowed Turing-complete transforms in config (Talend, Nifi) become unmaintainable.

**Parsing:**

```java
DocumentContext ctx = JsonPath.using(
    Configuration.builder().mappingProvider(new JacksonMappingProvider()).build()
).parse(responseBody);
for (Object row : ctx.read(mapping.rootPath(), List.class)) {
    DocumentContext rowCtx = JsonPath.parse(row);
    for (FieldMapping fm : mapping.fields()) {
        Object raw = rowCtx.read(fm.sourcePath());
        Object transformed = TransformRegistry.apply(fm.transform(), raw);
        // collect into CandidateMutation properties
    }
}
```

**Pitfall:** JSONPath array operators (`[*]`, `[?(@.x==1)]`) return `List` via Jackson provider — if you accidentally use the default provider you get `JSONArray` from `net.minidev`, which is not JSON-serializable cleanly. **Always configure `JacksonMappingProvider`**.

**Pitfall:** Missing paths return `null` by default with Jayway — pair with `required=true` in `FieldMapping` to enforce "no silent drops".

**Confidence:** HIGH for JSONPath choice. HIGH for the transform-registry-not-DSL recommendation.

**Sources:**
- `[CITED: https://github.com/json-path/JsonPath]` — Jayway JsonPath readme, 2.9.0 is current stable line.
- `[ASSUMED]` — JSONata JVM port maturity — general ecosystem knowledge, not verified this session.

---

## Q4 — OAuth2 resource server + Vault-held HMAC signing key

**Recommendation:** Load the HMAC key from Vault via Spring Cloud Vault Config Data API into a `@ConfigurationProperties`-bound secret, then build a singleton `NimbusJwtDecoder.withSecretKey(...)` bean that is reloadable on rotation.

**Dependencies (already in STACK.md):**
- `spring-boot-starter-oauth2-resource-server` (bring in Nimbus JOSE transitively)
- `spring-cloud-starter-vault-config` (4.2.x, Spring Cloud 2024.0.x train)

**`application.yml`:**

```yaml
spring:
  config:
    import: "vault://secret/tessera/auth"    # Config Data API, not bootstrap
  cloud:
    vault:
      uri: ${VAULT_ADDR}
      authentication: APPROLE
      app-role:
        role-id: ${VAULT_ROLE_ID}
        secret-id: ${VAULT_SECRET_ID}
      kv:
        enabled: true
        backend: secret
        default-context: tessera/auth
  security:
    oauth2:
      resourceserver:
        jwt:
          # algorithm is advisory; we build the decoder explicitly below
          jws-algorithms: HS256
```

Vault path `secret/tessera/auth/jwt_signing_key` holds `{ "hmac_key_b64": "..." , "key_version": 3 }`.

**Decoder wiring:**

```java
@ConfigurationProperties(prefix = "tessera.auth")
public record TesseraAuthProperties(String hmacKeyB64, int keyVersion) {}

@Bean
public JwtDecoder jwtDecoder(TesseraAuthProperties props) {
    byte[] raw = Base64.getDecoder().decode(props.hmacKeyB64());
    SecretKey key = new SecretKeySpec(raw, "HmacSHA256");
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("tessera"));
    return decoder;
}

@Bean
public JwtAuthenticationConverter jwtAuthConverter() {
    var grantedConverter = new JwtGrantedAuthoritiesConverter();
    grantedConverter.setAuthoritiesClaimName("roles");
    grantedConverter.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(grantedConverter);
    converter.setPrincipalClaimName("tenant");       // principal == tenant claim
    return converter;
}
```

**Rotation:** Spring Cloud Vault 4.x supports lease-aware refresh via `@RefreshScope` on beans that depend on Vault properties, BUT `JwtDecoder` is not safe to put in `@RefreshScope` directly (filter chain holds a hard reference). Pattern:

```java
@Component
public class RotatableJwtDecoder implements JwtDecoder {
    private final AtomicReference<NimbusJwtDecoder> current;

    public RotatableJwtDecoder(TesseraAuthProperties props) {
        this.current = new AtomicReference<>(build(props));
    }

    public Jwt decode(String token) { return current.get().decode(token); }

    @EventListener(RefreshScopeRefreshedEvent.class)
    public void onRefresh(RefreshScopeRefreshedEvent ev,
                          TesseraAuthProperties fresh) {
        this.current.set(build(fresh));
    }
}
```

**Rotation gotcha — the window:** An HMAC key change instantly invalidates every outstanding JWT. For a 15-min TTL this is acceptable (worst case: every client retries once). If you ever need zero-downtime rotation, hold `old` and `new` keys, try `new` first, fall back to `old` for tokens issued within the TTL. **Out of scope for Phase 2** per CONTEXT — noted here so Wave plan explicitly skips it.

**Bootstrap token signing (`/admin/tokens/issue`):**

```java
JWSSigner signer = new MACSigner(rawKeyBytes);
JWTClaimsSet claims = new JWTClaimsSet.Builder()
    .issuer("tessera")
    .subject(operatorId)
    .claim("tenant", modelId)
    .claim("roles", List.of("admin", "operator"))
    .expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
    .build();
SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
jwt.sign(signer);
return jwt.serialize();
```

Nimbus JOSE ships transitively with `spring-boot-starter-oauth2-resource-server` — no extra dep.

**Pitfall — `bootstrap.yml` vs Config Data API:** STACK.md already flags this. Use `spring.config.import=vault://...`, NOT `bootstrap.yml`. The Config Data API is the Spring Cloud 2024.0.x-supported path.

**Pitfall — Vault AppRole creds provisioning:** `role-id` and `secret-id` themselves have to come from somewhere. Convention: `VAULT_ROLE_ID` env var (safe), `VAULT_SECRET_ID` via Docker secret mount or one-shot init. Never bake into the image.

**Confidence:** HIGH for the Spring Security + Nimbus wiring (standard resource-server pattern). MEDIUM for the `RotatableJwtDecoder` pattern — it is idiomatic but not a framework-provided type.

**Sources:**
- `[CITED: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html]` — `NimbusJwtDecoder.withSecretKey` is the official HMAC path.
- `[CITED: https://docs.spring.io/spring-cloud-vault/docs/current/reference/html/]` — Config Data API replaces bootstrap.
- `[ASSUMED]` — exact behavior of `RefreshScopeRefreshedEvent` on non-refresh-scope beans — pattern is idiomatic but specifics should be confirmed with a spike.

---

## Q5 — RFC 7807 in Spring Boot 3.5

**Recommendation:** Use Spring 6's built-in `ProblemDetail` + `ErrorResponseException` and a thin `@RestControllerAdvice` that maps Tessera-specific exceptions. No external library (Zalando `problem-spring-web` is NOT needed on Spring Boot 3.x).

**Enable at framework level:**

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true      # returns application/problem+json from Spring's default exception handler
```

This alone turns Spring's `ResponseEntityExceptionHandler` into a problem+json emitter for `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `NoHandlerFoundException`, etc. — free RFC 7807 for the boring cases.

**Tessera extension fields:** Spring 6's `ProblemDetail` has `setProperty(String, Object)` for extensions.

**Global advice skeleton:**

```java
@RestControllerAdvice
public class TesseraProblemHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ExceptionHandler(TypeNotExposedException.class)
    @ExceptionHandler(CrossTenantException.class)
    public ProblemDetail handleNotFound(RuntimeException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(URI.create("https://tessera.dev/problems/not-found"));
        pd.setTitle("Not Found");
        pd.setDetail("Resource not found.");   // NEVER echo input — 404 deny-all leaks nothing
        pd.setProperty("code", "TESSERA_NOT_FOUND");
        // DO NOT set "tenant" here — cross-tenant path = no tenant context leak
        return pd;
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidation(ValidationException ex,
                                          @AuthenticationPrincipal Jwt jwt) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(URI.create("https://tessera.dev/problems/validation"));
        pd.setTitle("Validation Failed");
        pd.setDetail("One or more properties failed schema validation.");
        pd.setProperty("code", "TESSERA_VALIDATION_FAILED");
        pd.setProperty("tenant", jwt.getClaim("tenant"));
        pd.setProperty("errors", ex.fieldErrors().stream()
            .map(e -> Map.of("property", e.property(), "message", e.message()))
            .toList());
        return pd;
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex, @AuthenticationPrincipal Jwt jwt) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Forbidden");
        pd.setDetail("Role required.");
        pd.setProperty("code", "TESSERA_ROLE_MISSING");
        pd.setProperty("tenant", jwt.getClaim("tenant"));
        return pd;
    }
}
```

**SpringDoc integration:** SpringDoc 2.8.x auto-documents `ProblemDetail` response schemas when `spring.mvc.problemdetails.enabled=true`. Add a global response annotation once:

```java
@Bean
public OpenApiCustomizer problemDetailCustomizer() {
    return openApi -> openApi.getPaths().values().forEach(pathItem ->
        pathItem.readOperations().forEach(op -> {
            op.getResponses().addApiResponse("400",
                new ApiResponse().description("Bad Request").content(problemJsonContent()));
            op.getResponses().addApiResponse("404",
                new ApiResponse().description("Not Found").content(problemJsonContent()));
            op.getResponses().addApiResponse("422",
                new ApiResponse().description("Validation Failed").content(problemJsonContent()));
        }));
}
```

**Pitfall — XSS hygiene:** Never put caller input in `detail`. The handler above hardcodes human strings. All per-row data goes into `errors[].property` / `errors[].message` which are produced by server code, not echoed from request bodies.

**Pitfall — tenant leak in `ValidationReport`:** VALID-04 already enforces tenant-filtering inside the report. The advice must call `ValidationReport.filteredFor(tenant)` before mapping to `errors[]`. This is a Phase 1 helper that must be reused — do not re-implement filtering in the advice.

**Pitfall — `ResponseEntityExceptionHandler`'s default handlers:** Some return `ResponseEntity<Object>` not `ProblemDetail`. Override `handleExceptionInternal` to upgrade them uniformly.

**Confidence:** HIGH. This is the standard Spring 6 pattern.

**Sources:**
- `[CITED: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html]` — `ProblemDetail` official docs.
- `[CITED: https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-web-applications.spring-mvc.error-handling]` — `spring.mvc.problemdetails.enabled` flag.

---

## Q6 — ETag / Last-Modified detection for REST poller

**Recommendation:**

- **Per-connector high-water mark:** store ETag and Last-Modified in `connector_sync_status` (one row per connector instance), keyed on `(connector_id, model_id)`. These go into the NEXT request as `If-None-Match` / `If-Modified-Since`. 304 → no-op.
- **Per-row `_source_hash`:** compute `SHA-256(canonicalized source row JSON)` and store on the node as a system property. On the next poll, skip rows whose hash matches — this catches servers that don't implement ETag at all, which is most SaaS APIs in the wild.

**Why both layers:**

| Layer | Catches | Cost |
|---|---|---|
| Connector-level ETag/LM | Full-list cache hit (server says "nothing changed at all") | One header roundtrip, O(1) |
| Per-row `_source_hash` | Individual row unchanged among changed list | O(n) hash compute, O(1) skip |

The per-row hash is non-negotiable because `Last-Modified` on a list endpoint typically tracks "any row changed", so a one-row change forces a full-list fetch — without the per-row hash you'd re-apply `N−1` unchanged rows and burn circuit-breaker budget (RULE-07).

**JDK 21 `HttpClient` conditional request:**

```java
HttpClient client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .connectTimeout(Duration.ofSeconds(10))
    .build();

HttpRequest.Builder b = HttpRequest.newBuilder()
    .uri(URI.create(mapping.url()))
    .timeout(Duration.ofSeconds(30))
    .header("Authorization", "Bearer " + bearerToken)
    .header("Accept", "application/json");

if (state.etag() != null) b.header("If-None-Match", state.etag());
if (state.lastModified() != null)
    b.header("If-Modified-Since",
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            state.lastModified().atZone(ZoneOffset.UTC)));

HttpResponse<String> resp = client.send(b.GET().build(), BodyHandlers.ofString());

if (resp.statusCode() == 304) {
    return PollResult.unchanged(state);  // no candidates, keep state
}
String newEtag = resp.headers().firstValue("ETag").orElse(null);
Instant newLm = resp.headers().firstValue("Last-Modified")
    .map(s -> ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
    .orElse(null);
```

**Pitfall — weak vs strong ETags:** `W/"abc"` is a weak ETag (`W/` prefix). Send it back verbatim in `If-None-Match`; the server decides equivalence. Do NOT strip `W/`.

**Pitfall — `_source_hash` canonicalization:** `ObjectMapper` by default does not produce canonical JSON. Either sort keys via `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` and a sorted `LinkedHashMap`, or hash a stable path-value tuple list. The latter is easier and avoids Jackson-version drift: `sha256("\n".join(sorted(path + "=" + value for each mapped field)))`.

**Pitfall — clock skew on `Last-Modified`:** Source clocks are untrusted (CORE-08). Store `Last-Modified` as exactly what the server sent (for the next `If-Modified-Since` roundtrip), but NEVER use it as the Tessera event time.

**Confidence:** HIGH. This is a well-trodden pattern.

**Sources:**
- `[CITED: RFC 9110 §13.1.1-2]` — conditional requests spec. HIGH.
- `[ASSUMED]` — Jackson canonical JSON behavior — verified generally but specifics of `ORDER_MAP_ENTRIES_BY_KEYS` should be spiked.

---

## Q7 — Apache AGE cursor pagination under concurrent writes

**Problem:** Cypher's `SKIP n LIMIT m` is unstable under concurrent writes (new rows shift the window) and AGE compiles `SKIP` to a Postgres `OFFSET`, which is O(n) in the skipped rows. Either way is wrong.

**Recommendation:** Seek-method pagination using the per-tenant `SEQUENCE` allocator Phase 1 already built (W1-PLAN).

**Cursor encoding (opaque to client):**

```
base64( {
    "model": "acme",
    "type":  "person",
    "lastSeq": 147293,
    "lastNodeId": "..."    // tie-breaker if two nodes share a sequence (shouldn't happen, but defensive)
})
```

Cursor is signed with the same HMAC key as JWTs (small HMAC suffix) — prevents trivial tampering. Not encrypted; nothing secret inside.

**Cypher query (stable under concurrent writes):**

```cypher
SELECT * FROM cypher('tessera_main', $$
    MATCH (n:Person)
    WHERE n.model_id = $model
      AND n._sequence > $lastSeq
    RETURN n
    ORDER BY n._sequence ASC
    LIMIT $limit
$$, $params) AS (n agtype);
```

**Why this is stable:** `_sequence` is allocated by a Postgres `SEQUENCE` in the same transaction as the node create (Phase 1 W1). Sequences are strictly monotonic per tenant (EVENT-02 — NEVER `MAX()+1`). A new write AFTER the page was computed gets a higher `_sequence` and lands OUTSIDE the current page — no shifting.

**Index requirement:** AGE label tables (`tessera_main_person`) must have a composite index on `(model_id, _sequence)`. This is NOT automatic — Phase 1's label tables may only have the PK and `model_id`. **Wave 0 gap for Phase 2:** add Flyway V10 migration to create `CREATE INDEX … ON tessera_main."Person"(model_id, _sequence)` for every exposed type. Since types are registered at runtime, this probably has to be done in a post-`create_graph` hook that the Schema Registry's "declare type" flow runs.

**Performance baseline:** Reference `WritePipelineBench` (Phase 1 W1 baseline) for read-side comparisons. Seek-method should be O(log n) on the index, independent of page position. A dedicated `CursorPaginationBench` should land in Wave 0 to prove this at 100k / 1M dataset sizes.

**AGE-specific gotchas:**

1. **Parameter binding with agtype:** AGE's `cypher()` function takes parameters as a single `agtype` argument. For numeric `$lastSeq`, you must JSON-encode: `cypher('g', $$...$$, $1::agtype)` where `$1 = '{"model": "acme", "lastSeq": 147293, "limit": 50}'`. Use `JdbcTemplate` parameter binding for the jsonb, then AGE parses internally.
2. **Result extraction:** `ResultSet#getString(1)` returns the node as agtype JSON-ish. Parse with a tiny `AgtypeParser` helper (< 50 lines; Phase 1 should already have one in `graph.internal`).
3. **`ORDER BY` compilation:** AGE 1.6 compiles Cypher `ORDER BY` to Postgres `ORDER BY`. With the composite index, this is a straight index scan. Without it, it's a sort — slow.

**Pitfall — hidden `_sequence` property:** If Phase 1 stores `_sequence` only in the event log and NOT on the node itself, the query above has nothing to filter on. **Verification task for Wave 0:** grep Phase 1 W1-PLAN for `_sequence` — if only on events, add a Flyway migration to denormalize it onto nodes OR switch the cursor to use `_created_at` (monotonic-ish but not strict). Strongly prefer the former.

**Confidence:** MEDIUM. The seek-method pattern is HIGH confidence. AGE-specific parameter-binding nuances are MEDIUM (well-documented but tricky in practice). The assumption that Phase 1 puts `_sequence` on the node is a **research flag** the planner must verify before committing.

**Sources:**
- `[CITED: https://use-the-index-luke.com/no-offset]` — canonical reference for seek-method pagination. HIGH.
- `[ASSUMED]` — AGE 1.6's index-use characteristics on label tables — inferred from Postgres planner behavior; not benchmarked in this session.

---

## Q8 — TLS 1.3 + HSTS minimal config

**Recommendation:** Config-only. No custom code.

**Embedded Tomcat TLS 1.3 via `application.yml`:**

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: ${TESSERA_TLS_KEYSTORE}       # path, e.g. /run/secrets/tls.p12
    key-store-password: ${TESSERA_TLS_KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    enabled-protocols: TLSv1.3
    protocol: TLS
```

**Production:** terminate TLS at a reverse proxy (Caddy / nginx on the IONOS VPS) with Let's Encrypt, and let Spring Boot serve plaintext HTTP on the loopback interface. That's the standard IONOS pattern and avoids keystore rotation in the JVM. The config above is the fallback / dev / one-box deployment story.

**HSTS via Spring Security:** Spring Security turns HSTS on by default when HTTPS is enabled. Explicit config only if you want `includeSubDomains` or `preload`:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)       // 1 year
                    .preload(true)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/admin/**").hasRole("admin")
                .anyRequest().authenticated());
        return http.build();
    }
}
```

**Pitfall — HSTS on non-TLS:** Spring Security only emits the `Strict-Transport-Security` header on requests marked secure. If you're proxying from Caddy→Tomcat on plaintext, you must propagate via `X-Forwarded-Proto` and configure `server.forward-headers-strategy=framework` so Spring trusts it. Otherwise HSTS is silently suppressed in the proxied deployment.

**Pitfall — HSTS preload:** `preload(true)` commits to the browser preload list. Do NOT enable until you're sure every subdomain is TLS-only forever. For MVP, recommend `preload(false)`.

**Confidence:** HIGH.

**Sources:**
- `[CITED: https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html]` — `HttpStrictTransportSecurityHeaderWriter`.
- `[CITED: https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.webserver.configure-ssl]` — Tomcat TLS config.

---

## Q9 — ShedLock dynamic scheduling per connector

**Problem:** `@Scheduled(fixedDelayString="${tessera.connectors.pollInterval}")` is STATIC — one annotation, one interval. Tessera needs N connectors at N intervals with per-connector locks.

**Recommendation:** A single central scheduler bean running every 1 second, dispatching to connectors whose `poll_interval_seconds` has elapsed since their last poll, each under a per-`connector_id` ShedLock. **NO thread-per-connector.**

**Can ShedLock scope locks per `connector_id`?** Yes — ShedLock's programmatic API (`LockProvider` + `DefaultLockingTaskExecutor`) supports per-invocation lock names. The `@SchedulerLock` annotation is annotation-time static, but you don't use it; you use `LockingTaskExecutor` directly.

**Dependencies:** `net.javacrumbs.shedlock:shedlock-spring` + `net.javacrumbs.shedlock:shedlock-provider-jdbc-template` (both 5.16.0+, STACK.md confirms).

**Flyway migration V10:** ShedLock table (standard schema from ShedLock docs).

**Scheduler bean:**

```java
@Component
@RequiredArgsConstructor
public class ConnectorScheduler {

    private final ConnectorRegistry registry;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final ConnectorRunner runner;
    private final Clock clock;

    @Scheduled(fixedDelay = 1000L)   // tick every second
    public void tick() {
        Instant now = clock.instant();
        for (ConnectorInstance instance : registry.dueAt(now)) {
            String lockName = "connector-" + instance.id();
            Duration atMostFor = Duration.ofSeconds(instance.pollIntervalSeconds() * 3L);
            Duration atLeastFor = Duration.ofMillis(100);
            lockingTaskExecutor.executeWithLock(
                (Runnable) () -> runner.runOnce(instance),
                new LockConfiguration(now, lockName, atMostFor, atLeastFor));
        }
    }
}
```

**`ConnectorRegistry#dueAt`:** reads in-memory `Map<ConnectorId, ConnectorInstance>` (hot-reloaded on `ApplicationEventPublisher` events from the `/admin/connectors` endpoints), returns connectors whose `lastPollAt + pollIntervalSeconds <= now`.

**Virtual-thread dispatch:** Spring Boot 3.5 + `spring.threads.virtual.enabled=true` runs `@Scheduled` methods on the platform scheduler but `executeWithLock`'s `Runnable` can be dispatched into a virtual-thread executor to avoid blocking the 1-sec tick on slow connectors:

```java
private final ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();

vt.submit(() -> lockingTaskExecutor.executeWithLock(...));
```

**Pitfall — `atMostFor` sizing:** If a connector hangs, ShedLock releases after `atMostFor`. Set to `3× pollInterval` so a slow server doesn't cause the lock to expire mid-poll and two runners to double-fetch.

**Pitfall — `atLeastFor`:** Protects against fast tick loops "stealing" a lock that just released. Keep ≥ 100ms.

**Pitfall — overdue backlog:** If Tessera was down for an hour and 60 pollIntervals elapsed, `dueAt` should return each connector ONCE, not 60 times. Mark `nextPollAt = now + interval`, not `lastPollAt + k*interval`.

**Pitfall — `connector_sync_status` row locking:** `runner.runOnce` updates `connector_sync_status` in a separate transaction from the graph mutation (don't want sync-status bookkeeping in the same TX as the write funnel). ShedLock serializes concurrent runs, so no row lock is needed beyond the ShedLock itself.

**Confidence:** HIGH. This is standard ShedLock usage.

**Sources:**
- `[CITED: https://github.com/lukas-krecan/ShedLock#usage-without-the-annotation]` — programmatic `LockingTaskExecutor`. HIGH.

---

## Q10 — Testcontainers Vault for integration tests

**Recommendation:** `hashicorp/vault:1.15` (stable, widely used) via Testcontainers' official `VaultContainer` from `org.testcontainers:vault`.

**Dependency:**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>vault</artifactId>
    <scope>test</scope>
</dependency>
```

Version managed by testcontainers-bom 1.20.4 (already in STACK.md parent POM).

**Test harness:**

```java
@Testcontainers
class VaultIntegrationIT {

    @Container
    static final VaultContainer<?> vault = new VaultContainer<>(
            DockerImageName.parse("hashicorp/vault:1.15"))
        .withVaultToken("tessera-root-token-it")
        .withInitCommand(
            "secrets enable -path=secret -version=2 kv",
            "kv put secret/tessera/auth hmac_key_b64=" + base64(testKey) + " key_version=1",
            "kv put secret/tessera/connectors/conn-001 bearer_token=test-bearer-xyz",
            "auth enable approle",
            "write auth/approle/role/tessera-it " +
                "secret_id_ttl=10m token_ttl=20m policies=default",
            "read -field=role_id auth/approle/role/tessera-it/role-id"
        );

    @DynamicPropertySource
    static void vaultProps(DynamicPropertyRegistry r) {
        r.add("spring.cloud.vault.uri", vault::getHttpHostAddress);
        r.add("spring.cloud.vault.token", vault::getRootToken);    // token auth for IT simplicity
        r.add("spring.config.import", () -> "vault://secret/tessera/auth");
    }
}
```

**Simplification for tests:** Use `token` auth in tests (not AppRole). The CONTEXT-locked decision is AppRole in production; AppRole in tests costs a lot of init-command boilerplate for no additional coverage. Keep a SEPARATE `VaultAppRoleAuthIT` that specifically exercises AppRole wiring — one test class, not every test class.

**`VaultContainer.withInitCommand` syntax:** each vararg is a `vault ...` command executed inside the container after startup. Above: enable KV-v2, seed two secret paths, enable AppRole.

**Pitfall — `hashicorp/vault` vs `vault` image name:** HashiCorp rebranded the Docker image from `vault` to `hashicorp/vault` around 2023. Use `hashicorp/vault:1.15` or later; `vault:1.13` still works but pulls from the legacy location that may get deprecated.

**Pitfall — image pinning:** For reproducibility per the Phase 0 "pin all images by digest" policy, replace `:1.15` with `:1.15@sha256:...`. Add the digest to the Phase 0 image-pin test pattern.

**Pitfall — port 8200 and reuse across test classes:** `VaultContainer` gets a random host port per test class. If you need one Vault shared across ITs, use Singleton Container pattern with a static initializer — DO NOT use `@Container` on each class (spinning up Vault 20 times in a suite is ~40s wasted).

**Confidence:** HIGH for the recommended image and container API. MEDIUM for the exact `withInitCommand` syntax — Testcontainers 1.20.x VaultContainer accepts it as documented, but AppRole seeding has pitfalls that may need a spike.

**Sources:**
- `[CITED: https://java.testcontainers.org/modules/vault/]` — VaultContainer module docs.
- `[CITED: https://hub.docker.com/r/hashicorp/vault]` — current image location.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON path extraction | Regex / manual traversal | `com.jayway.jsonpath:json-path` | Q3 |
| HMAC JWT sign/verify | MessageDigest + custom claim parser | `NimbusJwtDecoder.withSecretKey` | Q4 |
| Error response shape | Custom `ErrorDto` | Spring 6 `ProblemDetail` | Q5 |
| Dynamic scheduling | Quartz / Timer / `ScheduledExecutorService` | Central tick + ShedLock `LockingTaskExecutor` | Q9 |
| Conditional HTTP | Manual header juggling | JDK 21 `HttpClient` | Q6 |
| Vault in tests | Mock Vault API server | `VaultContainer` | Q10 |
| Dynamic OpenAPI | Hand-written `openapi.yaml` | `OpenApiCustomizer` | Q1 |
| Per-row hashing canonicalization | Custom JSON serializer | Sorted path-value tuple list SHA-256 | Q6 |

---

## Common Pitfalls (Phase 2 specific)

1. **OpenAPI cache staleness** — `springdoc.cache.disabled=true` in dev; event-driven invalidation in prod. (Q1)
2. **Echoing caller input into `ProblemDetail.detail`** — XSS / tenant leak vector. (Q5)
3. **JSONPath default mapping provider** — returns `net.minidev.json` types; must use `JacksonMappingProvider`. (Q3)
4. **`RefreshScope` on `JwtDecoder`** — breaks filter-chain reference; wrap in `AtomicReference`. (Q4)
5. **`X-Forwarded-Proto` not propagated** — HSTS silently disabled behind reverse proxy. (Q8)
6. **`_sequence` not on nodes** — cursor pagination cannot filter. Verify Phase 1 persistence model. (Q7)
7. **AGE label tables missing composite index** — cursor pagination falls back to sort scan. (Q7)
8. **ShedLock `atMostFor` too small** — expires under slow connector → double-fetch. (Q9)
9. **Vault image rebrand** — `vault:1.13` legacy location vs `hashicorp/vault:1.15`. (Q10)
10. **Cross-tenant error leak via `ValidationReport`** — reuse Phase 1 `ValidationReport.filteredFor`. (Q5)

---

## Environment Availability

| Dependency | Required By | Available on dev | Fallback |
|---|---|---|---|
| Docker | Testcontainers (AGE + Vault + WireMock) | ✓ (macOS 25 / Docker 27.4 per CLAUDE.md) | — |
| JDK 21 | Build/runtime | ✓ (Corretto 23 installed, 21 available) | — |
| HashiCorp Vault image | IT tests | Pulled at first test run | — |
| `hashicorp/vault:1.15` | IT tests | ✓ (public registry) | Pin by digest for CI reproducibility |
| `apache/age:PG16_latest` | IT tests | Already used by Phase 1 | — |

**No blocking gaps.**

---

## Validation Architecture

### Test Framework

| Property | Value |
|---|---|
| Framework | JUnit 5 via `spring-boot-starter-test` 3.5.13 + Testcontainers 1.20.4 + REST-assured 5.5.x + WireMock 3.10.x |
| Config file | `src/test/resources/application-test.yml` per module |
| Quick run (per task) | `mvn -pl fabric-projections -am test` |
| Full suite (per wave merge) | `mvn verify` (runs surefire + failsafe + ArchUnit) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Command | Exists? |
|---|---|---|---|---|
| REST-01 | Dispatcher handles all CRUD | unit + IT | `mvn test -Dtest=GenericEntityControllerTest` | Wave 0 |
| REST-02 | Cursor pagination stable under concurrent writes | IT | `mvn verify -Dit.test=CursorPaginationConcurrencyIT` | Wave 0 |
| REST-04 | Undeclared type → 404 | IT | `mvn verify -Dit.test=DenyAllExposureIT` | Wave 0 |
| REST-05 | Type appears in `/v3/api-docs` after schema mutation | IT | `mvn verify -Dit.test=DynamicOpenApiIT` | Wave 0 |
| REST-06 | Error bodies never leak other tenants' data | IT (jqwik fuzz) | `mvn verify -Dit.test=ErrorLeakPropertyIT` | Wave 0 |
| CONN-01 | Connector SPI + runner separation | ArchUnit | `mvn test -Dtest=ConnectorArchitectureTest` | Wave 0 |
| CONN-03 | ShedLock per connector_id | IT | `mvn verify -Dit.test=ConnectorScheduleLockIT` | Wave 0 |
| CONN-05 | `_source_hash` skip unchanged rows | IT | `mvn verify -Dit.test=DeltaDetectionIT` | Wave 0 |
| CONN-07 | REST poller ingests WireMock endpoint | IT | `mvn verify -Dit.test=RestPollingConnectorIT` | Wave 0 |
| SEC-01 | TLS 1.3 + HSTS header | IT | `mvn verify -Dit.test=TlsHstsHeaderIT` | Wave 0 |
| SEC-02 | Vault secret loads at startup | IT | `mvn verify -Dit.test=VaultSecretLoadIT` | Wave 0 |
| SEC-06 | Startup guard rejects encrypted-marked schemas when flag off | IT | `mvn verify -Dit.test=EncryptionStartupGuardIT` | Wave 0 |

### Sampling Rate

- **Per task commit:** `mvn -pl {touched-module} -am test` (< 30s target).
- **Per wave merge:** `mvn verify` full suite.
- **Phase gate:** full suite green before `/gsd-verify-work`; Phase 0 `JmhRunner` regression check against Phase 1's `WritePipelineBench` baseline.

### Wave 0 Gaps

- [ ] All the IT classes above (10 files) — none exist yet (Phase 2 hasn't started).
- [ ] `CursorPaginationBench` — JMH class to prove Q7 seek-method is O(log n).
- [ ] WireMock fixture library for the REST poller tests.
- [ ] `VaultContainer` singleton helper in a test-support module.

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---|---|---|
| V2 Authentication | yes | OAuth2 resource server + HMAC JWT via Vault (Q4) |
| V3 Session Management | yes (stateless) | JWT TTL 15min, no server sessions, HSTS (Q8) |
| V4 Access Control | yes | Tenant claim ↔ path `{model}` match; role check for `/admin/**`; 404 deny-all (CONTEXT D-11) |
| V5 Input Validation | yes | Jena SHACL (reused from Phase 1); Bean Validation on DTOs |
| V6 Cryptography | yes | Vault for secret storage; **never hand-roll** — HMAC via Nimbus JOSE, TLS via Tomcat, no custom crypto. Field-level encryption deferred (CONTEXT D-2) |
| V7 Error Handling & Logging | yes | RFC 7807 without input echo (Q5); tenant-filtered `ValidationReport` (VALID-04) |
| V8 Data Protection | partial | At-rest via LUKS on IONOS (operational, not code); in-transit via TLS 1.3 (Q8); field-level deferred |
| V9 Communication | yes | TLS 1.3 minimum, HSTS, forward-proto trust (Q8) |
| V13 API & Web Service | yes | SpringDoc-documented API; problem+json errors; OAuth2 |

### Known Threat Patterns for Phase 2 Stack

| Pattern | STRIDE | Mitigation |
|---|---|---|
| Cross-tenant data read via path manipulation (`/api/v1/tenantA/...` with tenantB JWT) | Information Disclosure | 404 deny-all (CONTEXT D-11) + tenant-claim/path match enforced in filter before controller |
| SQL/Cypher injection via `{typeSlug}` | Tampering | Schema Registry lookup validates slug ∈ declared types BEFORE any Cypher; no string concatenation |
| JWT replay after rotation | Spoofing | TTL 15min; rotation accepted as a 15-min outage window per CONTEXT D-6 |
| Enumeration of valid types via 403 vs 404 timing | Information Disclosure | 404 for everything unauthorized (CONTEXT D-11); add constant-time response with a minimum latency floor in a later phase if side-channel becomes relevant |
| XSS in `ProblemDetail.detail` | Tampering / XSS in consuming UIs | Never echo caller input — Q5 hardcodes human-readable strings |
| Bearer token leak via logs | Info Disclosure | Logback scrubber for `Authorization: Bearer ...`; verified by a log-grep IT |
| Over-broad connector token access | Elevation of Privilege | Vault policy per connector path; each connector only reads `secret/tessera/connectors/{id}/*` |
| Schema-version race (new type arrives mid-request) | DoS / 500 | Cached schema descriptor + Caffeine invalidation; request-scoped snapshot prevents TOCTOU |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| A1 | SpringDoc 2.8.x rebuilds `/v3/api-docs` on every hit when `springdoc.cache.disabled=true` | Q1 | If caching is non-overridable, dynamic types won't appear without a restart — fallback: manual cache-bust bean |
| A2 | `RefreshScopeRefreshedEvent` fires for `TesseraAuthProperties` on Vault lease refresh | Q4 | If it doesn't, rotation silently fails — spike required in Wave 0 |
| A3 | Phase 1 persists `_sequence` on AGE nodes (not just events) | Q7 | If only on events, cursor pagination needs a different ordering column or a schema migration — Wave 0 grep-audit of Phase 1 |
| A4 | AGE 1.6 uses composite `(model_id, _sequence)` index efficiently | Q7 | If planner falls back to sort, seek-method is slower than offset — bench in Wave 0 |
| A5 | Jackson `ORDER_MAP_ENTRIES_BY_KEYS` gives stable canonical JSON across versions | Q6 | If not stable, `_source_hash` drifts across Jackson upgrades — mitigation: hash sorted tuple list instead of JSON string |
| A6 | Testcontainers 1.20.x `VaultContainer.withInitCommand` accepts multi-command vararg as documented | Q10 | If API differs, seed via separate `execInContainer` calls after startup |
| A7 | Springdoc `OpenApiCustomizer` runs every time `/v3/api-docs` is requested when cache is disabled | Q1 | If it only runs once at startup, the dynamic registration pattern is broken — spike required |
| A8 | JDK 21 `HttpClient` passes `If-Modified-Since` verbatim without reformatting | Q6 | If it mangles the header, 304 never fires — spike in Wave 0 |

**Risk flag:** A1, A2, A3, A7 are the research flags that the planner MUST address in Wave 0 spikes before Wave 1 commits. Everything else is MEDIUM-to-HIGH confidence based on training-data knowledge of the Spring ecosystem.

---

## Open Questions

1. **Schema-descriptor push vs pull for SpringDoc invalidation.** The `OpenApiCustomizer` pulls from `SchemaRegistry` every doc build. An event-driven push (invalidate on schema-version bump) is cleaner but requires wiring. **Recommendation:** pull-model with `cache.disabled=true` for MVP; revisit if doc-build latency becomes operator-visible.
2. **DLQ ownership.** Does `connector_dlq` write go through `GraphService.apply` as a "failed mutation" event, or sit as a pure relational table the runner writes to directly? (CONN-04 is ambiguous.) **Recommendation:** direct JDBC insert in the runner — DLQ rows are NOT graph data, and routing them through the write funnel muddies the single-funnel invariant.
3. **Admin endpoint prefix.** `/api/v1/admin/*` vs `/admin/*` — discretion. **Recommendation:** `/api/v1/admin/*` for versioning consistency.
4. **Bootstrap token mechanism.** One-shot token in Vault at `secret/tessera/auth/bootstrap_token` vs a CLI command. **Recommendation:** Vault-held static bootstrap token consumed on first `/admin/tokens/issue` call, with the value rotated immediately after first successful login. Simpler than a CLI.

---

## Sources

### Primary (HIGH confidence)
- `https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html` — NimbusJwtDecoder HMAC path
- `https://docs.spring.io/spring-cloud-vault/docs/current/reference/html/` — Config Data API
- `https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html` — ProblemDetail
- `https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html` — HSTS
- `https://github.com/lukas-krecan/ShedLock` — programmatic LockingTaskExecutor
- `https://java.testcontainers.org/modules/vault/` — VaultContainer
- `https://github.com/json-path/JsonPath` — Jayway JSONPath 2.9.0
- `https://use-the-index-luke.com/no-offset` — seek-method pagination
- `.planning/research/STACK.md` — already-confirmed stack versions

### Secondary (MEDIUM confidence — verified but pattern-level)
- Springdoc 2.8.x `OpenApiCustomizer` / `GroupedOpenApi` — API shape known, runtime invalidation timing needs spike

### Tertiary (LOW / unverified)
- AGE 1.6 composite-index query-planner behavior — requires `CursorPaginationBench` to confirm

---

## Metadata

| Area | Confidence | Reason |
|---|---|---|
| OpenAPI dynamic lifecycle (Q1) | MEDIUM | Pattern is idiomatic; runtime invalidation timing needs Wave 0 spike |
| Connector SPI (Q2) | HIGH | Standard Kafka-Connect-style design |
| Mapping DSL (Q3) | HIGH | Jayway JSONPath is a settled choice |
| OAuth2 + Vault HMAC (Q4) | HIGH for wiring, MEDIUM for rotation semantics |
| RFC 7807 (Q5) | HIGH | Native Spring 6 feature |
| ETag / delta (Q6) | HIGH | Well-trodden; per-row hash is the non-trivial bit |
| AGE cursor pagination (Q7) | MEDIUM | Seek-method is HIGH; AGE-specific index and param-binding need Wave 0 verification |
| TLS 1.3 + HSTS (Q8) | HIGH | Config-only standard |
| ShedLock dynamic (Q9) | HIGH | Documented programmatic API |
| Testcontainers Vault (Q10) | HIGH for image and dep; MEDIUM for init-command nuances |

**Research date:** 2026-04-15
**Valid until:** 2026-07-15 (stack pins are relatively stable; revisit if Spring Boot 3.6 or AGE 1.7-PG16 ships)
