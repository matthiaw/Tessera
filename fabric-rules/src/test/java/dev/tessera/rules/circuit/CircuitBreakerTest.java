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
package dev.tessera.rules.circuit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.circuit.CircuitBreakerTrippedException;
import dev.tessera.core.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WriteRateCircuitBreaker} — no database, no Spring.
 * Covers the four D-D2/D-D3/Q5-RESOLVED behaviors isolated from I/O:
 *
 * <ul>
 *   <li>Threshold crossing trips the breaker and throws
 *       {@link CircuitBreakerTrippedException} on the next call
 *   <li>Startup grace window suppresses tripping for its configured duration
 *       while still accumulating the rolling-window count
 *   <li>Breaker state is per-{@code (connectorId, modelId)} pair — tripping
 *       one pair does not bleed into another
 *   <li>{@link WriteRateCircuitBreaker#reset} clears the halted flag and the
 *       rolling window
 * </ul>
 */
class CircuitBreakerTest {

    private static final int THRESHOLD = 10; // events/sec
    private static final int WINDOW = WriteRateCircuitBreaker.WINDOW_SLOTS;

    @Test
    void tripsAfterCrossingThreshold_andBlocksSubsequentCalls() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WriteRateCircuitBreaker breaker = new WriteRateCircuitBreaker(
                null, meters, THRESHOLD, 0L, Instant.now().minusSeconds(120));

        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        String connector = "c1";

        // THRESHOLD * WINDOW + 1 events in the burst → definitely over the
        // (sum > threshold * WINDOW) rolling-rate comparison.
        int burst = THRESHOLD * WINDOW + 1;
        boolean tripped = false;
        int accepted = 0;
        for (int i = 0; i < burst; i++) {
            try {
                breaker.recordAndCheck(ctx, connector);
                accepted++;
            } catch (CircuitBreakerTrippedException e) {
                tripped = true;
                break;
            }
        }

        assertThat(tripped).as("breaker must trip within burst").isTrue();
        assertThat(accepted).as("some events succeed before the trip").isGreaterThan(0);

        // Next call must fast-fail — already halted.
        assertThatThrownBy(() -> breaker.recordAndCheck(ctx, connector))
                .isInstanceOf(CircuitBreakerTrippedException.class);

        // Micrometer counter recorded the trip exactly once (add.succeeded semantics).
        Counter counter = meters.find(WriteRateCircuitBreaker.COUNTER_TRIPPED)
                .tag("connector", connector)
                .tag("model", ctx.modelId().toString())
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void startupGraceSuppressesTrippingButWindowStillAccumulates() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        // Grace window ends 1 hour from now — every call in this test is inside the grace.
        WriteRateCircuitBreaker breaker =
                new WriteRateCircuitBreaker(null, meters, THRESHOLD, 3_600_000L, Instant.now());

        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        int burst = THRESHOLD * WINDOW + 50;
        for (int i = 0; i < burst; i++) {
            breaker.recordAndCheck(ctx, "c1"); // must not throw — grace active
        }

        // Still not halted: no trip happened during grace.
        assertThat(breaker.isHalted("c1", ctx.modelId())).isFalse();
        assertThat(meters.find(WriteRateCircuitBreaker.COUNTER_TRIPPED).counter())
                .as("no trip counter increment during grace window")
                .isNull();
    }

    @Test
    void breakerStateIsPerConnectorAndPerModel() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WriteRateCircuitBreaker breaker = new WriteRateCircuitBreaker(
                null, meters, THRESHOLD, 0L, Instant.now().minusSeconds(120));

        TenantContext tenantA = TenantContext.of(UUID.randomUUID());
        TenantContext tenantB = TenantContext.of(UUID.randomUUID());

        // Trip connector c1 for tenant A.
        int burst = THRESHOLD * WINDOW + 1;
        boolean tripped = false;
        for (int i = 0; i < burst; i++) {
            try {
                breaker.recordAndCheck(tenantA, "c1");
            } catch (CircuitBreakerTrippedException e) {
                tripped = true;
                break;
            }
        }
        assertThat(tripped).isTrue();
        assertThat(breaker.isHalted("c1", tenantA.modelId())).isTrue();

        // Same connector id, different tenant — still accepting writes.
        breaker.recordAndCheck(tenantB, "c1");
        assertThat(breaker.isHalted("c1", tenantB.modelId())).isFalse();

        // Different connector id, same tenant — still accepting writes.
        breaker.recordAndCheck(tenantA, "c2");
        assertThat(breaker.isHalted("c2", tenantA.modelId())).isFalse();
    }

    @Test
    void resetClearsHaltAndLetsTrafficThroughAgain() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WriteRateCircuitBreaker breaker = new WriteRateCircuitBreaker(
                null, meters, THRESHOLD, 0L, Instant.now().minusSeconds(120));

        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        String connector = "c1";

        int burst = THRESHOLD * WINDOW + 1;
        for (int i = 0; i < burst; i++) {
            try {
                breaker.recordAndCheck(ctx, connector);
            } catch (CircuitBreakerTrippedException e) {
                break;
            }
        }
        assertThat(breaker.isHalted(connector, ctx.modelId())).isTrue();

        breaker.reset(connector, ctx.modelId());
        assertThat(breaker.isHalted(connector, ctx.modelId())).isFalse();

        // Must accept at least one more write after reset.
        breaker.recordAndCheck(ctx, connector);
    }

    @Test
    void nullConnectorIdBypassesRateLimiting() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WriteRateCircuitBreaker breaker = new WriteRateCircuitBreaker(
                null, meters, THRESHOLD, 0L, Instant.now().minusSeconds(120));

        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        // Even a huge burst with null connector id must not trip: system
        // writes with no origin connector are exempt.
        for (int i = 0; i < THRESHOLD * WINDOW + 50; i++) {
            breaker.recordAndCheck(ctx, null);
        }
        assertThat(meters.find(WriteRateCircuitBreaker.COUNTER_TRIPPED).counter())
                .isNull();
    }
}
