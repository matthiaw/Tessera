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
package dev.tessera.core.metrics;

/**
 * Fabric-core SPI for metric emission across module boundaries. Implemented
 * in {@code fabric-app} by {@code TesseraMetricsAdapter}. Keeps
 * {@code fabric-core}, {@code fabric-rules}, and {@code fabric-connectors}
 * free of any direct Micrometer dependency — the dependency direction stays
 * {@code fabric-app → fabric-core}, never the reverse.
 *
 * <p>Callers (ShaclValidator, RuleEngine, ConnectorRunner) inject this port
 * via {@code @Autowired(required = false)} and null-guard every call, so the
 * port is optional in test fixtures that construct beans outside a Spring
 * context.
 *
 * <p>When {@code MetricsPort} is {@code null} (no Spring context or tests),
 * callers silently skip metric emission — correctness is never compromised.
 */
public interface MetricsPort {

    /**
     * Increment the {@code tessera.ingest.rate} counter by 1. Called by
     * {@code ConnectorRunner} once per committed entity.
     */
    void recordIngest();

    /**
     * Increment the {@code tessera.rules.evaluations} counter by 1. Called
     * by {@code RuleEngine} once per pipeline invocation (regardless of
     * outcome).
     */
    void recordRuleEvaluation();

    /**
     * Increment the {@code tessera.conflicts.count} counter by 1. Called
     * by {@code RuleEngine} once per conflict produced by the RECONCILE chain.
     */
    void recordConflict();

    /**
     * Record the duration of a SHACL validation in nanoseconds. Called by
     * {@code ShaclValidator} immediately after the Jena validation call
     * completes (whether or not the mutation conforms).
     *
     * @param nanos elapsed nanoseconds measured by {@code System.nanoTime()}
     */
    void recordShaclValidationNanos(long nanos);
}
