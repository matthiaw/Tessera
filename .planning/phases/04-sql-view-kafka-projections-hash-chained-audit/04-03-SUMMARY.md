---
phase: 04-sql-view-kafka-projections-hash-chained-audit
plan: "03"
subsystem: kafka-projections
tags: [kafka, debezium, outbox, cdc, docker-compose, actuator, health-indicator]

requires:
  - phase: 04-sql-view-kafka-projections-hash-chained-audit
    plan: "01"
    provides: V24__outbox_published_flag.sql (published column on graph_outbox)

provides:
  - Docker Compose kafka profile: kafka (KRaft) + Debezium Connect + connector-init
  - docker/debezium/connectors/tessera-outbox.json (Outbox Event Router SMT config)
  - OutboxPoller conditionalized via @ConditionalOnProperty(tessera.kafka.enabled=false)
  - DebeziumSlotHealthIndicator at /actuator/health/debezium (tessera.kafka.enabled=true)
  - OutboxPollerConditionalIT (3 tests) + DebeziumSlotHealthIndicatorTest (4 tests)

affects: []

tech-stack:
  added:
    - "spring-boot-starter-actuator added to fabric-projections pom.xml"
    - "bitnami/kafka:3.9 (KRaft) in Docker Compose kafka profile"
    - "quay.io/debezium/connect:3.4 in Docker Compose kafka profile"
    - "curlimages/curl:latest for one-shot connector registration"
  patterns:
    - "@ConditionalOnProperty(havingValue=false, matchIfMissing=true) for opt-in Kafka mode"
    - "AbstractHealthIndicator + pg_wal_lsn_diff for replication slot lag monitoring"
    - "Docker Compose profiles: [kafka] to keep default dev stack clean"
    - "ApplicationContextRunner.withUserConfiguration (not withBean) to honor @ConditionalOnProperty in tests"

key-files:
  created:
    - docker/debezium/connectors/tessera-outbox.json
    - fabric-projections/src/main/java/dev/tessera/projections/kafka/DebeziumSlotHealthIndicator.java
    - fabric-projections/src/test/java/dev/tessera/projections/kafka/DebeziumSlotHealthIndicatorTest.java
  modified:
    - docker-compose.yml
    - fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
    - fabric-app/src/main/resources/application.yml
    - fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java
    - fabric-projections/pom.xml

key-decisions:
  - "ApplicationContextRunner.withUserConfiguration(OutboxPoller.class) required (not withBean) — withBean bypasses @ConditionalOnProperty evaluation; withUserConfiguration registers as a @Component candidate so conditions are evaluated during context refresh"
  - "bitnami/kafka:3.9 chosen over apache/kafka:3.9.0 for KRaft first-class support; ImagePinningTest only enforces apache/age digest pinning, not Kafka/Debezium images"
  - "aggregatetype in GraphServiceImpl changed to modelId + . + type for Debezium EventRouter topic routing to tessera.{model_id}.{type_slug}; dot separator is safe for Kafka topic names"
  - "spring-boot-starter-actuator added to fabric-projections pom.xml — required for AbstractHealthIndicator; missing dependency is a Rule 3 (blocking) fix"

requirements-completed: [KAFKA-01, KAFKA-02, KAFKA-03]

duration: 5min
completed: 2026-04-17
---

# Phase 04 Plan 03: Debezium/Kafka CDC Fan-out + OutboxPoller Conditionalization Summary

**Debezium 3.4 Outbox Event Router replaces in-process OutboxPoller for Kafka-enabled deployments, with KRaft Kafka + Debezium Connect as a `--profile kafka` Docker Compose stack and replication slot lag health indicator**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-17T09:43:02Z
- **Completed:** 2026-04-17T09:48:20Z
- **Tasks:** 2
- **Files modified:** 9 (3 created + 6 modified)

## Accomplishments

- Added three Docker Compose services under `profiles: ["kafka"]`:
  - `kafka` — Bitnami Kafka 3.9 in KRaft mode (no ZooKeeper), port 9092, healthcheck via `kafka-topics.sh`
  - `debezium-connect` — Debezium Connect 3.4, depends on kafka + postgres-age healthy, port 8083
  - `debezium-connector-init` — one-shot curl container that registers the Outbox Event Router connector via PUT to Kafka Connect REST API
- Created `docker/debezium/connectors/tessera-outbox.json` with full Outbox Event Router SMT configuration: routes `graph_outbox` CDC events to `tessera.{model_id}.{type_slug}` topics via `aggregatetype` field routing
- Added `@ConditionalOnProperty(name="tessera.kafka.enabled", havingValue="false", matchIfMissing=true)` to `OutboxPoller` — poller is active by default; setting `tessera.kafka.enabled=true` removes the bean from context entirely
- Updated `MARK_DELIVERED_SQL` in `OutboxPoller` to also set `published = true` — wires the V24 `published` column for fallback poller mode (D-B4)
- Changed `aggregatetype` in `GraphServiceImpl.apply()` from bare type slug to `modelId + "." + typeSlug` — enables Debezium EventRouter to route to per-tenant topics
- Added `tessera.kafka.enabled=false` and `tessera.kafka.lag-threshold-bytes=104857600` to `application.yml`
- Implemented `DebeziumSlotHealthIndicator` — `@Component("debezium")` extending `AbstractHealthIndicator`, only active when `tessera.kafka.enabled=true`, queries `pg_wal_lsn_diff` for `tessera_outbox_slot` lag, reports UP/DOWN with configurable threshold
- `OutboxPollerConditionalIT`: 3 tests verifying bean presence/absence via `ApplicationContextRunner`
- `DebeziumSlotHealthIndicatorTest`: 4 unit tests with mocked `NamedParameterJdbcTemplate` covering UP (50MB), DOWN (200MB), slot missing, and SQL query correctness

## Task Commits

1. **Task 1: Docker Compose Kafka+Debezium stack, conditionalize OutboxPoller** — `2b06def`
2. **Task 2: DebeziumSlotHealthIndicator for replication slot lag monitoring** — `11280df`

## Files Created/Modified

- `docker-compose.yml` — Added kafka, debezium-connect, debezium-connector-init services with `profiles: ["kafka"]`; added `tessera-kafka-data` volume
- `docker/debezium/connectors/tessera-outbox.json` — Debezium Outbox Event Router connector config with `table.include.list=public.graph_outbox` and `route.topic.replacement=tessera.${routedByValue}`
- `OutboxPoller.java` — Added `@ConditionalOnProperty`, updated `MARK_DELIVERED_SQL` with `published = true`
- `GraphServiceImpl.java` — Changed `outbox.append()` aggregateType argument to `modelId + "." + type`
- `application.yml` — Added `tessera.kafka.enabled` and `tessera.kafka.lag-threshold-bytes` config
- `OutboxPollerConditionalIT.java` — Replaced Wave 0 stub with 3 working tests using `ApplicationContextRunner`
- `DebeziumSlotHealthIndicator.java` — New health indicator for replication slot lag
- `DebeziumSlotHealthIndicatorTest.java` — New 4-test unit test suite
- `fabric-projections/pom.xml` — Added `spring-boot-starter-actuator` dependency

## Decisions Made

- `withUserConfiguration(OutboxPoller.class)` not `withBean(OutboxPoller.class)` in `ApplicationContextRunner` — `withBean` bypasses condition processing; `withUserConfiguration` treats the class as a `@Configuration`/`@Component` candidate so `@ConditionalOnProperty` is evaluated during context refresh
- `bitnami/kafka:3.9` for KRaft support; `ImagePinningTest` only enforces digest pinning for `apache/age` images, not Kafka/Debezium
- `modelId + "." + typeSlug` dot separator for `aggregatetype` — safe for Kafka topic names, enables per-tenant topic isolation via Debezium SMT topic replacement pattern

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Missing spring-boot-starter-actuator in fabric-projections pom.xml**
- **Found during:** Task 2 (compilation error for AbstractHealthIndicator)
- **Issue:** `DebeziumSlotHealthIndicator` extends `AbstractHealthIndicator` from `spring-boot-starter-actuator`, which was not in the `fabric-projections` dependency list
- **Fix:** Added `spring-boot-starter-actuator` to `fabric-projections/pom.xml` dependencies
- **Files modified:** `fabric-projections/pom.xml`
- **Commit:** `11280df`

**2. [Rule 1 - Bug] ApplicationContextRunner.withBean bypasses @ConditionalOnProperty**
- **Found during:** Task 1 test execution (`pollerAbsentWhenKafkaEnabled` failed)
- **Issue:** Initial test used `.withBean(OutboxPoller.class)` which registers the bean directly into the context, bypassing Spring Boot's condition evaluation — `@ConditionalOnProperty` was never checked
- **Fix:** Changed to `.withUserConfiguration(MinimalConfig.class, OutboxPoller.class)` so conditions are evaluated during `AnnotationConfigApplicationContext` refresh
- **Files modified:** `OutboxPollerConditionalIT.java`
- **Commit:** `2b06def`

---

**Total deviations:** 2 auto-fixed (1 Rule 3 — missing actuator dependency, 1 Rule 1 — ApplicationContextRunner bean registration bypassed conditions)

## Known Stubs

None — all plan deliverables fully implemented. Wave 0 `OutboxPollerConditionalIT` stub replaced with working tests.

## Threat Flags

No new threat surface beyond the plan's threat model. T-04 mitigations applied:
- T-04-I3: Topics named `tessera.{model_id}.{type_slug}` enable per-tenant ACL enforcement. Production Kafka ACL requirement documented in connector JSON comment.
- T-04-T3: `tessera-outbox.json` contains dev credentials (tessera/tessera) — accepted, file is Docker Compose dev-only. Production must use Vault-sourced credentials.
- T-04-D2: `DebeziumSlotHealthIndicator` fires DOWN when lag exceeds threshold; V26 `max_slot_wal_keep_size=2GB` caps WAL growth.
- T-04-S3: Connector init uses unauthenticated Kafka Connect REST — accepted for local dev.

---
*Phase: 04-sql-view-kafka-projections-hash-chained-audit*
*Completed: 2026-04-17*
