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
package dev.tessera.core.circuit;

import dev.tessera.core.tenant.TenantContext;

/**
 * Fabric-core SPI for the write-amplification circuit breaker (RULE-07 /
 * CONTEXT §D-D2, §D-D3). Implemented in {@code fabric-rules} by
 * {@code WriteRateCircuitBreaker}. Kept as a port so fabric-core does not
 * depend on fabric-rules — the dependency direction stays
 * {@code fabric-rules → fabric-core}.
 *
 * <p>{@code GraphServiceImpl} calls {@link #recordAndCheck(TenantContext, String)}
 * at the entry of {@code apply()}, BEFORE schema load, so trips short-
 * circuit the pipeline at its cheapest point. Legacy test harnesses may
 * wire a {@code null} port and skip the breaker entirely.
 */
public interface CircuitBreakerPort {

    /**
     * Record a mutation and evaluate tripping state for the given
     * {@code (connectorId, modelId)} pair. Implementations MUST:
     *
     * <ol>
     *   <li>If the pair is already halted, throw {@link CircuitBreakerTrippedException}
     *   <li>Increment the rolling window
     *   <li>Evaluate {@code shouldTrip} — if true, trip the breaker (halt,
     *       increment {@code tessera.circuit.tripped} counter, DLQ any
     *       passed queue), then throw {@link CircuitBreakerTrippedException}
     * </ol>
     *
     * During the startup grace window the breaker MUST accumulate the rolling
     * window but MUST NOT trip (RESEARCH §"Open Questions Q5 RESOLVED").
     *
     * <p>{@code connectorId} may be {@code null} — system writes with no
     * origin connector are exempt from rate limiting (the breaker returns
     * immediately without any state change).
     */
    void recordAndCheck(TenantContext ctx, String connectorId) throws CircuitBreakerTrippedException;
}
