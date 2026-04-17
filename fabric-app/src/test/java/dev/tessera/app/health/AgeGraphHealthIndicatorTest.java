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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * OPS-02: Unit tests for AgeGraphHealthIndicator — AGE graph health check.
 *
 * <p>Uses Mockito to control the JDBC query results; exercises all three expected outcomes:
 * graphs found (UP), AGE loaded but empty (UP), AGE not loaded / exception (DOWN).
 */
class AgeGraphHealthIndicatorTest {

    private NamedParameterJdbcTemplate jdbc;
    private AgeGraphHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        indicator = new AgeGraphHealthIndicator(jdbc);
    }

    @Test
    void upWithGraphCountWhenGraphsExist() {
        when(jdbc.queryForList(
                        anyString(),
                        any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
                        any(Class.class)))
                .thenReturn(List.of("tessera_graph"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("graphs_count", 1);
    }

    @Test
    void upWithZeroCountWhenAgeLoadedButNoGraphs() {
        when(jdbc.queryForList(
                        anyString(),
                        any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
                        any(Class.class)))
                .thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("graphs_count", 0);
    }

    @Test
    void downWhenAgeQueryThrowsException() {
        when(jdbc.queryForList(
                        anyString(),
                        any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
                        any(Class.class)))
                .thenThrow(new org.springframework.dao.DataAccessException("AGE extension not loaded") {});

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
