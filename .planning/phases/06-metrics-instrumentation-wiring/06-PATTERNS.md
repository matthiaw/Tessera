
# Phase 6: Metrics Instrumentation Wiring - Patterns

**Generated:** 2026-04-17
**Source:** 06-RESEARCH.md + direct codebase inspection

---

## Files to Create or Modify

### Overview

| # | File | Action | Role |
|---|------|--------|------|
| 1 | `fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java` | CREATE | SPI interface (port) |
| 2 | `fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java` | CREATE | Port adapter (@Component) |
| 3 | `fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java` | MODIFY | Timer wiring |
| 4 | `fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java` | MODIFY | Counter wiring |
| 5 | `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java` | MODIFY | Ingest counter wiring |
| 6 | `fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsAdapterTest.java` | CREATE | Adapter unit test |
| 7 | `fabric-core/src/test/java/dev/tessera/core/validation/ShaclValidatorMetricsTest.java` | CREATE | Timer recording unit test |

---

## File 1: `fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java`

**Role:** SPI interface (port). Defined in `fabric-core` so that `fabric-rules`, `fabric-connectors`, and `fabric-core` itself can all inject it without importing `fabric-app`.

**Closest existing analog:** `fabric-core/src/main/java/dev/tessera/core/rules/RuleEnginePort.java`

Key structural excerpts from `RuleEnginePort` that govern the pattern:

```java
// RuleEnginePort.java — the canonical pattern to replicate
package dev.tessera.core.rules;

// 1. Package is dev.tessera.core.{concern} — new package: dev.tessera.core.metrics
// 2. Plain interface, no @Component, no Spring imports
// 3. Javadoc explains: fabric-core defines the port; fabric-app provides the implementation;
//    null is legal for legacy test harnesses
// 4. NO Micrometer imports — the port must be Micrometer-free
//    (fabric-core has micrometer-observation only, not micrometer-core)

public interface RuleEnginePort {
    Outcome run(
            TenantContext tenantContext,
            NodeTypeDescriptor descriptor,
            Map<String, Object> currentProperties,
            Map<String, String> currentSourceSystem,
            GraphMutation mutation);

    default void onCommitted(
            TenantContext tenantContext, UUID nodeUuid, String originConnectorId, String originChangeId) {}
    ...
}
```

`CircuitBreakerPort.java` reinforces the same pattern with a one-method interface:

```java
// CircuitBreakerPort.java excerpt
package dev.tessera.core.circuit;

public interface CircuitBreakerPort {
    void recordAndCheck(TenantContext ctx, String connectorId) throws CircuitBreakerTrippedException;
}
```

**Data flow:**
- Called by: `ShaclValidator.validate()`, `RuleEngine.run(RuleContext)`, `ConnectorRunner.runOnce()`
- Calls: nothing (interface)
- Implemented by: `TesseraMetricsAdapter` in `fabric-app`
- Spring wires: `TesseraMetricsAdapter` as the live bean; `null` in pre-Phase-6 test fixtures

**File to create:**

```java
package dev.tessera.core.metrics;

/**
 * OPS-01 — Fabric-core SPI for Micrometer metric emission. Fabric-core
 * defines the port; {@code fabric-app} provides the
 * {@code TesseraMetricsAdapter} implementation.
 *
 * <p>Intentionally free of Micrometer imports: fabric-core carries only
 * {@code micrometer-observation} (not {@code micrometer-core}) on its
 * compile path. All Micrometer types are resolved in the adapter.
 *
 * <p>Callers inject via {@code @Autowired(required = false)} and guard every
 * call site with {@code if (metricsPort != null)} so legacy test fixtures
 * that pre-date Phase 6 continue to compile without a MetricsPort bean.
 */
public interface MetricsPort {

    /** Increment {@code tessera.ingest.rate} counter by 1. */
    void recordIngest();

    /** Increment {@code tessera.rules.evaluations} counter by 1. */
    void recordRuleEvaluation();

    /** Increment {@code tessera.conflicts.count} counter by 1. */
    void recordConflict();

    /**
     * Record a SHACL validation duration.
     *
     * @param nanos elapsed nanoseconds measured by the caller with
     *              {@code System.nanoTime()} around the Jena validate call
     */
    void recordShaclValidationNanos(long nanos);
}
```

---

## File 2: `fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java`

**Role:** Port adapter. Implements `MetricsPort` (defined in `fabric-core`) and delegates to `TesseraMetrics` (also in `fabric-app`). Registered as a Spring `@Component` so Spring Boot auto-wires it as the `MetricsPort` bean.

**Closest existing analog:** `TesseraMetrics.java` itself (same package; `@Component`, constructor-injected, delegates to Micrometer types).

Key excerpts from `TesseraMetrics.java` that show the adapter's target API:

```java
// TesseraMetrics.java — the methods the adapter delegates to
@Component
public class TesseraMetrics {

    public void recordIngest()         { ingestRateCounter.increment(); }
    public void recordRuleEvaluation() { ruleEvaluationsCounter.increment(); }
    public void recordConflict()       { conflictsCounter.increment(); }

    // shaclTimer() returns the registered Timer instance
    public Timer shaclTimer() { return shaclValidationTimer; }
}
```

The `recordShaclValidationNanos(long)` delegation uses `Timer.record(long, TimeUnit)`, a standard Micrometer API available in `fabric-app` where `micrometer-core` is a direct dependency.

**Data flow:**
- Called by: Spring `@Autowired` injection into `ShaclValidator`, `RuleEngine`, `ConnectorRunner`
- Calls: `TesseraMetrics.recordIngest()`, `recordRuleEvaluation()`, `recordConflict()`, `shaclTimer().record(nanos, NANOSECONDS)`
- Depends on: `MetricsPort` (fabric-core), `TesseraMetrics` (fabric-app — same module, no ArchUnit issue)

**File to create:**

```java
package dev.tessera.app.metrics;

import dev.tessera.core.metrics.MetricsPort;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * OPS-01 — Adapter that implements the {@link MetricsPort} SPI defined in
 * fabric-core by delegating to the already-registered {@link TesseraMetrics}
 * bean. Spring Boot auto-detects this {@code @Component} and wires it as the
 * {@code MetricsPort} bean in production; test harnesses that do not load the
 * full application context receive {@code null} (see caller null-guards).
 */
@Component
public class TesseraMetricsAdapter implements MetricsPort {

    private final TesseraMetrics metrics;

    public TesseraMetricsAdapter(TesseraMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void recordIngest() {
        metrics.recordIngest();
    }

    @Override
    public void recordRuleEvaluation() {
        metrics.recordRuleEvaluation();
    }

    @Override
    public void recordConflict() {
        metrics.recordConflict();
    }

    @Override
    public void recordShaclValidationNanos(long nanos) {
        metrics.shaclTimer().record(nanos, TimeUnit.NANOSECONDS);
    }
}
```

---

## File 3: `fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java`

**Role:** MODIFY — inject `MetricsPort` and wrap the Jena validate call with a `System.nanoTime()` timer.

**Closest existing analog:** `GraphServiceImpl.java` — shows the canonical optional-bean constructor injection + null-guard call-site pattern used throughout fabric-core.

Exact excerpts from `GraphServiceImpl` that define the null-guard pattern:

```java
// GraphServiceImpl.java — null-guard pattern for optional ports (lines 86-104)
public GraphServiceImpl(
        GraphSession graphSession,
        EventLog eventLog,
        Outbox outbox,
        SchemaRegistry schemaRegistry,
        ShaclValidator shaclValidator,       // optional — null in early test fixtures
        RuleEnginePort ruleEngine,            // optional — null in early test fixtures
        ReconciliationConflictsRepository conflictsRepository,
        CircuitBreakerPort circuitBreaker) {  // optional — null in early test fixtures
    ...
    this.ruleEngine = ruleEngine;
    this.circuitBreaker = circuitBreaker;
}

// Call site (lines 142-145):
if (ruleEngine != null) {
    engineOutcome = ruleEngine.run(...);
    ...
}

// @Autowired(required = false) field pattern (lines 74-75):
@Autowired(required = false)
private ConnectorDlqWriter connectorDlqWriter;
```

The current `ShaclValidator` constructor (lines 60-63):

```java
public ShaclValidator(ShapeCache shapeCache, ValidationReportFilter reportFilter) {
    this.shapeCache = shapeCache;
    this.reportFilter = reportFilter;
}
```

The current `validate()` method hot path (lines 70-81):

```java
public void validate(TenantContext ctx, NodeTypeDescriptor descriptor, GraphMutation mutation) {
    Shapes shapes = shapeCache.shapesFor(ctx, descriptor);
    UUID focusUuid = effectiveUuid(mutation);
    Graph dataGraph = buildDataGraph(ctx, descriptor, mutation, focusUuid);
    ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);
    if (!raw.conforms()) {
        RedactedValidationReport redacted = reportFilter.redact(raw, ctx, focusUuid);
        throw new ShaclValidationException(
                "SHACL validation failed for " + descriptor.slug() + " " + reportFilter.toSafeString(redacted),
                redacted);
    }
}
```

**Data flow:**
- Called by: `GraphServiceImpl.apply()` (line 165: `shaclValidator.validate(...)`)
- Calls: `MetricsPort.recordShaclValidationNanos(long)` (new), `shapeCache.shapesFor(...)`, `org.apache.jena.shacl.ShaclValidator.get().validate(...)`
- MetricsPort injection: add field + constructor parameter (mirrors ruleEngine/circuitBreaker pattern)

**Changes required:**

1. Add `import dev.tessera.core.metrics.MetricsPort;` — no Micrometer import needed.
2. Add `private final MetricsPort metricsPort;` field.
3. Extend constructor to accept `MetricsPort metricsPort` (annotated `@Autowired(required = false)` OR added as an `@Autowired(required = false)` field — field injection matches the `connectorDlqWriter` precedent if constructor change is undesirable for `PipelineFixture`).
4. Wrap the Jena call in `validate()`:

```java
// Modified validate() — System.nanoTime pattern (avoids checked-exception lambda gymnastics)
public void validate(TenantContext ctx, NodeTypeDescriptor descriptor, GraphMutation mutation) {
    Shapes shapes = shapeCache.shapesFor(ctx, descriptor);
    UUID focusUuid = effectiveUuid(mutation);
    Graph dataGraph = buildDataGraph(ctx, descriptor, mutation, focusUuid);
    long start = (metricsPort != null) ? System.nanoTime() : 0L;
    ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);
    if (metricsPort != null) {
        metricsPort.recordShaclValidationNanos(System.nanoTime() - start);
    }
    if (!raw.conforms()) {
        RedactedValidationReport redacted = reportFilter.redact(raw, ctx, focusUuid);
        throw new ShaclValidationException(
                "SHACL validation failed for " + descriptor.slug() + " " + reportFilter.toSafeString(redacted),
                redacted);
    }
}
```

**Constructor injection vs. field injection trade-off:**
`TargetedValidationTest` constructs `ShaclValidator` directly via `new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter())`. Adding a third constructor parameter breaks that. Use `@Autowired(required = false)` **field injection** for `metricsPort` — exactly as `GraphServiceImpl` does for `connectorDlqWriter` — so existing test constructors compile unchanged.

```java
// Field injection (mirrors connectorDlqWriter precedent in GraphServiceImpl lines 74-75):
@Autowired(required = false)
private MetricsPort metricsPort;
```

---

## File 4: `fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java`

**Role:** MODIFY — inject `MetricsPort` and add one counter call per pipeline invocation plus one per conflict.

**Closest existing analog:** `RuleEngine.java` itself, which already follows the optional-bean pattern for `EchoLoopSuppressionRule`. The `EngineResult.conflicts()` list (a `List<ConflictRecord>`) is available at the return point.

Current constructor (lines 49-56):

```java
public RuleEngine(
        RuleRepository ruleRepository,
        ChainExecutor chainExecutor,
        EchoLoopSuppressionRule echoLoopSuppressionRule) {
    this.ruleRepository = ruleRepository;
    this.chainExecutor = chainExecutor;
    this.echoLoopSuppressionRule = echoLoopSuppressionRule;
}
```

Current `run(RuleContext)` return point (lines 86-87):

```java
return new EngineResult(false, null, null, properties, routingHints, conflicts);
// `conflicts` is a List<ConflictRecord> accumulated from the RECONCILE chain
```

`EngineResult.conflicts()` returns `List<ConflictRecord>` (immutable copy per the record compact constructor).

**Data flow:**
- Called by: `GraphServiceImpl.apply()` via `RuleEnginePort` SPI bridge (lines 150-158)
- `RuleEngine` is in `fabric-rules`; it imports `fabric-core` (legal per ArchUnit)
- `MetricsPort` is in `dev.tessera.core.metrics` — import is legal for `fabric-rules`
- Calls: `MetricsPort.recordRuleEvaluation()` once at pipeline entry, `MetricsPort.recordConflict()` once per `ConflictRecord` in the result

**Changes required:**

1. Add `import dev.tessera.core.metrics.MetricsPort;`
2. Add `@Autowired(required = false) private MetricsPort metricsPort;` field (field injection, same as ShaclValidator decision above — `PipelineFixture` calls `new RuleEngine(repo, executor, echoLoop)` and must not be broken)
3. In `run(RuleContext ctx)`, add calls at two points:

```java
// Modified run(RuleContext ctx) — metric call sites only
public EngineResult run(RuleContext ctx) {
    if (metricsPort != null) metricsPort.recordRuleEvaluation();  // ADD — once per pipeline

    List<Rule> rules = ruleRepository.activeRulesFor(ctx.tenantContext().modelId());
    // ... existing 4-chain pipeline unchanged ...

    EngineResult result = new EngineResult(false, null, null, properties, routingHints, conflicts);
    if (metricsPort != null) {                                      // ADD — once per conflict
        result.conflicts().forEach(c -> metricsPort.recordConflict());
    }
    return result;
}
```

Note: the early-return path (VALIDATE reject at line 67-69) does not add a conflict counter call — rejected pipelines produce zero conflicts by design. The `recordRuleEvaluation()` call fires even on rejection (the pipeline was evaluated).

---

## File 5: `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java`

**Role:** MODIFY — inject `MetricsPort` and call `recordIngest()` once per `Committed` outcome.

**Closest existing analog:** `ConnectorRunner.java` itself — it already uses `@Autowired(required = false)` for three optional beans (`EntityResolutionService`, `EmbeddingService`, `ExtractionReviewRepository`) in the constructor (lines 66-79).

Current constructor (lines 66-79):

```java
public ConnectorRunner(
        GraphService graphService,
        SyncStatusRepository syncStatusRepo,
        Clock clock,
        @Autowired(required = false) EntityResolutionService entityResolutionService,
        @Autowired(required = false) EmbeddingService embeddingService,
        @Autowired(required = false) ExtractionReviewRepository reviewRepository) {
    ...
}
```

Current `Committed` branch (lines 128-129):

```java
if (outcome instanceof GraphMutationOutcome.Committed committed) {
    successCount++;
    // ... embedding storage ...
}
```

**Data flow:**
- `ConnectorRunner` is in `fabric-connectors`; it imports `fabric-core` and `fabric-rules` (legal per ArchUnit)
- `MetricsPort` is in `dev.tessera.core.metrics` — import is legal
- `TesseraMetrics` is in `dev.tessera.app.metrics` — import is BLOCKED by ArchUnit (`fabric_connectors_should_not_depend_on_projections_or_app`)
- Calls: `MetricsPort.recordIngest()` once per `Committed` outcome in `runOnce()`

**Changes required:**

1. Add `import dev.tessera.core.metrics.MetricsPort;`
2. Add `metricsPort` as an optional constructor parameter (mirrors the three existing optional beans):

```java
public ConnectorRunner(
        GraphService graphService,
        SyncStatusRepository syncStatusRepo,
        Clock clock,
        @Autowired(required = false) EntityResolutionService entityResolutionService,
        @Autowired(required = false) EmbeddingService embeddingService,
        @Autowired(required = false) ExtractionReviewRepository reviewRepository,
        @Autowired(required = false) MetricsPort metricsPort) {   // ADD
    ...
    this.metricsPort = metricsPort;  // ADD
}
```

3. In `runOnce()`, add the counter increment inside the `Committed` branch (line 129):

```java
if (outcome instanceof GraphMutationOutcome.Committed committed) {
    successCount++;
    if (metricsPort != null) metricsPort.recordIngest();  // ADD
    // ... existing embedding storage ...
}
```

---

## File 6: `fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsAdapterTest.java`

**Role:** CREATE — unit test verifying that `TesseraMetricsAdapter` correctly delegates all four `MetricsPort` method calls to `TesseraMetrics`.

**Closest existing analog:** `TesseraMetricsTest.java` — same package, same `SimpleMeterRegistry` setup pattern.

Key excerpts from `TesseraMetricsTest.java` that define the test structure:

```java
// TesseraMetricsTest.java — setup pattern to replicate
class TesseraMetricsTest {

    private SimpleMeterRegistry registry;
    private TesseraMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Pass null for NamedParameterJdbcTemplate — gauge lambdas null-guard and return 0
        metrics = new TesseraMetrics(registry, null);
    }

    // Counter increment verification pattern:
    @Test
    void recordIngestIncrementsCounter() {
        double before = registry.find("tessera.ingest.rate").counter().count();
        metrics.recordIngest();
        double after = registry.find("tessera.ingest.rate").counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }
}
```

**Data flow:**
- Constructs: `TesseraMetrics(registry, null)` then `new TesseraMetricsAdapter(metrics)`
- Calls each `MetricsPort` method on the adapter
- Asserts against the `SimpleMeterRegistry` for counters; asserts timer `count()` for `recordShaclValidationNanos`

**File to create:**

```java
package dev.tessera.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TesseraMetricsAdapterTest {

    private SimpleMeterRegistry registry;
    private TesseraMetrics metrics;
    private TesseraMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TesseraMetrics(registry, null);
        adapter = new TesseraMetricsAdapter(metrics);
    }

    @Test
    void recordIngest_delegates_to_ingest_counter() {
        double before = registry.find("tessera.ingest.rate").counter().count();
        adapter.recordIngest();
        assertThat(registry.find("tessera.ingest.rate").counter().count() - before).isEqualTo(1.0);
    }

    @Test
    void recordRuleEvaluation_delegates_to_evaluations_counter() {
        double before = registry.find("tessera.rules.evaluations").counter().count();
        adapter.recordRuleEvaluation();
        assertThat(registry.find("tessera.rules.evaluations").counter().count() - before).isEqualTo(1.0);
    }

    @Test
    void recordConflict_delegates_to_conflicts_counter() {
        double before = registry.find("tessera.conflicts.count").counter().count();
        adapter.recordConflict();
        assertThat(registry.find("tessera.conflicts.count").counter().count() - before).isEqualTo(1.0);
    }

    @Test
    void recordShaclValidationNanos_records_one_timer_observation() {
        long before = registry.find("tessera.shacl.validation.time").timer().count();
        adapter.recordShaclValidationNanos(500_000L);
        assertThat(registry.find("tessera.shacl.validation.time").timer().count() - before).isEqualTo(1L);
    }
}
```

---

## File 7: `fabric-core/src/test/java/dev/tessera/core/validation/ShaclValidatorMetricsTest.java`

**Role:** CREATE — unit test verifying that `ShaclValidator.validate()` records a timing observation on the `MetricsPort` when one is present, and continues to work when `metricsPort` is `null`.

**Closest existing analog:** `TargetedValidationTest.java` — same package, same construction pattern: `new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter())`. This test uses field injection for `metricsPort`, so the two-arg constructor call still works.

Key excerpts from `TargetedValidationTest.java` that define construction and mutation fixtures:

```java
// TargetedValidationTest.java — construction pattern
private ShaclValidator newValidator() {
    return new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter());
}

// Descriptor factory:
private static NodeTypeDescriptor personWithRequiredName() { ... }

// Mutation factory:
private static GraphMutation createMutation(TenantContext ctx, UUID target, Map<String, Object> payload) { ... }
```

For this test, a stub `MetricsPort` is needed. Since `MetricsPort` is a single-method-group interface, an anonymous class or a simple recording stub works without Mockito.

**File to create:**

```java
package dev.tessera.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.metrics.MetricsPort;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.internal.ShapeCache;
import dev.tessera.core.validation.internal.ShapeCompiler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** OPS-01 — verifies MetricsPort timer wiring in ShaclValidator. */
class ShaclValidatorMetricsTest {

    // Recording stub — avoids Mockito dependency in fabric-core tests
    static class RecordingMetricsPort implements MetricsPort {
        int ingestCount;
        int evalCount;
        int conflictCount;
        final AtomicLong lastNanos = new AtomicLong(-1L);

        @Override public void recordIngest()          { ingestCount++; }
        @Override public void recordRuleEvaluation()  { evalCount++; }
        @Override public void recordConflict()        { conflictCount++; }
        @Override public void recordShaclValidationNanos(long nanos) { lastNanos.set(nanos); }
    }

    private ShaclValidator newValidator(MetricsPort port) {
        ShaclValidator v = new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter());
        ReflectionTestUtils.setField(v, "metricsPort", port);
        return v;
    }

    private static NodeTypeDescriptor personDescriptor() {
        return new NodeTypeDescriptor(
                UUID.randomUUID(), "Person", "Person", "Person", "desc", 1L,
                List.of(new PropertyDescriptor("name", "name", "string", true, null, null, null, null, null)),
                null);
    }

    private static GraphMutation validMutation(TenantContext ctx) {
        return new GraphMutation(
                ctx, Operation.CREATE, "Person", UUID.randomUUID(),
                Map.of("name", "Alice"),
                SourceType.SYSTEM, "src-1", "test", BigDecimal.ONE,
                null, null, null, null, null, null);
    }

    @Test
    void valid_mutation_records_timer_observation() {
        RecordingMetricsPort port = new RecordingMetricsPort();
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        ShaclValidator validator = newValidator(port);

        validator.validate(ctx, personDescriptor(), validMutation(ctx));

        assertThat(port.lastNanos.get())
                .as("recordShaclValidationNanos must be called with a non-negative duration")
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    void null_metricsPort_does_not_throw() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        // newValidator(null) leaves metricsPort as null — existing test fixtures work unchanged
        ShaclValidator validator = newValidator(null);
        // Should not throw even with no MetricsPort
        validator.validate(ctx, personDescriptor(), validMutation(ctx));
    }
}
```

---

## Cross-Cutting: Null-Guard and ArchUnit Constraints

### Null-guard pattern summary

Every call site uses the same two-part idiom extracted from `GraphServiceImpl`:

| Where applied | How declared | Call site |
|---------------|-------------|-----------|
| `ShaclValidator.metricsPort` | `@Autowired(required = false)` field | `if (metricsPort != null) metricsPort.recordShaclValidationNanos(...)` |
| `RuleEngine.metricsPort` | `@Autowired(required = false)` field | `if (metricsPort != null) metricsPort.recordRuleEvaluation()` |
| `ConnectorRunner.metricsPort` | `@Autowired(required = false)` constructor param | `if (metricsPort != null) metricsPort.recordIngest()` |

Field injection is chosen for `ShaclValidator` and `RuleEngine` because their existing tests (`TargetedValidationTest`, `PipelineFixture`) construct instances directly via the two/three-arg constructor and must not be broken. `ConnectorRunner` uses the constructor param approach because it already accepts `@Autowired(required = false)` params in its constructor.

### ArchUnit enforcement reference

From `ModuleDependencyTest.java` (enforced at build time):

```java
// fabric_core_should_not_depend_on_others — MetricsPort is IN fabric-core, so no violation
noClasses().that().resideInAPackage("dev.tessera.core..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("dev.tessera.rules..", "dev.tessera.projections..",
                        "dev.tessera.connectors..", "dev.tessera.app..");

// fabric_rules_should_only_depend_on_core — MetricsPort in dev.tessera.core.metrics is legal
noClasses().that().resideInAPackage("dev.tessera.rules..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("dev.tessera.projections..", "dev.tessera.connectors..", "dev.tessera.app..");

// fabric_connectors_should_not_depend_on_projections_or_app — MetricsPort in core, legal
noClasses().that().resideInAPackage("dev.tessera.connectors..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("dev.tessera.projections..", "dev.tessera.app..");
```

Verdict: `dev.tessera.core.metrics.MetricsPort` is visible to all three target modules (`fabric-core`, `fabric-rules`, `fabric-connectors`) without any ArchUnit violation. `TesseraMetrics` and `TesseraMetricsAdapter` in `dev.tessera.app.metrics` must never appear in imports outside `fabric-app`.

---

## PipelineFixture Impact Assessment

`PipelineFixture.boot()` constructs `GraphServiceImpl` directly (line 110):

```java
GraphServiceImpl graphService = new GraphServiceImpl(session, log, outbox, null, null, port, conflicts, null);
```

This passes `null` for `shaclValidator` (position 5) and `circuitBreaker` (position 8). `MetricsPort` is NOT in the `GraphServiceImpl` constructor — it is field-injected into `ShaclValidator` and `RuleEngine` separately. No change to `PipelineFixture` is required.

`RuleEngine` is constructed as `new RuleEngine(repo, executor, echoLoop)` (line 106). After the change, `metricsPort` is a field, not a constructor parameter — so `PipelineFixture` compiles unchanged. In tests, `metricsPort` will be `null`; the null-guard prevents any NPE.

`TargetedValidationTest` calls `new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter())`. After the change, `metricsPort` is a field defaulting to `null` — the two-arg constructor still compiles. `null_metricsPort_does_not_throw` in `ShaclValidatorMetricsTest` confirms this path.
