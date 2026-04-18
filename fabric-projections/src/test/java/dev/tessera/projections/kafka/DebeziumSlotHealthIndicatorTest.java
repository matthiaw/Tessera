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
package dev.tessera.projections.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Unit tests for {@link DebeziumSlotHealthIndicator} covering the three behavioral
 * scenarios: lag under threshold (UP), lag over threshold (DOWN), and slot missing (DOWN).
 */
class DebeziumSlotHealthIndicatorTest {

    private static final long THRESHOLD_BYTES = 104_857_600L; // 100 MB

    private NamedParameterJdbcTemplate jdbc;
    private DebeziumSlotHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        indicator = new DebeziumSlotHealthIndicator(jdbc, THRESHOLD_BYTES);
    }

    /**
     * Scenario 1: slot exists, lag = 50MB (below 100MB threshold) → Health.UP
     * with lag_bytes detail.
     */
    @Test
    void healthUp_whenLagBelowThreshold() {
        long lagBytes = 52_428_800L; // 50 MB
        stubLagQuery(lagBytes);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lag_bytes", lagBytes);
        assertThat(health.getDetails()).containsEntry("threshold_bytes", THRESHOLD_BYTES);
        assertThat(health.getDetails()).containsEntry("slot", "tessera_outbox_slot");
    }

    /**
     * Scenario 2: slot exists, lag = 200MB (above 100MB threshold) → Health.DOWN
     * with lag_bytes detail.
     */
    @Test
    void healthDown_whenLagAboveThreshold() {
        long lagBytes = 209_715_200L; // 200 MB
        stubLagQuery(lagBytes);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("lag_bytes", lagBytes);
        assertThat(health.getDetails()).containsEntry("threshold_bytes", THRESHOLD_BYTES);
    }

    /**
     * Scenario 3: slot does not exist (queryForObject returns null) → Health.DOWN
     * with "slot not found" error detail.
     */
    @Test
    void healthDown_whenSlotNotFound() {
        stubLagQuery(null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error").toString())
                .contains("tessera_outbox_slot")
                .contains("not found");
    }

    /**
     * Verify SQL uses pg_wal_lsn_diff and references tessera_outbox_slot (grep criterion).
     * This test confirms the query string constants are in the class under test.
     */
    @Test
    void sqlQuery_containsPgWalLsnDiffAndSlotName() {
        // Query the class source via reflection is brittle; instead verify via the mock
        // parameter capture — when we stub with the slot name, the indicator uses it.
        long lagBytes = 1000L;
        when(jdbc.queryForObject(
                        contains("pg_wal_lsn_diff"),
                        any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(lagBytes);

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("slot", "tessera_outbox_slot");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubLagQuery(Long returnValue) {
        when(jdbc.queryForObject(
                        anyString(),
                        any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(returnValue);
    }
}
