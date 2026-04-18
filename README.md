# Tessera

> **The graph is the truth. Everything else is a projection.**

Tessera is a graph-based, protocol-agnostic integration layer that sits between heterogeneous systems (ERP, CRM, legacy DBs, SaaS tools, LLM agents) as the single source of truth. A REST endpoint is a projection of the graph. A SQL view is a projection. A Kafka topic is a projection. An MCP tool is a projection. Applications consume projections — the graph is the source.

**Status:** v1.0 MVP shipped (2026-04-18)
**License:** Apache 2.0
**Language:** Java 21 + Spring Boot 3.5

---

## The Problem

Data lives in silos. Every system (ERP, CRM, legacy DB, SaaS tool) has its own model, its own "truth," its own API. Integration today means point-to-point coupling: 5 systems need 10 integrations, 20 systems need 190. LLM agents have to call N different APIs to form a complete picture. When a schema changes in one system, integrations break. And worst of all: nobody knows which system holds the "right" version of a datum.

## The Approach

A graph-based integration layer that sits as a Single Point of Truth between arbitrary systems. Generated endpoints (REST, GraphQL, MCP, SQL views, Kafka topics, WebSocket/SSE) are derived at runtime from a central meta-model. Consumers (circlead, BI tools, agents, LLMs) query projections; connectors reconcile inbound data from source systems through a priority-based rule engine. The graph itself holds the canonical state, and every mutation is an event.

## Architecture

```
                    ┌──────────────────────────────────────┐
                    │        Consumers (North side)         │
                    │   circlead │ BI │ Agents │ LLMs       │
                    │   Dashboards │ Mobile │ Webhooks      │
                    └──────────────┬───────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────┐
                    │        Protocol Adapters              │
                    │   REST  GraphQL  MCP  SQL Views       │
                    │   Kafka Topics  WebSocket/SSE         │
                    │   (generated from schema at runtime)  │
                    └──────────────┬───────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────┐
                    │        Projection Engine              │
                    │   Graph schema → endpoints / topics   │
                    │   Caching │ Pagination │ Filtering    │
                    │   Subscription management             │
                    └──────────────┬───────────────────────┘
                                   │
          ┌────────────────────────▼────────────────────────┐
          │              Rule Engine                         │
          │   Reconciliation │ Validation │ Routing          │
          │   Priority rule chains with fallback             │
          │   Conflict resolution │ Transformation           │
          └────────────────────────┬────────────────────────┘
                                   │
          ┌────────────────────────▼────────────────────────┐
          │           Knowledge Graph (Core)                 │
          │   PostgreSQL + Apache AGE                        │
          │   Property Graph + SHACL Validation              │
          │   Event Log (every mutation = an event)          │
          │   Ontology Registry (schema metadata)            │
          └────────────────────────┬────────────────────────┘
                                   │
          ┌────────────────────────▼────────────────────────┐
          │           Connector Layer (South side)          │
          │   API polling │ CDC │ Kafka consumer             │
          │   Webhook receiver │ Legacy DB sync              │
          │   File import │ JDBC bridge                      │
          │   Mapping + schedule + reconciliation policy     │
          └─────────────────────────────────────────────────┘
```

## Core Concepts

### 1. Knowledge Graph on PostgreSQL + Apache AGE

The graph lives in PostgreSQL with the Apache AGE extension. This is deliberate: it gives us ACID guarantees across graph **and** relational queries in a single transaction, native SQL views for BI tools, and one operational system instead of two. Neo4j remains a possible future read-replica for graph-heavy traversals — not the primary store.

Every node and edge carries:
- `uuid` — stable external reference
- `model_id` — tenant scope
- `_type` — graph label
- `_created_at`, `_updated_at`, `_created_by`
- `_source` — which connector produced or last updated this node
- `_source_id` — the ID in the source system

### 2. Event Log — Every Mutation is an Event

Every graph change persists as an event in `graph_events`: full payload, delta, cause (`connector:sap`, `api:user:42`, `rule:reconcile-name`), sequence number per tenant. This enables:

- **Temporal queries** — "What did the org look like on March 1?" → replay events up to that point
- **Audit trail** — who changed what, when, triggered by which connector
- **Reactive propagation** — a Kafka producer reads events and publishes to topics
- **Undo/replay** — every change is traceable and in principle reversible
- **Conflict detection** — when two connectors modify the same node simultaneously

### 3. Meta-Model Layer — The Ontology

The graph is schema-flexible (any node can carry any properties) but not schemaless. A Schema Registry defines per tenant which node types, properties, and edge types are allowed. Validation uses **SHACL** (Shapes Constraint Language) via Apache Jena — pragmatic, W3C-standard, and covering roughly 90% of real validation needs. OWL reasoning is available as an optional opt-in module for tenants who need inference.

### 4. Rule Engine — Priority Rule Chains

The rule engine is the central decision point for three tasks:

1. **Reconciliation** — when graph and source system disagree, who wins?
2. **Validation** — is this mutation allowed? (beyond SHACL: business rules)
3. **Routing** — where should this event propagate?

Rules are priority-ordered and chain-of-responsibility evaluated. A per-tenant **Source Authority Matrix** configures which system is authoritative for which field — not just per-entity, but per-property (HR owns `name`, CRM owns `phone`, ERP owns `cost_center`). Unresolved conflicts land in a `reconciliation_conflicts` register for review.

The MVP ships a custom chain-of-responsibility rule engine. Drools is a deferred option for later, when CEP and decision tables become worth the weight.

### 5. Projection Engine — Dynamic Endpoint Generation

When an admin adds a new type "Risk" in the Schema Registry, the Projection Engine immediately exposes:

| Protocol | Generated surface | Example |
|---|---|---|
| REST | `GET/POST/PUT/DELETE /api/v1/{model}/entities/{typeSlug}` | `/api/v1/org-42/entities/risk` |
| GraphQL | Type `Risk` with all properties | `query { risks { name probability } }` |
| Kafka | Topic `fabric.{model}.{typeSlug}.events` | `fabric.org-42.risk.events` |
| SQL View | `v_{model}_{typeSlug}` | `SELECT * FROM v_org42_risk` |
| MCP Tool | `query_{typeSlug}`, `create_{typeSlug}` | `query_risk(filter: "probability > 0.7")` |
| WebSocket/SSE | Channel `/ws/{model}/{typeSlug}` | Real-time updates |

Endpoints are **runtime-routed** through generic handlers — never code-generated, never reflection over JPA entities. One `GenericEntityController` per protocol resolves the type slug against the Schema Registry at request time.

### 6. Connector Layer — Source System Integration

Each connector is a self-contained module with three responsibilities: read data from the source (pull or push), map source schema to graph schema, and declare a reconciliation policy (which rule chain applies).

Supported sync strategies:

| Source capability | Strategy | Latency |
|---|---|---|
| Webhooks / events | Push connector, event-driven | Seconds |
| CDC | Debezium → Kafka → connector | Seconds–minutes |
| REST API only | Polling with delta detection (ETag, `Last-Modified`) | Minutes |
| DB access only | JDBC bridge with timestamp delta | Minutes |
| File export only | File watcher with diff detection | Hours |

The first concrete connector is a **generic REST poller** (read-only). Write-back to source systems is deferred until the conflict register is battle-tested.

### 7. MCP and Agent Integration

If the graph is canonical truth and MCP is the access protocol, the graph becomes universal memory for all agents. An agent no longer calls "CRM API, then ERP API, then Jira" — it runs one graph traversal:

```
Agent → MCP tool: traverse(
    query: "All projects linked to risks whose owner sits in a circle
            below maturity level 3"
)

→ Internally resolved to Cypher:
MATCH (p:Project)-[:HAS_RISK]->(r:Risk)-[:OWNED_BY]->(role:Role)-[:BELONGS_TO]->(c:Circle)
WHERE c.maturity_level < 3 AND p.model_id = $modelId
RETURN p, r, role, c
```

Agents can also communicate *through* the graph: Agent A writes an `AiInsight` node, the event publishes, Agent B reacts. The graph becomes shared memory for multi-agent systems.

The MCP projection is the flagship differentiator: in 2026, no mainstream data fabric ships a native MCP surface over a typed graph.

## Security

Tessera assumes hostile environments from day one.

- **TLS 1.3 in transit** — HTTPS with HSTS on all consumer-facing endpoints; mTLS for service-to-service
- **Postgres TDE at rest** — LUKS/dm-crypt self-hosted, CMK-managed backups
- **Field-level encryption** (opt-in) — AES-GCM for secrets, AES-deterministic for queryable fields, HMAC for lookup-only identifiers. All-or-nothing: the feature ships with full per-tenant blind indexes, multi-version DEKs, fail-closed writes on KMS outage, and a KMS chaos test in CI — or it stays off entirely.
- **Envelope encryption** — per-tenant DEKs wrapped by a KEK that never leaves the KMS; notfall rotation is a single action
- **Secrets in HashiCorp Vault** — connector credentials and keys never land in the fabric DB or config files
- **Tenant isolation** — `model_id` on every node, edge, and event; queries always filtered; central `GraphSession` as the only Cypher execution surface; ArchUnit bans raw Cypher outside `graph.internal`; `TenantContext` is a mandatory explicit method parameter (never `ThreadLocal`)
- **Audit integrity** (opt-in) — hash-chained event log for GxP, SOX, BSI C5 compliance

## Tech Stack

| Layer | Technology | Reason |
|---|---|---|
| **Language** | Java 21 (Corretto) + Spring Boot 3.5 | LTS, virtual threads, consistent with the circlead ecosystem |
| **Graph DB** | PostgreSQL 16 + Apache AGE 1.6 | One system for graph + relational, ACID, native SQL views |
| **Event Bus** | In-process events (MVP) → Kafka 3.9 + Debezium (Phase 4) | Start light, swap via transactional outbox without write-path changes |
| **Rule Engine** | Custom chain-of-responsibility (MVP); Drools later | Lightweight first, scale when needed |
| **Schema Validation** | Apache Jena SHACL 5.2 | Java-native, W3C standard |
| **REST API** | Spring WebMVC + SpringDoc OpenAPI 2.8 | Dynamic OpenAPI via customizer |
| **GraphQL** | Spring for GraphQL (deferred to v2) | Dynamic schema from Registry |
| **MCP** | Spring AI MCP Server 1.0.x | Native agent access, interface-isolated against churn |
| **Secrets** | HashiCorp Vault + Spring Cloud Vault 4.2 | Self-hosted, open source |
| **CDC** | Debezium 3.4 (Phase 4) | Standard for change data capture |
| **Build** | Maven multi-module | Consistent with the circlead ecosystem |
| **Hosting** | Self-hosted (IONOS VPS, Docker Compose) | Consistent with the circlead operational model |

## Module Structure

```
tessera/
├── fabric-core/              ← Graph core, event log, schema registry
│   ├── model/                  Node, Edge, GraphEvent, SchemaNodeType, ...
│   ├── graph/                  AGE integration, Cypher executor
│   ├── schema/                 Schema registry, SHACL validator
│   ├── event/                  Event publisher, event store
│   └── service/                GraphService (facade, single write funnel)
│
├── fabric-rules/             ← Rule engine
│   ├── api/                    Rule interface, RuleChainExecutor
│   ├── reconciliation/         Conflict resolution, authority matrix
│   ├── validation/             Business rule validation
│   └── routing/                Event routing rules
│
├── fabric-connectors/        ← Connector framework + implementations
│   ├── api/                    Connector interface, MappingDefinition
│   ├── polling/                API polling base connector
│   ├── cdc/                    CDC / Debezium base connector
│   ├── webhook/                Webhook receiver base connector
│   ├── jdbc/                   JDBC bridge connector
│   └── impl/                   Concrete connectors (generic-rest, sap, salesforce, ...)
│
├── fabric-projections/       ← Projection engine
│   ├── rest/                   Dynamic REST endpoints
│   ├── graphql/                Dynamic GraphQL schema (v2)
│   ├── mcp/                    MCP tool generation
│   ├── kafka/                  Kafka topic management
│   ├── sql/                    SQL view generation
│   └── websocket/              Real-time subscriptions
│
├── fabric-app/               ← Spring Boot executable
│   ├── config/
│   └── resources/
│
└── pom.xml                   ← Parent POM
```

Module dependencies are strictly upward from `fabric-core`, enforced by Maven enforcer and ArchUnit.

## Roadmap

**v1.0 MVP — Shipped 2026-04-18** (12 phases, 38 plans, 98 requirements):
- Graph core with single-TX write funnel, SHACL validation, 4-chain rule engine
- Four projection engines: REST (dynamic), MCP (7 agent tools), SQL views (BI), Kafka/Debezium (CDC)
- Structured (REST polling) and unstructured (LLM extraction + pgvector) connectors
- circlead as first consumer with production wiring
- Observability, DR drill, Vault secrets, field-level ACL

**v2+ (planned):** GraphQL projection, LLM-assisted ontology evolution, visual schema designer, OWL reasoning, Drools migration, write-back connectors, additional connectors (SAP, Salesforce, Jira, JDBC), Neo4j read-replica.

See `.planning/ROADMAP.md` for details.

## Architecture Decisions (ADRs)

| # | Decision | Rationale |
|---|---|---|
| ADR-1 | PostgreSQL + Apache AGE as primary store | ACID across graph + relational in one transaction; native SQL views; one ops system; Apache 2.0 |
| ADR-2 | Property Graph first, OWL optional | Property graphs are developer-friendly and cover ~90% of needs; OWL as opt-in |
| ADR-3 | Custom rule engine for MVP, Drools later | Chain-of-responsibility is sufficient; Drools adds weight without MVP payoff |
| ADR-4 | Event log in PostgreSQL, Kafka as projection | Postgres is authoritative and transactional; Kafka handles fan-out |
| ADR-5 | Schema Registry co-located with graph DB | Schema change and data validation in one transaction |
| ADR-6 | circlead stays standalone, consumes via REST/MCP | No big-bang migration; parallel operation during transition |

## Quick Start

Prerequisites: Docker (Compose v2), JDK 21.

### Start

```bash
git clone https://github.com/matthiaw/Tessera.git
cd Tessera

# 1. Start infrastructure (Postgres 16 + AGE 1.6 + pgvector, Ollama for embeddings)
docker compose up -d

# 2. Build all modules
./mvnw -B clean install -DskipTests -Dspotless.check.skip=true

# 3. Run the application
java -jar fabric-app/target/fabric-app-*.jar
```

The app starts on `http://localhost:8080` with:
- REST API: `/api/v1/{model}/entities/{typeSlug}`
- OpenAPI docs: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`
- MCP SSE endpoint: `/mcp/sse`
- Actuator health: `/actuator/health`
- Prometheus metrics: `/actuator/prometheus`

**With Kafka/Debezium (optional):**

```bash
docker compose --profile kafka up -d
```

Then set `tessera.kafka.enabled=true` in `application.yml` or via env var `TESSERA_KAFKA_ENABLED=true`.

**Environment variables:**

| Variable | Default | Purpose |
|----------|---------|---------|
| `TESSERA_JWT_SIGNING_KEY` | (empty) | HMAC key for JWT token signing |
| `TESSERA_BOOTSTRAP_TOKEN` | (empty) | Initial admin token for `/api/v1/admin/tokens/issue` |
| `ANTHROPIC_API_KEY` | `placeholder` | API key for LLM-based entity extraction |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint for embeddings |
| `TESSERA_CIRCLEAD_BASE_URL` | `http://localhost:8080` | circlead REST API base URL |

### Stop

```bash
# Stop the application
# Ctrl+C in the terminal running the jar, or kill the process

# Stop infrastructure (preserves data volumes)
docker compose down

# Stop infrastructure AND delete data volumes (clean slate)
docker compose down -v

# Stop Kafka stack too (if started with --profile kafka)
docker compose --profile kafka down -v
```

### Test

```bash
# Run all tests (unit + integration via Testcontainers — requires Docker running)
./mvnw -B verify -Dspotless.check.skip=true

# Run tests for a single module
./mvnw -B test -pl fabric-core -Dspotless.check.skip=true
./mvnw -B test -pl fabric-rules -Dspotless.check.skip=true
./mvnw -B test -pl fabric-connectors -Dspotless.check.skip=true
./mvnw -B test -pl fabric-projections -Dspotless.check.skip=true

# Run a single test class
./mvnw -B test -pl fabric-core -Dtest=GraphServiceImplTest -Dspotless.check.skip=true

# Run integration tests only (Testcontainers — *IT.java)
./mvnw -B verify -pl fabric-app -Dspotless.check.skip=true

# Run benchmarks (JMH)
./mvnw -B test -pl fabric-core -Dtest=JmhRunner -Dspotless.check.skip=true
```

**Note:** Tests use Testcontainers with a custom AGE+pgvector Docker image — Docker must be running. The image is built automatically on first test run. Add `-Dspotless.check.skip=true` if running JDK 23 (spotless 2.44.1 is incompatible).

## Image pinning

The Apache AGE container is **pinned by sha256 digest**, not by a floating tag, per Phase 0 decision D-09:

```
apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed
```

This digest appears in three enforcement sites — bumping it requires updating all three:

1. `docker-compose.yml` (local dev stack)
2. `fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java` (Testcontainers helper, plan 00-03)
3. This README section

To resolve the current digest for the AGE 1.6.0 PG16 release tag:

```bash
docker pull apache/age:release_PG16_1.6.0
docker image inspect --format '{{index .RepoDigests 0}}' apache/age:release_PG16_1.6.0
```

> Note: the planner's reference command used `apache/age:PG16_latest`, but upstream no longer
> publishes that floating tag — the actual 1.6.0 PG16 tag on Docker Hub is `release_PG16_1.6.0`.
> The pinning guarantee (digest, not tag) is unchanged.

## Project Structure

```
.
├── fabric-core/          ← Graph core, event log, schema registry, SHACL, tenant isolation
├── fabric-rules/         ← Rule engine, entity resolution, reconciliation
├── fabric-connectors/    ← Connector framework, REST poller, LLM extraction, mappings
├── fabric-projections/   ← REST, MCP, SQL view, Kafka projections
├── fabric-app/           ← Spring Boot executable, config, Flyway migrations
├── docker/               ← Custom AGE+pgvector Dockerfile, Debezium config
├── scripts/              ← DR drill, benchmarks
├── .planning/            ← GSD project memory (milestones, roadmap, retrospective)
├── docker-compose.yml    ← Local dev stack
├── LICENSE               ← Apache 2.0
└── pom.xml               ← Parent POM (Maven multi-module)
```

## Contributing

Tessera is open to contributors from day one. Contribution guidelines and issue templates will land alongside the Phase 0 scaffold. In the meantime, feel free to open an issue to discuss ideas, report problems, or ask questions.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Copyright © 2026 Matthias Wegner
