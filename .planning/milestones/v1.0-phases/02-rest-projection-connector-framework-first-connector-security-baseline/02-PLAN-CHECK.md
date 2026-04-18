---
gsd_artifact: plan-check
phase: "02"
checked: 2026-04-15
checker: gsd-plan-checker
verdict: PASS WITH CAVEATS
---

# Phase 2 — Plan Check Report

**Verdict:** PASS WITH CAVEATS — execute after applying 3 minor patches (no re-plan needed).

Plans are dense, internally consistent, and trace end-to-end from CONTEXT → RESEARCH → PLAN → VALIDATION. 4 waves / 8 autonomous tasks / 24 ITs+unit tests. Task specificity on the three flagged-risk tasks (W1-01, W2-02, W3-02) is high enough to execute without mid-wave re-planning — each includes interface blocks, read-first pointers, TDD order, and acceptance greps.

Note: user prompt said "28 requirements"; ROADMAP and CONTEXT frontmatter actually list **21** (REST-01..07 = 7, CONN-01..08 = 8, SEC-01..06 = 6). Checked against 21.

---

## 1. Requirement Coverage (21 rows)

| Req    | Plan(s)       | Covering Task(s)                           | Test Artifact                          | Status |
|--------|---------------|--------------------------------------------|----------------------------------------|--------|
| REST-01| W2            | W2-01 GenericEntityController              | DenyAllExposureIT                      | ✓ |
| REST-02| W1, W2        | W1-01 _seq; W2-01 CursorCodec              | CursorPaginationConcurrencyIT          | ✓ |
| REST-03| W2            | W2-01 dispatcher; W2-02 advice             | ErrorShapeProblemJsonIT                | ✓ |
| REST-04| W1, W2        | W1-01 V12 flags; W2-01 exposure check      | DenyAllExposureIT                      | ✓ |
| REST-05| W0, W2        | W0-01 spike; W2-01 OpenApiSchemaCustomizer | SchemaVersionBumpIT+DynamicOpenApiIT   | ✓ |
| REST-06| W2            | W2-02 TesseraProblemHandler                | CrossTenantLeakPropertyIT (jqwik 500)  | ✓ |
| REST-07| W2            | W2-01 tenant-claim check                   | DenyAllExposureIT + CrossTenantLeak    | ✓ |
| CONN-01| W3            | W3-01 SPI + ArchUnit                       | ConnectorArchitectureTest              | ✓ |
| CONN-02| W3            | W3-01 MappingDefinition validation         | MappingDefinitionValidationTest        | ✓ |
| CONN-03| W3            | W3-01 Scheduler + ShedLock                 | ConnectorScheduleLockIT                | ✓ |
| CONN-04| W1, W3        | W1-02 DlqWriter; W3-02 runner DLQ path     | ConnectorDlqSameTxIT+RestPollingConn.IT| ✓ |
| CONN-05| W3            | W3-02 ETag + SourceHashCodec               | EtagDeltaDetectionIT                   | ✓ |
| CONN-06| W3            | W3-02 ConnectorStatusController            | ConnectorAdminCrudIT                   | ✓ |
| CONN-07| W3            | W3-02 GenericRestPollerConnector           | RestPollingConnectorIT (WireMock)      | ✓ |
| CONN-08| W3            | W3-01 ArchUnit (no write path in SPI)      | ConnectorArchitectureTest              | ✓ |
| SEC-01 | W2            | W2-02 SecurityConfig HSTS + TLS            | TlsHstsHeaderIT (direct + proxied)     | ✓ |
| SEC-02 | W2, W3        | W2-02 RotatableJwtDecoder; W3-02 Vault     | JwtRotationIT + VaultAppRoleAuthIT     | ✓ |
| SEC-03 | —             | Acknowledged in VALIDATION.md as infra/docs; **not in any plan frontmatter** | LUKS runbook only | ⚠ MAJOR |
| SEC-04 | W2            | W2-02 tenant claim filter                  | CrossTenantLeakPropertyIT              | ✓ |
| SEC-05 | W2            | Tenant-level only per CONTEXT D-10         | covered via REST-07                    | ✓ (scoped) |
| SEC-06 | W1            | W1-02 EncryptionStartupGuard               | EncryptionStartupGuardIT (3 flavours)  | ✓ |

**Coverage: 20/21 via tasks; SEC-03 scoped to docs with no plan pointer.**

---

## 2. Success Criteria Traceability (5 rows)

| # | ROADMAP Criterion                                                | Primary Gate                                                          | Wave   | Status |
|---|-------------------------------------------------------------------|-----------------------------------------------------------------------|--------|--------|
| 1 | Declare type + /v3/api-docs reflects flip without redeploy        | SchemaVersionBumpIT (spike) → DynamicOpenApiIT (prod)                 | W0+W2  | ✓ |
| 2 | Undeclared/disabled → 404 never 200; no cross-tenant leak         | DenyAllExposureIT + CrossTenantLeakPropertyIT + ErrorShapeProblemJsonIT| W2     | ✓ |
| 3 | Generic REST poller + ETag/LM + DLQ + sync status surface         | RestPollingConnectorIT + EtagDeltaDetectionIT + ConnectorAdminCrudIT  | W3     | ✓ |
| 4 | TLS 1.3 + HSTS + Vault + tenant isolation                        | TlsHstsHeaderIT + JwtRotationIT + VaultAppRoleAuthIT + CrossTenantLeak| W2+W3  | ✓ |
| 5 | Field-encryption decision recorded+enforced (flag off + guard)   | EncryptionStartupGuardIT                                              | W1     | ✓ |

---

## 3. Decision Honor (21 rows — spot-check of critical decisions)

| D  | Decision                                      | Honored In              | Status |
|----|-----------------------------------------------|-------------------------|--------|
| 1  | model_id = per-customer                       | W2-01 JWT tenant↔path   | ✓ |
| 2  | Field-encryption flag OFF + startup guard     | W1-02 EncryptionStartupGuard + V12 columns ship but no crypto code | ✓ |
| 3  | Connector auth = Bearer only                  | W3-01 CHECK (auth_type IN ('BEARER')) + W3-02 Vault bearer path | ✓ |
| 4  | Cursor pagination (opaque base64)             | W2-01 CursorCodec + CursorPaginationConcurrencyIT | ✓ |
| 5  | Schema Registry rest_*_enabled flags          | W1-01 V12 + W2-01 exposure check | ✓ |
| 6  | OAuth2 JWT + HMAC from Vault + roles claim    | W2-02 RotatableJwtDecoder + NimbusJwtDecoder.withSecretKey | ✓ |
| 7  | Connector lifecycle DB-backed + admin REST    | W3-01 V13 + W3-02 ConnectorAdminController + hot-reload events | ✓ |
| 8  | RFC 7807 problem+json                         | W2-02 TesseraProblemHandler + ProblemDetail + ErrorShapeIT | ✓ |
| 9  | Fixed interval + ShedLock per connector_id    | W3-01 Scheduler + ConnectorScheduleLockIT | ✓ |
| 10 | Row ACL = tenant-only                         | W2-02; property ACL deferred explicitly | ✓ |
| 11 | 404 deny-all, NEVER 403, for unauth           | W2-02 advice maps TypeNotExposed/CrossTenant→404; CrossTenantLeakPropertyIT fuzz 500 | ✓ |
| 12 | `_seq` denormalization in Wave 1              | W1-01 GraphSession writes _seq; V10 composite index | ✓ |
| 13 | SpringDoc Wave 0 spike with STOP-and-escalate | W0-01; explicit "If fails, STOP and escalate to orchestrator" in action step 5 and Javadoc | ✓ |
| 14 | DLQ same-TX via REQUIRES_NEW                  | W1-02 ConnectorDlqWriter (@Transactional(REQUIRES_NEW)) + ConnectorDlqSameTxIT asserts row survives outer rollback; planner flagged CONTEXT wording ambiguity and interpreted loudly | ✓ (with interpretation note) |
| 15 | /admin/* prefix                                | W2-02 TokenIssueController + W3-02 ConnectorAdminController all under `/api/v1/admin/*` — see minor M-2 | ✓ (with minor) |
| 16 | Jayway JSONPath 2.9.0 + closed transform enum | W3-01 TransformRegistry closed switch; W3-02 JacksonMappingProvider | ✓ |
| 17 | Vault AppRole (token fallback in tests)       | W3-02 VaultAppRoleAuthIT dedicated, other ITs use token auth | ✓ |
| 18 | Two-layer delta (ETag + per-row hash)         | W3-02 ETag conditional headers + SourceHashCodec + `_source_hash` node property | ✓ |
| 19 | Dedicated /status endpoint                    | W3-02 ConnectorStatusController (separate from list) | ✓ |
| 20 | OpenApiCustomizer + cache.disabled=true       | W0-01 spike + W2-01 promotion; `springdoc.cache.disabled=true` in W2-02 application.yml | ✓ |
| 21 | /admin/tokens/issue bootstrap                 | W2-02 TokenIssueController + X-Tessera-Bootstrap header guard | ✓ |

---

## 4. Other Dimensions

| Check                                                                          | Status |
|--------------------------------------------------------------------------------|--------|
| Phase 1 deviations #1 (currentSourceSystem) + #2 (ConflictRecord label) closed | ✓ W0-02 |
| ArchUnit boundary: `fabric-connectors` ↛ `graph.internal`                      | ✓ W3-01 ConnectorArchitectureTest |
| ArchUnit boundary: `fabric-projections` ↛ `graph.internal`                     | ✓ W2-01 ProjectionsModuleDependencyTest |
| Wave ordering W0 → W1 → W2 → W3, no forward deps                                | ✓ depends_on chain is linear |
| Every task has ≥1 IT/unit                                                      | ✓ 8/8 |
| Reused assets not reinvented (GraphService.apply, Caffeine, SequenceAllocator, WriteRateCircuitBreaker, Flyway pattern, Testcontainers AGE helper) | ✓ all reused explicitly |
| No deferred ideas leak into plans (GraphQL, row ACLs, full FLE, OAuth2 CC, HTTP Basic, cron, OIDC, write-back, property ACLs) | ✓ none present |
| Scope per plan                                                                  | W2 is large (11 ITs, 15 files) but cohesively split across 2 tasks; acceptable for the "security baseline" bundling. Borderline, see MINOR M-1. |

---

## 5. Findings

### MAJOR

**M-2: SEC-03 is absent from all plan frontmatter `requirements:` lists.**
ROADMAP maps SEC-03 to Phase 2. VALIDATION.md §Per-Requirement Map row 57 correctly notes it as "Operational / infra — LUKS on IONOS, documented in deployment runbook; no code gate this phase". That is a defensible scoping decision, but the plan frontmatter remains silent, which will trip any mechanical coverage audit (including this one) and creates a requirements-tracking blind spot for Phase 2 sign-off.
**Fix:** Add `- SEC-03` to W2's frontmatter `requirements:` list with an inline `# docs-only, see 02-VALIDATION.md §row 57` comment, OR add an explicit "SEC-03 → deferred to deployment runbook (not a Phase 2 code deliverable)" note to ROADMAP Phase 2 block so the roadmap and plans agree.

### MINOR

**m-1: W2 scope is at the upper bound.** 15 files modified + 11 ITs across 2 tasks. Plan-checker threshold is "warning" at 10 files or 4 tasks per plan. W2 sits at 2 tasks but double the file count. Task boundaries are cleanly drawn (T01 CRUD+cursor+OpenAPI; T02 security+RFC 7807+TLS) and each task has its own verify command, so execution should stay within a single context window per task. Monitor W2 execution for context pressure; consider splitting T02 (security config vs problem handler vs TLS IT) only if the executor agent reports degradation.

**m-3: W2-01 acceptance grep uses glob `fabric-projections/src/main/java/dev/tessera/projections/**/*.java`** which bash will not expand without `shopt -s globstar` or `find -path`. Executor should convert to `grep -rn "graph.internal" fabric-projections/src/main/java`. File: 02-W2-PLAN.md line 225.

**m-4: W3-01 ShedLock V15 migration may collide with Phase 1 V10 shedlock.** The plan action step 2 anticipates this and instructs `CREATE TABLE IF NOT EXISTS` or renumber; behaviour is correct but choice must be documented in W3 SUMMARY. Non-blocking but flagged for executor awareness. File: 02-W3-PLAN.md line 243.

### INFO

- W1-01 action step 1 correctly calls out that the AGE `agtype`-to-BIGINT index expression needs verification against Phase 1's actual label table layout before committing V10. This is the highest execution-time risk in the phase; planner flagged it explicitly, which is the right move.
- W1-02 raises and interprets a CONTEXT Decision 14 wording ambiguity (literal reading ("same TX") would make DLQ invisible) and resolves to `Propagation.REQUIRES_NEW`. Interpretation is correct and standard. Surface to user at wave SUMMARY per planner note.
- W2-02 accepts T-02-W2-10 (unauth readable /v3/api-docs) in threat model — consistent with Decision 20 rationale. Non-issue.
- W3-02 Bearer-token log-scrubber grep gate is mentioned in the threat table but not in acceptance_criteria greps. Consider promoting to an acceptance line if log hygiene regression matters. Non-blocking.

---

## 6. Recommendation

**Patch then execute.** Apply M-2 (add SEC-03 to a plan frontmatter or to ROADMAP) before Wave 0 commit — 2-line edit. m-3 and m-4 are executor-time concerns the planner already anticipated. m-1 is a scope watch, not a block.

No re-plan required. Plans are execution-ready.
