# Backlog: Tessera v2+ Requirements

**Status:** Deferred — tracked but not yet scheduled into a milestone.
**Origin:** Defined during initial project setup (2026-04-13), carried forward after v1.0 MVP shipped (2026-04-18).

---

## GraphQL Projection (GQL)

- **GQL-01**: Dynamic GraphQL schema generated from Schema Registry
- **GQL-02**: Field-level auth inherited from REST projection rules

## LLM-Assisted Ontology (LLM)

- **LLM-01**: LLM agent proposes schema extensions from natural-language descriptions
- **LLM-02**: Proposed changes require explicit admin review before activation
- **LLM-03**: Agent is aware of the existing ontology and proposes consistent extensions

## Visual Schema Designer (UI)

- **UI-01**: Graph explorer web UI for browsing entities and relationships
- **UI-02**: Visual schema editor with drag-and-drop type/property creation
- **UI-03**: Conflict register operator UI (approve / reject / merge resolutions)

## Drools Migration (DROOLS)

- **DROOLS-01**: Rule engine swappable to Drools for CEP and decision-table support
- **DROOLS-02**: Existing custom rules migrate without authoring changes

## OWL Reasoning (OWL)

- **OWL-01**: Optional Apache Jena OWL reasoner module activatable per tenant
- **OWL-02**: Automatic type classification and transitive relation inference

## Write-Back Connectors (WRITE)

- **WRITE-01**: Per-connector opt-in write-back propagation from graph to source system
- **WRITE-02**: Conflict register must be battle-tested in read-only mode before any write-back ships

## Advanced Connectors (ACON)

- **ACON-01**: SAP connector (BAPI / OData)
- **ACON-02**: Salesforce connector (SOAP / Bulk API)
- **ACON-03**: Jira connector
- **ACON-04**: Generic JDBC bridge for legacy databases
- **ACON-05**: CDC-based connectors via Debezium

## Neo4j Read Replica (N4J)

- **N4J-01**: Optional Neo4j read-replica fed from the event log for graph-intensive traversals
- **N4J-02**: Event-bus-driven synchronization between Postgres primary and Neo4j replica

## Code Property Graph / Joern Integration (CPG)

- **CPG-01**: Joern connector that imports Code Property Graph exports (JSON) into Tessera via the connector framework
- **CPG-02**: Schema types for code entities: `Method`, `Class`, `File`, `Package`, `CallSite`, `Vulnerability` with edge types `CALLS` (with `count` property), `CONTAINS`, `IMPORTS`, `EXTENDS`, `REACHES`
- **CPG-03**: Multi-repo support — multiple repositories in a single graph, scoped per `model_id` or tagged per repo
- **CPG-04**: Temporal code analysis — "Was this vulnerability present on date X?" via event-log replay
- **CPG-05**: Reconciliation of multiple analysis sources (Joern, SonarQube, custom) in the same graph using the Source Authority Matrix

---

## Summary

| Group | Requirements | Key Dependency |
|-------|-------------|----------------|
| GraphQL Projection | GQL-01..02 | REST projection + Schema Registry (v1.0 shipped) |
| LLM-Assisted Ontology | LLM-01..03 | MCP projection + Schema Registry (v1.0 shipped) |
| Visual Schema Designer | UI-01..03 | Schema Registry + Conflict Register (v1.0 shipped) |
| Drools Migration | DROOLS-01..02 | Custom rule engine battle-tested (v1.0 shipped) |
| OWL Reasoning | OWL-01..02 | SHACL validation (v1.0 shipped) |
| Write-Back Connectors | WRITE-01..02 | Conflict register battle-tested in production |
| Advanced Connectors | ACON-01..05 | Connector framework + mapping definitions (v1.0 shipped) |
| Neo4j Read Replica | N4J-01..02 | Event log (v1.0 shipped) |
| Code Property Graph / Joern | CPG-01..05 | Connector framework (v1.0 shipped) + [Joern](https://github.com/joernio/joern) |

**Total: 9 groups, 25 requirements**

---
*Restored from git history (commit 2b07593, 2026-04-13) on 2026-04-18*
*CPG/Joern integration added: 2026-04-19*
