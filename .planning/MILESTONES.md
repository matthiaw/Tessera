# Milestones: Tessera

## v1.0 MVP — Shipped 2026-04-18

**Delivered:** Graph-based integration layer with 4 projections (REST, MCP, SQL Views, Kafka), structured + unstructured connectors, circlead as first consumer, and production hardening.

| Metric | Value |
|--------|-------|
| Phases | 12 (0-10 + 2.5) |
| Plans | 38 |
| Commits | 267 |
| Files changed | 638 |
| Java LOC | ~140k |
| Timeline | 5 days (2026-04-13 → 2026-04-18) |
| Requirements | 98/98 shipped |

**Key accomplishments:**
1. Graph core: single-TX write funnel with SHACL validation, 4-chain rule engine, tenant isolation on PostgreSQL + Apache AGE
2. Dynamic REST projection: runtime-routed endpoints from Schema Registry, OAuth2 + Vault, cursor pagination
3. Unstructured ingestion: LLM entity extraction with pgvector resolution and review queue
4. MCP projection: 7 agent tools with audit, quota, prompt-injection protection — flagship differentiator
5. SQL views + Kafka/Debezium CDC + hash-chained audit integrity
6. Circlead as first real consumer with production observability, DR drill, Vault secrets, field-level ACL

**Archive:** [v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md) | [v1.0-REQUIREMENTS.md](milestones/v1.0-REQUIREMENTS.md)
