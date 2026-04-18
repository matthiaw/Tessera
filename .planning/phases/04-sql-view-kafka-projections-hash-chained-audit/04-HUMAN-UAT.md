---
status: resolved
phase: 04-sql-view-kafka-projections-hash-chained-audit
source: [04-VERIFICATION.md]
started: 2026-04-17T00:00:00Z
updated: 2026-04-18T00:00:00Z
---

## Current Test

[2 items deferred to post-release validation]

## Tests

### 1. Kafka end-to-end topic delivery
expected: docker compose --profile kafka up, submit mutation, event arrives on tessera.{model_id}.{type_slug} topic
result: DEFERRED — requires full Docker Compose Kafka+Debezium stack; OutboxPollerConditionalIT (3/3 passed) confirms conditionalization logic; Kafka topic delivery is a deployment validation item

### 2. SQL view BI-tool aggregate performance
expected: Query v_{prefix}_{typeSlug} via JDBC, aggregate speed exceeds equivalent Cypher
result: DEFERRED — requires seeded DB with 10k+ nodes; SqlViewProjectionIT has pre-existing AGE label seeding issue (tracked); SQL view DDL generation and staleness detection verified via code review

## Summary

total: 2
passed: 0
issues: 0
pending: 0
skipped: 0
blocked: 0
deferred: 2

## Gaps
