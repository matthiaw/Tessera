---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
created: 2026-04-15
nyquist_compliant: true
---

# Phase 2 — Validation Strategy

Per-phase validation contract mirroring `01-VALIDATION.md`. Source: `02-RESEARCH.md` §Validation Architecture + `02-CONTEXT.md` Success Criteria.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`spring-boot-starter-test` 3.5.13) + AssertJ + jqwik 1.9.2 (REST-06 fuzz) + Testcontainers 1.20.4 (AGE, Vault, WireMock) + REST-assured 5.5.x + WireMock 3.10.x + ArchUnit 1.3 |
| Quick run | `./mvnw -pl <touched-module> -am test` (<30s) |
| Wave gate | `./mvnw -B verify` |
| Integration suffix | `*IT.java` (Failsafe) |
| Unit suffix | `*Test.java` (Surefire) |
| JMH | `./mvnw -pl fabric-projections -Pjmh -Djmh.bench=RestProjectionBench verify` (new p95 < 50ms gate, warn-only until Wave 3 merge) |
| Expected full-suite runtime | ~8 min (adds ~2 min for VaultContainer startup + WireMock scenarios over Phase 1 baseline) |

---

## Sampling Rate

- **After every task commit:** `./mvnw -pl <touched-module> test` (surefire only, <30s)
- **After every wave merge:** `./mvnw -B verify` (full reactor, surefire + failsafe + ArchUnit)
- **Before `/gsd-verify-work`:** full `./mvnw -B verify` + JMH check `./mvnw -pl fabric-projections -Pjmh -Djmh.bench=RestProjectionBench verify` + Phase 1 regression check `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench verify` (must stay < 11ms p95 — Phase 1's gate)
- **Max feedback latency:** ~30s quick; ~8 min wave gate

---

## Per-Requirement Verification Map

| Req ID | Plan | Wave | Test File | Command | Type |
|--------|------|------|-----------|---------|------|
| REST-01 | 02-W2 | W2 | `GenericEntityControllerTest` (unit) + `DenyAllExposureIT` | `./mvnw -pl fabric-projections -Dit.test=DenyAllExposureIT verify` | unit + IT |
| REST-02 | 02-W2 | W2 | `CursorPaginationConcurrencyIT` | `./mvnw -pl fabric-projections -Dit.test=CursorPaginationConcurrencyIT verify` | IT |
| REST-03 | 02-W2 | W2 | `ErrorShapeProblemJsonIT` (validation path) | `./mvnw -pl fabric-projections -Dit.test=ErrorShapeProblemJsonIT verify` | IT |
| REST-04 | 02-W2 | W2 | `DenyAllExposureIT` | `./mvnw -pl fabric-projections -Dit.test=DenyAllExposureIT verify` | IT |
| REST-05 | 02-W0 + 02-W2 | W0 + W2 | `SchemaVersionBumpIT` (spike) + `DynamicOpenApiIT` (prod) | `./mvnw -pl fabric-projections -Dit.test='SchemaVersionBumpIT,DynamicOpenApiIT' verify` | IT |
| REST-06 | 02-W2 | W2 | `CrossTenantLeakPropertyIT` + `ErrorShapeProblemJsonIT` | `./mvnw -pl fabric-projections -Dit.test='CrossTenantLeakPropertyIT,ErrorShapeProblemJsonIT' verify` | IT (jqwik fuzz) |
| REST-07 | 02-W2 | W2 | `DenyAllExposureIT` (tenant scope) + `CrossTenantLeakPropertyIT` | (see above) | IT |
| CONN-01 | 02-W3 | W4 | `ConnectorArchitectureTest` + `ConnectorScheduleLockIT` | `./mvnw -pl fabric-app -Dtest=ConnectorArchitectureTest test` + `./mvnw -pl fabric-connectors -Dit.test=ConnectorScheduleLockIT verify` | ArchUnit + IT |
| CONN-02 | 02-W3 | W4 | `MappingDefinitionValidationTest` | `./mvnw -pl fabric-connectors -Dtest=MappingDefinitionValidationTest test` | unit |
| CONN-03 | 02-W3 | W4 | `ConnectorScheduleLockIT` | `./mvnw -pl fabric-connectors -Dit.test=ConnectorScheduleLockIT verify` | IT |
| CONN-04 | 02-W1 + 02-W3 | W2 + W4 | `ConnectorDlqSameTxIT` (substrate) + `RestPollingConnectorIT` (end-to-end) | `./mvnw -pl fabric-core,fabric-connectors -Dit.test='ConnectorDlqSameTxIT,RestPollingConnectorIT' verify` | IT |
| CONN-05 | 02-W3 | W4 | `EtagDeltaDetectionIT` + `RestPollingConnectorIT` (per-row hash skip path) | `./mvnw -pl fabric-connectors -Dit.test='EtagDeltaDetectionIT,RestPollingConnectorIT' verify` | IT |
| CONN-06 | 02-W3 | W4 | `ConnectorAdminCrudIT` (status endpoint) | `./mvnw -pl fabric-projections -Dit.test=ConnectorAdminCrudIT verify` | IT |
| CONN-07 | 02-W3 | W4 | `RestPollingConnectorIT` (WireMock end-to-end) | `./mvnw -pl fabric-connectors -Dit.test=RestPollingConnectorIT verify` | IT |
| CONN-08 | 02-W3 | W4 | `ConnectorArchitectureTest` (runner-only write funnel) | `./mvnw -pl fabric-app -Dtest=ConnectorArchitectureTest test` | ArchUnit |
| SEC-01 | 02-W2 | W2 | `TlsHstsHeaderIT` | `./mvnw -pl fabric-projections -Dit.test=TlsHstsHeaderIT verify` | IT |
| SEC-02 | 02-W2 + 02-W3 | W2 + W4 | `JwtRotationIT` (Vault → JWT decoder) + `VaultAppRoleAuthIT` (Vault → connector creds) | `./mvnw -pl fabric-projections,fabric-connectors -Dit.test='JwtRotationIT,VaultAppRoleAuthIT' verify` | IT |
| SEC-03 | — | — | Operational / infra — LUKS on IONOS, documented in deployment runbook; no code gate this phase | (docs only) | docs |
| SEC-04 | 02-W2 | W2 | `CrossTenantLeakPropertyIT` (tenant row ACL) | (see REST-06) | IT |
| SEC-05 | 02-W2 | W2 | Satisfied at tenant-level only; property/row ACL deferred per CONTEXT D-10 | — | n/a |
| SEC-06 | 02-W1 | W2 | `EncryptionStartupGuardIT` (three flavours) | `./mvnw -pl fabric-core -Dit.test=EncryptionStartupGuardIT verify` | IT |

---

## Per-Roadmap-Success-Criterion Mapping

| # | ROADMAP Phase 2 Success Criterion | Primary Gate | Wave |
|---|-----------------------------------|--------------|------|
| 1 | Declare type + flip exposure → CRUD endpoints appear in /v3/api-docs without redeploy | `DynamicOpenApiIT` + `DenyAllExposureIT` flip case | W0 (spike) + W2 |
| 2 | Undeclared/disabled types → 404 (never 200); error bodies never leak other tenants' data | `DenyAllExposureIT` + `CrossTenantLeakPropertyIT` + `ErrorShapeProblemJsonIT` | W2 |
| 3 | Generic REST poller ingests mock + ETag/LM delta + DLQ/sync surface | `RestPollingConnectorIT` + `EtagDeltaDetectionIT` + `ConnectorAdminCrudIT` (status endpoint) | W4 |
| 4 | TLS 1.3 + HSTS + Vault-loaded connector creds + tenant isolation ITs | `TlsHstsHeaderIT` + `VaultAppRoleAuthIT` + `CrossTenantConnectorIsolationIT` + `CrossTenantLeakPropertyIT` | W2 + W4 |
| 5 | Field-encryption decision recorded + enforced (flag off + startup guard) | `EncryptionStartupGuardIT` | W2 |

---

## New Gates Introduced in Phase 2

| Gate | Scope | Threshold |
|------|-------|-----------|
| `RestProjectionBench` | List endpoint p95 at 100k nodes | < 50ms (warn-only in W2, gate-on-merge in W3) |
| `ConnectorArchitectureTest` | ArchUnit: `fabric-connectors` ↛ `graph.internal`; only `ConnectorRunner` may call `GraphService` | binary pass/fail |
| `ProjectionsModuleDependencyTest` | ArchUnit: `fabric-projections` ↛ `graph.internal` | binary pass/fail |
| `CrossTenantLeakPropertyIT` | jqwik 500 random (tenant_jwt, tenant_path) pairs | zero leaks, identical error shape |

---

## Regression Gates (Phase 1, MUST stay green)

- `./mvnw -pl fabric-core -Dit.test=TenantBypassPropertyIT verify` (CORE-03 jqwik 7×1000)
- `./mvnw -pl fabric-core -Dit.test=GraphServiceApplyIT verify` (CORE-01 single TX)
- `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench verify` (p95 < 11ms)
- `./mvnw -pl fabric-core -Pjmh -Djmh.bench=ShaclValidationBench verify` (p95 < 2ms)
- `./mvnw -pl fabric-app -Dtest='RawCypherBanTest,ModuleDependencyTest' test`
- All Phase 1 rule-engine ITs (`SourceAuthorityIT`, `ConflictRegisterIT`, `EchoLoopSuppressionIT`, `RuleRegistrationIT`, `BusinessRuleRejectIT`, `CircuitBreakerIT`)

---

## Wave Gate Summary

| Wave | Plan | Blocking ITs | Advisory |
|------|------|--------------|----------|
| W0 (wave:1) | 02-W0 | `SchemaVersionBumpIT`, `GraphServiceAuthorityThreadingIT`, `ChainExecutorTest` | — |
| W1 (wave:2) | 02-W1 | `NodeSequencePropertyIT`, `SchemaRestExposureColumnsIT`, `ConnectorDlqSameTxIT`, `EncryptionStartupGuardIT` | WritePipelineBench regression watch |
| W2 (wave:3) | 02-W2 | `DenyAllExposureIT`, `CursorPaginationConcurrencyIT`, `DynamicOpenApiIT`, `CrossTenantLeakPropertyIT`, `ErrorShapeProblemJsonIT`, `JwtRotationIT`, `TlsHstsHeaderIT`, `ProjectionsModuleDependencyTest` | `RestProjectionBench` p95 |
| W3 (wave:4) | 02-W3 | `ConnectorScheduleLockIT`, `MappingDefinitionValidationTest`, `ConnectorArchitectureTest`, `RestPollingConnectorIT`, `EtagDeltaDetectionIT`, `ConnectorAdminCrudIT`, `CrossTenantConnectorIsolationIT`, `VaultAppRoleAuthIT` | — |

---

*Authored alongside plan creation, 2026-04-15.*
