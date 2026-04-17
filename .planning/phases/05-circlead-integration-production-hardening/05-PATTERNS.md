# Phase 5: Circlead Integration & Production Hardening — Patterns

**Created:** 2026-04-17
**Phase:** 05-circlead-integration-production-hardening

---

## Files to Create / Modify

### New Files

| File | Module | Requirement |
|------|--------|-------------|
| `fabric-app/src/main/resources/db/migration/V28__model_config_lifecycle.sql` | fabric-app | OPS-03, OPS-04 |
| `fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetrics.java` | fabric-app | OPS-01 |
| `fabric-app/src/main/java/dev/tessera/app/health/AgeGraphHealthIndicator.java` | fabric-app | OPS-02 |
| `fabric-app/src/main/java/dev/tessera/app/health/ConnectorHealthIndicator.java` | fabric-app | OPS-02 |
| `fabric-core/src/main/java/dev/tessera/core/events/EventRetentionJob.java` | fabric-core | OPS-04 |
| `fabric-core/src/main/java/dev/tessera/core/events/snapshot/EventSnapshotService.java` | fabric-core | OPS-03 |
| `fabric-core/src/main/java/dev/tessera/core/admin/EventLifecycleController.java` | fabric-core | OPS-03 |
| `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java` | fabric-connectors | CIRC-01, CIRC-02 |
| `fabric-connectors/src/main/resources/connectors/circlead-role-mapping.json` | fabric-connectors | CIRC-02 |
| `fabric-connectors/src/main/resources/connectors/circlead-circle-mapping.json` | fabric-connectors | CIRC-02 |
| `fabric-connectors/src/main/resources/connectors/circlead-activity-mapping.json` | fabric-connectors | CIRC-02 |
| `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` | fabric-connectors | CIRC-01, CIRC-04 |
| `docs/circlead-mapping.md` | root | CIRC-02 |
| `docs/DR-DRILL.md` | root | D-D3 |
| `scripts/dr_drill.sh` | root | D-D1 |

### Modified Files

| File | Change | Requirement |
|------|--------|-------------|
| `fabric-app/src/main/resources/application.yml` | Add `prometheus` to `management.endpoints.web.exposure.include` | OPS-01 |
| `fabric-app/pom.xml` | Add `micrometer-registry-prometheus` and `micrometer-tracing-bridge-otel` dependencies | OPS-01, OPS-03 (D-B3) |
| `.github/workflows/ci.yml` | Extend to run full milestone-1 test suite; add DR drill step | D-D2 |
| All test-module `src/test/resources/db/migration/` copies | Add V28 migration file | OPS-03, OPS-04 |

---

## Pattern 1: Micrometer Custom Meter Registration

**Analog:** `fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java`

The canonical codebase pattern for custom Micrometer meters is constructor-time registration using `Counter.builder()` and `Gauge.builder()` with tag dimensions. The `WriteRateCircuitBreaker` registers meters in its constructor via the injected `MeterRegistry`. New meters for Phase 5 follow this exact shape.

Key points from the analog:
- Null-guard the `meterRegistry` before calling `.register()` (line 209: `if (meterRegistry != null)`) — `SimpleMeterRegistry` is always present in tests but the guard prevents double-registration errors
- Use `Counter.builder("metric.name").description("...").tag("key", "value").register(registry).increment()` for counters created on-demand at trip time
- Use constructor-time registration for long-lived counters
- Metric names use dot-notation: `tessera.circuit.tripped`, `tessera.ingest.rate`

**`TesseraMetrics.java` — new file pattern:**
```java
// fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetrics.java
// Analog: WriteRateCircuitBreaker.java constructor + trip() method
@Component
public class TesseraMetrics {
    private final Counter ingestRate;
    private final Counter rulesEvaluations;
    private final Counter conflictsCount;

    public TesseraMetrics(MeterRegistry registry, NamedParameterJdbcTemplate jdbc) {
        this.ingestRate = Counter.builder("tessera.ingest.rate")
            .description("Graph mutations applied")
            .tag("source", "connector")
            .register(registry);
        this.rulesEvaluations = Counter.builder("tessera.rules.evaluations")
            .description("Rule chain evaluations executed")
            .register(registry);
        this.conflictsCount = Counter.builder("tessera.conflicts.count")
            .description("Reconciliation conflicts recorded")
            .register(registry);
        // Gauge: supplier lambda, polled on scrape
        Gauge.builder("tessera.outbox.lag", jdbc,
                j -> j.queryForObject("SELECT COUNT(*) FROM graph_outbox WHERE status='PENDING'",
                    new MapSqlParameterSource(), Long.class))
            .description("Pending outbox entries")
            .register(registry);
        Gauge.builder("tessera.replication.slot.lag", jdbc,
                j -> {
                    List<Long> rows = j.queryForList(
                        "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) " +
                        "FROM pg_replication_slots WHERE slot_name='tessera_outbox_slot'",
                        new MapSqlParameterSource(), Long.class);
                    return rows.isEmpty() ? -1L : rows.get(0);
                })
            .description("Debezium replication slot WAL lag bytes")
            .register(registry);
    }

    public void recordIngest() { ingestRate.increment(); }
    public void recordRuleEvaluation() { rulesEvaluations.increment(); }
    public void recordConflict() { conflictsCount.increment(); }
}
```

Timer for SHACL validation uses `Timer.builder()` at the call site:
```java
// At the SHACL validation call site in GraphServiceImpl or validation layer
Timer.Sample sample = Timer.start(registry);
shaclValidator.validate(shapes, data);
sample.stop(Timer.builder("tessera.shacl.validation.time")
    .description("SHACL shape validation duration")
    .tag("model_id", ctx.modelId().toString())
    .register(registry));
```

---

## Pattern 2: AbstractHealthIndicator Extension

**Analog:** `fabric-projections/src/main/java/dev/tessera/projections/kafka/DebeziumSlotHealthIndicator.java`

The exact template for all new health indicators. Key structural points:
- Extends `AbstractHealthIndicator` from `spring-boot-starter-actuator`
- Annotated `@Component("beanName")` — the bean name becomes the key in `/actuator/health`
- Uses `@ConditionalOnProperty` when the indicator is optional (debezium indicator uses this)
- Injects `NamedParameterJdbcTemplate` for DB probes
- `doHealthCheck(Health.Builder builder)` sets `builder.up()` or `builder.down()` with `.withDetail()`
- Returns DOWN only on actual failure, not on empty results (critical for `AgeGraphHealthIndicator`)

**`AgeGraphHealthIndicator.java` pattern:**
```java
// fabric-app/src/main/java/dev/tessera/app/health/AgeGraphHealthIndicator.java
// Analog: DebeziumSlotHealthIndicator.java — same structure, different query
@Component("ageGraph")
public class AgeGraphHealthIndicator extends AbstractHealthIndicator {

    private final NamedParameterJdbcTemplate jdbc;

    public AgeGraphHealthIndicator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // Query ag_catalog.ag_graph — if AGE is not loaded this throws
        // "schema ag_catalog does not exist", which AbstractHealthIndicator
        // catches and maps to DOWN automatically.
        List<String> graphs = jdbc.queryForList(
            "SELECT name FROM ag_catalog.ag_graph",
            new MapSqlParameterSource(), String.class);
        // Empty set = AGE loaded but no graphs yet = UP (not a failure)
        builder.up().withDetail("graphs_count", graphs.size());
    }
}
```

**`ConnectorHealthIndicator.java` pattern:**
```java
// fabric-app/src/main/java/dev/tessera/app/health/ConnectorHealthIndicator.java
// Analog: DebeziumSlotHealthIndicator.java + ConnectorRegistry pattern
@Component("connectors")
public class ConnectorHealthIndicator extends AbstractHealthIndicator {
    private final ConnectorRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // Read last_outcome from connector_sync_status for each enabled connector
        List<Map<String, Object>> statuses = jdbc.queryForList(
            "SELECT c.id, css.last_outcome, css.last_poll_at " +
            "FROM connectors c LEFT JOIN connector_sync_status css ON c.id = css.connector_id " +
            "WHERE c.enabled = TRUE",
            new MapSqlParameterSource());
        boolean anyFailed = statuses.stream()
            .anyMatch(s -> "FAILED".equals(s.get("last_outcome")));
        Map<String, Object> details = new LinkedHashMap<>();
        statuses.forEach(s -> details.put(s.get("id").toString(), s.get("last_outcome")));
        builder.withDetails(details);
        if (anyFailed) builder.down(); else builder.up();
    }
}
```

---

## Pattern 3: @Scheduled + @SchedulerLock Job

**Analog:** `fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` and `fabric-core/src/main/java/dev/tessera/core/events/internal/PartitionMaintenanceTask.java`

`OutboxPoller` shows the full `@Scheduled` + `@SchedulerLock` + `@Transactional(REQUIRES_NEW)` triad. `PartitionMaintenanceTask` shows a simpler cron-only job (no ShedLock — idempotent DDL). `EventRetentionJob` needs ShedLock because it does DML deletes that must not double-fire.

Key points from `OutboxPoller`:
- `@Scheduled(fixedDelay = 500)` for sub-second polling; use `@Scheduled(cron = "0 0 2 * * *")` for daily jobs
- `@SchedulerLock(name = "tessera-event-retention", lockAtMostFor = "PT55M")` — name must be unique across all jobs; `lockAtMostFor` should be shorter than the cron period to guarantee lock release on crash
- `@Transactional(propagation = Propagation.REQUIRES_NEW)` — own transaction, not inherited from caller
- `LockProviderConfig` in `fabric-core` already wires the ShedLock `LockProvider` bean — no new config needed

**`EventRetentionJob.java` structure:**
```java
// fabric-core/src/main/java/dev/tessera/core/events/EventRetentionJob.java
// Analog: OutboxPoller.java structure, PartitionMaintenanceTask.java cron style
@Component
public class EventRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(EventRetentionJob.class);
    private final NamedParameterJdbcTemplate jdbc;

    public EventRetentionJob(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 2 * * *")   // daily at 02:00
    @SchedulerLock(name = "tessera-event-retention", lockAtMostFor = "PT55M")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweep() {
        List<Map<String, Object>> configs = jdbc.queryForList(
            "SELECT model_id, retention_days, snapshot_boundary " +
            "FROM model_config WHERE retention_days IS NOT NULL",
            new MapSqlParameterSource());
        for (Map<String, Object> cfg : configs) {
            UUID modelId = UUID.fromString(cfg.get("model_id").toString());
            int days = ((Number) cfg.get("retention_days")).intValue();
            // snapshot_boundary guards: never delete snapshot events
            MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", modelId.toString())
                .addValue("days", days);
            int deleted = jdbc.update(
                "DELETE FROM graph_events " +
                "WHERE model_id = :model_id::uuid " +
                "  AND event_time < now() - :days * INTERVAL '1 day' " +
                "  AND event_time >= COALESCE(" +
                "    (SELECT snapshot_boundary FROM model_config WHERE model_id = :model_id::uuid)," +
                "    '-infinity'::timestamptz)",
                p);
            LOG.info("EventRetentionJob: deleted {} events for model {} (retention={}d)", deleted, modelId, days);
        }
    }
}
```

---

## Pattern 4: Admin REST Controller with JWT Tenant Scoping

**Analog:** `fabric-projections/src/main/java/dev/tessera/projections/mcp/audit/McpAuditController.java`

`McpAuditController` is the canonical admin controller pattern: `@RestController`, `@RequestMapping("/admin/...")`, JWT `@AuthenticationPrincipal`, tenant-match guard before any DB operation, `NamedParameterJdbcTemplate` for queries, returns `ResponseEntity<Map<String, Object>>`.

**`EventLifecycleController.java` structure:**
```java
// fabric-core/src/main/java/dev/tessera/core/admin/EventLifecycleController.java
// Analog: McpAuditController.java — same RequestMapping pattern, JWT guard
@RestController
@RequestMapping("/admin/events")
public class EventLifecycleController {

    private final EventSnapshotService snapshotService;
    private final NamedParameterJdbcTemplate jdbc;

    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> takeSnapshot(
            @RequestParam("model_id") UUID modelId,
            @AuthenticationPrincipal Jwt jwt) {
        // Same tenant guard as McpAuditController
        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Tenant mismatch"));
        }
        SnapshotResult result = snapshotService.compact(modelId);
        return ResponseEntity.ok(Map.of(
            "model_id", modelId,
            "snapshot_boundary", result.boundary(),
            "events_written", result.eventsWritten(),
            "events_deleted", result.eventsDeleted()));
    }

    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> getRetentionConfig(
            @RequestParam("model_id") UUID modelId,
            @AuthenticationPrincipal Jwt jwt) {
        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Tenant mismatch"));
        }
        // Query model_config for retention_days and snapshot_boundary
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT retention_days, snapshot_boundary FROM model_config WHERE model_id = :id::uuid",
            new MapSqlParameterSource("id", modelId.toString()));
        return ResponseEntity.ok(rows.isEmpty() ? Map.of("retention_days", null) : rows.get(0));
    }

    private static boolean isTenantMatch(Jwt jwt, UUID modelId) {
        if (jwt == null) return false;
        return modelId.toString().equals(jwt.getClaimAsString("tenant"));
    }
}
```

---

## Pattern 5: Circlead MappingDefinition JSON + Config Class

**Analog:** `fabric-connectors/src/main/java/dev/tessera/connectors/MappingDefinition.java` (record) and `RestPollingConnectorIT.java` (usage pattern)

The `MappingDefinition` record is the single canonical format for expressing connector field mappings. `GenericRestPollerConnector.poll()` reads `mapping.rootPath()`, `mapping.fields()`, `mapping.identityFields()`, and `mapping.sourceUrl()` — these four fields must be populated correctly in the circlead mapping JSON.

The `RestPollingConnectorIT` test shows the exact construction: `new MappingDefinition(sourceEntityType, targetNodeTypeSlug, rootPath, fields, identityFields, sourceUrl, null, null, null, null, null, null)` — trailing nulls are Phase 2.5 unstructured connector fields.

**`circlead-role-mapping.json` shape (classpath resource):**
```json
{
  "sourceEntityType": "role",
  "targetNodeTypeSlug": "role",
  "rootPath": "$.content[*]",
  "sourceUrl": "${tessera.connectors.circlead.base-url}/circlead/workitem/list?type=ROLE&details=true",
  "identityFields": ["circlead_id"],
  "fields": [
    {"source": "$.id",             "target": "circlead_id",   "required": true},
    {"source": "$.title",          "target": "title",         "required": true},
    {"source": "$.abbreviation",   "target": "abbreviation",  "required": false},
    {"source": "$.purpose",        "target": "purpose",       "required": false},
    {"source": "$.status",         "target": "status",        "required": false},
    {"source": "$.type.name",      "target": "_type",         "required": false}
  ]
}
```

**`CircleadConnectorConfig.java` — wiring pattern:**
```java
// fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java
// Analog: ConnectorRegistry.loadRow() — reads mapping from JSON, constructs ConnectorInstance
@Configuration
public class CircleadConnectorConfig {

    @Value("classpath:connectors/circlead-role-mapping.json")
    private Resource roleMappingResource;

    @Bean
    public MappingDefinition circleadRoleMapping(ObjectMapper objectMapper) throws IOException {
        return objectMapper.readValue(roleMappingResource.getInputStream(), MappingDefinition.class);
    }
    // Similar beans for circleadCircleMapping, circleadActivityMapping
}
```

Note: `sourceUrl` placeholders in JSON use `@Value` injection or Spring property resolution through `@ConfigurationProperties`. The `GenericRestPollerConnector` receives the final URL string, not a placeholder.

---

## Pattern 6: WireMock Integration Test for REST Connector

**Analog:** `fabric-connectors/src/test/java/dev/tessera/connectors/rest/RestPollingConnectorIT.java`

The existing `RestPollingConnectorIT` is the exact template for `CircleadConnectorIT`. It uses `@RegisterExtension static WireMockExtension wm` (not `@SpringBootTest`) — tests the connector directly without a Spring context, injecting a mock `GraphRepository`. This is the correct approach for connector integration tests per the ArchUnit rule that connectors do not call `GraphService` directly.

**`CircleadConnectorIT.java` structural template:**
```java
// fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java
// Analog: RestPollingConnectorIT.java — same WireMock setup, same assertion pattern
class CircleadConnectorIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @Test
    void polls_role_list_and_maps_to_tessera_node() {
        wm.stubFor(get(urlPathEqualTo("/circlead/workitem/list"))
            .withQueryParam("type", equalTo("ROLE"))
            .withQueryParam("details", equalTo("true"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"content": [
                      {"id": "role-001", "title": "Product Owner",
                       "abbreviation": "PO", "status": "ACTIVE",
                       "type": {"name": "role"}}
                    ], "status": 200}
                    """)));

        MappingDefinition mapping = new MappingDefinition(
            "role", "role",
            "$.content[*]",
            List.of(
                new FieldMapping("circlead_id", "$.id", null, true),
                new FieldMapping("title", "$.title", null, true),
                new FieldMapping("abbreviation", "$.abbreviation", null, false),
                new FieldMapping("status", "$.status", null, false)),
            List.of("circlead_id"),
            wm.baseUrl() + "/circlead/workitem/list?type=ROLE&details=true",
            null, null, null, null, null, null);

        GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
        PollResult result = connector.poll(Clock.systemUTC(), mapping,
            new ConnectorState(null, null, null, 0L, Map.of("connector_id", "circlead-role")),
            TenantContext.of(UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).properties().get("title")).isEqualTo("Product Owner");
        assertThat(result.candidates().get(0).properties().get("circlead_id")).isEqualTo("role-001");
    }
}
```

---

## Pattern 7: Flyway Migration — Additive ALTER TABLE

**Analog:** `fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql`

V27 creates `model_config` with `hash_chain_enabled BOOLEAN NOT NULL DEFAULT false`. V28 must extend this table additively using `ADD COLUMN IF NOT EXISTS` with nullable defaults so existing rows are unaffected.

Pattern from V27's structure — comments must include phase reference, use `COMMENT ON COLUMN`:
```sql
-- fabric-app/src/main/resources/db/migration/V28__model_config_lifecycle.sql
-- Phase 5 / OPS-03 / OPS-04: event-log lifecycle columns for model_config
ALTER TABLE model_config
    ADD COLUMN IF NOT EXISTS retention_days      INT         NULL,
    ADD COLUMN IF NOT EXISTS snapshot_boundary   TIMESTAMPTZ NULL;

COMMENT ON COLUMN model_config.retention_days IS
    'OPS-04: days to retain events; NULL means retain forever';
COMMENT ON COLUMN model_config.snapshot_boundary IS
    'OPS-03: earliest event_time still available via temporal replay; events below this were compacted';
```

V28 must be copied to all 4 migration directories that receive migration copies:
- `fabric-app/src/main/resources/db/migration/`
- `fabric-core/src/test/resources/db/migration/`
- `fabric-projections/src/test/resources/db/migration/`
- `fabric-rules/src/test/resources/db/migration/`

---

## Pattern 8: application.yml Extension for Prometheus

**Analog:** `fabric-app/src/main/resources/application.yml` lines 74-82

The current `management.endpoints.web.exposure.include` is `health,info`. This must be extended to include `prometheus`. The Prometheus endpoint access should be `unrestricted` for the scraper — Prometheus cannot send a JWT.

```yaml
# fabric-app/src/main/resources/application.yml — modify management section
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when-authorized
    prometheus:
      access: unrestricted   # Allow Prometheus scraper without auth token
```

---

## Pattern 9: fabric-app/pom.xml Dependency Addition

**Analog:** `fabric-app/pom.xml` existing dependency block — `spring-boot-starter-actuator` already present at line 47. New dependencies go into the same `<dependencies>` block, no version needed (BOM-managed):

```xml
<!-- fabric-app/pom.xml — add after spring-boot-starter-actuator -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

---

## Pattern 10: DR Drill Script Extension

**Analog:** `scripts/dump_restore_rehearsal.sh`

The existing `dump_restore_rehearsal.sh` is a complete runbook for the AGE-aware dump/restore cycle. `dr_drill.sh` extends this pattern with three additional sections:

1. **Application data dump:** Standard `pg_dump -Fc -n public` already in the rehearsal at step 5 (line 158-159) — covers Flyway-managed tables (`model_config`, `connectors`, `graph_events` relational layer, etc.)
2. **Flyway verify step:** After restore, run `./mvnw flyway:validate` against the restored DB to confirm migration checksums match
3. **Circlead mapping smoke test:** After Flyway validate, call `GET /admin/events/retention?model_id=<test-uuid>` and `GET /actuator/health` against a briefly-started Tessera instance

Key patterns to preserve from the existing script:
- `set -euo pipefail` at top
- Image digest sourced from `docker-compose.yml` via `grep -oE 'apache/age@sha256:[a-f0-9]{64}'` (line 61)
- `WORKDIR=$(mktemp -d)` + `trap cleanup EXIT` (lines 82-87)
- Container start with TCP readiness polling (lines 89-111)
- Pre/post query diff with hard-fail `exit 1` on divergence (lines 236-242)

New `dr_drill.sh` adds a section 8 after the restore succeeds:
```bash
# ---------------------------------------------------------------------------
# 8. FLYWAY VALIDATE: verify migration checksums match the restored schema
# ---------------------------------------------------------------------------
echo "==> [8/9] Flyway validate against restored DB"
./mvnw -B -ntp -pl fabric-app flyway:validate \
  -Dflyway.url="jdbc:postgresql://localhost:${DST_PORT}/tessera" \
  -Dflyway.user=tessera \
  -Dflyway.password=tessera
echo "PASS: Flyway validate — all migration checksums match"

# ---------------------------------------------------------------------------
# 9. SMOKE TEST: start Tessera briefly, hit circlead-mapping endpoints
# ---------------------------------------------------------------------------
echo "==> [9/9] Tessera smoke test against restored DB"
# Start Tessera pointing at DST container
TESSERA_PID=...
sleep 5
STATUS=$(curl -sf http://localhost:8080/actuator/health | jq -r '.status')
[[ "$STATUS" == "UP" ]] || { echo "FAIL: Tessera health DOWN after restore" >&2; exit 1; }
echo "PASS: Tessera smoke test"
```

---

## Pattern 11: EventSnapshotService — Three-Transaction Compaction

**Analog:** `fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` (append pattern) + `McpAuditController.java` (admin endpoint pattern)

`EventLog.append()` runs inside the caller's `@Transactional` boundary. `EventSnapshotService` must manage its own transaction boundaries explicitly to avoid holding locks across the three phases. Use `@Transactional(propagation = REQUIRES_NEW)` per phase, or use `TransactionTemplate` for finer control.

```java
// fabric-core/src/main/java/dev/tessera/core/events/snapshot/EventSnapshotService.java
@Service
public class EventSnapshotService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;  // injected

    public SnapshotResult compact(UUID modelId) {
        // Phase 1: read current entity states (read-only TX)
        List<Map<String, Object>> entities = readCurrentStates(modelId);

        // Phase 2: write snapshot events + record boundary (short write TX)
        Instant boundary = writeSnapshotEvents(modelId, entities);

        // Phase 3: delete pre-boundary events (separate delete TX, idempotent)
        int deleted = deletePreBoundaryEvents(modelId, boundary);

        return new SnapshotResult(boundary, entities.size(), deleted);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    private List<Map<String, Object>> readCurrentStates(UUID modelId) {
        return jdbc.queryForList(
            "SELECT DISTINCT ON (node_uuid) node_uuid, type_slug, payload " +
            "FROM graph_events WHERE model_id = :mid::uuid " +
            "ORDER BY node_uuid, sequence_nr DESC",
            new MapSqlParameterSource("mid", modelId.toString()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private Instant writeSnapshotEvents(UUID modelId, List<Map<String, Object>> entities) {
        Instant boundary = Instant.now();
        // INSERT one SNAPSHOT event per entity into graph_events
        // UPDATE model_config SET snapshot_boundary = :boundary WHERE model_id = :mid
        return boundary;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private int deletePreBoundaryEvents(UUID modelId, Instant boundary) {
        return jdbc.update(
            "DELETE FROM graph_events " +
            "WHERE model_id = :mid::uuid AND event_time < :boundary AND event_type != 'SNAPSHOT'",
            new MapSqlParameterSource("mid", modelId.toString()).addValue("boundary", boundary));
    }
}
```

---

## Pattern 12: CI Pipeline Extension

**Analog:** `.github/workflows/ci.yml`

The existing CI pipeline has two jobs: `verify` (Maven verify + JaCoCo) and `benchmark-100k` (JMH + regression check). Phase 5 extends this — the `verify` job already runs all tests via `./mvnw -B -ntp verify` which includes Surefire + Failsafe, so all Phase 5 unit and integration tests run automatically once the test files exist.

For the DR drill, a new job `dr-drill` should run conditionally (on push to main only, not on PRs, to avoid Docker-in-Docker issues):

```yaml
# .github/workflows/ci.yml — add after benchmark-100k job
  dr-drill:
    name: DR drill rehearsal
    runs-on: ubuntu-latest
    needs: verify
    if: github.event_name == 'push'   # PRs skip — Docker layer
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '21', cache: maven }
      - name: Run DR drill
        run: scripts/dr_drill.sh
```

---

## Summary Lookup Table

| New File | Primary Analog | Key Methods / APIs Reused |
|----------|---------------|---------------------------|
| `TesseraMetrics.java` | `WriteRateCircuitBreaker.java` | `Counter.builder()`, `Gauge.builder()`, `MeterRegistry` constructor injection |
| `AgeGraphHealthIndicator.java` | `DebeziumSlotHealthIndicator.java` | `AbstractHealthIndicator`, `doHealthCheck()`, `Health.Builder.up()/.down().withDetail()` |
| `ConnectorHealthIndicator.java` | `DebeziumSlotHealthIndicator.java` + `ConnectorStatusController.java` | `AbstractHealthIndicator`, connector_sync_status query |
| `EventRetentionJob.java` | `OutboxPoller.java` + `PartitionMaintenanceTask.java` | `@Scheduled(cron=)`, `@SchedulerLock`, `@Transactional(REQUIRES_NEW)`, `NamedParameterJdbcTemplate` |
| `EventSnapshotService.java` | `EventLog.java` | `NamedParameterJdbcTemplate`, `Propagation.REQUIRES_NEW`, `MapSqlParameterSource` |
| `EventLifecycleController.java` | `McpAuditController.java` | `@RestController`, `@AuthenticationPrincipal Jwt`, tenant-match guard, `ResponseEntity<Map<>>` |
| `CircleadConnectorConfig.java` | `ConnectorRegistry.loadRow()` | `MappingDefinition`, `ObjectMapper.readValue()`, classpath resource loading |
| `CircleadConnectorIT.java` | `RestPollingConnectorIT.java` | `WireMockExtension`, `GenericRestPollerConnector`, `MappingDefinition` constructor, `PollResult` assertions |
| `V28__model_config_lifecycle.sql` | `V27__tenant_hash_chain_config.sql` | `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, `COMMENT ON COLUMN` |
| `application.yml` (metrics block) | existing `management:` block | `management.endpoints.web.exposure.include`, `prometheus.access: unrestricted` |
| `dr_drill.sh` | `dump_restore_rehearsal.sh` | `set -euo pipefail`, image digest extraction, `start_container()`, `trap cleanup EXIT` |
