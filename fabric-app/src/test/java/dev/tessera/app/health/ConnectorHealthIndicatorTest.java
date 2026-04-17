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
package dev.tessera.app.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * OPS-02: Unit tests for ConnectorHealthIndicator — per-connector sync status health check.
 *
 * <p>Uses Mockito to return controlled query rows; verifies UP/DOWN aggregation logic.
 */
class ConnectorHealthIndicatorTest {

    private NamedParameterJdbcTemplate jdbc;
    private ConnectorHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        indicator = new ConnectorHealthIndicator(jdbc);
    }

    @Test
    void upWithEmptyDetailsWhenNoEnabledConnectors() {
        when(jdbc.queryForList(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void upWhenAllConnectorsHaveSuccessOutcome() {
        Map<String, Object> row = Map.of("id", "conn-1", "last_outcome", "SUCCESS");
        when(jdbc.queryForList(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of(row));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("conn-1");
    }

    @Test
    void downWhenAnyConnectorHasFailedOutcome() {
        Map<String, Object> row = Map.of("id", "conn-2", "last_outcome", "FAILED");
        when(jdbc.queryForList(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of(row));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("conn-2");
    }

    @Test
    void downWhenMixedSuccessAndFailedConnectors() {
        Map<String, Object> successRow = Map.of("id", "conn-ok", "last_outcome", "SUCCESS");
        Map<String, Object> failedRow = Map.of("id", "conn-fail", "last_outcome", "FAILED");
        when(jdbc.queryForList(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of(successRow, failedRow));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKeys("conn-ok", "conn-fail");
    }
}
