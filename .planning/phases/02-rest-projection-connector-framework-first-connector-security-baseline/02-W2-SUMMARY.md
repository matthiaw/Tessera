---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W2
subsystem: rest-projection-security-baseline
status: complete
tags:
  - rest
  - crud
  - cursor-pagination
  - security
  - oauth2
  - jwt
  - rfc7807
  - hsts
  - tls
  - openapi
  - jmh
dependency_graph:
  requires:
    - Phase 1 graph core (GraphService.apply, TenantContext, SchemaRegistry)
    - W0 SpringDoc OpenApiCustomizer lifecycle proof
    - W1 _seq denormalization + schema REST/encryption flags
  provides:
    - GenericEntityController CRUD dispatcher (REST-01, REST-02, REST-03)
    - Cursor pagination via _seq seek method (REST-02)
    - Deny-all exposure flag enforcement (REST-04)
    - Dynamic OpenAPI rebuild from SchemaRegistry (REST-05)
    - RFC 7807 problem+json errors (REST-06)
    - Tenant-only row ACL via JWT claim matching (REST-07)
    - OAuth2 resource server with HMAC-signed JWTs (SEC-01, SEC-02)
    - TLS 1.3 + HSTS header verification (SEC-01)
    - Bootstrap token issuance /admin/tokens/issue (Decision 21)
    - REST projection JMH benchmark harness (p95 target <50ms)
  affects:
    - fabric-projections (full REST surface + security layer)
    - fabric-app (application.yml auth config, application-prod.yml TLS)
tech-stack:
  added:
    - "spring-boot-starter-oauth2-resource-server 3.5.13 (fabric-projections)"
    - "net.jqwik:jqwik 1.9.2 (fabric-projections test scope)"
    - "org.openjdk.jmh:jmh-core 1.37 (fabric-projections test scope)"
  patterns:
    - "Single GenericEntityController dispatching to EntityDispatcher"
    - "CursorCodec base64-encoding (model, type, lastSeq, lastNodeId)"
    - "OpenApiSchemaCustomizer walking SchemaRegistry per /v3/api-docs hit"
    - "RotatableJwtDecoder wrapping AtomicReference<NimbusJwtDecoder>"
    - "Constant 404 body for all not-found/cross-tenant/disabled-type cases"
    - "ResponseEntity<ProblemDetail> to suppress instance URI leakage"
key-files:
  created:
    - fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/EntityDispatcher.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/CursorCodec.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/OpenApiSchemaCustomizer.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/TesseraAuthProperties.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/problem/TesseraProblemHandler.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/admin/TokenIssueController.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/TlsHstsHeaderIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/DynamicOpenApiIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/CrossTenantLeakPropertyIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/ErrorShapeProblemJsonIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/JwtRotationIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/DenyAllExposureIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/CursorPaginationConcurrencyIT.java
    - fabric-projections/src/jmh/java/dev/tessera/projections/bench/RestProjectionBench.java
    - fabric-projections/src/jmh/java/dev/tessera/projections/bench/RestProjectionBenchRunner.java
    - fabric-app/src/main/resources/application-prod.yml
  modified:
    - fabric-projections/pom.xml
    - fabric-app/src/main/resources/application.yml
    - fabric-projections/src/test/resources/application-projection-it.yml
decisions:
  - "Return ResponseEntity<ProblemDetail> to suppress automatic instance URI population (prevents path leakage)"
  - "Constant instance URI /api/v1/entities on 404 responses to eliminate enumeration signal"
  - "VaultContainer-based JwtRotationIT deferred to W3; rotation tested via RotatableJwtDecoder.rotateKey()"
  - "/admin/tokens/issue is permitAll, protected by X-Tessera-Bootstrap header"
  - "OpenApiSchemaCustomizer replaces Wave 0 spike; spike remains @Profile(spike-openapi) for reference"
  - "TlsHstsHeaderIT uses keytool-generated PKCS12 at test time; hits /v3/api-docs (no actuator in projections module)"
  - "RestProjectionBench measures GraphRepository.queryAllAfter (dominant DB cost) rather than full HTTP round-trip"
metrics:
  duration: "W2a 55m + W2b 48m + W2c 19m = 122m total"
  completed: "2026-04-16T11:43:00+02:00"
  tasks: 2
  files: 27
---

# Phase 2 Plan W2: REST Projection + Security Baseline Summary

**One-liner:** Full REST CRUD dispatcher with cursor pagination, OAuth2 resource server (HMAC JWTs from Vault), RFC 7807 error shape, deny-all exposure policy, dynamic OpenAPI from SchemaRegistry, TLS 1.3 + HSTS verification, and JMH bench harness for pagination p95.

## What Was Built

### W2a: CRUD Dispatcher + Cursor Pagination
- **GenericEntityController**: single `@RestController` handling GET-list, GET-one, POST, PUT, DELETE for `/api/v1/{model}/entities/{typeSlug}`
- **EntityDispatcher**: domain logic layer checking `restReadEnabled`/`restWriteEnabled` flags, delegating reads to GraphRepository and writes to GraphService.apply
- **CursorCodec**: base64 encoding of `(model, type, lastSeq, lastNodeId)` cursor; rejects malformed input with `InvalidCursorException` (400)

### W2b: Security Baseline
- **SecurityConfig**: OAuth2 resource server with HS256 JWT validation, HSTS (includeSubDomains, 1-year), stateless sessions
- **RotatableJwtDecoder**: `AtomicReference<NimbusJwtDecoder>` with runtime key rotation via `rotateKey()` or `RefreshScopeRefreshedEvent`
- **TesseraProblemHandler**: RFC 7807 for all errors; constant 404 for cross-tenant/disabled-type (never leaks tenant data)
- **TokenIssueController**: `POST /admin/tokens/issue` mints HS256 JWTs; bootstrap header guard
- **application-prod.yml**: TLS 1.3 + Vault Config Data API + AppRole auth

### W2c: TLS/HSTS IT + OpenAPI Promotion + Bench
- **TlsHstsHeaderIT**: boots with self-signed PKCS12 keystore, verifies `Strict-Transport-Security: max-age=31536000; includeSubDomains` over HTTPS
- **OpenApiSchemaCustomizer**: production-grade replacement for W0 spike; walks `SchemaRegistry.listDistinctExposedModels()` + `listExposedTypes()`; generates per-type PathItems with GET/POST/PUT/DELETE + property-derived schemas namespaced as `{model}_{slug}Entity`
- **DynamicOpenApiIT**: declares type with `rest_read_enabled=false`, hits `/v3/api-docs/entities` (absent), flips to `true`, hits again (present with CRUD ops + property schemas), flips back (absent). REST-05 definitive gate.
- **RestProjectionBench**: JMH benchmark seeding 100k nodes via GraphService.apply, measuring `queryAllAfter(limit=50)` cursor pagination p95. Warn-only gate at <50ms.

## Integration Tests

| Test | Purpose | Status |
|------|---------|--------|
| DenyAllExposureIT | rest_read_enabled=false -> 404, flip -> 200 | GREEN |
| CursorPaginationConcurrencyIT | 150 nodes, stable cursor under concurrent writes | GREEN |
| DynamicOpenApiIT | REST-05 gate: api-docs reflects exposure flag flip | GREEN |
| CrossTenantLeakPropertyIT | 100-pair fuzz: random (tenantA_jwt, tenantB_path) -> 404 | GREEN |
| ErrorShapeProblemJsonIT | RFC 7807 shape, no input echoing | GREEN |
| JwtRotationIT | Key rotation via rotateKey(), old token rejected 401 | GREEN |
| TlsHstsHeaderIT | HTTPS + Strict-Transport-Security header | GREEN |
| SchemaVersionBumpIT | W0 spike still green with security excluded | GREEN |
| CursorCodecTest | Codec round-trip + edge cases (unit) | GREEN |

**Reactor build:** `./mvnw -B verify` BUILD SUCCESS (4:18 min, 5/5 modules, all ArchUnit gates green)

## Deviations from Plan

### W2c Auto-fixed Issues

**1. [Rule 1 - Bug] /actuator/health returns 404 in fabric-projections IT context**
- **Found during:** TlsHstsHeaderIT first run
- **Issue:** fabric-projections does not include spring-boot-starter-actuator; `/actuator/health` is not mapped
- **Fix:** Changed TlsHstsHeaderIT to hit `/v3/api-docs` (public, always available via springdoc)
- **Files modified:** `TlsHstsHeaderIT.java`

### W2b Deviations (carried from W2b SUMMARY)

1. Duplicate YAML key in application configs (Rule 3)
2. ProblemDetail instance field leaking request URI (Rule 1)
3. HS256 key too short for Nimbus (Rule 1)
4. SchemaVersionBumpIT picking up security autoconfiguration (Rule 3)
5. VaultContainer-based JwtRotationIT deferred to W3

## Known Stubs

None. All data paths are wired to real SchemaRegistry + GraphRepository.

## Acceptance Criteria -- Final Status

- [x] GenericEntityController CRUD 5 methods green (DenyAllExposureIT, CursorPaginationConcurrencyIT)
- [x] Cursor pagination stable under concurrent writes (CursorPaginationConcurrencyIT)
- [x] Deny-all: disabled type -> 404, unknown type -> 404, cross-tenant -> 404 (DenyAllExposureIT, CrossTenantLeakPropertyIT)
- [x] Dynamic OpenAPI: flip rest_read_enabled visible in /v3/api-docs without restart (DynamicOpenApiIT)
- [x] RFC 7807 problem+json on all errors, detail never echoes input (ErrorShapeProblemJsonIT)
- [x] OAuth2 resource server + HMAC JWT validation (JwtRotationIT, all ITs use JWT tokens)
- [x] TLS 1.3 + HSTS header verified (TlsHstsHeaderIT)
- [x] application-prod.yml has TLS + Vault config
- [x] RestProjectionBench created with JMH profile
- [x] ArchUnit gates green (RawCypherBanTest, ModuleDependencyTest)
- [x] Full reactor BUILD SUCCESS

## What W3 Should Know

1. **OpenApiSchemaCustomizer** is live in production profile (no `@Profile` gate). The W0 spike (`SpringDocDynamicSpike`) is still present under `@Profile("spike-openapi")` -- can be removed in a cleanup pass.
2. **TlsHstsHeaderIT** tests HSTS via `/v3/api-docs` since actuator is not in fabric-projections classpath. When actuator is added in fabric-app integration tests, a second variant can be added.
3. **RestProjectionBench** is opt-in via `./mvnw -pl fabric-projections -Pjmh verify`. Dataset size configurable via `-Djmh.dataset=N`.
4. **VaultContainer-based JwtRotationIT** is still deferred. W2b tests key rotation via `RotatableJwtDecoder.rotateKey()` directly. W3 should add `VaultAppRoleAuthIT` with Testcontainers Vault.
5. **Token issuance** at `/admin/tokens/issue` is permitAll with bootstrap header guard. W3 should evaluate whether this needs tightening once full Vault auth is wired.

## Self-Check: PASSED
