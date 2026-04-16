---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W2
subsystem: security-baseline
status: complete
tags:
  - security
  - oauth2
  - jwt
  - rfc7807
  - cross-tenant
  - hsts
dependency_graph:
  requires:
    - Phase 1 graph core (GraphService.apply, TenantContext, SchemaRegistry)
    - W2a REST CRUD dispatcher + cursor pagination
    - W0 SpringDoc OpenApiCustomizer lifecycle proof
  provides:
    - OAuth2 resource server with HMAC-signed JWTs (SEC-01, SEC-02)
    - RFC 7807 problem+json for all error responses (REST-06)
    - Cross-tenant 404 deny-all enforcement (Decision 11)
    - JWT key rotation via RotatableJwtDecoder (SEC-02)
    - Bootstrap token issuance endpoint /admin/tokens/issue (Decision 21)
    - TLS 1.3 + HSTS config for production (SEC-01)
  affects:
    - fabric-projections (security layer added to all REST endpoints)
    - fabric-app (application.yml auth config, application-prod.yml TLS)
    - W2a ITs (updated with JWT tokens)
tech-stack:
  added:
    - "spring-boot-starter-oauth2-resource-server 3.5.13 (fabric-projections)"
    - "net.jqwik:jqwik 1.9.2 (fabric-projections test scope)"
  patterns:
    - "RotatableJwtDecoder wrapping AtomicReference<NimbusJwtDecoder> for key rotation"
    - "ResponseEntity<ProblemDetail> return type to suppress instance URI leakage"
    - "JwtTestHelper utility for minting test JWTs with static signing key"
    - "Constant 404 body for all not-found/cross-tenant/disabled-type cases"
key-files:
  created:
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/RotatableJwtDecoder.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/TesseraAuthProperties.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/problem/TesseraProblemHandler.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/admin/TokenIssueController.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/CrossTenantException.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/InvalidCursorException.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/JwtTestHelper.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/CrossTenantLeakPropertyIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/ErrorShapeProblemJsonIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/JwtRotationIT.java
    - fabric-app/src/main/resources/application-prod.yml
  modified:
    - fabric-projections/pom.xml
    - fabric-projections/src/main/java/dev/tessera/projections/rest/GenericEntityController.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/CursorCodec.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/DenyAllExposureIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/CursorPaginationConcurrencyIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/CursorCodecTest.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java
    - fabric-projections/src/test/resources/application-projection-it.yml
    - fabric-app/src/main/resources/application.yml
decisions:
  - "Return ResponseEntity<ProblemDetail> instead of raw ProblemDetail to suppress automatic instance URI population (prevents path leakage in 404 responses)"
  - "Set constant instance URI /api/v1/entities on 404 responses to eliminate tenant/type enumeration signal"
  - "Exclude security autoconfiguration from SchemaVersionBumpIT SpikeApp (no DB, no auth context needed)"
  - "Defer VaultContainer-based JwtRotationIT to W3 (VaultAppRoleAuthIT); W2b tests rotation via RotatableJwtDecoder.rotateKey() directly"
  - "/admin/tokens/issue is permitAll (bootstrap endpoint) -- protected by X-Tessera-Bootstrap header matching Vault-held secret"
metrics:
  duration: 48m
  completed: "2026-04-16T11:18:25+02:00"
  tasks: 1
  files: 21
---

# Phase 2 Plan W2b: Security Baseline Summary

OAuth2 resource server with HMAC-signed JWTs, RFC 7807 problem+json error handling, cross-tenant 404 deny-all enforcement, and TLS 1.3 + HSTS production config.

## What Was Built

### Security Infrastructure
- **SecurityConfig**: Spring Security filter chain with OAuth2 resource server JWT validation, HSTS (includeSubDomains, 1-year max-age), stateless sessions, CSRF disabled
- **RotatableJwtDecoder**: Wraps `AtomicReference<NimbusJwtDecoder>` built from Base64-encoded HMAC key; supports runtime key rotation via `rotateKey()` and `RefreshScopeRefreshedEvent`
- **TesseraAuthProperties**: `@ConfigurationProperties("tessera.auth")` binding for `jwtSigningKey`, `tokenTtlMinutes` (default 15), `bootstrapToken`
- **TokenIssueController**: `POST /admin/tokens/issue` mints HS256 JWTs; protected by `X-Tessera-Bootstrap` header matching Vault-held secret

### Error Handling
- **TesseraProblemHandler**: `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`; maps all Tessera exceptions to RFC 7807 `application/problem+json` with constant detail strings (never echoes caller input)
- **CrossTenantException** and **InvalidCursorException**: new exception types
- **CursorCodec**: updated to throw `InvalidCursorException` (400 problem+json) instead of `IllegalArgumentException`

### Tenant Enforcement
- **GenericEntityController**: All 5 CRUD methods now accept `@AuthenticationPrincipal Jwt` and enforce `jwt.tenant == {model}` path segment; mismatch throws `CrossTenantException` (mapped to 404)

### Configuration
- **application-projection-it.yml**: static test signing key, bootstrap token, problem details enabled
- **application.yml**: auth properties with env var placeholders, `spring.mvc.problemdetails.enabled: true`, `server.forward-headers-strategy: framework`
- **application-prod.yml**: TLS 1.3 config, Vault Config Data API import, AppRole auth

## Integration Tests

| Test | Purpose | Status |
|------|---------|--------|
| CrossTenantLeakPropertyIT | 100-pair fuzz: random (tenantA_jwt, tenantB_path) always 404, no UUID leakage | GREEN |
| ErrorShapeProblemJsonIT | RFC 7807 shape for 404, 400, cross-tenant; detail never echoes input | GREEN |
| JwtRotationIT | Key A works, rotate to key B, old token rejected 401, new token accepted 200 | GREEN |
| DenyAllExposureIT (updated) | W2a test with JWT tokens | GREEN |
| CursorPaginationConcurrencyIT (updated) | W2a test with JWT tokens | GREEN |
| SchemaVersionBumpIT (updated) | W0 spike with security autoconfiguration excluded | GREEN |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Duplicate YAML key in application configs**
- **Found during:** Initial test run
- **Issue:** Adding `spring.mvc.problemdetails.enabled` created a duplicate `spring:` root key in both `application.yml` and `application-projection-it.yml`, causing SnakeYAML to reject the file
- **Fix:** Merged `mvc.problemdetails` into the existing `spring:` block
- **Files modified:** `application.yml`, `application-projection-it.yml`

**2. [Rule 1 - Bug] ProblemDetail instance field leaking request URI**
- **Found during:** CrossTenantLeakPropertyIT failure
- **Issue:** Spring auto-populates `ProblemDetail.instance` from the request URI, which includes tenant UUIDs and type slugs
- **Fix:** Changed all handlers to return `ResponseEntity<ProblemDetail>` and set `instance` to a constant `/api/v1/entities` URI
- **Files modified:** `TesseraProblemHandler.java`

**3. [Rule 1 - Bug] HS256 key too short for Nimbus**
- **Found during:** JwtRotationIT failure
- **Issue:** Key B was 31 bytes; Nimbus requires minimum 256 bits (32 bytes) for HS256
- **Fix:** Extended key B to 32 bytes
- **Files modified:** `JwtRotationIT.java`

**4. [Rule 3 - Blocking] SchemaVersionBumpIT picking up security autoconfiguration**
- **Found during:** Full test suite run
- **Issue:** Spring Security autoconfiguration blocked the spike's `/v3/api-docs` endpoint with a login page
- **Fix:** Excluded `SecurityAutoConfiguration` and `OAuth2ResourceServerAutoConfiguration` from the spike's `SpikeApp`
- **Files modified:** `SchemaVersionBumpIT.java`

### Scope Adjustments

- **VaultContainer-based JwtRotationIT deferred to W3** per explicit W2b scope note ("No Spring Cloud Vault actual wiring with Testcontainers Vault"). Rotation is tested directly via `RotatableJwtDecoder.rotateKey()`.
- **TlsHstsHeaderIT deferred to W2c** per explicit scope note ("No TlsHstsHeaderIT in W2b"). TLS config is in `application-prod.yml` but not tested with a real TLS server in this wave.
- **DynamicOpenApiIT deferred to W2c** per scope note.
- **RestProjectionBench deferred to W2c** per scope note.

## Items Carrying Forward to W2c

1. **TlsHstsHeaderIT** -- test with self-signed keystore + HTTPS + X-Forwarded-Proto variant
2. **DynamicOpenApiIT** -- full SpringDoc dynamic rebuild test with SchemaRegistry
3. **OpenApiSchemaCustomizer** -- promote W0 spike to production, wire to SchemaRegistry
4. **RestProjectionBench** -- JMH bench targeting p95 < 50ms at 100k nodes
5. **ProjectionsModuleDependencyTest** -- ArchUnit rule for fabric-projections module boundary (already exists in fabric-projections/src/test but plan also specifies fabric-app location)

## Self-Check: PASSED
