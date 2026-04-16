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
 * CONN-04 / CONTEXT Decision 14: DLQ listing endpoint. Returns rows
 * from {@code connector_dlq} scoped to the caller's tenant and the
 * given connector.
 */
@RestController
@RequestMapping("/admin/connectors")
public class ConnectorDlqController {

    private final NamedParameterJdbcTemplate jdbc;

    public ConnectorDlqController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}/dlq")
    public ResponseEntity<?> listDlq(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");

        // Verify connector ownership
        MapSqlParameterSource p = new MapSqlParameterSource("id", id.toString()).addValue("model_id", tenant);
        List<Map<String, Object>> connector =
                jdbc.queryForList("SELECT id FROM connectors WHERE id = :id::uuid AND model_id = :model_id::uuid", p);
        if (connector.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> dlqRows = jdbc.queryForList(
                """
                SELECT id, connector_id, reason, raw_payload, rejection_reason,
                       rejection_detail, rule_id, origin_change_id, created_at
                FROM connector_dlq
                WHERE connector_id = :connector_id AND model_id = :model_id::uuid
                ORDER BY created_at DESC
                LIMIT 100
                """,
                new MapSqlParameterSource("connector_id", id.toString()).addValue("model_id", tenant));

        return ResponseEntity.ok(dlqRows);
    }
}
