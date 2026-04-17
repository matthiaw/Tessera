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
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * OPS-02 / D-B2: Spring Boot Actuator health indicator for Apache AGE graph availability.
 *
 * <p>Registered as the {@code ageGraph} health component, accessible at
 * {@code /actuator/health/ageGraph}.
 *
 * <p>Queries {@code ag_catalog.ag_graph} to verify that the AGE extension is loaded and
 * reachable. Outcomes:
 *
 * <ul>
 *   <li>Query returns rows — UP with {@code graphs_count} = number of graphs found.
 *   <li>Query returns empty list — UP with {@code graphs_count=0} (AGE loaded, no graphs yet).
 *   <li>Query throws exception — DOWN (AGE extension not installed or not loaded).
 * </ul>
 *
 * <p>Health details are protected by {@code management.endpoint.health.show-details:
 * when-authorized} in application.yml (T-05-01-01 accepted risk: only graph count exposed).
 */
@Component("ageGraph")
public class AgeGraphHealthIndicator extends AbstractHealthIndicator {

    private static final String GRAPHS_QUERY = "SELECT name FROM ag_catalog.ag_graph";

    private final NamedParameterJdbcTemplate jdbc;

    public AgeGraphHealthIndicator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<String> graphs = jdbc.queryForList(GRAPHS_QUERY, new MapSqlParameterSource(), String.class);
        builder.up().withDetail("graphs_count", graphs.size());
    }
}
