---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W1
type: execute
wave: 2
depends_on: [W0]
files_modified:
  - fabric-app/src/main/resources/db/migration/V10__node_seq_indexes.sql
  - fabric-app/src/main/resources/db/migration/V11__connector_dlq.sql
  - fabric-app/src/main/resources/db/migration/V12__schema_rest_exposure_and_encryption_flags.sql
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
  - fabric-core/src/main/java/dev/tessera/core/schema/SchemaDescriptorCache.java
  - fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java
  - fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java
  - fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java
  - fabric-core/src/main/java/dev/tessera/core/security/EncryptionStartupGuard.java
  - fabric-core/src/test/java/dev/tessera/core/graph/NodeSequencePropertyIT.java
  - fabric-core/src/test/java/dev/tessera/core/graph/ConnectorDlqSameTxIT.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaRestExposureColumnsIT.java
  - fabric-core/src/test/java/dev/tessera/core/security/EncryptionStartupGuardIT.java
autonomous: true
requirements:
  - REST-04
  - REST-02
  - CONN-04
  - SEC-06

must_haves:
  truths:
    - "Every node created or updated through GraphService.apply carries a monotonic BIGINT _seq property allocated from the same per-tenant sequence as the event row"
    - "Schema Registry node-type descriptor exposes rest_read_enabled / rest_write_enabled (default false) and property descriptors expose property_encrypted / property_encrypted_alg"
    - "A connector-submitted mutation that fails validation or rule rejection causes a connector_dlq row to be written in the SAME transaction that rolls back the graph mutation"
    - "At startup, if any schema property in any tenant is marked property_encrypted=true AND tessera.security.field-encryption.enabled=false, the application refuses to boot with a clear IllegalStateException"
  artifacts:
    - path: fabric-app/src/main/resources/db/migration/V10__node_seq_indexes.sql
      provides: "Composite (model_id, _seq) indexes on AGE label tables supporting cursor pagination"
    - path: fabric-app/src/main/resources/db/migration/V11__connector_dlq.sql
      provides: "connector_dlq table (payload, rejection_reason, rule_id, connector_id, model_id, created_at)"
    - path: fabric-app/src/main/resources/db/migration/V12__schema_rest_exposure_and_encryption_flags.sql
      provides: "rest_read_enabled / rest_write_enabled on schema_node_types; property_encrypted / property_encrypted_alg on schema_properties"
    - path: fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java
      provides: "Same-TX DLQ insertion on connector-origin mutation rejection"
  key_links:
    - from: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      to: fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java
      via: "try/catch inside @Transactional — DLQ row written before rollback on connector-origin rejection"
      pattern: "ConnectorDlqWriter.record"
    - from: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
      to: "AGE node properties"
      via: "apply(CREATE|UPDATE) writes _seq property from SequenceAllocator.nextSequenceNr(ctx) on every mutation"
      pattern: "_seq"
---

<objective>
Build the graph-and-schema substrate that REST projection (Wave 2) and connector framework (Wave 3) consume. Four concerns, one wave:

1. **Node `_seq` denormalization** (CONTEXT Decision 12 / RESEARCH Q7 assumption A3): every node carries a monotonic BIGINT `_seq` property from the per-tenant SEQUENCE allocator Phase 1 built, with composite `(model_id, _seq)` indexes on the AGE label tables. This is the stable sort key Wave 2 cursor pagination requires.
2. **Schema Registry exposure + encryption flags** (CONTEXT Decisions 2, 5): add `rest_read_enabled` / `rest_write_enabled` / `property_encrypted` / `property_encrypted_alg` columns to schema node-type / property descriptors. Defaults false. Caffeine descriptor cache carries the new fields. No REST controllers yet.
3. **Connector DLQ substrate** (CONTEXT Decision 14): Flyway V11 creates `connector_dlq`, `GraphServiceImpl.apply` grows a try/catch that writes a DLQ row in the SAME TX on connector-origin validation/rule rejection before the graph mutation rollback. No admin endpoint yet — that's Wave 3.
4. **Field-encryption startup guard** (CONTEXT Decision 2 / SEC-06): a Spring `ApplicationRunner` scans the Schema Registry and fails fast if any tenant marks a property `property_encrypted=true` while the feature flag is off. Pure fail-closed guard, no encryption machinery.

Purpose: lock down the data-model prerequisites so Wave 2 (REST) and Wave 3 (connectors) can be written against a stable schema.

Output: Flyway V10+V11+V12, production changes to `GraphServiceImpl`/`GraphSession`/schema cache, four new ITs, no REST or connector code.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-CONTEXT.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-RESEARCH.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W0-PLAN.md
@fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java
@fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
@fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
@fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
@fabric-core/src/main/java/dev/tessera/core/schema/SchemaDescriptorCache.java
@fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql
</context>

<interfaces>
From Phase 1 V8 (partial connector_dlq scaffold — verify actual columns before V11):
```sql
-- connector_dlq table already exists from Phase 1 W3 circuit breaker work;
-- V11's job is to ADD columns if missing (rejection_reason, rule_id, tenant, connector_id, payload JSONB)
-- and add an INDEX on (model_id, connector_id, created_at DESC) for admin list queries.
-- READ V8 first and decide ALTER vs CREATE.
```

From Phase 1 `SequenceAllocator.nextSequenceNr(TenantContext)`:
```java
public long nextSequenceNr(TenantContext ctx);  // returns BIGINT from per-tenant SEQUENCE
```
Wave 1 REUSES this; does NOT introduce a separate node-sequence. The same value lands on the graph_events row AND as the `_seq` node property.

From Phase 1 `NodeTypeDescriptor` / `PropertyDescriptor` (Schema Registry):
```java
public record NodeTypeDescriptor(String slug, int version, List<PropertyDescriptor> properties, ...) {}
public record PropertyDescriptor(String slug, String datatype, boolean required, ...) {}
```
Wave 1 ADDs: `boolean restReadEnabled, boolean restWriteEnabled` on NodeTypeDescriptor; `boolean encrypted, String encryptedAlg` on PropertyDescriptor. Caffeine cache invalidation already wired in Phase 1 W2.
</interfaces>

<tasks>

<task type="auto" tdd="true">
  <name>Task 02-W1-01: V10 node _seq denormalization + V12 schema exposure/encryption columns</name>
  <files>
    fabric-app/src/main/resources/db/migration/V10__node_seq_indexes.sql,
    fabric-app/src/main/resources/db/migration/V12__schema_rest_exposure_and_encryption_flags.sql,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java,
    fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java,
    fabric-core/src/main/java/dev/tessera/core/schema/SchemaDescriptorCache.java,
    fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java,
    fabric-core/src/test/java/dev/tessera/core/graph/NodeSequencePropertyIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaRestExposureColumnsIT.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-CONTEXT.md Decisions 2, 5, 12
    - .planning/phases/02-.../02-RESEARCH.md §Q7 (cursor pagination, `_seq` requirement)
    - fabric-app/src/main/resources/db/migration/V4__schema_registry.sql
    - fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java (Phase 1 apply path)
    - fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java
  </read_first>
  <behavior>
    - Every `GraphServiceImpl.apply(CREATE|UPDATE|TOMBSTONE)` call writes a `_seq BIGINT` property on the resulting node, sourced from `SequenceAllocator.nextSequenceNr(ctx)` — the SAME allocation used for the `graph_events.sequence_nr` row (one allocation per mutation, used twice).
    - `_seq` is monotonic per tenant: NodeSequencePropertyIT seeds N mutations, reads nodes back via `GraphRepository`, sorts by uuid, asserts `_seq` values are strictly increasing across creation order.
    - Flyway V10 creates a composite index on `(model_id, _seq)` for every AGE label table that already exists. Because AGE label tables are created dynamically on type declaration, V10 ALSO adds a trigger or a one-shot post-`create_graph` hook helper invoked by `SchemaRegistry.declareNodeType` so future label tables get the index automatically. If dynamic trigger is too clever, V10 can be a one-shot catch-up plus a Java-side call in `SchemaRegistry.declareNodeType` that runs `CREATE INDEX IF NOT EXISTS ...` — either path is acceptable; document the choice in the SUMMARY.
    - Flyway V12 adds `rest_read_enabled BOOLEAN NOT NULL DEFAULT FALSE` and `rest_write_enabled BOOLEAN NOT NULL DEFAULT FALSE` to `schema_node_types`, and `property_encrypted BOOLEAN NOT NULL DEFAULT FALSE` + `property_encrypted_alg TEXT NULL` to `schema_properties`.
    - `NodeTypeDescriptor` and `PropertyDescriptor` records gain the new fields. `SchemaDescriptorCache` + `SchemaRegistry` JDBC loaders read the new columns. Existing Phase 1 `SchemaToShaclIT` must stay green.
    - `SchemaRestExposureColumnsIT`: declare a type with `rest_read_enabled=false`, load via `SchemaRegistry`, assert descriptor field is false; flip via an `UPDATE schema_node_types` JDBC call; invalidate cache; load again; assert true. No REST controller involved.
  </behavior>
  <action>
    1. `V10__node_seq_indexes.sql`: comment block + catch-up loop over existing AGE label tables for the main graph, using a PL/pgSQL DO block that iterates `information_schema.tables` under the `tessera_main` schema and emits `CREATE INDEX IF NOT EXISTS idx_<table>_model_seq ON tessera_main."<table>"(model_id, (properties->>'_seq')::bigint)` — AGE stores properties as agtype, so the index expression must match how `_seq` is stored. VERIFY the expression against Phase 1's existing label table layout (grep for any existing AGE indexes). If AGE's agtype operator precludes a direct expression index, fall back to a functional index that casts via the `agtype_access_operator` and document it. This is the single trickiest SQL in the wave — take the time to verify with a spike-in-the-spike inside the Testcontainer.
    2. `V12__schema_rest_exposure_and_encryption_flags.sql`: `ALTER TABLE schema_node_types ADD COLUMN rest_read_enabled BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN rest_write_enabled BOOLEAN NOT NULL DEFAULT FALSE; ALTER TABLE schema_properties ADD COLUMN property_encrypted BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN property_encrypted_alg TEXT NULL;`
    3. `GraphSession.apply`: after stamping system properties (Phase 1 W1), before building the Cypher, allocate `long seq = sequenceAllocator.nextSequenceNr(ctx)` and add `_seq` to the agtype payload. Plumb `SequenceAllocator` into `GraphSession` via the constructor (update Spring config). IMPORTANT: the same `seq` value must be threaded back up to `GraphServiceImpl.apply` so `EventLog.append` uses the SAME allocation for its `sequence_nr` row. The cleanest shape is `GraphSession.apply` returning a `NodeState` that already carries `_seq`, and `GraphServiceImpl` passing `state._seq()` into `EventLog.append` in place of a fresh allocation. Refactor carefully — Phase 1's `EventLog` currently allocates inside `append`. Move the allocation UP into `GraphServiceImpl.apply` so both consumers share one value.
    4. `NodeTypeDescriptor` + `PropertyDescriptor`: add new record components with sensible defaults. Update all call sites (grep the tree). `SchemaRegistry` JDBC row mappers read the new columns. `SchemaDescriptorCache` is already keyed by `(model_id, version)` — no cache-key change.
    5. `NodeSequencePropertyIT.java`: create 50 Person nodes, read them back via `GraphRepository.queryAll`, assert every node has `_seq` and values strictly increase with creation order. Cross-tenant variant: create 50 nodes for tenant A interleaved with 50 for tenant B, assert each tenant's _seq sequence is independently monotonic.
    6. `SchemaRestExposureColumnsIT.java`: Testcontainers AGE + Flyway. Declare a node type via `SchemaRegistry`, assert `restReadEnabled==false` on the loaded descriptor; JDBC `UPDATE schema_node_types SET rest_read_enabled=true WHERE slug=?`; invalidate the schema cache; reload descriptor; assert `restReadEnabled==true`. Also assert property encrypted flag round-trips.
    7. TDD order per task: write NodeSequencePropertyIT first (RED — no _seq property exists), then implement `GraphSession` changes (GREEN); write SchemaRestExposureColumnsIT first (RED — descriptor has no field), then add fields + row mapping (GREEN).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='NodeSequencePropertyIT,SchemaRestExposureColumnsIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-core -Dit.test=NodeSequencePropertyIT verify` exits 0
    - `./mvnw -pl fabric-core -Dit.test=SchemaRestExposureColumnsIT verify` exits 0
    - Phase 1 ITs remain green: `./mvnw -pl fabric-core -Dit.test='NodeLifecycleIT,GraphServiceApplyIT,SchemaToShaclIT' verify`
    - `grep -q "_seq" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` succeeds
    - `grep -q "restReadEnabled" fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java` succeeds
    - `grep -q "rest_read_enabled" fabric-app/src/main/resources/db/migration/V12__schema_rest_exposure_and_encryption_flags.sql` succeeds
    - Phase 1 WritePipelineBench p95 has NOT regressed beyond the <11ms Phase 1 gate (soft check — warn, not fail)
  </acceptance_criteria>
  <done>
    `_seq` property landed on all nodes; schema descriptors carry exposure + encryption flags; composite index in place for Wave 2 cursor queries.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 02-W1-02: V11 connector_dlq + same-TX DLQ write path + SEC-06 startup guard</name>
  <files>
    fabric-app/src/main/resources/db/migration/V11__connector_dlq.sql,
    fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/main/java/dev/tessera/core/security/EncryptionStartupGuard.java,
    fabric-core/src/test/java/dev/tessera/core/graph/ConnectorDlqSameTxIT.java,
    fabric-core/src/test/java/dev/tessera/core/security/EncryptionStartupGuardIT.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-CONTEXT.md Decisions 2, 14
    - fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql (Phase 1 DLQ scaffold — verify existing columns before V11)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (post-W0, with currentSourceSystem threaded)
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java (origin_connector_id already present per Phase 1 EVENT-03)
  </read_first>
  <behavior>
    - Submitting a `GraphMutation` whose `originConnectorId != null` (i.e. came from a connector) that fails SHACL validation OR rule rejection causes a row to appear in `connector_dlq` with: `payload JSONB` = the full candidate mutation, `rejection_reason TEXT` = short machine code, `rejection_detail TEXT` = human message, `rule_id UUID NULL` = rule that rejected (if applicable), `connector_id TEXT`, `model_id`, `created_at`. The DLQ row is written in the SAME transaction that then rolls back the graph mutation — BUT, because the DLQ write must survive the rollback, use `Propagation.REQUIRES_NEW` for the DLQ writer (a nested TX on a new connection from the pool). The graph TX rolls back; the DLQ TX commits. This is a deliberate split and matches the standard "audit log on failure" pattern.
    - Wait — re-reading CONTEXT Decision 14: *"writes a DLQ row in the same Postgres transaction before rolling back the graph mutation"*. That phrasing literally means: DLQ INSERT happens inside the outer TX, THEN the outer TX rolls back, erasing the DLQ row too. That cannot be what the user meant — a rolled-back DLQ row is invisible to operators. **Interpret as:** DLQ write happens as a separate, nested `Propagation.REQUIRES_NEW` TX so it commits while the outer graph-mutation TX rolls back. Document this interpretation loudly in the SUMMARY and surface to the orchestrator if the interpretation is wrong. The production pattern matches Spring's standard "audit on exception" recipe.
    - ConnectorDlqSameTxIT: seed a SHACL shape that rejects a specific property value; submit a `CandidateMutation` with `originConnectorId="conn-001"` violating the shape; assert `RuleRejectException` (or `ValidationException`) thrown, assert zero graph_events rows AND zero nodes AND exactly one `connector_dlq` row with `connector_id='conn-001'` and the expected payload.
    - Mutations with `originConnectorId == null` (direct admin writes) on failure must NOT write a DLQ row — DLQ is exclusively for connector-origin attempts.
    - `EncryptionStartupGuard`: Spring `ApplicationRunner` (or `@PostConstruct` on a `@Component`) that, at boot, queries `SELECT 1 FROM schema_properties WHERE property_encrypted=true LIMIT 1` across ALL tenants. If any row AND `tessera.security.field-encryption.enabled=false` → throws `IllegalStateException("Field-level encryption is disabled but schema_properties contains property_encrypted=true rows. Enable the flag or remove the marker. See CONTEXT.md Decision 2.")` — context refuses to start.
    - `EncryptionStartupGuardIT`: two test flavours — (a) flag off + no encrypted rows → context starts cleanly; (b) flag off + one encrypted row → context fails to start with the expected message; (c) flag ON + encrypted row → context starts cleanly. Use `ApplicationContextRunner` or `SpringBootTest` with `@ExpectedException`.
  </behavior>
  <action>
    1. `V11__connector_dlq.sql`: first READ V8 and see what already exists. Assume V8 created a stub table for Phase 1 circuit breaker work; V11 ADDs any missing columns and an index. If V8's table is sufficient, V11 can be a tiny ALTER adding a composite index `(model_id, connector_id, created_at DESC)`. If V8 doesn't exist, create the full table here. **Concrete schema:** `connector_dlq(id UUID PK DEFAULT gen_random_uuid(), model_id UUID NOT NULL, connector_id TEXT NOT NULL, payload JSONB NOT NULL, rejection_reason TEXT NOT NULL, rejection_detail TEXT NULL, rule_id UUID NULL, origin_change_id TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())`. Add `CREATE INDEX idx_connector_dlq_model_connector_time ON connector_dlq(model_id, connector_id, created_at DESC);`.
    2. `ConnectorDlqWriter.java` package `dev.tessera.core.connector.dlq`: `@Component`, constructor takes `NamedParameterJdbcTemplate`. One method `@Transactional(propagation = Propagation.REQUIRES_NEW) public UUID record(TenantContext ctx, GraphMutation m, String rejectionReason, String rejectionDetail, UUID ruleId)`. INSERT one row, return the UUID. REQUIRES_NEW ensures the DLQ write survives the outer graph-TX rollback.
    3. `GraphServiceImpl.apply` — extend the existing rule/SHACL failure handling: wrap the `ruleEngine.run` + `shaclValidator.validate` + `graphSession.apply` chain in a try/catch. On `RuleRejectException` or `ShaclValidationException`, IF `m.originConnectorId() != null` then `connectorDlqWriter.record(...)` BEFORE re-throwing. The REQUIRES_NEW propagation on the writer ensures the DLQ insert commits even though the current method is about to roll back. Guard: if `originConnectorId == null`, skip DLQ entirely — admin writes do not generate DLQ rows.
    4. `EncryptionStartupGuard.java` package `dev.tessera.core.security`: `@Component` with `@ConditionalOnProperty(name="tessera.security.field-encryption.enabled", havingValue="false", matchIfMissing=true)` and an `@PostConstruct` method that runs the SELECT described above. Throw `IllegalStateException` if a hit is found. Use a plain JDBC query — no Schema Registry abstraction needed; we're reading the raw table.
    5. Add config property `tessera.security.field-encryption.enabled: false` to `fabric-app/src/main/resources/application.yml` (and equivalent `application-test.yml` values — off by default per CONTEXT Decision 2).
    6. `ConnectorDlqSameTxIT.java`: Testcontainers AGE + Flyway V1..V12. Wire a minimal Spring slice with `GraphServiceImpl`, `ConnectorDlqWriter`, `RuleEngine` (with a test rule that rejects a specific value), `ShaclValidator`. Submit a rejecting mutation from `originConnectorId="conn-001"`. Assert: exception thrown AND 0 graph_events rows AND 0 AGE nodes AND 1 connector_dlq row with the expected fields. Then submit the SAME rejecting payload with `originConnectorId=null` → assert exception thrown AND 0 connector_dlq rows (no new DLQ entry).
    7. `EncryptionStartupGuardIT.java`: three scenarios per behaviour above, using `@SpringBootTest` with property overrides and manual schema_properties seeding via Flyway + direct INSERT in `@BeforeAll`.
    8. TDD order: DLQ IT first (RED — no DlqWriter), then implement; Startup guard IT first (RED — no guard bean), then implement.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='ConnectorDlqSameTxIT,EncryptionStartupGuardIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-core -Dit.test=ConnectorDlqSameTxIT verify` exits 0
    - `./mvnw -pl fabric-core -Dit.test=EncryptionStartupGuardIT verify` exits 0
    - `grep -q "REQUIRES_NEW" fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java` succeeds
    - `grep -q "property_encrypted" fabric-core/src/main/java/dev/tessera/core/security/EncryptionStartupGuard.java` succeeds
    - `grep -q "tessera.security.field-encryption.enabled" fabric-app/src/main/resources/application.yml` succeeds
    - `./mvnw -B verify` green reactor-wide
  </acceptance_criteria>
  <done>
    Connector-origin failures drop into `connector_dlq` in a committed REQUIRES_NEW TX while the graph write rolls back. SEC-06 startup guard refuses to boot if an encrypted property is marked while the flag is off.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Connector → GraphService.apply | Connector-origin payloads cross into the write funnel and can be rejected |
| Startup → Schema Registry | Encrypted-marker rows block boot when crypto machinery is disabled |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-02-W1-01 | Repudiation | Connector failures invisible to operator | mitigate | REQUIRES_NEW DLQ write survives outer rollback; IT asserts row count after failure |
| T-02-W1-02 | Information Disclosure | Silent "ships-with-encryption-disabled-but-schema-declares-it" footgun | mitigate | Fail-closed startup guard; three-flavour IT |
| T-02-W1-03 | Tampering | _seq gaps enabling cursor pagination skew | mitigate | Same SEQUENCE allocation for node property and event row; monotonicity IT |
| T-02-W1-04 | DoS | Unindexed AGE label tables slow cursor pagination | mitigate | V10 composite index; Wave 2 adds `CursorPaginationBench` gate |
</threat_model>

<verification>
`./mvnw -B verify` green. Four new ITs green. Phase 1 suites regression-free. No REST or connector code yet.
</verification>

<success_criteria>
- V10, V11, V12 migrations applied cleanly
- `_seq` property present on every node after `GraphService.apply`; monotonic per tenant
- Schema descriptors expose rest_read_enabled / rest_write_enabled / property_encrypted / property_encrypted_alg
- `connector_dlq` row written same-TX-REQUIRES_NEW on connector-origin rejection
- Encryption startup guard fails boot when a marker exists with the flag off
- WritePipelineBench p95 does not regress beyond Phase 1's 11ms gate
</success_criteria>

<output>
After completion, create `.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W1-SUMMARY.md`.
</output>
