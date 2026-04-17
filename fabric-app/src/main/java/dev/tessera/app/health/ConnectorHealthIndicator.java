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

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * OPS-02 / D-B2: Spring Boot Actuator health indicator for connector sync status.
 *
 * <p>Registered as the {@code connectors} health component, accessible at
 * {@code /actuator/health/connectors}.
 *
 * <p>Queries {@code connector_sync_status} joined with {@code connectors} for all enabled
 * connectors. Aggregation logic:
 *
 * <ul>
 *   <li>No enabled connectors — UP with no details.
 *   <li>All connectors have {@code last_outcome='SUCCESS'} — UP with per-connector details.
 *   <li>Any connector has {@code last_outcome='FAILED'} — DOWN (any failure = down).
 * </ul>
 *
 * <p>Health details are protected by {@code management.endpoint.health.show-details:
 * when-authorized} (T-05-01-02 mitigation: connector IDs visible only to authenticated operators).
 */
@Component("connectors")
public class ConnectorHealthIndicator extends AbstractHealthIndicator {

    private static final String STATUS_QUERY =
            """
            SELECT c.id, css.last_outcome, css.last_poll_at
            FROM connectors c
            LEFT JOIN connector_sync_status css ON c.id = css.connector_id
            WHERE c.enabled = TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ConnectorHealthIndicator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<Map<String, Object>> rows = jdbc.queryForList(STATUS_QUERY, new MapSqlParameterSource());

        boolean anyFailed = false;
        for (Map<String, Object> row : rows) {
            String connectorId = String.valueOf(row.get("id"));
            String outcome = String.valueOf(row.get("last_outcome"));
            builder.withDetail(connectorId, outcome);
            if ("FAILED".equals(outcome)) {
                anyFailed = true;
            }
        }

        if (anyFailed) {
            builder.down();
        } else {
            builder.up();
        }
    }
}
