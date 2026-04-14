# ADR-7: Rule Engine Contract — Phase 1 Override of REQUIREMENTS.md RULE-01..04

**Status:** Accepted
**Date:** 2026-04-14
**Phase:** 1
**Supersedes:** REQUIREMENTS.md RULE-01..04 (wording pre-discuss-phase)
**Related:** ADR-3 (custom chain-of-responsibility rule engine, Drools deferred)

## Context

REQUIREMENTS.md v1 was written before `/gsd-discuss-phase 1` surfaced Phase 1's load-bearing decisions for the rule engine. During that discussion four decisions were locked into `01-CONTEXT.md` (D-C1..D-C4) that shape the rule engine at full fidelity:

- **D-C1:** Four named chains in fixed pipeline order — `VALIDATE → RECONCILE → ENRICH → ROUTE`
- **D-C2:** `source_authority` table is runtime-editable per tenant × per-property, Caffeine-cached
- **D-C3:** `reconciliation_conflicts` dedicated relational table with operator-UI-ready indexes
- **D-C4:** Business rules implemented as Java `Rule` classes (not SHACL-SPARQL)

Simultaneously, D-A1..D-A3 locked the Phase 2.5 forward-commit: `GraphMutation` carries full provenance, the review queue is a Phase 2.5 pre-funnel layer (not a rule-engine outcome), and the rule engine treats all sources uniformly.

The Phase 1 plan checker (after `/gsd-plan-phase 1`) correctly flagged that these decisions diverged from REQUIREMENTS.md RULE-01..04 without an explicit override record. This ADR is that record.

## Decisions

### RULE-01: Priority Sort Direction

**REQUIREMENTS.md original:** Sort rules by priority **DESC** (higher priority runs first), short-circuit on match.

**Phase 1 override:** **No change in intent — re-align naming.**

CONTEXT D-C1 said "lower = earlier" (ASC), which is semantically equivalent in intent but inverts the column value vs REQUIREMENTS.md. This was an inconsistency, not a design choice. Phase 1 aligns to REQUIREMENTS.md: `ChainExecutor` sorts by `priority()` **DESC**, so a rule with priority 100 runs before a rule with priority 10. CONTEXT.md §D-C1 "lower = earlier" language is corrected to "higher = earlier / DESC sort".

### RULE-02: Rule Interface

**REQUIREMENTS.md original:**
```java
interface ReconciliationRule {
    int priority();
    boolean matches(Event event);
    RuleResult apply(Event event);
}
```

**Phase 1 override:**
```java
public interface Rule {
    String id();                                 // stable identifier for logging + conflict register
    Chain chain();                               // VALIDATE | RECONCILE | ENRICH | ROUTE (D-C1)
    int priority();                              // DESC sorted — higher runs first (RULE-01)
    boolean applies(RuleContext ctx);
    RuleOutcome evaluate(RuleContext ctx);
}
```

**Rationale:**
1. The interface was specified before the four-chain structure existed (D-C1). `chain()` is required so rules self-register into the correct named chain.
2. `id()` is required for the `reconciliation_conflicts` table (D-C3) to record `rule_id` as the decider of every conflict resolution.
3. `RuleContext` is richer than bare `Event`: it carries `TenantContext`, the loaded schema descriptor, the current node state, and the incoming `GraphMutation`. Rules need all four to make informed decisions, not just the raw event.
4. `RuleOutcome` (see RULE-03) is a sealed interface with richer semantics than the old `RuleResult`.

`ReconciliationRule` as a name was also misleading: after D-C1 most rules run in VALIDATE, ENRICH, or ROUTE chains — only the RECONCILE chain is about reconciliation in the strict sense.

### RULE-03: Rule Outcomes

**REQUIREMENTS.md original:** `ACCEPT_SOURCE`, `REJECT`, `FLAG_FOR_REVIEW`, `MERGE`, `TRANSFORM`, `DEFER`

**Phase 1 override:** `COMMIT`, `REJECT`, `MERGE`, `OVERRIDE`, `ADD`, `ROUTE`

Mapping and rationale:

| REQUIREMENTS.md | Phase 1 | Change |
|---|---|---|
| `ACCEPT_SOURCE` | `COMMIT` | Renamed for clarity — a rule commits the candidate mutation, it does not "accept a source" (sources are identified via `GraphMutation.sourceSystem`, not via rule outcome) |
| `REJECT` | `REJECT` | Unchanged |
| `FLAG_FOR_REVIEW` | *(moved to Phase 2.5)* | Per D-A2: the review queue is a Phase 2.5 pre-funnel layer, not a rule engine terminal outcome. A low-confidence extraction is routed to `extraction_review_queue` BEFORE `GraphService.apply()` is called. The rule engine stays pure. |
| `MERGE` | `MERGE` | Unchanged |
| `TRANSFORM` | `OVERRIDE` | Renamed: "transform" implies input-level rewriting, "override" accurately describes the RECONCILE chain replacing a contested property value |
| `DEFER` | *(moved to Phase 2.5)* | Same reason as `FLAG_FOR_REVIEW` — deferral is a pre-funnel routing decision in Phase 2.5 |
| — | `ADD` (new) | The ENRICH chain needs an outcome that adds derived properties without touching existing ones. This didn't exist in the original outcomes because the ENRICH chain didn't exist. |
| — | `ROUTE` (new) | The ROUTE chain writes downstream routing hints into `graph_outbox.routing_hints`. This didn't exist in the original outcomes because the ROUTE chain didn't exist. |

Put differently: removing `FLAG_FOR_REVIEW` and `DEFER` is the **direct consequence** of D-A2. Adding `ADD` and `ROUTE` is the **direct consequence** of D-C1. The other two renames (`ACCEPT_SOURCE` → `COMMIT`, `TRANSFORM` → `OVERRIDE`) are cosmetic.

### RULE-04: Rule Storage

**REQUIREMENTS.md original:** Rules are stored in `reconciliation_rules` table and reloaded on change; per-tenant configuration.

**Phase 1 override:** **Hybrid — Java classes for logic, DB table for per-tenant activation metadata.**

Rationale: REQUIREMENTS.md RULE-04 is ambiguous between two possible designs:
1. **Rules-as-data** — rule logic stored as SQL / DSL / config in the DB, interpreted at runtime
2. **Rules-as-code-plus-config** — rule logic stored as Java classes, activation/ordering/parameters stored in the DB per tenant

Phase 1 adopts design 2. Reasons:
- Java gives full language features for cross-entity and time-dependent rules (the kind that REQUIREMENTS.md RULE-05 implies — "HR owns name, CRM owns phone, ERP owns cost center")
- Rules-as-data would mean inventing a rule DSL in Phase 1 — that's scope creep against ADR-3 ("custom chain-of-responsibility for MVP")
- Rules-as-data's "hot reload" case is the same as design 2's "enable/disable this rule for this tenant at runtime" case, which is achievable via the hybrid model

Concrete schema for `reconciliation_rules` (landing as Flyway V9 in Phase 1 plan W0-01):

```sql
CREATE TABLE reconciliation_rules (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    rule_id TEXT NOT NULL,              -- matches Rule.id() in Java
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority_override INTEGER,          -- NULL = use Rule.priority() default; non-NULL = per-tenant override
    parameters JSONB,                   -- rule-specific per-tenant config (e.g. thresholds)
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by TEXT NOT NULL,
    UNIQUE (model_id, rule_id)
);
CREATE INDEX idx_reconciliation_rules_model ON reconciliation_rules (model_id);
```

Rule loading: `RuleRepository` reads this table + `List<Rule>` Spring beans, joins them by `rule_id`, filters by `enabled`, sorts by `priority_override` (or `Rule.priority()` default) DESC. Result is cached in Caffeine keyed by `model_id`. An admin endpoint (`POST /admin/rules/reload/{model_id}`, internal-only in Phase 1) invalidates the cache. This satisfies RULE-04's "stored in table and reloaded on change" intent without requiring runtime code generation.

## Consequences

**Positive:**
- Plans W0-01, W3-02, and W3-03 now match a consistent, coherent contract
- Phase 2.5 forward-commit (D-A1..D-A3) is preserved — adding extraction does not touch the rule engine
- REQUIREMENTS.md traceability is restored: RULE-01..04 point to this ADR as the authoritative interpretation
- The four-chain structure (D-C1) that makes Phase 1 reasonable about rule execution order stays intact

**Negative:**
- REQUIREMENTS.md v1 text must be updated in-place to reference ADR-7, creating a small documentation debt
- Anyone reading REQUIREMENTS.md v1 in isolation will not see the Phase 1 contract without following the ADR link

**Neutral:**
- None of the five Phase 1 ROADMAP success criteria change
- Nothing in Phase 2+ downstream is affected — the rule engine contract is internal to Phases 1 and 2.5

## Supersession

This ADR is the authoritative source for RULE-01..04 contracts until a future ADR explicitly supersedes it. REQUIREMENTS.md RULE-01..04 text is updated in-place to "See ADR-7" with a short summary.

---
*ADR-7 accepted 2026-04-14 after Phase 1 plan-checker flagged the silent divergence*
