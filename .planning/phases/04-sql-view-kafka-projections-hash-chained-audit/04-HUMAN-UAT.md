---
status: partial
phase: 04-sql-view-kafka-projections-hash-chained-audit
source: [04-VERIFICATION.md]
started: 2026-04-17T00:00:00Z
updated: 2026-04-17T00:00:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Kafka end-to-end topic delivery
expected: docker compose --profile kafka up, submit mutation, event arrives on tessera.{model_id}.{type_slug} topic
result: [pending]

### 2. SQL view BI-tool aggregate performance
expected: Query v_{prefix}_{typeSlug} via JDBC, aggregate speed exceeds equivalent Cypher
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps
