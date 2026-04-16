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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.ConnectorMutatedEvent;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.MappingDefinitionValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONN-03 / CONTEXT Decision 7 + 15: CRUD endpoints for connector
 * instances. Scoped to the caller's JWT {@code tenant} claim.
 * Publishes {@link ConnectorMutatedEvent} on every mutation for
 * {@code ConnectorRegistry} hot-reload.
 *
 * <p>Placed in fabric-connectors (not fabric-projections) because
 * the ArchUnit gate prevents projections from depending on connectors.
 */
@RestController
@RequestMapping("/admin/connectors")
public class ConnectorAdminController {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ConnectorAdminController(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null) {
            return badRequest("Missing tenant claim");
        }

        String type = (String) body.get("type");
        String authType = (String) body.getOrDefault("authType", "BEARER");
        String credentialsRef = (String) body.get("credentialsRef");
        int pollInterval =
                body.containsKey("pollIntervalSeconds") ? ((Number) body.get("pollIntervalSeconds")).intValue() : 60;

        MappingDefinition mapping;
        try {
            mapping = objectMapper.convertValue(body.get("mappingDef"), MappingDefinition.class);
        } catch (Exception e) {
            return badRequest("Invalid mappingDef: " + e.getMessage());
        }

        List<String> errors = MappingDefinitionValidator.validate(mapping, authType, pollInterval);
        if (!errors.isEmpty()) {
            return badRequest(String.join("; ", errors));
        }

        // Validate credentials_ref pattern (T-02-W3-03)
        if (credentialsRef == null || credentialsRef.isBlank()) {
            return badRequest("credentialsRef must not be blank");
        }

        UUID id = UUID.randomUUID();
        try {
            String mappingJson = objectMapper.writeValueAsString(mapping);
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("id", id.toString());
            p.addValue("model_id", tenant);
            p.addValue("type", type);
            p.addValue("mapping_def", mappingJson);
            p.addValue("auth_type", authType.toUpperCase());
            p.addValue("credentials_ref", credentialsRef);
            p.addValue("poll_interval_seconds", pollInterval);

            jdbc.update(
                    """
                    INSERT INTO connectors (id, model_id, type, mapping_def, auth_type,
                        credentials_ref, poll_interval_seconds)
                    VALUES (:id::uuid, :model_id::uuid, :type, :mapping_def::jsonb,
                        :auth_type, :credentials_ref, :poll_interval_seconds)
                    """,
                    p);

            eventPublisher.publishEvent(new ConnectorMutatedEvent(id, false));

            Map<String, Object> result = Map.of(
                    "id", id.toString(),
                    "type", type,
                    "authType", authType,
                    "pollIntervalSeconds", pollInterval,
                    "enabled", true);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create connector"));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type, auth_type, poll_interval_seconds, enabled, created_at, updated_at"
                        + " FROM connectors WHERE model_id = :model_id::uuid",
                new MapSqlParameterSource("model_id", tenant));
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM connectors WHERE id = :id::uuid AND model_id = :model_id::uuid",
                new MapSqlParameterSource("id", id.toString()).addValue("model_id", tenant));
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rows.get(0));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable UUID id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");

        // Verify ownership
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM connectors WHERE id = :id::uuid AND model_id = :model_id::uuid",
                new MapSqlParameterSource("id", id.toString()).addValue("model_id", tenant));
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        int pollInterval =
                body.containsKey("pollIntervalSeconds") ? ((Number) body.get("pollIntervalSeconds")).intValue() : 60;
        Boolean enabled = body.containsKey("enabled") ? (Boolean) body.get("enabled") : null;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id.toString());
        p.addValue("poll_interval_seconds", pollInterval);
        p.addValue("enabled", enabled);
        p.addValue("updated_at", java.sql.Timestamp.from(java.time.Instant.now()));

        jdbc.update(
                """
                UPDATE connectors SET
                    poll_interval_seconds = COALESCE(:poll_interval_seconds, poll_interval_seconds),
                    enabled = COALESCE(:enabled, enabled),
                    updated_at = :updated_at
                WHERE id = :id::uuid
                """,
                p);

        eventPublisher.publishEvent(new ConnectorMutatedEvent(id, false));
        return ResponseEntity.ok(Map.of("id", id.toString(), "updated", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM connectors WHERE id = :id::uuid AND model_id = :model_id::uuid",
                new MapSqlParameterSource("id", id.toString()).addValue("model_id", tenant));
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        jdbc.update("DELETE FROM connectors WHERE id = :id::uuid", new MapSqlParameterSource("id", id.toString()));

        eventPublisher.publishEvent(new ConnectorMutatedEvent(id, true));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> badRequest(String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        return ResponseEntity.badRequest().body(pd);
    }
}
