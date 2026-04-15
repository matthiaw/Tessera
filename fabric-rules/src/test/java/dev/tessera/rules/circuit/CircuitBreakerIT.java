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

import com.zaxxer.hikari.HikariDataSource;
import dev.tessera.core.circuit.CircuitBreakerTrippedException;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.support.AgePostgresContainer;
import dev.tessera.rules.support.RulesTestHarness;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration test for the write-amplification circuit breaker (RULE-07 /
 * D-D2 / D-D3). Boots the real AGE Postgres container + migrations so the
 * {@code connector_limits} + {@code connector_dlq} tables are reachable,
 * then exercises the breaker against a per-tenant override of
 * {@code threshold=1} so a tiny burst trips the breaker in the test.
 *
 * <p>Phase 1 note: {@code GraphServiceImpl} has no connector-side queue
 * yet (connectors land in Phase 2), so the DLQ table is always empty on
 * a trip. This test asserts the table shape is reachable and the
 * Micrometer counter fires.
 */
class CircuitBreakerIT {

    private static PostgreSQLContainer<?> pg;
    private static HikariDataSource ds;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void bootContainer() {
        pg = AgePostgresContainer.create();
        pg.start();
        ds = RulesTestHarness.dataSourceFor(pg);
        jdbc = new NamedParameterJdbcTemplate(ds);
    }

    @AfterAll
    static void stopContainer() {
        if (ds != null) {
            ds.close();
        }
        if (pg != null) {
            pg.stop();
        }
    }

    @Test
    void perTenantThresholdOverrideTripsBreakerAndIncrementsCounter() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WriteRateCircuitBreaker breaker = new WriteRateCircuitBreaker(
                jdbc,
                meters, /*defaultThreshold*/
                500, /*graceMs*/
                0L,
                Instant.now().minusSeconds(120));

        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        String connector = "it-breaker-" + UUID.randomUUID();

        // Seed a per-tenant override threshold=1 → breaker trips as soon as
        // sum > 1 * WINDOW_SLOTS = 30 events in the rolling window.
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("connector_id", connector);
        jdbc.update(
                "INSERT INTO connector_limits (model_id, connector_id, window_seconds, threshold) "
                        + "VALUES (:model_id::uuid, :connector_id, 30, 1)",
                p);

        boolean tripped = false;
        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            try {
                breaker.recordAndCheck(ctx, connector);
                accepted++;
            } catch (CircuitBreakerTrippedException e) {
                tripped = true;
                assertThat(e.connectorId()).isEqualTo(connector);
                assertThat(e.modelId()).isEqualTo(ctx.modelId());
                break;
            }
        }

        assertThat(tripped).as("breaker must trip before 200 events").isTrue();
        assertThat(accepted).isGreaterThan(0);
        assertThat(breaker.isHalted(connector, ctx.modelId())).isTrue();

        // Micrometer counter fired exactly once for this (connector, model) pair.
        Counter counter = meters.find(WriteRateCircuitBreaker.COUNTER_TRIPPED)
                .tag("connector", connector)
                .tag("model", ctx.modelId().toString())
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // Subsequent calls throw.
        assertThatThrownBy(() -> breaker.recordAndCheck(ctx, connector))
                .isInstanceOf(CircuitBreakerTrippedException.class);

        // Phase 1 DLQ note: no queued buffer yet, so connector_dlq has 0
        // rows for this trip. The table is reachable — selecting proves the
        // schema is live.
        Integer dlqRows = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM connector_dlq WHERE connector_id = :connector_id",
                new MapSqlParameterSource("connector_id", connector),
                Integer.class);
        assertThat(dlqRows).isEqualTo(0);

        // Admin reset clears the halt.
        CircuitBreakerAdminController admin = new CircuitBreakerAdminController(breaker);
        admin.reset(connector, ctx.modelId());
        assertThat(breaker.isHalted(connector, ctx.modelId())).isFalse();

        // Next call accepted (rolling window also cleared by reset).
        breaker.recordAndCheck(ctx, connector);
    }
}
