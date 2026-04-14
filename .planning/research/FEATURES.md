# Feature Research

**Domain:** Graph-first, meta-model-driven data fabric / integration layer (Tessera)
**Researched:** 2026-04-13
**Confidence:** MEDIUM-HIGH (PROJECT.md + concept blueprint = HIGH; competitive feature framing from WebSearch = MEDIUM)

## Scope Note

Tessera positions as a **graph-first, LLM-native integration layer** where REST, GraphQL, MCP, SQL views, and Kafka topics are projections of a central meta-model over a Postgres + Apache AGE knowledge graph. Feature scoping below reflects that: we categorize against the wider data-fabric / metadata-platform category (Atlan, Palantir Foundry, Microsoft Dataverse, Stardog, Alation) but treat the LLM/MCP + graph-as-source-of-truth angle as the spine, not a side car.

The research question explicitly asked to distinguish table stakes vs differentiators vs anti-features for each sub-domain: schema/ontology, reconciliation, connectors, observability, lineage, DQ, access control, agent/LLM integration, temporal queries. Structure below groups first by category, then rolls up to an MVP definition and prioritization matrix.

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features that users of any 2026 data fabric / metadata platform / integration layer assume exist. Missing any of these makes the product feel incomplete or unusable for production.

| # | Feature | Category | Why Expected | Complexity |
|---|---------|----------|--------------|------------|
| T1 | Typed schema / node-type & edge-type registry with properties, cardinalities, required fields | Schema | No meta-model platform ships without this; it is the product | MEDIUM |
| T2 | Schema versioning with backwards-compatible evolution (add property, widen type) | Schema | Users run in production; they cannot afford breaking schema changes | MEDIUM |
| T3 | Schema validation on write (reject invalid mutations) | Schema | "Graph is the truth" requires this; SHACL via Jena covers it | MEDIUM |
| T4 | Multi-tenant isolation by `model_id` scoping every node/edge/event | Schema | Enterprise non-negotiable; already in PROJECT.md | MEDIUM |
| T5 | Conflict register for reconciliation disagreements (which source said what, when, why rejected) | Reconciliation | Users must be able to explain "why does the graph show X when system Y says Z?" | MEDIUM |
| T6 | Source authority matrix (per entity-type / per property, which source wins) configurable per tenant | Reconciliation | Every MDM / data fabric has this; without it reconciliation is a black box | MEDIUM |
| T7 | Deterministic, replayable reconciliation (same events in = same graph out) | Reconciliation | Debugging and audit require it | HIGH |
| T8 | Connector framework with pluggable protocols (polling REST, JDBC, webhook, CDC, file) | Connectors | Covers 90% of real source systems | HIGH |
| T9 | Mapping definitions (source field → graph property) declarative, versioned, testable | Connectors | Maintenance burden is unbearable without this | MEDIUM |
| T10 | Delta detection (ETag, last-modified, watermark, sequence token) | Connectors | Full-reloads do not scale past toy datasets | MEDIUM |
| T11 | Credential management through KMS / Vault, never in fabric DB | Connectors | Security baseline; already in PROJECT.md | MEDIUM |
| T12 | Sync status dashboard per connector (last successful run, rows in/out, errors, lag) | Observability | Operators will not run a data fabric blind | MEDIUM |
| T13 | Structured audit log of every mutation with source attribution | Observability | Compliance, debugging, and trust all depend on it; already in PROJECT.md | LOW-MEDIUM |
| T14 | Entity-level lineage (what source systems contributed to this node; which events shaped it) | Lineage | 2026 baseline for any metadata platform; Atlan / Foundry make this central | MEDIUM |
| T15 | Data quality rules (not-null, regex, referential integrity, allowed values) declarative and runnable on ingest | DQ | SHACL in Jena covers most of this; expected by users | MEDIUM |
| T16 | DQ rule violations visible in conflict register / quality dashboard | DQ | Silent DQ failures are worse than no DQ | MEDIUM |
| T17 | Row-level (node-level) access control scoped by tenant / `model_id` | Access control | Multi-tenant enterprise baseline | MEDIUM |
| T18 | Field-level access control (sensitive properties hidden/masked per role) | Access control | Already in PROJECT.md via field-level encryption; standard in 2026 | MEDIUM-HIGH |
| T19 | REST API projection generated from schema (CRUD + search by property) | Projections | Primary consumer surface; already in PROJECT.md as first projection | MEDIUM |
| T20 | OpenAPI/JSON Schema publication of generated projection | Projections | Consumers expect to generate clients; SpringDoc already in stack | LOW |
| T21 | Event log of every mutation with sequence numbers, replayable | Temporal | Foundation for time-travel, audit, and reconciliation debugging | MEDIUM |
| T22 | Replay-based point-in-time query ("what did entity X look like on date D?") | Temporal | Event-sourced systems must expose this or the event log adds no value | HIGH |
| T23 | Health / liveness / readiness endpoints for the fabric itself | Observability | Operational baseline | LOW |
| T24 | Metrics export (Prometheus / OpenTelemetry) for ingest rate, rule evaluation count, conflict count | Observability | Standard for any Spring Boot service in 2026 | LOW |

### Differentiators (Competitive Advantage)

Features that set Tessera apart from Atlan / Alation / Foundry / Dataverse. These are the reasons a user would pick Tessera over a mainstream data fabric. Several of these are already in PROJECT.md — the point of listing them here is to make sure the roadmap treats them as first-class, not as afterthoughts.

| # | Feature | Category | Value Proposition | Complexity |
|---|---------|----------|-------------------|------------|
| D1 | **MCP projection: agents query and mutate the graph via MCP tools** (`query_entities`, `traverse`, `get_state_at`, `find_path`, `describe_schema`) | LLM/MCP | This is the flagship differentiator. One Cypher traversal replaces N API calls for an agent. No mainstream fabric ships this natively in 2026 | HIGH |
| D2 | **Graph as shared agent memory** — durable, typed, multi-agent shared state, not a vector store | LLM/MCP | Vector stores lose structure; Tessera keeps it. Agent-to-agent coordination through the graph is architecturally cleaner than passing context | MEDIUM |
| D3 | **LLM-assisted ontology evolution** — agent proposes schema changes from real data; human approves | Schema / LLM | Concept doc §4.3 explicitly calls this out; Atlan has AI cataloguing but not schema-proposing loops into a meta-model | HIGH |
| D4 | **Graph-first meta-model where projections are generated, not hand-written** (REST, GraphQL, MCP, SQL views, Kafka topics all from one schema) | Projections | Most fabrics catalog external data; Tessera generates the serving layer. Removes whole classes of drift between schema docs and API | HIGH |
| D5 | **Priority-based rule engine with transparent chain-of-responsibility** — every reconciliation decision has a named rule that made it, visible in the conflict register | Reconciliation | Black-box ML reconciliation is the Atlan/Alation norm; Tessera's explainability is a selling point for regulated industries | MEDIUM-HIGH |
| D6 | **Temporal queries as a first-class projection** (`get_state_at`, replay, diffs between two points in time) | Temporal | Dataverse and Foundry have snapshots; few fabrics expose real event-sourced replay via the same API surface agents use | HIGH |
| D7 | **Hash-chained audit log** (opt-in) for tamper-evident provenance — GxP, SOX, BSI C5 friendly | Observability / Security | Already in PROJECT.md. Compliance-heavy sectors (pharma, finance, public sector) consider this a bar-raiser | MEDIUM |
| D8 | **Field-level encryption with per-tenant KMS keys and envelope encryption** | Access control / Security | Already in PROJECT.md. Atlan and similar catalog products do not encrypt underlying data — they sit on top of warehouses that do or don't | HIGH |
| D9 | **In-graph data fabric on Postgres + AGE** — one ACID transaction for graph + relational + schema + events | Architecture / Operations | Avoids distributed-transaction horror stories. Differentiator vs. Neo4j + separate RDBMS + separate event bus | MEDIUM |
| D10 | **Kafka projection for downstream fan-out** with topic-per-entity-type convention, tenant-aware partitioning | Projections | Lets existing event-driven consumers integrate without knowing the fabric exists | MEDIUM |
| D11 | **SQL view projection** over the graph for BI tools (Metabase, Looker, PowerBI) — users keep their BI stack unchanged | Projections | Foundry makes you use Foundry tools; Tessera lets users bring their own BI | MEDIUM |
| D12 | **Entity-level + event-level + rule-level lineage** ("this node has this value because source S sent event E at time T, and rule R selected it over source S2") | Lineage | Deeper than Atlan's SQL-parsed column lineage: it captures *decision* lineage, not just *data flow* lineage | HIGH |
| D13 | **Self-hosted first, no cloud lock-in** (IONOS VPS, Docker Compose, portable) | Operations | Competitive against SaaS-only Atlan / Alation / Foundry for EU / compliance-sensitive users | LOW-MEDIUM |
| D14 | **Source authority matrix configurable per property, not just per entity** | Reconciliation | Most MDMs let you pick a "system of record" per entity. Real life is "HR system owns name, CRM owns phone, ERP owns cost center." Per-property authority is an underserved need | MEDIUM |
| D15 | **`describe_schema` MCP tool that returns the meta-model as a prompt-ready context block** | LLM/MCP | Agents grounded in schema make far fewer hallucinations; this is a small feature with outsized impact | LOW |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem attractive but would harm Tessera's focus, scope, or identity. Either delegate to existing tools, or explicitly reject.

| # | Feature | Why Requested | Why Problematic | Alternative |
|---|---------|---------------|-----------------|-------------|
| A1 | Full OWL reasoning / DL inference in MVP | "We need a real ontology" | OWL is a research project, not a product feature. 10x complexity for a use case that SHACL covers | SHACL now; OWL as an opt-in module later (ADR-2) |
| A2 | Becoming a data warehouse / lakehouse / OLAP engine | "We already have the data here, why not query it?" | Tessera is OLTP-shaped for typed entities, not columnar analytics. Postgres+AGE is wrong for petabyte aggregations | Delegate to BI via the SQL view projection; keep warehouse separate |
| A3 | General-purpose BI / dashboard UI | "Users want charts" | Metabase / Looker / PowerBI already solve this. Tessera is the source, not the renderer | SQL view projection; point users at their existing BI |
| A4 | ETL / transformation DAG orchestrator (competing with Airflow, dbt, Dagster) | "We need to run transformations" | Massive scope creep; ETL is a mature market with entrenched tools | Connectors do ingest + mapping. For cross-source transforms, delegate to dbt running on the SQL projection |
| A5 | Visual schema designer / drag-and-drop modeling (MVP) | "Business users want GUIs" | Schema-as-data (YAML / JSON / SHACL stored in DB) is better for diff/review/CI. GUI can come later as a view on top | Schema files in repo, reviewed via PRs; optional GUI in v2+ |
| A6 | Generic vector store / RAG retrieval | "LLMs need embeddings" | pgvector / Qdrant / Weaviate exist. Tessera's angle is *structured* agent memory, not fuzzy search | Let users plug pgvector into the same Postgres if they want both; Tessera does not own embedding pipelines |
| A7 | Write-back / bidirectional connectors in MVP | "Reconciled data should flow back to source systems" | Bidirectional sync is the hardest problem in integration. Source systems each have bespoke write semantics | Read-only first; bidirectional per-connector, later, explicitly opt-in (already in PROJECT.md Out of Scope) |
| A8 | Big-bang migration from source systems into Tessera | "Let's move everything into the graph" | Destroys the value proposition; Tessera is integration, not replacement | Source systems keep running; Tessera reconciles. circlead stays standalone (ADR-6) |
| A9 | Positioning as "Palantir Foundry alternative" | "We need a clear competitor story" | Comparison marketing traps the product in someone else's narrative. Graph-first + LLM-native is its own story | Own positioning; already in PROJECT.md Out of Scope |
| A10 | LLM-generated mapping definitions applied automatically at ingest | "AI writes the connectors" | Silent schema drift and data corruption. Mappings must be reviewed by humans | LLM *suggests* mappings in a PR-style workflow; human approves |
| A11 | Row-level security via database GRANTs / RLS in Postgres directly exposed to consumers | "Use Postgres RLS, it's free" | Consumers never touch Postgres. Auth happens at the projection layer, which is the right place | Projection-layer ACL driven by tenant / role / attribute; Postgres RLS only as belt-and-braces internally |
| A12 | GraphQL projection in MVP | "Modern consumers want GraphQL" | REST + MCP + SQL + Kafka already covers Phase 1-2. GraphQL adds schema-stitching complexity | Already in PROJECT.md — defer to Phase 4 |
| A13 | Column-level lineage parsed from SQL statements (Atlan-style) | "Real data fabrics do column lineage" | Tessera doesn't run ETL on warehouses — there's no SQL for it to parse. Its lineage is event/rule-level, which is richer | D12: entity + event + rule lineage in the graph itself |
| A14 | Graph visualizer UI in MVP | "Users need to see the graph" | Real graphs have 1M+ nodes; naive force-directed layouts collapse. Users actually want typed entity views with relations | Projection-driven entity pages (REST + small UI) in MVP; dedicated graph explorer is a v2+ concern |
| A15 | Plug-in marketplace / connector marketplace in MVP | "Ecosystem!" | No ecosystem exists yet; marketplace infrastructure is a distraction pre-PMF | Ship 1 first-class connector (REST polling), document the connector SPI, revisit after real users |
| A16 | Built-in data masking engine beyond field-level encryption | "We need compliance masking" | Field-level encryption + role-based decryption already gives 90% of masking needs | Use decryption policies (concept §10.12) as the masking surface |
| A17 | Running LLM inference inside the fabric process | "Agents should be local" | Model hosting is a full separate ops problem. Tessera talks MCP to whatever agent runtime the user already has | MCP server projection; agent runtime is the user's concern |

---

## Feature Dependencies

```
[Schema Registry / Meta-Model T1,T2]
    ├──enables──> [SHACL Validation T3,T15]
    ├──enables──> [REST Projection T19,T20 / D4]
    ├──enables──> [MCP Projection D1,D15]
    ├──enables──> [SQL View Projection D11]
    ├──enables──> [Kafka Projection D10]
    └──enables──> [LLM-assisted Ontology Evolution D3]

[Event Log T21]
    ├──enables──> [Temporal Queries T22, D6]
    ├──enables──> [Replayable Reconciliation T7]
    ├──enables──> [Entity + Rule Lineage T14, D12]
    ├──enables──> [Audit Log T13]
    └──enables──> [Hash-chained Audit D7]

[Connector Framework T8,T9,T10]
    ├──requires──> [KMS / Credential Management T11]
    ├──requires──> [Schema Registry T1] (for mapping targets)
    └──feeds─────> [Rule Engine T6,T7, D5]

[Rule Engine D5]
    ├──requires──> [Source Authority Matrix T6, D14]
    ├──requires──> [Event Log T21] (for replay + attribution)
    └──produces──> [Conflict Register T5]

[Multi-tenant model_id T4]
    └──required-by──> [Row/Field Access Control T17,T18,D8]
                          └──required-by──> [Field-level Encryption D8]
                                                └──requires──> [KMS T11]

[MCP Projection D1]
    ├──requires──> [Schema Registry T1] (for describe_schema D15)
    ├──requires──> [Event Log T21] (for get_state_at D6)
    └──enhances──> [Graph as Agent Memory D2]

[Observability T12,T23,T24]
    └──enhances──> [all connector + rule + projection surfaces]
```

### Dependency Notes

- **Everything downstream of Schema Registry:** The meta-model is the spine. Projections, validation, MCP tooling, ontology evolution all key off it. This must land first and land well.
- **Event Log is the second spine:** Temporal queries, replay-based debugging, audit, and lineage all depend on events being complete, ordered, and tenant-scoped. Any shortcut here (e.g., missing events for bulk loads) destroys multiple downstream features at once.
- **Rule Engine depends on Source Authority Matrix + Event Log together:** A rule decision without recorded attribution is unauditable. Conflict register is a byproduct, not a separate feature.
- **MCP projection depends on Schema Registry AND Event Log:** `describe_schema` needs the first; `get_state_at` needs the second. Without either, the MCP surface is hollow.
- **Field-level encryption depends on KMS + tenant scoping:** Cannot ship one without the others without creating a security footgun.
- **Observability has no hard dependencies but touches everything:** It should be built in from day one, not bolted on, because retrofitting metrics across the rule engine / connectors / projections is painful.
- **Connector framework depends on KMS:** Shipping a connector with credentials in a YAML file would cement a bad pattern; KMS integration is therefore in MVP even though it adds weight.

---

## MVP Definition

### Launch With (v1, matches PROJECT.md Phase 1)

Minimum viable product — enough to validate "graph is truth, projections are generated" with one real consumer path.

- [ ] T1, T2 — Schema Registry with node types, edge types, properties, cardinalities, versioning
- [ ] T3, T15, T16 — SHACL validation + DQ rules + visible violations
- [ ] T4 — Multi-tenant `model_id` isolation on every node/edge/event
- [ ] T5, T6, D5, D14 — Rule engine + conflict register + per-property source authority matrix
- [ ] T8, T9, T10 — Connector framework (pluggable, with mappings, with delta)
- [ ] T11 — KMS integration (Vault) for connector credentials
- [ ] T12, T13, T23, T24 — Sync dashboard, audit log, health, metrics
- [ ] T14 — Entity-level lineage (which sources contributed)
- [ ] T17, T18, D8 — Row + field-level access control, field-level encryption
- [ ] T19, T20, D4 — Generated REST projection + OpenAPI
- [ ] T21 — Event log (authoritative in Postgres)
- [ ] T22, D6 (basic) — Point-in-time query for a single entity
- [ ] First concrete connector: generic REST polling

### Add After Validation (v1.x, matches Phase 2)

Triggered once Phase 1 proves the graph-as-truth hypothesis.

- [ ] D1, D2, D15 — MCP projection with `query_entities`, `traverse`, `get_state_at`, `find_path`, `describe_schema` — **the flagship differentiator; do not slip further than v1.x**
- [ ] D10 — Kafka projection (topics per entity type)
- [ ] D11 — SQL view projection for BI tools
- [ ] D7 — Hash-chained audit log (opt-in, compliance trigger)
- [ ] D12 (full) — Rule-level + event-level lineage surfaced in API
- [ ] Second and third connectors (JDBC, webhook) to validate the SPI

### Future Consideration (v2+)

Defer until PMF is established. Specifically defer until the MCP projection has real agent usage.

- [ ] GraphQL projection (Phase 4, already in PROJECT.md)
- [ ] D3 — LLM-assisted ontology evolution (needs real ontology churn to be worth it)
- [ ] Graph explorer UI (defer until users ask)
- [ ] Visual schema designer (schema-as-code is the better default)
- [ ] OWL reasoning module (opt-in, only if someone needs it)
- [ ] Drools-based rule engine migration (only when decision tables / CEP demand it)
- [ ] Write-back connectors (only per-connector, only opt-in)
- [ ] Neo4j read-replica for heavy traversals (ADR-1 mentions; only if Postgres+AGE hits a wall)
- [ ] Connector marketplace / plugin registry

---

## Feature Prioritization Matrix

| # | Feature | User Value | Impl. Cost | Priority |
|---|---------|------------|------------|----------|
| T1 | Schema Registry / Meta-Model | HIGH | MEDIUM | P1 |
| T2 | Schema versioning | HIGH | MEDIUM | P1 |
| T3 | SHACL validation on write | HIGH | MEDIUM | P1 |
| T4 | `model_id` multi-tenant isolation | HIGH | MEDIUM | P1 |
| T5 | Conflict register | HIGH | MEDIUM | P1 |
| T6 | Source authority matrix | HIGH | MEDIUM | P1 |
| T7 | Replayable reconciliation | HIGH | HIGH | P1 |
| T8 | Connector framework | HIGH | HIGH | P1 |
| T9 | Declarative mapping definitions | HIGH | MEDIUM | P1 |
| T10 | Delta detection | HIGH | MEDIUM | P1 |
| T11 | KMS / Vault credentials | HIGH | MEDIUM | P1 |
| T12 | Sync status dashboard | HIGH | MEDIUM | P1 |
| T13 | Audit log of mutations | HIGH | LOW-MEDIUM | P1 |
| T14 | Entity-level lineage | MEDIUM-HIGH | MEDIUM | P1 |
| T15 | DQ rules | HIGH | MEDIUM | P1 |
| T16 | DQ violations surfaced | MEDIUM-HIGH | LOW | P1 |
| T17 | Row-level access control | HIGH | MEDIUM | P1 |
| T18 | Field-level access control | HIGH | MEDIUM-HIGH | P1 |
| T19 | REST projection generated from schema | HIGH | MEDIUM | P1 |
| T20 | OpenAPI publication | MEDIUM | LOW | P1 |
| T21 | Event log | HIGH | MEDIUM | P1 |
| T22 | Point-in-time replay | MEDIUM-HIGH | HIGH | P1 |
| T23 | Health / liveness endpoints | MEDIUM | LOW | P1 |
| T24 | Metrics export | MEDIUM | LOW | P1 |
| D4 | Projection generation from schema | HIGH | HIGH | P1 |
| D5 | Explainable rule engine chain | HIGH | MEDIUM-HIGH | P1 |
| D8 | Field-level encryption + per-tenant KMS | HIGH | HIGH | P1 |
| D9 | Postgres + AGE single-transaction graph | HIGH | MEDIUM | P1 |
| D13 | Self-hosted, no cloud lock-in | MEDIUM-HIGH | LOW-MEDIUM | P1 |
| D14 | Per-property authority matrix | HIGH | MEDIUM | P1 |
| D1 | MCP projection + tools | HIGH | HIGH | **P2 (flagship — do not slip past v1.x)** |
| D2 | Graph as shared agent memory | HIGH | MEDIUM | P2 |
| D15 | `describe_schema` MCP tool | MEDIUM-HIGH | LOW | P2 |
| D6 | Full temporal query surface | MEDIUM-HIGH | HIGH | P2 |
| D7 | Hash-chained audit log | MEDIUM (HIGH for compliance users) | MEDIUM | P2 |
| D10 | Kafka projection | MEDIUM-HIGH | MEDIUM | P2 |
| D11 | SQL view projection for BI | HIGH | MEDIUM | P2 |
| D12 | Rule / event lineage surfaced | MEDIUM-HIGH | HIGH | P2 |
| D3 | LLM-assisted ontology evolution | MEDIUM | HIGH | P3 |
| — | GraphQL projection | MEDIUM | MEDIUM | P3 |
| — | Graph explorer UI | MEDIUM | HIGH | P3 |
| — | Visual schema designer | LOW-MEDIUM | HIGH | P3 |
| — | Write-back connectors | MEDIUM (per connector) | HIGH | P3 |
| — | OWL module | LOW | HIGH | P3 |

**Priority key:**
- **P1:** Must have for v1 launch (maps to PROJECT.md Phase 1 and the "table stakes" + foundational differentiators)
- **P2:** Add in v1.x (Phase 2) — includes the flagship MCP differentiator, which is P2 only because it depends on P1 foundations being solid
- **P3:** Defer to v2+

---

## Competitor Feature Analysis

Tessera does not position against these but inherits user expectations from them.

| Product | What Users Take From It | Tessera Stance |
|---------|--------------------------|----------------|
| **Palantir Foundry** | Ontology + lineage + access control in one platform; polished lineage UI | Tessera matches on ontology-as-spine, goes deeper on event/rule lineage, skips the closed ecosystem |
| **Microsoft Dataverse** | Declarative entities, generated REST/OData, row/field security | Tessera's generated REST projection is the same instinct; Tessera is not Power Platform-locked |
| **Atlan** | Column-level lineage, active metadata, business glossary, SaaS-first catalog | Tessera does NOT do column-level SQL lineage (A13); does entity/event/rule lineage instead; self-hosted vs SaaS |
| **Alation** | Data catalog, stewardship, governance workflows | Tessera is not a catalog layered over warehouses; it owns the data model and serves it |
| **Stardog** | Knowledge graph + SPARQL + virtualization + some reasoning | Closest in spirit; Tessera differs in: Postgres+AGE not RDF-native, SHACL not OWL-heavy, generated projections (REST/MCP/SQL/Kafka) rather than SPARQL primacy |
| **Neo4j + GraphRAG** | Graph + LLM retrieval | Tessera's graph is a *typed meta-model-validated* graph, not an ad-hoc LPG; MCP projection is first-class, not an add-on |
| **Talend / MuleSoft / Informatica** | Mature connectors, ETL orchestration | Tessera has connectors but is not an ETL tool (A4); delegates transformation to dbt / downstream |
| **Dagster / Airflow / dbt** | Pipeline orchestration | Out of scope — Tessera is the destination and source of truth, not the orchestrator |

---

## What Tessera Should NOT Build (Delegate List)

Explicit "we do not ship this, use X" statements for the roadmap and docs.

| Capability | Delegate To | Rationale |
|------------|-------------|-----------|
| Warehouse-scale OLAP / aggregations | Existing warehouse via SQL view projection | Postgres+AGE is wrong shape |
| Dashboards / charts | Metabase, Looker, PowerBI | SQL view projection is the integration point |
| Vector search / RAG | pgvector, Qdrant, Weaviate | Tessera is structured memory, not fuzzy |
| ETL orchestration | Airflow, Dagster, dbt | Huge entrenched market |
| Agent runtime / LLM hosting | User's own MCP client (Claude Desktop, custom) | MCP keeps the boundary clean |
| Secret storage | HashiCorp Vault, cloud KMS | Never in fabric DB |
| TLS termination | Nginx / Traefik / ingress | Standard infra pattern |
| Identity provider | Keycloak, Auth0, enterprise IdP | Tessera consumes JWT / OIDC, does not issue |
| Backup | Postgres-native (pg_basebackup, WAL-G) | Use proven tooling |
| BI governance workflows | Alation / Atlan if users want them | Out of scope |

---

## Confidence and Gaps

**Confidence:** MEDIUM-HIGH overall.
- PROJECT.md + concept doc pin down the architectural feature set at HIGH confidence.
- Competitive framing (Atlan column lineage, Foundry ontology, Dataverse generated REST) is MEDIUM — based on WebSearch of 2026 vendor and analyst material, not direct hands-on verification.
- MCP projection as a differentiator is HIGH confidence that it's novel in 2026; MEDIUM confidence on the exact tool surface (`query_entities`, `traverse`, `get_state_at`, etc.) until a prototype validates it.

**Open questions for phase-level research later:**
1. Exact MCP tool surface — which operations must be synchronous vs paginated vs streaming?
2. Point-in-time query semantics — strict replay vs materialized snapshots vs hybrid? Performance envelope matters for T22/D6 scope.
3. Conflict register UX — API-only in MVP, or minimal operator UI from day one?
4. Connector SPI shape — how much of the reconciliation loop is connector-owned vs framework-owned?
5. DQ rule authoring format — SHACL shapes only, or a higher-level DSL layered on top?
6. Tenant model — one `model_id` per customer, or per "domain within a customer"? Affects schema registry scope.

## Visual Inspiration Sources (v2+ Operator UI)

Not architectural references. Ideas to revisit **only** when the deferred operator / graph-explorer UI is revisited in v2+ (see anti-feature A14). Tracked here so they are not forgotten.

| Source | What to borrow | What NOT to borrow | Notes |
|--------|----------------|---------------------|-------|
| **NeuroLinked** (`~/Downloads/NeuroLinked-Release`, Python + Three.js, reviewed 2026-04-14) | Entity-type color coding, signal-particle flows along edges (good for visualizing connector ingestion + MCP tool invocations), bloom/glow on "hot" nodes for recent-activity emphasis, "maturity stage" metaphor applied to graph evolution (first nodes → reconciliation kicks in → rule engine fires → conflicts surface) | 3D force-directed layout with fixed regions — collapses past ~10k nodes. The Three.js code is a single-purpose demo (~950 LOC), not a graph explorer. The "neuromorphic brain" framing is marketing gloss; the architecture is a spiking-neuron simulation, not a knowledge graph. | Tessera's operator UI, when built, should use **2D + WebGL** (Sigma.js or Cytoscape.js) with typed-entity filtering, not 3D. NeuroLinked is inspiration for *aesthetic vocabulary*, not layout strategy. |

---

## Sources

- [10 Data Fabric Tools for 2026 (Features & Benefits) — Domo](https://www.domo.com/learn/article/best-data-fabric-tools)
- [Data Fabric vs. Data Mesh: 2026 Guide — Alation](https://www.alation.com/blog/data-mesh-vs-data-fabric/)
- [Metadata Management Architecture: 5 Patterns — Promethium](https://promethium.ai/guides/metadata-management-architecture-patterns-enterprise-scale/)
- [Data Fabric Architecture: Components, Uses & Setup in 2026 — Atlan](https://atlan.com/data-fabric-explained/)
- [The Metadata Imperative for AI in 2026 — Alation](https://www.alation.com/blog/metadata-ai-2026-trust-compliance-scale/)
- [Metadata-Driven Lineage in Microsoft Fabric — AnalyticsCreator](https://www.analyticscreator.com/blog/metadata-driven-data-lineage-and-governance-in-microsoft-fabric-and-synapse)
- [Data Lineage Overview — Palantir Foundry](https://www.palantir.com/docs/foundry/data-lineage/overview)
- [Column-Level Lineage on Atlan](https://atlan.com/column-level-lineage/)
- [External Catalogs — Stardog Documentation](https://docs.stardog.com/knowledge-catalog/external-catalogs)
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/PROJECT.md`
- `/Users/matthiaswegner/Documents/Leo's Gedankenspeicher/CONCEPT-Meta-Model-Driven-Data-Fabric.md`
