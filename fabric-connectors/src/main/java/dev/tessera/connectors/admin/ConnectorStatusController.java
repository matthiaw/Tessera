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
package dev.tessera.connectors.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONN-06 / CONTEXT Decision 19: sync status endpoint for operator
 * dashboards. Returns last poll time, outcome, DLQ count, events
 * processed. Tenant-scoped via JWT claim.
 */
@RestController
@RequestMapping("/admin/connectors")
public class ConnectorStatusController {

    private final NamedParameterJdbcTemplate jdbc;

    public ConnectorStatusController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> getStatus(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");

        // Verify connector ownership
        MapSqlParameterSource p = new MapSqlParameterSource("id", id.toString()).addValue("model_id", tenant);
        List<Map<String, Object>> connector =
                jdbc.queryForList("SELECT id FROM connectors WHERE id = :id::uuid AND model_id = :model_id::uuid", p);
        if (connector.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> status = jdbc.queryForList(
                """
                SELECT connector_id, last_poll_at, last_success_at, last_outcome,
                       events_processed, dlq_count, next_poll_at
                FROM connector_sync_status WHERE connector_id = :id::uuid
                """,
                new MapSqlParameterSource("id", id.toString()));

        if (status.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "connector_id",
                    id.toString(),
                    "last_poll_at",
                    "",
                    "last_outcome",
                    "NEVER_POLLED",
                    "events_processed",
                    0,
                    "dlq_count",
                    0));
        }

        return ResponseEntity.ok(status.get(0));
    }
}
