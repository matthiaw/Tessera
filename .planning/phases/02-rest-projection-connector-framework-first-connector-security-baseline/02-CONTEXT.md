---
gsd_artifact: context
phase: "02"
phase_name: "REST Projection, Connector Framework, First Connector, Security Baseline"
created: 2026-04-15
requirements: [REST-01, REST-02, REST-03, REST-04, REST-05, REST-06, REST-07, CONN-01, CONN-02, CONN-03, CONN-04, CONN-05, CONN-06, CONN-07, CONN-08, SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, SEC-06]
---

# Phase 2 — Context

**Goal (from ROADMAP.md):** Expose the graph through a dynamically-generated REST projection and ingest real data through the first concrete connector (generic REST polling), with TLS, Vault-managed secrets, row/field-level access control, and fail-closed endpoint defaults. Decide explicitly on field-level encryption: ship fully or keep feature-flagged off.

**Depends on:** Phase 1 (graph core, schema registry, rule engine, event log, outbox — all complete).

**Boundary:** 28 requirements. No MCP (Phase 3), no unstructured ingestion (Phase 2.5), no SQL/Kafka projections (Phase 4).

---

## Locked Decisions

These are answered. Downstream agents (researcher, planner) MUST treat them as constraints, not gray areas.

### 1. Tenancy — `model_id` = per-customer

**Decision:** `model_id` maps one-to-one to a customer organization.

**Why:** Matches the common SaaS pattern; simplest RBAC story; per-customer isolation is already enforced by Phase 1's `TenantBypassPropertyIT` fuzz tests. Domain- or project-scoped models are deferred as a future refinement.

**How it shapes planning:**
- REST URL `/api/v1/{model}/entities/{typeSlug}` — `{model}` is the customer identifier, not a schema domain.
- JWT `tenant` (or equivalent) claim must match the `{model}` path segment or the request fails at the filter layer.
- Connector `model_id` column references customer identity — a single connector serves one customer.

### 2. Field-Level Encryption — **feature-flagged OFF for Phase 2, fail-closed**

**Decision:** Ship Phase 2 with field-level encryption disabled by feature flag. At startup, if any schema in any tenant declares a property as `encrypted: true`, the app **refuses to boot** until either the flag is turned on (full machinery) or the schema is changed.

**Why:** Full field-level crypto (per-tenant blind index, multi-version DEKs, KMS chaos test in CI) is ~1 wave of its own; Phase 2 is already 3–4 waves wide. A later security-focused phase will flip the flag. Silent-skip is rejected as security theater.

**How it shapes planning:**
- Add `tessera.security.field-encryption.enabled: false` config flag.
- Add a Spring `ApplicationRunner` or `@PostConstruct` check that scans Schema Registry for any property marked `encrypted: true` and throws `IllegalStateException` if the flag is off.
- Schema Registry columns `property_encrypted: boolean` and `property_encrypted_alg: text` can ship in Phase 2's Flyway migration so later phases don't need a schema change.
- No `EncryptionService`, no KMS envelope logic, no blind indexes in Phase 2 code — only the startup guard.

### 3. Connector Auth — Bearer token only for MVP

**Decision:** The generic REST poller supports **Bearer token** authentication only in Phase 2. Credentials live in Vault under a predictable path (`secret/tessera/connectors/{connector_id}/bearer_token`).

**Why:** Covers the majority of SaaS + internal API cases with a single code path. HTTP Basic, API-key header, and OAuth2 client-credentials are explicit follow-ups but not MVP.

**How it shapes planning:**
- Connector `auth_type` column exists from day one (future-proof), but only value `BEARER` is accepted in Phase 2.
- Validation at connector-create rejects other values with a clear error.
- No OAuth2 token refresh loop, no Basic credential handling.

### 4. REST Pagination — cursor (opaque)

**Decision:** List endpoints paginate via an opaque cursor token. Response shape: `{ items: [...], next_cursor: "..." | null }`.

**Why:** Stable under concurrent writes (inserts don't shift page boundaries), aligns with the event-log ordering model, easy to scale, and avoids offset arithmetic against AGE.

**How it shapes planning:**
- Cursor implementation: base64-encode `(model_id, type_slug, last_sequence, last_node_id)` — opaque to clients.
- No `page` or `offset` query params in Phase 2. Add them in a later phase only if real consumers demand it.
- `limit` query param with a sane default (50) and cap (500).

### 5. REST Exposure Policy — Schema Registry flag

**Decision:** The Schema Registry's node type descriptor grows two columns: `rest_read_enabled: boolean` and `rest_write_enabled: boolean`, both defaulting to **false**. Deny-all by default is enforced by: if a type has `rest_read_enabled = false`, all `GET` requests for that type return **404**.

**Why:** Single source of truth, survives replay, versioned with schema changes, no separate policy table to drift from schema state.

**How it shapes planning:**
- Flyway migration adds the columns to the schema node-type table.
- `GenericEntityController` consults the Schema Registry descriptor (already cached in Caffeine per Phase 1) before dispatching.
- Admin REST endpoint to flip the flags per type, audited to the event log.
- Property-level `rest_exposed` flag is **out of scope** for Phase 2 — whole type is enough.

### 6. RBAC — OAuth2 JWT + roles claim + Vault-held HMAC key

**Decision:** Spring Security resource server. JWTs signed by an HMAC key held in Vault (path: `secret/tessera/auth/jwt_signing_key`). Roles come from the `roles` claim inside the JWT. Tenant identity comes from the `tenant` claim.

**Why:** No external IdP dependency for MVP. Vault is already a Phase 2 requirement, so the signing key gets the same lifecycle as connector secrets. Switching to a full IdP (Keycloak / Authentik) is a later migration — the JWT contract stays the same.

**How it shapes planning:**
- Dependencies: `spring-boot-starter-oauth2-resource-server`, Spring Cloud Vault (already planned for connector secrets).
- Filter chain: resource-server JWT decoder with key loaded from Vault at startup (cached, rotatable).
- A minimal `/admin/tokens/issue` endpoint (protected by a bootstrap admin role) to mint tokens for testing and initial operator access. Not a full OAuth2 authorization server — just HMAC signing a claim set.
- Token TTL: short (15 min) with refresh deferred to a later phase.

### 7. Connector Lifecycle — DB-backed + admin REST endpoints

**Decision:** Connector instances live in a `connectors` table (`id`, `model_id`, `type`, `mapping_def JSONB`, `auth_type`, `credentials_ref`, `poll_interval_seconds`, `enabled`, …). CRUD via `/admin/connectors` REST endpoints. Configuration changes hot-reload — no app restart.

**Why:** Operator experience matches the rest of the admin surface (rule engine, schema registry). Runtime reconfiguration is non-negotiable for real production use.

**How it shapes planning:**
- Flyway migration creates `connectors` and `connector_sync_status` tables.
- `ConnectorRegistry` Spring bean owns an in-memory map; reacts to `ApplicationEventPublisher` events when rows change.
- Admin endpoints enforce `tenant` claim scoping — an operator for tenant A cannot see or mutate tenant B's connectors.
- `credentials_ref` is a Vault path; no secrets ever stored in Postgres.

### 8. REST Error Shape — RFC 7807 problem+json

**Decision:** All REST error responses follow RFC 7807 (`application/problem+json`) with fields `type`, `title`, `status`, `detail`, `instance`, plus Tessera extensions `tenant` (optional, never cross-tenant), `code` (machine-readable error code), and for validation errors `errors: [{ property, message }]`.

**Why:** Spring Boot 3 supports problem+json natively. OpenAPI tooling understands it. Consistent across all endpoints. Zero reinvention.

**How it shapes planning:**
- Global `@ControllerAdvice` translates `ValidationException`, `RuleRejectException`, `TenantIsolationException`, `NotFoundException`, `ForbiddenException` into problem-json responses.
- Never echo caller input verbatim in `detail` (XSS hygiene for JSON-rendering clients).
- SpringDoc OpenAPI auto-documents error schemas.

### 9. Connector Scheduling — fixed interval only (ShedLock-guarded)

**Decision:** Connectors have one scheduling knob: `poll_interval_seconds`. No cron for Phase 2. ShedLock on a Postgres lock table prevents double-polling even if the operator ever scales past one instance.

**Why:** Covers 95% of real cases. Cron adds a dependency (Quartz or equivalent) and a second code path. Defer until a real user asks.

**How it shapes planning:**
- `@Scheduled(fixedDelayString = "...")` + ShedLock dynamic locks per `connector_id`.
- `ShedLock` Flyway migration creates the `shedlock` table.
- A single scheduler bean dispatches to connectors based on `poll_interval_seconds`; it does NOT create a thread-per-connector.

### 10. Row-Level Access Control — tenant-only

**Decision:** Access control beyond "can you see this type at all" is **tenant-only** for Phase 2. Caller's `tenant` claim filters rows; no row-level predicates, no per-property visibility rules.

**Why:** Tenant isolation is the hard security guarantee Tessera already makes (fuzz tests in Phase 1). Adding row- or property-level predicates is a security-focused phase of its own. Exposure flag on the type gives sufficient granularity for MVP.

**How it shapes planning:**
- `TenantContext` already injects `model_id` into every Cypher query (Phase 1).
- `GenericEntityController` extracts the `tenant` claim from the JWT, sets the `TenantContext`, and rejects any request whose path `{model}` segment disagrees with the claim (**400** or **404**, not 403, to avoid leaking tenant existence).
- No property-level redaction. No row predicates on `owner`, etc.

### 11. Deny-All Response Code — **404 Not Found**

**Decision:** An undeclared type, a disabled type, a cross-tenant path, or an unknown instance all return **404**. Never 403. Never 200. The success criterion "undeclared type returns 403/404 and never 200" is satisfied by the 404 branch exclusively.

**Why:** Zero information leak to unauthenticated or cross-tenant callers. A 403 tells an attacker "this exists but you can't have it." 404 tells them nothing. This is the standard practice for multi-tenant SaaS APIs.

**How it shapes planning:**
- Global error mapping: `NotFoundException`, `TypeNotExposedException`, `CrossTenantException` all → 404 with `{ title: "Not Found", status: 404 }` problem-json.
- `ForbiddenException` is reserved for cases where the user is authenticated, owns the tenant, but lacks the specific role needed (returns 403).
- A security test verifies a curl against `/api/v1/tenantA/entities/hidden` from a `tenantB` JWT returns 404, indistinguishable from a truly non-existent type.

---

## Open / Claude's Discretion

Items below are **NOT locked decisions** — they're explicit gray areas the planner or researcher can resolve based on codebase patterns and best practice. If a decision below becomes critical during planning, spawn back for a follow-up discuss round.

- **Admin endpoint path prefix:** `/admin/*`, `/api/v1/admin/*`, or separate port? Claude's discretion.
- **Connector mapping definition DSL:** JSONB shape for `mapping_def` — JSONPath? JSONata? JMESPath? Claude's discretion based on research.
- **Vault auth method:** AppRole vs Kubernetes vs token. Likely AppRole per STACK.md but confirm in research.
- **ETag / Last-Modified detection:** store last seen value per-row or per-page? Claude's discretion.
- **Sync-status surface:** separate `/admin/connectors/{id}/status` endpoint or embedded in list response? Claude's discretion.
- **OpenAPI lifecycle for dynamic controllers:** Springdoc hook timing is a known research flag — researcher must investigate.
- **Bootstrap token issuance:** CLI command or one-shot REST endpoint for the very first admin token? Claude's discretion.

---

## Reused Assets from Phase 1

Things the planner can assume already exist and should not re-implement:

- `GraphService.apply(GraphMutation, TenantContext)` — the single write funnel. Connectors MUST call this, never write raw Cypher.
- `TenantContext` primitive (Phase 0/1).
- `SchemaRegistry.loadFor(model_id)` with Caffeine cache.
- `OutboxPoller` — already drains to `ApplicationEventPublisher`. Connectors should not drain the outbox.
- `RuleEngine.run(RuleContext)` — four chains, authority matrix, conflict register.
- `WriteRateCircuitBreaker` — already wraps `GraphService.apply`. Connectors get circuit-breaker protection for free.
- ArchUnit `RawCypherBanTest` and module-direction tests — the connector module **must not** reach into `graph.internal`.
- Flyway plain-SQL migrations (V1..V9 landed in Phase 1). Phase 2 migrations start at V10.
- Testcontainers `apache/age:PG16_latest` pattern with custom image, pinned by sha256.
- JMH bench harness pattern (`WritePipelineBench`, `ShaclValidationBench`).

---

## Known Deviations from Phase 1 to Address

Two follow-ups flagged in Phase 1's VERIFICATION — neither blocks Phase 2, but planner should be aware:

1. **`GraphServiceImpl.apply` passes empty `currentSourceSystem` map to `ruleEngine.run`.** Phase 2's connector path should thread the `(property → source_system)` map from the current graph state so `AuthorityReconciliationRule` actually fires through the write funnel (not just in tests). This likely lands in the Wave 1 plan.

2. **`ChainExecutor` hardcodes `ConflictRecord.winningSourceSystem` to the incoming mutation's source system.** Fix (two-line change) should piggyback on the same wave where the above deviation is addressed.

Both are tiny but close the RULE-05 / RULE-06 production gap that Phase 1 W4 documented.

---

## Deferred Ideas (Scope Creep Catches)

Items raised but deliberately pushed out of Phase 2:

- **GraphQL projection** — Phase 4+ per PROJECT.md.
- **Row- and property-level ACLs** — dedicated security phase.
- **Full field-level encryption machinery** — dedicated security phase after Phase 5.
- **OAuth2 client-credentials / HTTP Basic / API-key connector auth** — ship after real use-case demand.
- **Cron-style connector scheduling** — ship if operator feedback demands.
- **Full OIDC flow against Keycloak/Authentik** — migration path preserved; MVP uses Vault-signed JWTs.
- **Write-back connectors** — MVP connector is read-only polling (PROJECT.md).
- **Bidirectional circlead integration** — Phase 5.

---

## Success Criteria Recap (from ROADMAP.md)

The planner must produce plans that, when executed, satisfy:

1. Declaring a new node type + flipping `rest_read_enabled` yields working `GET/POST/PUT/DELETE /api/v1/{model}/entities/{typeSlug}` without redeploy, visible in `/v3/api-docs`.
2. Undeclared or disabled types return 404 (never 200), and error bodies never leak other tenants' data.
3. Generic REST poller configured via `mapping_def` ingests from a mock REST endpoint, applies ETag / Last-Modified delta detection, lands nodes through `GraphService.apply`, and exposes per-connector sync status (last success, DLQ count, events processed).
4. All consumer-facing HTTP is TLS 1.3 with HSTS. Connector credentials load from Vault via Spring Cloud Vault Config Data API. Tenant isolation proven by integration tests.
5. Field-level encryption decision is **recorded and enforced**: feature flag off + startup guard rejects any encrypted-marked property at boot time.

---

## Next Step

Run `/gsd-plan-phase 2 --skip-discuss` (or plain `/gsd-plan-phase 2` — it will see `02-CONTEXT.md` and proceed). Researcher will investigate:

- Spring AI / Spring Security JWT + Vault HMAC key loading patterns
- SpringDoc dynamic controller registration lifecycle (known research flag)
- Connector SPI shape (known research flag)
- ETag / Last-Modified delta detection libraries vs roll-your-own
- Connector mapping DSL libraries (JSONata, JMESPath, JSONPath)
- RFC 7807 Spring Boot 3 integration (`ProblemDetail`)
- Apache AGE cursor pagination patterns for label table scans

Then planner produces waves (likely 3–4 waves: scaffold + exposure + RBAC → REST CRUD → connector framework + first poller → TLS/Vault/audit).

---

*CONTEXT.md authored 2026-04-15 via /gsd-discuss-phase 2. 11 decisions locked; 7 items left to Claude's discretion or research.*
