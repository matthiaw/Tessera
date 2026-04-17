# Phase 6: Metrics Instrumentation Wiring - Research

**Researched:** 2026-04-17
**Domain:** Micrometer counter/timer injection into production write path (ConnectorRunner, GraphServiceImpl, ShaclValidator)
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| OPS-01 | Prometheus / OpenTelemetry metrics for: ingest rate, rule evaluations per second, conflict count, outbox lag, replication slot lag, SHACL validation time | TesseraMetrics bean already registered all 6 meters; this phase wires increment/timer calls into the 3 production code paths that perform these operations. Research maps exact call sites and injection strategy. |
</phase_requirements>

---

## Summary

TesseraMetrics (`fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetrics.java`) is a fully implemented `@Component` that registers all 6 Micrometer meters on startup: `tessera.ingest.rate` (Counter), `tessera.rules.evaluations` (Counter), `tessera.conflicts.count` (Counter), `tessera.outbox.lag` (Gauge, DB-polled), `tessera.replication.slot.lag` (Gauge, DB-polled), and `tessera.shacl.validation.time` (Timer). The three public methods `recordIngest()`, `recordRuleEvaluation()`, and `recordConflict()`, plus `shaclTimer()`, are tested in `TesseraMetricsTest`. They currently have zero callers — counters stay at zero even under real load.

The three production code paths that need wiring are: (1) `ConnectorRunner.runOnce()` in `fabric-connectors` — increments `tessera.ingest.rate` once per successfully committed candidate; (2) `RuleEngine.run(RuleContext)` in `fabric-rules` — increments `tessera.rules.evaluations` once per pipeline invocation (four chains counts as one call) and increments `tessera.conflicts.count` for each conflict entry in the engine result; (3) `ShaclValidator.validate()` in `fabric-core` — wraps the Jena validation call in `shaclTimer().record(...)`.

The central architectural constraint is the ArchUnit-enforced dependency direction: `fabric-core` cannot depend on `fabric-app` (where TesseraMetrics lives). This means metrics injection into `GraphServiceImpl` and `ShaclValidator` requires an intermediary. The established pattern for exactly this problem already exists in the codebase — the `RuleEnginePort` SPI (defined in `fabric-core`, implemented in `fabric-rules`). The same port pattern applies here: define a `MetricsPort` interface in `fabric-core`, implement it in `fabric-app` as a thin adapter over `TesseraMetrics`, and inject it into `GraphServiceImpl` and `ShaclValidator` as `@Autowired(required = false)`.

**Primary recommendation:** Use a `MetricsPort` SPI in `fabric-core` (mirrors `RuleEnginePort`). Inject via `@Autowired(required = false)` so existing null-tolerant test fixtures continue compiling. Wire `ConnectorRunner` directly against `TesseraMetrics` (or against `MeterRegistry` via constructor injection — both are compile-scope available in `fabric-connectors`).

---

## Standard Stack

### Core (already on classpath — verified in this session)

| Library | Version | Purpose | Module Scope |
|---------|---------|---------|--------------|
| `io.micrometer:micrometer-core` | 1.15.10 | Counter, Timer, MeterRegistry | `fabric-rules` direct compile; transitive into `fabric-connectors` [VERIFIED: mvn dependency:tree] |
| `io.micrometer:micrometer-observation` | 1.15.10 | Transitive via spring-boot-starter | Compile in `fabric-core` — does NOT include Counter/Timer [VERIFIED: mvn dependency:tree] |
| `io.micrometer:micrometer-registry-prometheus` | (Spring Boot BOM) | Prometheus export | `fabric-app` direct dependency [VERIFIED: fabric-app/pom.xml] |

**Critical finding:** `fabric-core` has `micrometer-observation` (no Counter/Timer) but NOT `micrometer-core` as a direct or transitive dependency. Any code in `fabric-core` that imports `io.micrometer.core.instrument.Counter` or `Timer` will fail to compile unless `micrometer-core` is added to `fabric-core/pom.xml` — OR the `MetricsPort` interface avoids Micrometer imports entirely. [VERIFIED: mvn dependency:tree on fabric-core]

### No new library dependencies required

All required Micrometer types are already on the classpath for each target module. No new `pom.xml` entries are needed unless the `MetricsPort` SPI in `fabric-core` directly imports `Timer` (which it should not — the port should be Micrometer-free to avoid adding a dependency to `fabric-core`).

---

## Architecture Patterns

### Module Dependency Constraints (ArchUnit-enforced)

```
fabric-core    ← cannot depend on fabric-app, fabric-rules, fabric-projections, fabric-connectors
fabric-rules   → fabric-core only (cannot depend on fabric-app, fabric-projections, fabric-connectors)
fabric-connectors → fabric-core, fabric-rules (cannot depend on fabric-app, fabric-projections)
fabric-app     → all modules (assembly module)
```
[VERIFIED: ModuleDependencyTest.java]

### Pattern: SPI Port in fabric-core (mirrors RuleEnginePort)

The existing `RuleEnginePort` in `fabric-core` is the canonical pattern for crossing the dependency boundary. Define `MetricsPort` in `fabric-core.rules` or `fabric-core.metrics` package. `TesseraMetricsAdapter` in `fabric-app` implements it and delegates to `TesseraMetrics`.

```java
// Source: codebase pattern — RuleEnginePort in fabric-core/src/main/java/dev/tessera/core/rules/RuleEnginePort.java
// Place in: fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java

package dev.tessera.core.metrics;

/** SPI for Micrometer metric emission. Fabric-core defines the port;
 *  fabric-app provides the TesseraMetricsAdapter implementation.
 *  Null-safe callers: inject @Autowired(required = false). */
public interface MetricsPort {
    void recordIngest();
    void recordRuleEvaluation();
    void recordConflict();
    /** Wraps callable in the SHACL validation timer. */
    void timeShacl(Runnable task);
}
```

Implementation in `fabric-app`:

```java
// Place in: fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java
@Component
public class TesseraMetricsAdapter implements MetricsPort {
    private final TesseraMetrics metrics;
    public TesseraMetricsAdapter(TesseraMetrics metrics) { this.metrics = metrics; }

    @Override public void recordIngest()         { metrics.recordIngest(); }
    @Override public void recordRuleEvaluation() { metrics.recordRuleEvaluation(); }
    @Override public void recordConflict()        { metrics.recordConflict(); }
    @Override public void timeShacl(Runnable task) {
        metrics.shaclTimer().record(task);
    }
}
```

### Pattern: Direct MeterRegistry injection (for ConnectorRunner in fabric-connectors)

`fabric-connectors` has `micrometer-core` transitively available. `ConnectorRunner` can be wired two ways:

**Option A (recommended):** Inject `TesseraMetrics` directly — but `TesseraMetrics` is in `fabric-app` which `fabric-connectors` cannot import (ArchUnit rule). So this is BLOCKED by ArchUnit.

**Option B (correct):** Inject `MetricsPort` (defined in `fabric-core`) into `ConnectorRunner` as `@Autowired(required = false)`. Same pattern as `GraphServiceImpl` and `ShaclValidator`.

**Option C (alternative):** Inject `MeterRegistry` directly into `ConnectorRunner`, call `registry.counter(TesseraMetrics.METRIC_INGEST_RATE, "source", "connector").increment()`. This avoids the port entirely. The metric name string must match exactly what `TesseraMetrics` registers. Risk: naming drift if metric names ever change — the port pattern centralizes that.

**Verdict:** Option B (inject `MetricsPort`) is the cleanest — consistent with `RuleEnginePort` pattern, tolerant of null in tests, no ArchUnit violations. [ASSUMED — no prior decision document mandates which option to use]

### Pattern: RuleEngine wiring

`RuleEngine` in `fabric-rules` cannot depend on `fabric-app`. It CAN depend on `fabric-core`. Inject `MetricsPort` as `@Autowired(required = false)`.

Wiring point in `RuleEngine.run(RuleContext)`:
- Call `metricsPort.recordRuleEvaluation()` once at the start of pipeline execution
- After `EngineResult` is assembled, iterate `er.conflicts()` and call `metricsPort.recordConflict()` for each entry

The `RuleEnginePort.Outcome.conflicts()` list is already populated by `ChainExecutor` when an `Override` outcome is produced. `ConflictRecord` entries in `EngineResult` map to conflicts. [VERIFIED: RuleEngine.java, ChainExecutor.java]

### Pattern: ShaclValidator wiring

`ShaclValidator` in `fabric-core` cannot depend on `fabric-app`. Inject `MetricsPort` as `@Autowired(required = false)`.

Wiring point in `ShaclValidator.validate()`:

```java
// Current (no timing):
Shapes shapes = shapeCache.shapesFor(ctx, descriptor);
UUID focusUuid = effectiveUuid(mutation);
Graph dataGraph = buildDataGraph(ctx, descriptor, mutation, focusUuid);
ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);

// After wiring (time the Jena validate call only — not shape compilation):
if (metricsPort != null) {
    metricsPort.timeShacl(() -> {
        ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);
        // handle result — but lambdas can't throw checked exceptions easily
    });
} else {
    ValidationReport raw = ...;
}
```

**Problem:** `ValidationReport raw` result must escape the lambda, and the exception path (if validation fails, throw `ShaclValidationException`) cannot be thrown from a `Runnable`. Use `Timer.Sample` instead of `record(Runnable)`:

```java
// Correct pattern for timing with side effects and exception propagation:
// Source: Micrometer Timer docs pattern [CITED: https://micrometer.io/docs/concepts#_storing_start_state_in_timer_sample]
Timer.Sample sample = (metricsPort != null) ? metricsPort.startTimerSample() : null;
ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);
if (sample != null) { sample.stop(metricsPort.shaclTimer()); }
```

**Simpler alternative:** Keep `MetricsPort.timeShacl(Runnable)` and move the exception into an AtomicReference or use a checked exception wrapper. OR: expose `startShaclSample()` and `stopShaclSample(sample)` on the port. This is a design decision for the planner.

**Simplest viable approach** that avoids checked-exception complexity: add `recordShaclValidationNanos(long nanos)` to `MetricsPort`, measure with `System.nanoTime()` before and after the Jena call, and call `metrics.shaclTimer().record(nanos, TimeUnit.NANOSECONDS)` in the adapter. No lambda required, no exception wrapping. [ASSUMED — simplicity tradeoff, valid per Micrometer API]

### Pattern: ConnectorRunner ingest counter placement

In `ConnectorRunner.runOnce()`, the successful ingest count is already tracked in `successCount`. The metric call goes at the point where `successCount++` happens — inside the `Committed` branch of the candidate loop:

```java
// Current (line ~129):
if (outcome instanceof GraphMutationOutcome.Committed committed) {
    successCount++;
    // ... embedding ...
}

// After wiring:
if (outcome instanceof GraphMutationOutcome.Committed committed) {
    successCount++;
    if (metricsPort != null) metricsPort.recordIngest();
    // ... embedding ...
}
```

This satisfies success criterion 1 precisely: `tessera_ingest_total` (Prometheus name for `tessera.ingest.rate` counter) increments by the number of entities processed — one increment per `Committed` outcome. [VERIFIED: ConnectorRunner.java lines 128-129]

### Prometheus metric name translation

Micrometer converts dots and camelCase to underscore-separated names for Prometheus. The acceptance criteria use Prometheus names:

| Micrometer name (Java) | Prometheus name (scrape) |
|------------------------|--------------------------|
| `tessera.ingest.rate` | `tessera_ingest_rate_total` |
| `tessera.rules.evaluations` | `tessera_rules_evaluations_total` |
| `tessera.conflicts.count` | `tessera_conflicts_count_total` |
| `tessera.shacl.validation.time` | `tessera_shacl_validation_time_seconds_{count,sum,max}` |

The acceptance criteria name `tessera_ingest_total` (no `_rate`) and `tessera_shacl_validation_seconds` (no `_time`) differ from what Micrometer produces with the current names. This is a naming discrepancy to surface to the planner. [VERIFIED: TesseraMetrics.java metric name constants; Prometheus naming is CITED from Micrometer docs]

**Naming discrepancy:** The success criteria call the metric `tessera_ingest_total` and `tessera_shacl_validation_seconds`. Micrometer would produce `tessera_ingest_rate_total` and `tessera_shacl_validation_time_seconds`. Either the criteria are using approximate names (acceptable — they just want the counter to be non-zero) or the meter names in `TesseraMetrics` need renaming. Recommend treating the acceptance criteria as functional intent, not exact Prometheus names, since `TesseraMetrics` was already built and tested.

### Anti-Patterns to Avoid

- **Calling `Counter.builder(...).register(registry)` inside the hot path (per-request):** Micrometer meters are singletons — re-registering returns the existing one, but the lookup has overhead. Call `register()` once at startup (already done in `TesseraMetrics` constructor). Callers only call `increment()` / `record()`. [CITED: https://micrometer.io/docs/concepts#_counters]
- **Importing `dev.tessera.app.*` in `fabric-core` or `fabric-rules`:** ArchUnit will reject this at build time.
- **Bypassing the null-guard for test fixtures:** `GraphServiceImpl` constructor accepts null for optional beans. `MetricsPort` must be injected with `@Autowired(required = false)` and guarded: `if (metricsPort != null)` before calls.
- **Placing `MetricsPort` in `fabric-app`:** The port must be in `fabric-core` (or a shared-api module) so both `fabric-core` and `fabric-rules` can depend on it without importing `fabric-app`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SHACL timing with exception propagation | Custom try/finally timing wrapper | `System.nanoTime()` + `Timer.record(long, TimeUnit)` | Avoids lambda checked-exception gymnastics; Micrometer Timer handles nanos natively |
| Metric name registry | String constants in multiple classes | `TesseraMetrics` static constants (already exist) | `METRIC_INGEST_RATE` etc. are package-private — consider promoting to public or duplicating in `MetricsPort` |
| Prometheus scrape endpoint | Custom servlet | Spring Boot Actuator + `micrometer-registry-prometheus` (already configured) | Already wired in `fabric-app` |

---

## Common Pitfalls

### Pitfall 1: ArchUnit violation — `fabric-connectors` importing `TesseraMetrics`

**What goes wrong:** Developer injects `TesseraMetrics` directly into `ConnectorRunner` because it "looks like a Spring bean". ArchUnit test `fabric_connectors_should_not_depend_on_projections_or_app` fails at build time.
**Why it happens:** `TesseraMetrics` is in `dev.tessera.app.metrics` — the `app` package.
**How to avoid:** Always use `MetricsPort` (in `fabric-core`) as the injection point in non-app modules.
**Warning signs:** Any `import dev.tessera.app.*` in a non-app module.

### Pitfall 2: `fabric-core` importing Micrometer Counter/Timer directly

**What goes wrong:** `MetricsPort` interface imports `io.micrometer.core.instrument.Timer`. Compile fails on `fabric-core` because `micrometer-core` is not a dependency of that module.
**Why it happens:** `fabric-core` only has `micrometer-observation` transitively, not `micrometer-core`. [VERIFIED: dependency:tree]
**How to avoid:** Keep `MetricsPort` free of Micrometer imports. Use primitive types (`long nanos`) or `Runnable` for the timer method. The adapter in `fabric-app` does the Micrometer-specific wiring.
**Fix if violated:** Add `micrometer-core` to `fabric-core/pom.xml` explicitly — or redesign the port signature.

### Pitfall 3: Missing null-guard breaks PipelineFixture and JMH benches

**What goes wrong:** `GraphServiceImpl` or `ShaclValidator` calls `metricsPort.recordX()` without null guard. `PipelineFixture` (test harness) constructs `GraphServiceImpl` without a `MetricsPort` → NPE in rule tests.
**Why it happens:** Many test harnesses pre-date new optional beans and pass explicit `null` to the constructor.
**How to avoid:** Mirror the existing pattern: `@Autowired(required = false)` in the constructor, `if (metricsPort != null)` at each call site. [VERIFIED: GraphServiceImpl null-tolerance pattern for `ruleEngine`, `shaclValidator`, `circuitBreaker`]

### Pitfall 4: Double-counting conflicts

**What goes wrong:** `recordConflict()` is called both in `RuleEngine` AND in `GraphServiceImpl.apply()` (which also iterates `engineOutcome.conflicts()`).
**Why it happens:** `GraphServiceImpl` already loops over `engineOutcome.conflicts()` to persist them. Adding a `recordConflict()` call there is tempting.
**How to avoid:** Wire the conflict counter in ONE place only. `RuleEngine.run(RuleContext)` is the best location — it directly produces the `EngineResult` with the `conflicts()` list, before any persistence happens.

### Pitfall 5: Acceptance criteria metric names vs. actual Prometheus output

**What goes wrong:** Integration test checks for `tessera_ingest_total` but Prometheus scrape serves `tessera_ingest_rate_total`. Test fails even though the metric is incrementing correctly.
**Why it happens:** Micrometer appends `_total` to counter names AND preserves the rest of the name unchanged. `tessera.ingest.rate` → `tessera_ingest_rate_total`.
**How to avoid:** Verify exact Prometheus metric names via `/actuator/prometheus` endpoint after wiring. Acceptance criteria names are intent, not exact strings.

---

## Code Examples

### MetricsPort interface (no Micrometer imports)

```java
// Source: derived from RuleEnginePort pattern [VERIFIED: fabric-core/src/main/java/dev/tessera/core/rules/RuleEnginePort.java]
// Place: fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java
package dev.tessera.core.metrics;

public interface MetricsPort {
    /** Increment tessera.ingest.rate counter by 1. */
    void recordIngest();
    /** Increment tessera.rules.evaluations counter by 1. */
    void recordRuleEvaluation();
    /** Increment tessera.conflicts.count counter by 1. */
    void recordConflict();
    /** Record SHACL validation duration in nanoseconds. */
    void recordShaclValidationNanos(long nanos);
}
```

### TesseraMetricsAdapter (in fabric-app)

```java
// Source: TesseraMetrics API [VERIFIED: fabric-app/.../TesseraMetrics.java]
// Place: fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java
package dev.tessera.app.metrics;

import dev.tessera.core.metrics.MetricsPort;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class TesseraMetricsAdapter implements MetricsPort {
    private final TesseraMetrics metrics;
    public TesseraMetricsAdapter(TesseraMetrics metrics) { this.metrics = metrics; }

    @Override public void recordIngest()         { metrics.recordIngest(); }
    @Override public void recordRuleEvaluation() { metrics.recordRuleEvaluation(); }
    @Override public void recordConflict()       { metrics.recordConflict(); }
    @Override public void recordShaclValidationNanos(long nanos) {
        metrics.shaclTimer().record(nanos, TimeUnit.NANOSECONDS);
    }
}
```

### ShaclValidator wiring (System.nanoTime pattern)

```java
// Source: current ShaclValidator.validate() [VERIFIED: fabric-core/.../ShaclValidator.java]
public void validate(TenantContext ctx, NodeTypeDescriptor descriptor, GraphMutation mutation) {
    Shapes shapes = shapeCache.shapesFor(ctx, descriptor);
    UUID focusUuid = effectiveUuid(mutation);
    Graph dataGraph = buildDataGraph(ctx, descriptor, mutation, focusUuid);
    long start = (metricsPort != null) ? System.nanoTime() : 0;
    ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);
    if (metricsPort != null) {
        metricsPort.recordShaclValidationNanos(System.nanoTime() - start);
    }
    if (!raw.conforms()) {
        RedactedValidationReport redacted = reportFilter.redact(raw, ctx, focusUuid);
        throw new ShaclValidationException(...);
    }
}
```

### RuleEngine wiring

```java
// Source: current RuleEngine.run(RuleContext) [VERIFIED: fabric-rules/.../RuleEngine.java]
public EngineResult run(RuleContext ctx) {
    if (metricsPort != null) metricsPort.recordRuleEvaluation();
    // ... existing 4-chain pipeline ...
    EngineResult result = new EngineResult(false, null, null, properties, routingHints, conflicts);
    if (metricsPort != null) {
        result.conflicts().forEach(c -> metricsPort.recordConflict());
    }
    return result;
}
```

### ConnectorRunner wiring (ingest counter)

```java
// Source: current ConnectorRunner.runOnce() line ~128 [VERIFIED: fabric-connectors/.../ConnectorRunner.java]
if (outcome instanceof GraphMutationOutcome.Committed committed) {
    successCount++;
    if (metricsPort != null) metricsPort.recordIngest();
    // ... embedding ...
}
```

---

## Runtime State Inventory

Step 2.5: SKIPPED — this is a greenfield instrumentation phase (wiring calls into existing code paths), not a rename/refactor/migration phase.

---

## Environment Availability

Step 2.6: No new external dependencies. Micrometer is already on the classpath. Prometheus scrape is already configured. No new tools required.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|---------|
| `micrometer-core` | Timer.record(), Counter.increment() | Yes (transitive in fabric-rules/fabric-connectors) | 1.15.10 | — |
| `micrometer-registry-prometheus` | Prometheus scrape endpoint | Yes (fabric-app direct dep) | Spring Boot BOM | — |
| Spring Boot Actuator | `/actuator/prometheus` | Yes | Spring Boot BOM | — |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + AssertJ |
| Config file | `fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsTest.java` (existing unit test) |
| Quick run command | `./mvnw test -pl fabric-app -Dtest=TesseraMetricsTest -DfailIfNoTests=false` |
| Full suite command | `./mvnw verify -pl fabric-app,fabric-core,fabric-rules,fabric-connectors` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OPS-01 (ingest) | `tessera.ingest.rate` increments after ConnectorRunner processes a committed entity | integration | `./mvnw test -pl fabric-connectors -Dtest=RestPollingConnectorIT` | Exists (RestPollingConnectorIT) |
| OPS-01 (rules) | `tessera.rules.evaluations` increments per rule engine pipeline call | unit | `./mvnw test -pl fabric-rules -Dtest=ChainExecutorTest` | Exists (add assertion) |
| OPS-01 (conflicts) | `tessera.conflicts.count` increments per Override conflict | integration | `./mvnw test -pl fabric-rules -Dtest=ConflictRegisterIT` | Exists (add assertion) |
| OPS-01 (shacl timer) | `tessera.shacl.validation.time` timer records duration | unit | `./mvnw test -pl fabric-core -Dtest=ShaclValidatorTest` | ❌ Wave 0: new test needed |
| OPS-01 (port wiring) | MetricsPort adapter delegates correctly | unit | `./mvnw test -pl fabric-app -Dtest=TesseraMetricsAdapterTest` | ❌ Wave 0: new test needed |

### Sampling Rate

- **Per task commit:** `./mvnw test -pl fabric-app -Dtest=TesseraMetricsTest,TesseraMetricsAdapterTest`
- **Per wave merge:** `./mvnw verify -pl fabric-app,fabric-core,fabric-rules,fabric-connectors`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `fabric-core/src/test/java/dev/tessera/core/validation/ShaclValidatorMetricsTest.java` — verifies timer recording with a `SimpleMeterRegistry`-backed `MetricsPort` stub
- [ ] `fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsAdapterTest.java` — verifies adapter delegates to `TesseraMetrics` methods
- [ ] `fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java` — the SPI interface itself (production code, Wave 0)
- [ ] `fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java` — the adapter (production code, Wave 0)

---

## Security Domain

This phase is instrumentation wiring only. No authentication, authorization, secrets, or user-facing endpoints are added. The Prometheus endpoint was already configured and hardened in Phase 5 (`prometheus.access: unrestricted` + network-level firewall — documented as accepted risk T-05-00-01). No new ASVS categories apply.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `MetricsPort` SPI injected via `@Autowired(required = false)` is the correct wiring pattern (vs. injecting `MeterRegistry` directly in fabric-connectors) | Architecture Patterns | Low risk — either approach works; port pattern is cleaner and consistent with existing codebase |
| A2 | Acceptance criteria metric names (`tessera_ingest_total`, `tessera_shacl_validation_seconds`) are approximate intent, not exact Prometheus strings — no renaming of `TesseraMetrics` constants needed | Common Pitfalls | Low-medium — if exact names are mandated, `TesseraMetrics` constants need changing and `TesseraMetricsTest` needs updating |
| A3 | `recordShaclValidationNanos(long)` approach (System.nanoTime) is preferable to `Timer.record(Runnable)` for the ShaclValidator to avoid checked-exception lambda wrapping | Code Examples | Low risk — both are correct Micrometer patterns; nanos approach is slightly simpler |
| A4 | Rule evaluation counter should be incremented once per `RuleEngine.run(RuleContext)` call (once per entity mutation through the pipeline), not once per individual rule in the chain | Architecture Patterns | Medium — if "evaluations per second" means per-rule, the counter should move to `ChainExecutor` and increment once per `rule.evaluate(ctx)` call; clarification may be needed |

---

## Open Questions

1. **Metric granularity for `tessera.rules.evaluations`**
   - What we know: the requirement says "rule evaluations per second"; `RuleEngine.run()` runs 4 chains; `ChainExecutor.execute()` iterates N rules per chain
   - What's unclear: does "one evaluation" mean one pipeline invocation (per entity) or one rule.evaluate() call?
   - Recommendation: increment at `RuleEngine.run()` entry (per-entity pipeline) — this matches "evaluations per sync batch" semantics and is the natural unit for alerting; per-rule granularity can be added later if needed

2. **Should MetricsPort be placed in `fabric-core.metrics` or `fabric-core.rules`?**
   - What we know: `RuleEnginePort` is in `fabric-core.rules`; a metrics port is cross-cutting
   - What's unclear: whether a new `dev.tessera.core.metrics` package should be created
   - Recommendation: new `dev.tessera.core.metrics` package — it's a different concern from rules

---

## Sources

### Primary (HIGH confidence)

- [VERIFIED: fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetrics.java] — meter registration, method signatures
- [VERIFIED: fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java] — exact ingest call sites
- [VERIFIED: fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java] — pipeline invocation, conflict accumulation
- [VERIFIED: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java] — write funnel, null-tolerance pattern
- [VERIFIED: fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java] — validate() call site
- [VERIFIED: fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java] — ArchUnit dependency direction rules
- [VERIFIED: mvn dependency:tree] — micrometer-core present in fabric-connectors/fabric-rules; absent from fabric-core
- [VERIFIED: fabric-core/src/main/java/dev/tessera/core/rules/RuleEnginePort.java] — canonical SPI port pattern to replicate

### Secondary (MEDIUM confidence)

- [CITED: https://micrometer.io/docs/concepts#_counters] — Counter singleton pattern, `increment()` usage
- [CITED: https://micrometer.io/docs/concepts#_timers] — `Timer.record(long, TimeUnit)` for nanos-based recording

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all Micrometer versions verified via dependency:tree
- Architecture: HIGH — dependency rules verified via ArchUnit source; wiring pattern derived from existing `RuleEnginePort` precedent
- Pitfalls: HIGH — all pitfalls derived from direct code inspection of actual files

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (stable Spring Boot / Micrometer versions; code topology unlikely to change before Phase 6 execution)
