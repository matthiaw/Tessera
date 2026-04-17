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
package dev.tessera.app.metrics;

import dev.tessera.core.metrics.MetricsPort;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * OPS-01 / D-B1: Spring {@link Component} adapting the fabric-core
 * {@link MetricsPort} SPI to the concrete Micrometer-backed
 * {@link TesseraMetrics} bean.
 *
 * <p>This adapter lives in {@code fabric-app} (the application assembly
 * module), which is the only module permitted to import Micrometer directly.
 * Callers in {@code fabric-core}, {@code fabric-rules}, and
 * {@code fabric-connectors} inject {@link MetricsPort} and never see
 * Micrometer types directly.
 *
 * <p>All delegation methods are thin pass-throughs — no buffering, no
 * aggregation, no synchronisation needed. Micrometer counters and timers are
 * thread-safe by design.
 */
@Component
public class TesseraMetricsAdapter implements MetricsPort {

    private final TesseraMetrics metrics;

    public TesseraMetricsAdapter(TesseraMetrics metrics) {
        this.metrics = metrics;
    }

    /** Delegates to {@link TesseraMetrics#recordIngest()}. */
    @Override
    public void recordIngest() {
        metrics.recordIngest();
    }

    /** Delegates to {@link TesseraMetrics#recordRuleEvaluation()}. */
    @Override
    public void recordRuleEvaluation() {
        metrics.recordRuleEvaluation();
    }

    /** Delegates to {@link TesseraMetrics#recordConflict()}. */
    @Override
    public void recordConflict() {
        metrics.recordConflict();
    }

    /**
     * Delegates to {@link TesseraMetrics#shaclTimer()}.record(...).
     *
     * @param nanos elapsed nanoseconds to record on the SHACL validation timer
     */
    @Override
    public void recordShaclValidationNanos(long nanos) {
        metrics.shaclTimer().record(nanos, TimeUnit.NANOSECONDS);
    }
}
