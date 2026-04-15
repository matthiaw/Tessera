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
 * Wave 3 — plan 01-W3-01. Per-mutation Jena SHACL validation p95. Phase 1
 * gate: p95 &lt; 2 ms against cached shapes for a single-node delta. Skeleton
 * only in Wave 0 so the JMH harness does not need restructuring downstream.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class ShaclValidationBench {

    @Setup(Level.Trial)
    public void setup() {
        // Wave 3 plan 01-W3-01 fills: boot AgePostgresContainer, seed schema,
        // compile shapes, prime Caffeine cache.
    }

    @Benchmark
    public void validate() {
        // Wave 3 plan 01-W3-01 fills: call ShaclValidator.validate on a
        // single-node delta and consume the ValidationReport.
    }
}
