# Roadmap: Tessera

**Milestone:** 1 — MVP through first real consumer
**Created:** 2026-04-13
**Granularity:** standard
**Core Value:** The graph is the truth; everything else is a projection.

## Phases

- [ ] **Phase 0: Foundations & Risk Burndown** - Scaffold, pin AGE, benchmark harness, dump/restore rehearsal, tenant primitives
- [ ] **Phase 1: Graph Core, Schema Registry, Validation, Rules** - The two spines (Schema Registry + Event Log/Outbox) plus SHACL and rule engine
- [ ] **Phase 2: REST Projection, Connector Framework, First Connector, Security Baseline** - Runtime-routed REST, generic REST poller, Vault, TLS, RBAC
- [ ] **Phase 2.5: Unstructured Ingestion & Entity Extraction** - LLM-based entity extraction from free text, pgvector-backed entity resolution, first unstructured connector
- [ ] **Phase 3: MCP Projection (Flagship Differentiator)** - Spring AI MCP tools driven by the Schema Registry, read-only by default, audited
- [ ] **Phase 4: SQL View + Kafka Projections, Hash-Chained Audit** - Aggregation escape hatch, Debezium outbox swap, optional compliance audit chain
- [ ] **Phase 5: Circlead Integration & Production Hardening** - First real consumer, observability, DR drill, snapshots and retention

## Phase Details

### Phase 0: Foundations & Risk Burndown
**Goal**: Establish a reproducible, pinned environment for PostgreSQL 16 + Apache AGE 1.6 and prove (via benchmarks and a dump/restore rehearsal) that the highest-risk technology behaves acceptably before any feature work begins.
**Depends on**: Nothing (first phase)
**Requirements**: FOUND-01, FOUND-02, FOUND-03, FOUND-04, FOUND-05, FOUND-06
**Success Criteria** (what must be TRUE):
  1. A developer can clone the repo and bring up the full local environment (Docker Compose with PG16 + AGE pinned to digest) in one command, with Flyway baseline applied and AGE session init working through HikariCP.
  2. `mvn verify` runs a multi-module build (`fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`) with Maven enforcer and ArchUnit blocking illegal upward dependencies and raw Cypher outside `graph.internal`.
  3. The benchmark harness executes point-lookup, 2-hop traversal, aggregate, and ordered pagination against 100k and 1M node datasets in CI, and publishes results that can be compared phase-over-phase.
  4. A scheduled CI job performs `pg_dump` followed by `pg_restore` against a seeded AGE database and validates the restored graph is queryable — the major-upgrade runbook is proven, not assumed.
  5. Testcontainers-based integration tests run green against `apache/age:PG16_latest` from a fresh checkout.
**Plans**: 5 plans
Plans:
- [ ] 00-01-PLAN.md — Maven multi-module scaffold (parent POM + 5 modules + Wrapper + hygiene + LICENSE/NOTICE/CONTRIBUTING)
- [ ] 00-02-PLAN.md — docker-compose with sha256 digest-pinned apache/age + README quick-start
- [ ] 00-03-PLAN.md — Flyway V1 + HikariCP init + Testcontainers helper + 4 ITs + TenantContext + ArchUnit module-direction + image-pin tests
- [ ] 00-04-PLAN.md — JMH benchmark harness (SeedGenerator + 4 bench classes + JmhRunner + regression script + 100k baseline)
- [ ] 00-05-PLAN.md — dump/restore rehearsal script + GitHub Actions ci.yml + nightly.yml

### Phase 1: Graph Core, Schema Registry, Validation, Rules
**Goal**: Deliver the two spines of Tessera — the Schema Registry and the Event Log + Outbox — wrapped by a single transactional write funnel that enforces tenant isolation, SHACL validation, and priority-based reconciliation rules. No projections, no connectors; just a trustworthy graph core.
**Depends on**: Phase 0
**Requirements**: CORE-01, CORE-02, CORE-03, CORE-04, CORE-05, CORE-06, CORE-07, CORE-08, SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04, SCHEMA-05, SCHEMA-06, SCHEMA-07, SCHEMA-08, VALID-01, VALID-02, VALID-03, VALID-04, VALID-05, EVENT-01, EVENT-02, EVENT-03, EVENT-04, EVENT-05, EVENT-06, EVENT-07, RULE-01, RULE-02, RULE-03, RULE-04, RULE-05, RULE-06, RULE-07, RULE-08
**Success Criteria** (what must be TRUE):
  1. Every mutation flows through `GraphService.apply()` as a single Postgres transaction covering auth → rules → SHACL → Cypher → event log → outbox; an ArchUnit test fails the build if any caller bypasses it or executes raw Cypher outside `graph.internal`.
  2. An operator can define a node type, its properties, and edge types through the Schema Registry API, version the schema, rename a property via alias without breaking reads, and observe the change picked up by SHACL validation without restart.
  3. A mutation that violates a SHACL shape or a business rule is rejected at commit time with a tenant-filtered `ValidationReport` — the test suite includes fuzz tests proving no code path can read or write across `model_id` boundaries.
  4. Given a node UUID and a past timestamp, the system reconstructs that node's state via event-log replay; given a node, the full mutation history with cause attribution (`origin_connector_id`, `origin_change_id`) is retrievable.
  5. With two connectors configured on conflicting properties, the per-tenant authority matrix deterministically resolves the winner, the loser is recorded in `reconciliation_conflicts`, and the write-amplification circuit breaker halts a runaway connector before a conflict storm.
**Plans**: 4 plans
Plans:
- [x] 01-W0-PLAN.md — Wave 0 scaffolding: jqwik, Flyway V2..V8, GraphMutation/GraphService/GraphSession contracts, ArchUnit raw-Cypher ban (CORE-02), test shells, JMH skeletons
- [x] 01-W1-PLAN.md — Wave 1 graph core + tenant safety: GraphServiceImpl + single-TX write funnel, GraphSession raw-Cypher executor, per-tenant SEQUENCE allocator, event log + outbox writers, jqwik TenantBypassPropertyIT (CORE-03), WritePipelineBench baseline
- [x] 01-W2-PLAN.md — Wave 2 two spines: Schema Registry CRUD + event-sourced versioning + aliases + Caffeine cache, event log hardening (partitioning, provenance, temporal replay, audit history), outbox poller with @Scheduled + ShedLock + ApplicationEventPublisher
- [x] 01-W3-PLAN.md — Wave 3 SHACL + Rule Engine + circuit breaker: Jena SHACL validator + shape cache + tenant-filtered ValidationReport, four-chain rule engine (VALIDATE/RECONCILE/ENRICH/ROUTE), source authority matrix + conflict register + echo-loop suppression, write-amplification circuit breaker + DLQ + admin reset, ShaclValidationBench < 2ms, WritePipelineBench < 11ms

### Phase 2: REST Projection, Connector Framework, First Connector, Security Baseline
**Goal**: Expose the graph through a dynamically-generated REST projection and ingest real data through the first concrete connector (generic REST polling), with TLS, Vault-managed secrets, row/field-level access control, and fail-closed endpoint defaults. Decide explicitly on field-level encryption: ship fully or keep feature-flagged off.
**Depends on**: Phase 1
**Requirements**: REST-01, REST-02, REST-03, REST-04, REST-05, REST-06, REST-07, CONN-01, CONN-02, CONN-03, CONN-04, CONN-05, CONN-06, CONN-07, CONN-08, SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, SEC-06
**Success Criteria** (what must be TRUE):
  1. A developer declares a new node type in the Schema Registry and, without touching controller code or redeploying, can `GET/POST/PUT/DELETE /api/v1/{model}/entities/{typeSlug}` through a single `GenericEntityController`, with the new endpoint visible in `/v3/api-docs`.
  2. Newly generated endpoints are deny-all until an explicit `exposure` policy is declared; a test proves an undeclared type returns 403/404 and never 200, and that error responses cannot leak other tenants' data.
  3. A generic REST poller connector configured via a `MappingDefinition` pulls rows from a mock REST endpoint, applies ETag / `Last-Modified` delta detection, lands them as graph nodes through `GraphService.apply()`, and exposes sync status (last success, DLQ count, events processed) per `(connector_id, model_id)`.
  4. All consumer-facing HTTP traffic is TLS 1.3 with HSTS; connector credentials are loaded from HashiCorp Vault via Spring Cloud Vault Config Data API and never appear in config files or the fabric DB; row-level and field-level access control filters responses based on caller role.
  5. The field-level encryption decision is recorded and enforced: either the feature flag is off (and writes to encrypted-marked properties are rejected at startup), or the full machinery (per-tenant blind index, multi-version DEKs, fail-closed writes on KMS outage, KMS chaos test in CI) is green.
**Plans**: 4 plans
Plans:
- [x] 02-W0-PLAN.md — Wave 0 SpringDoc dynamic-OpenAPI spike (SchemaVersionBumpIT) + Phase 1 deviation closure (thread currentSourceSystem through GraphServiceImpl.apply; fix ChainExecutor ConflictRecord.winningSourceSystem labelling)
- [x] 02-W1-PLAN.md — Wave 1 graph denormalization + schema exposure/encryption columns + DLQ substrate: V10 node _seq indexes, V11 connector_dlq, V12 rest_read_enabled/rest_write_enabled/property_encrypted, GraphSession writes _seq, same-TX REQUIRES_NEW DLQ writer, SEC-06 startup guard
- [x] 02-W2-PLAN.md — Wave 2 REST projection + security baseline: GenericEntityController dispatcher, cursor pagination (seek-method), OpenApiCustomizer (promoted from W0 spike), OAuth2 resource server + Vault HMAC via RotatableJwtDecoder, RFC 7807 ControllerAdvice, TLS 1.3 + HSTS, /api/v1/admin/tokens/issue, RestProjectionBench p95 < 50ms, ProjectionsModuleDependencyTest
- [x] 02-W3-PLAN.md — Wave 3 connector framework + first connector: Connector SPI + V13/V14/V15 migrations, ConnectorRegistry + Runner + Scheduler (ShedLock per connector_id), GenericRestPollerConnector (Bearer from Vault + Jayway JSONPath + ETag/LM + _source_hash), /api/v1/admin/connectors CRUD + /status + /dlq, ConnectorArchitectureTest, VaultAppRoleAuthIT

### Phase 2.5: Unstructured Ingestion & Entity Extraction
**Goal**: Extend the connector framework with a second mode — LLM-based extraction of typed entities and relationships from unstructured text — so that free-text sources (wikis, notes, chat logs, emails, code commentary) land as first-class graph data that flows through the same rule engine, SHACL validation, reconciliation, and source authority matrix as structured connectors. Extracted candidates must be indistinguishable from REST-polled candidates downstream of the connector boundary.
**Depends on**: Phase 2
**Requirements**: EXTR-01, EXTR-02, EXTR-03, EXTR-04, EXTR-05, EXTR-06, EXTR-07, EXTR-08
**Success Criteria** (what must be TRUE):
  1. A text document ingested by an `UnstructuredTextConnector` is chunked, run through an LLM extractor whose output schema is derived from the Schema Registry, and lands candidate entities and relationships that pass through `GraphService.apply()` — an ArchUnit test proves the extraction path cannot bypass the same write funnel used by the REST polling connector.
  2. Every extracted node carries complete provenance recorded on the event log: `source_document_id`, `source_chunk_range`, `extractor_version`, `llm_model_id`, and `extraction_confidence`; these are queryable per node and survive replay.
  3. Entity Resolution against existing graph state is deterministic and reproducible: for a given `(name, type, optional embedding)`, running resolution twice on the same DB state returns the same merge decision; a test proves that matches above the configured threshold merge into the existing node and matches below land in a review queue.
  4. Reconciliation between extracted and structured-source entities honors the per-tenant authority matrix: when an extraction says a property value is X and a structured connector says Y, the matrix picks the winner, the loser is logged in `reconciliation_conflicts` with the extraction metadata intact, and the conflict is queryable per source type.
  5. The `pgvector` extension is installed via Flyway migration, embeddings are optional per entity type (configured in the Schema Registry), and at least one concrete unstructured connector (Markdown folder / Obsidian-vault shape) is green end-to-end against a Testcontainers-seeded AGE + pgvector database.
**Plans**: 4 plans
Plans:
- [x] 02.5-01-PLAN.md — Infrastructure: custom AGE+pgvector Docker image, Flyway V16-V21 (pgvector, entity_embeddings, review queue, provenance columns, embedding flags, auth_type widening), CandidateMutation/GraphMutation provenance extension, POM dependencies
- [x] 02.5-02-PLAN.md — Text chunking + LLM extraction: TextChunker strategy (ParagraphChunker, SentenceChunker), ExtractionCandidate, DynamicSchemaOutputConverter, SchemaRegistrySchemaBuilder, ExtractionService with retry
- [x] 02.5-03-PLAN.md — Entity resolution + review queue: EntityResolutionService (three-tier: exact, embedding, fuzzy), EmbeddingService, FuzzyNameMatcher, ExtractionReviewController + Repository
- [x] 02.5-04-PLAN.md — Integration: MarkdownFolderConnector, ConnectorRunner extraction hooks, ArchUnit enforcement, Docker Compose Ollama, E2E MarkdownFolderConnectorIT

### Phase 3: MCP Projection (Flagship Differentiator)
**Goal**: Make Tessera usable as durable, typed shared memory for LLM agents through a Spring AI MCP Server whose tool surface is dynamically registered from the Schema Registry, read-only by default, audited per invocation, and hardened against prompt injection and schema-mutation abuse.
**Depends on**: Phase 2
**Requirements**: SEC-07, SEC-08, MCP-01, MCP-02, MCP-03, MCP-04, MCP-05, MCP-06, MCP-07, MCP-08, MCP-09
**Success Criteria** (what must be TRUE):
  1. An MCP-capable agent connects to the server and can invoke `list_entity_types`, `describe_type`, `query_entities`, `get_entity`, `traverse`, `find_path`, and `get_state_at` scoped to its tenant — with the temporal tool answering "state at timestamp T" directly from the event log.
  2. Adding a new node type via the Schema Registry surfaces new MCP tools without a redeploy (or, if Spring AI blocks runtime registration, triggers a documented restart-on-schema-change fallback that is observable in the audit log).
  3. Agents are read-only by default; any write attempt from an agent without an explicit per-agent write quota is rejected, and no MCP tool is exposed that can mutate the Schema Registry.
  4. MCP tool responses wrap source-system content in `<data>...</data>` markers; a prompt-injection test suite proves the wrapper is applied consistently and that embedded instructions in source data do not alter tool behavior.
  5. Every MCP tool invocation is recorded in an audit log with agent identity, tool name, arguments, and outcome, and an operator can query the log per-tenant.
**Plans**: 5 plans
Plans:
- [x] 03-00-PLAN.md — Wave 0: AGE shortestPath spike (resolve Research Q1) + test stubs for Waves 1-2 behavioral verification
- [x] 03-01-PLAN.md — Infrastructure + contracts: Flyway V22/V23 (mcp_audit_log, mcp_agent_quotas), ToolProvider/ToolResponse interfaces, GraphRepository extensions (executeTenantCypher, findShortestPath), SpringAiMcpAdapter + ToolResponseWrapper + McpProjectionConfig, SecurityConfig ROLE_AGENT, enable ToolResponseWrapperTest
- [x] 03-02-PLAN.md — 7 MCP tool implementations: ListEntityTypes, DescribeType, QueryEntities, GetEntity (depth 0-3), Traverse (read-only Cypher), FindPath (AGE shortestPath), GetStateAt (EventLog temporal replay)
- [x] 03-03-PLAN.md — Audit + quota: McpAuditLog JDBC writer, AgentQuotaService (AtomicLong counters + DB quota table), McpAuditController (GET /admin/mcp/audit + /quotas), wire audit/quota into SpringAiMcpAdapter, enable McpAuditLogTest + AgentQuotaServiceTest
- [x] 03-04-PLAN.md — Tests: ArchUnit McpMutationAllowlistTest, McpPromptInjectionIT (adversarial seeds), McpCrossTenantIT, McpAuditLogIT, McpQuotaEnforcementIT (full dispatch layer with mock write tool), spotless:check

### Phase 4: SQL View + Kafka Projections, Hash-Chained Audit
**Goal**: Add the two remaining projections required for real-world consumption — SQL views for BI tools (bypassing the AGE aggregation cliff) and Kafka topics for downstream event fan-out via Debezium — plus optional hash-chained audit integrity for compliance-driven tenants. The write path must not change.
**Depends on**: Phase 3
**Requirements**: SQL-01, SQL-02, SQL-03, KAFKA-01, KAFKA-02, KAFKA-03, AUDIT-01, AUDIT-02
**Success Criteria** (what must be TRUE):
  1. A Metabase / Looker / PowerBI user can point at per-tenant per-type SQL views (`v_{model}_{typeSlug}`) and run aggregate queries that are measurably faster than the equivalent Cypher, reading AGE label tables directly; views are regenerated on schema change and survive restart.
  2. Debezium 3.4 with Outbox Event Router SMT replaces the in-process outbox poller and publishes one topic per `(model_id, typeSlug)` — crucially, nothing on the write path changes (same `graph_outbox` rows, same `GraphService.apply` transaction).
  3. Replication slot lag and `max_slot_wal_keep_size` are monitored, with alerts firing before WAL bloat threatens the primary.
  4. For a tenant with audit integrity enabled, each event row records the hash of the previous event plus its own payload; an on-demand verification job (also runnable in CI) detects any tampering and reports the first broken link.
**Plans**: 3 plans
Plans:
- [x] 04-01-PLAN.md — Flyway V24-V27 migrations + SQL View Projection (SqlViewProjection, SqlViewNameResolver, SqlViewAdminController, schema-change listener, startup regeneration, ITs)
- [ ] 04-02-PLAN.md — Hash-Chained Audit (HashChain helper, EventLog.append() hash extension, AuditVerificationService, AuditVerificationController, concurrency IT)
- [ ] 04-03-PLAN.md — Kafka/Debezium Projection (Docker Compose Kafka+Debezium, Outbox Event Router config, OutboxPoller conditionalization, DebeziumSlotHealthIndicator)

### Phase 5: Circlead Integration & Production Hardening
**Goal**: Prove the whole stack against the first real consumer (circlead) without a big-bang migration, and harden operations with observability, snapshots, retention, and a rehearsed DR drill so Tessera is safe to run on IONOS VPS.
**Depends on**: Phase 4
**Requirements**: CIRC-01, CIRC-02, CIRC-03, OPS-01, OPS-02, OPS-03, OPS-04, OPS-05
**Success Criteria** (what must be TRUE):
  1. circlead reads Role, Circle, and Activity data from Tessera via REST and MCP projections in parallel with its own JPA model; a documented mapping round-trips cleanly, and circlead continues to function (gracefully degraded) when Tessera is unavailable.
  2. An operator can view Prometheus / OpenTelemetry metrics for ingest rate, rule evaluations per second, conflict count, outbox lag, replication slot lag, and SHACL validation time, and the Spring Boot Actuator health endpoint reports the status of Postgres, AGE, Vault, and every registered connector.
  3. An operator can configure per-tenant event-log retention and trigger a per-tenant snapshot that compacts the event log while preserving the ability to answer temporal queries above the snapshot boundary.
  4. A full DR drill (dump → restore → replay → consumer smoke test against circlead) is rehearsed end-to-end and documented, and the whole milestone-1 scope (all prior phases) remains green on CI.
**Plans**: TBD
**UI hint**: yes

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 0. Foundations & Risk Burndown | 0/0 | Not started | - |
| 1. Graph Core, Schema Registry, Validation, Rules | 0/0 | Not started | - |
| 2. REST Projection, Connector Framework, First Connector, Security Baseline | 0/0 | Not started | - |
| 2.5. Unstructured Ingestion & Entity Extraction | 0/4 | Planned | - |
| 3. MCP Projection | 3/5 | In Progress|  |
| 4. SQL View + Kafka Projections, Hash-Chained Audit | 1/4 | In Progress|  |
| 5. Circlead Integration & Production Hardening | 0/0 | Not started | - |

## Coverage

- v1 requirements: 98
- Mapped: 98
- Unmapped: 0

All v1 requirements map to exactly one phase. See REQUIREMENTS.md Traceability table.

## Notes

- **Phase ordering is strictly bottom-up.** Schema Registry before projections; outbox before in-process events; MCP cannot move earlier because it depends on the full read path + access control.
- **Parallelism inside Phase 2.** REST projection and connector framework can develop in parallel once Phase 1 lands.
- **Phase 1 forward-commitment for Phase 2.5.** The Rule Engine in Phase 1 must accept candidate entities from *any* connector mode — structured (REST-polled, JDBC, CDC) or extraction-based (LLM-extracted from text). This is achieved by designing the rule engine input contract around a generic `CandidateMutation` shape that carries provenance metadata (source_type, source_id, confidence, extractor_version) rather than assuming structured field mappings. Phase 2.5 then adds the extraction pipeline as a new connector mode without touching the rule engine.
- **Research flags** (from research/SUMMARY.md) to revisit during plan-phase: Phase 1 SHACL perf + Postgres RLS on AGE label tables + generic `CandidateMutation` contract for extraction forward-compatibility; Phase 2 connector SPI shape + SpringDoc dynamic OpenAPI lifecycle; Phase 2.5 pgvector-on-AGE coexistence + LLM structured-output libraries (Spring AI vs LangChain4j) + entity-resolution algorithms; Phase 3 Spring AI MCP dynamic tool registration semantics (high priority); Phase 4 Debezium Outbox Router with multi-tenant partitioning.
- **UI indicator** on Phase 5 reflects operator dashboards (sync status, conflict register, metrics, extraction review queue); earlier phases are backend-only.

---
*Roadmap created: 2026-04-13*
