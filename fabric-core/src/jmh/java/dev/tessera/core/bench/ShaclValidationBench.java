/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.tessera.core.bench;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.ShaclValidator;
import dev.tessera.core.validation.ValidationReportFilter;
import dev.tessera.core.validation.internal.ShapeCache;
import dev.tessera.core.validation.internal.ShapeCompiler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Wave 3 — plan 01-W3-03. Per-mutation Jena SHACL validation p95. Phase 1
 * gate (soft): p95 &lt; 2 ms against cached shapes for a single-node delta
 * with a 5-property {@code Person} descriptor.
 *
 * <p>Pure in-process benchmark — no Postgres, no AGE container. Builds a
 * synthetic {@link NodeTypeDescriptor}, primes the {@link ShapeCache}, and
 * repeatedly calls {@link ShaclValidator#validate} on a passing mutation.
 * This isolates the Jena shape-compile + single-subject validate cost from
 * the full write-pipeline cost measured by {@link WritePipelineBench}.
 *
 * <p>Runs via {@code ./mvnw -pl fabric-core -Pjmh verify}. Results land in
 * {@code .planning/benchmarks/<timestamp>-<dataset>.json} by way of
 * {@link JmhRunner}.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@State(Scope.Benchmark)
public class ShaclValidationBench {

    private ShaclValidator validator;
    private NodeTypeDescriptor descriptor;
    private TenantContext ctx;
    private GraphMutation sample;

    @Setup(Level.Trial)
    public void setup() {
        ShapeCompiler compiler = new ShapeCompiler();
        ShapeCache cache = new ShapeCache(compiler);
        ValidationReportFilter filter = new ValidationReportFilter();
        validator = new ShaclValidator(cache, filter);

        ctx = TenantContext.of(UUID.randomUUID());
        descriptor = new NodeTypeDescriptor(
                ctx.modelId(),
                "Person",
                "Person",
                "Person",
                "bench-synthetic Person type",
                1L,
                List.of(
                        new PropertyDescriptor("name", "Name", "string", true, null, null, null, null, null),
                        new PropertyDescriptor("email", "Email", "string", true, null, null, null, null, null),
                        new PropertyDescriptor("age", "Age", "int", false, null, null, null, null, null),
                        new PropertyDescriptor("active", "Active", "boolean", false, null, null, null, null, null),
                        new PropertyDescriptor(
                                "department", "Department", "string", false, null, null, null, null, null)),
                null);

        sample = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .targetNodeUuid(UUID.randomUUID())
                .payload(Map.of(
                        "name", "Alice",
                        "email", "alice@example.com",
                        "age", 42,
                        "active", true,
                        "department", "Engineering"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("bench")
                .sourceSystem("bench")
                .confidence(BigDecimal.ONE)
                .build();

        // Prime the ShapeCache — subsequent @Benchmark invocations exercise
        // the hot-cache code path.
        validator.validate(ctx, descriptor, sample);
    }

    @Benchmark
    public void validateHotCache() {
        validator.validate(ctx, descriptor, sample);
    }
}
