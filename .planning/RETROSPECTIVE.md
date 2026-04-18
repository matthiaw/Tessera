# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.0 — MVP

**Shipped:** 2026-04-18
**Phases:** 12 | **Plans:** 38

### What Was Built
- Graph core on PostgreSQL + Apache AGE with single-TX write funnel, SHACL validation, 4-chain rule engine
- Four projection engines: REST (dynamic), MCP (7 agent tools), SQL views (BI), Kafka/Debezium (CDC)
- Connector framework with structured (REST polling) and unstructured (LLM extraction + pgvector resolution) modes
- circlead integration as first real consumer with 3 mapping definitions
- Production hardening: Prometheus metrics, health indicators, Vault secrets, DR drill, field-level ACL
- Gap-closure phases (6-10) addressing audit findings: metrics wiring, schema events, circlead production wiring, Vault health, field-level ACL

### What Worked
- Yolo mode with parallel execution kept velocity high across 267 commits in 5 days
- Bottom-up phase ordering (foundations → core → projections → integration → hardening) avoided rework
- Milestone audit after Phase 5 caught real gaps (placeholder resolution bug, DR drill column mismatch, missing metrics wiring) that would have been production incidents
- Gap-closure phases (6-10) were focused and fast — 1-2 plans each, surgical fixes
- Testcontainers with custom AGE+pgvector Docker image gave high-fidelity integration tests

### What Was Inefficient
- Phase 5 was too large (5 plans, 4 concerns: observability + circlead + event lifecycle + DR drill) — should have been 2-3 smaller phases
- REQUIREMENTS.md traceability table fell behind during execution — many items stayed "Pending" despite being implemented
- ROADMAP.md progress table was not updated as phases completed — stale metadata
- UAT scenarios requiring live IONOS VPS deployment could not be tested locally — deferred to post-deploy

### Patterns Established
- MetricsPort SPI pattern: fabric-core defines the port interface, fabric-app provides the Micrometer adapter — clean cross-module metrics without upward dependencies
- AclPropertyCache pattern: per-tenant cached ACL lookups keyed by (model_id, typeSlug, propertySlug)
- SchemaChangeEvent infrastructure: ApplicationEvent published from SchemaRegistry, consumed by projections for live refresh
- agtype→jsonb cast convention: all SQL views explicitly cast AGE properties to jsonb
- MappingDefinition placeholder resolution: Spring Environment.resolvePlaceholders() in connector config, not in Jackson deserialization

### Key Lessons
1. Run milestone audit early (after the "core" phases), not just at the end — the Phase 5 audit caught integration gaps that were cheap to fix as targeted phases
2. Keep traceability tables up to date during execution, not just at milestone close — stale metadata creates unnecessary confusion
3. Large "integration + hardening" phases should be split by concern — observability, integration, and DR are independent workstreams
4. Gap-closure phases work well as 1-plan surgical fixes — don't bundle them into a single catch-all phase

### Cost Observations
- Model mix: primarily Opus for planning and execution
- Sessions: ~15 sessions over 5 days
- Notable: parallel agent execution for independent plans significantly reduced wall-clock time

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Phases | Plans | Key Change |
|-----------|--------|-------|------------|
| v1.0 | 12 | 38 | Established GSD workflow with yolo mode, milestone audit, gap-closure pattern |

### Top Lessons (Verified Across Milestones)

1. Milestone audit → gap-closure phases is an effective pattern for catching integration gaps
2. Bottom-up phase ordering prevents rework in graph-based architectures
