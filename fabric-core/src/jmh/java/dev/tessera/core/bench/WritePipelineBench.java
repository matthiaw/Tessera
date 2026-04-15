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
 * Wave 1 / Wave 3 — plans 01-W1-02 / 01-W3-03. Full write-pipeline p95 via
 * {@code GraphService.apply}. Wave 1 seeds the BASELINE number (no SHACL,
 * no rules, p95 &lt; 3 ms warning-only); Wave 3 flips to the FULL-PIPELINE
 * gate (SHACL + rules, p95 &lt; 11 ms build-fail).
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class WritePipelineBench {

    @Setup(Level.Trial)
    public void setup() {
        // Wave 1 plan 01-W1-02 fills: boot AgePostgresContainer, Flyway,
        // GraphService bean, warm pool.
    }

    @Benchmark
    public void apply() {
        // Wave 1 fills with a BASELINE call; Wave 3 upgrades to full pipeline.
    }
}
