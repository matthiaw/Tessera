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

### 12. Node sequence denormalization — add `_seq` to nodes in Wave 1

**Decision:** Phase 1 stored `sequence_nr` only on `graph_events`. Phase 2 Wave 1 denormalizes it onto nodes: every `GraphServiceImpl.apply` call writes `_seq BIGINT` (from the per-tenant sequence already allocated for the event) as a property on the created/updated node. A Flyway migration (V10) adds a composite index on `(model_id, _seq)` via AGE's label-table access.

**Why:** Cursor pagination needs a stable, monotonic sort key on nodes. UUID-order is random; JOIN-to-events is slow. Denormalization is a one-wave change with clean long-term semantics.

**How it shapes planning:**
- Wave 1 task: modify `GraphServiceImpl.apply` to pass `_seq` into the node create/update Cypher.
- Wave 1 task: Flyway V10 indexes `(model_id, _seq)` on the AGE label tables.
- Cursor is `base64(model_id || type_slug || last_seq || last_node_id)`.
- Existing Phase 1 test fixtures may need update if they assert exact property sets on nodes.

### 13. SpringDoc dynamic lifecycle risk — Wave 0 spike

**Decision:** Before Wave 1 touches REST, a dedicated Wave 0 spike (~30 min) ships a `SchemaVersionBumpIT` that declares a type, hits `/v3/api-docs`, flips `rest_read_enabled`, hits `/v3/api-docs` again, and asserts the new path appears. Only if the spike passes does Wave 1 commit to the `OpenApiCustomizer` rebuild-per-request model. If it fails, the fallback (documented restart-on-schema-change) is discussed with the user BEFORE Wave 1 code lands.

**Why:** The `OpenApiCustomizer` pattern is idiomatic but unverified on SpringDoc 2.8.x. 30 minutes of spike work de-risks the entire REST-05 success criterion and protects the "without a redeploy" ROADMAP guarantee.

**How it shapes planning:**
- Phase 2 plan starts with a tiny Wave 0 (spike only, no production code).
- If spike fails: orchestrator returns to user for fallback decision before proceeding.

### 14. DLQ write path — inside `GraphService.apply` same-TX

**Decision:** When a connector-submitted `CandidateMutation` fails validation or a rule rejects it, `GraphService.apply` writes a DLQ row in the same Postgres transaction before rolling back the graph mutation. The DLQ row captures: mutation payload, rejection reason, rule id (if applicable), connector id, tenant, timestamp.

**Why:** Preserves the single-write-funnel invariant (no caller outside `graph.internal` writes to Postgres). Operator visibility is immediate. Slight coupling from `core` to the DLQ table is acceptable — DLQ becomes a first-class piece of the core surface.

**How it shapes planning:**
- Flyway V11 creates `connector_dlq` table.
- `GraphServiceImpl.apply` grows a `try/catch` that writes DLQ and re-throws (or returns a rejected outcome) inside the same TX.
- Distinction between "DLQ row = caller wants to know" and "event log = authoritative truth" must stay clean: the DLQ write is metadata about the failed attempt, the event log is untouched on rejection.
- Admin REST endpoint `/admin/connectors/{id}/dlq` lists rows per connector.

---

## Resolved — Previously Open Items

The seven items below were initially left to Claude's discretion. They have all been resolved by the Phase 2 research pass (`02-RESEARCH.md`) and locked in by the planner. Recording the decisions here so CONTEXT.md remains the single source of truth for execution.

### 15. Admin endpoint path prefix — `/admin/*`

**Decision:** All admin endpoints live under `/admin/*` at the root, not nested inside `/api/v1/*` and not on a separate port.

**Why:** Keeps the `/api/v1/{model}/entities/*` surface clean for data consumers. One Spring Security filter chain can protect the prefix with a bootstrap `ROLE_ADMIN`. Separate port adds ops burden for no isolation benefit (the Vault-signed JWT already carries role).

**Endpoints in Phase 2:** `/admin/tokens/issue`, `/admin/connectors` (CRUD), `/admin/connectors/{id}/status`, `/admin/connectors/{id}/dlq`, `/admin/schema/*/expose` (flip `rest_read_enabled`).

### 16. Connector mapping DSL — Jayway JSONPath 2.9.0

**Decision:** `mapping_def` JSONB stores a `JSONPath`-based mapping: a flat map from target property name to `{ path: "$.data.name", transform: "lowercase" }` entries. Transforms are a **closed registry** (Java enum `Transform` with values like `LOWERCASE`, `UPPERCASE`, `ISO8601_DATE`, `TRIM`, `SHA256`). No expression language.

**Why:** Jayway JSONPath 2.9.0 is mature, battle-tested in Spring, and covers the 95% case. JSONata and JMESPath add a second-language surface with marginal gains. A closed transform registry keeps auditing and failure modes tractable — operators can't inject arbitrary code via `mapping_def`.

**Dependencies:** `com.jayway.jsonpath:json-path:2.9.0` with `JacksonMappingProvider` so types align with Spring's Jackson config.

**Deferred:** Per-field JavaScript/Groovy expressions, nested-array flattening beyond JSONPath semantics, conditional mappings.

### 17. Vault auth method — AppRole (with token fallback for tests)

**Decision:** Production uses AppRole (`role_id` + `secret_id`) loaded via Spring Cloud Vault Config Data API. Integration tests default to token auth for speed, with a dedicated `VaultAppRoleAuthIT` exercising the production path against a Testcontainers Vault.

**Why:** AppRole is the standard pattern for non-interactive service auth, cleanly rotatable, and doesn't require Kubernetes (Tessera targets IONOS VPS). Kubernetes auth is deferred to the day someone actually runs Tessera on K8s.

**Paths:**
- `secret/tessera/auth/jwt_signing_key` — HMAC signing key for resource-server JWTs
- `secret/tessera/connectors/{connector_id}/bearer_token` — per-connector bearer tokens
- AppRole credentials live outside Vault's KV store (bootstrapped via operator)

### 18. ETag / Last-Modified detection — two-layer

**Decision:** The generic REST poller combines two delta-detection layers:

1. **Connector level.** Per connector, store the latest `ETag` and `Last-Modified` seen in `connector_sync_status`. Send `If-None-Match` / `If-Modified-Since` on subsequent polls. A 304 response skips the entire poll.
2. **Per-row hash.** Each polled row is hashed (SHA-256 over sorted `(path, value)` tuples) and the hash stored as `_source_hash` on the resulting node. On the next poll, rows whose hash matches the existing node are skipped before any mutation is built. Rows with a new hash produce a `CandidateMutation`.

**Why:** Most SaaS APIs don't implement RFC-correct ETags. Per-row hashing is the safety net that makes dedup actually work. The connector-level layer shaves traffic when the upstream behaves.

**Storage:** `_source_hash` is a reserved system property alongside `_seq`.

### 19. Sync-status surface — dedicated `/admin/connectors/{id}/status` endpoint

**Decision:** Sync status is served from a dedicated `GET /admin/connectors/{id}/status` endpoint. The list endpoint `GET /admin/connectors` returns only configuration (id, type, enabled, poll interval); it does NOT embed live status.

**Why:** Keeps the list response fast and cacheable. Status data (last success timestamp, DLQ count, events processed, last error) changes on every poll and is best fetched on demand. Operator dashboards poll the status endpoint independently.

**Shape:**
```json
{
  "connector_id": "...",
  "last_poll_started_at": "...",
  "last_poll_completed_at": "...",
  "last_poll_outcome": "SUCCESS|FAILED|NO_CHANGE",
  "last_error": null,
  "dlq_count": 0,
  "events_processed_total": 12345,
  "rows_seen_last_poll": 42
}
```

### 20. OpenAPI lifecycle for dynamic controllers — `OpenApiCustomizer` + Wave 0 spike

**Decision:** `GenericEntityController` is a single `@RequestMapping` dispatcher. OpenAPI is produced by a `GroupedOpenApi` bean with an `OpenApiCustomizer` that walks the Schema Registry at document-build time. `springdoc.cache.disabled=true` forces a rebuild on every `/v3/api-docs` request so a newly-flipped `rest_read_enabled` shows up without restart. Wave 0 ships a `SchemaVersionBumpIT` spike gating this approach (Decision 13).

**Why:** Confirmed in research (`02-RESEARCH.md` Q1) as the idiomatic SpringDoc 2.8.x path. The `cache.disabled=true` setting is load-bearing — without it, the rebuild only happens on a restart.

**Fallback path (if spike fails):** Return to user for a documented restart-on-schema-change decision before Wave 1 writes production code.

### 21. Bootstrap token issuance — `/admin/tokens/issue` REST endpoint

**Decision:** A minimal `POST /admin/tokens/issue` endpoint mints short-lived (15 min) JWTs signed with the Vault-held HMAC key. Protected by a bootstrap `ROLE_TOKEN_ISSUER` that is seeded from a Vault secret at first startup.

**Why:** Keeps the entire auth path inside the application — no separate CLI tool to build, document, and keep in sync with the JWT signing key. Bootstrap flow: operator reads the seeded token-issuer secret from Vault, calls `/admin/tokens/issue` once to get an admin JWT, then uses that JWT for everything else. Rotation by rewriting the Vault secret and reissuing.

**Out of scope:** Refresh tokens, full OAuth2 authorization server flows, IdP federation. Those arrive with the Keycloak/Authentik migration in a later phase.

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
