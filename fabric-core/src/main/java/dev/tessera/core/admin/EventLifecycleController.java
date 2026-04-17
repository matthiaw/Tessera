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
package dev.tessera.core.admin;

import dev.tessera.core.events.snapshot.EventSnapshotService;
import dev.tessera.core.events.snapshot.SnapshotResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OPS-03 / OPS-04 / T-05-03-01 / T-05-03-04: admin endpoints for event-log lifecycle operations.
 *
 * <p>All endpoints enforce a JWT tenant-match guard (same pattern as {@code McpAuditController}):
 * the {@code tenant} claim in the JWT must equal the requested {@code model_id}. A mismatch returns
 * 403 to prevent cross-tenant snapshot or retention disclosure.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /admin/events/snapshot} — trigger three-phase compaction
 *   <li>{@code GET /admin/events/retention} — read retention config for a tenant
 *   <li>{@code PUT /admin/events/retention} — update retention_days for a tenant
 * </ul>
 */
@RestController
@RequestMapping("/admin/events")
public class EventLifecycleController {

    private final EventSnapshotService snapshotService;
    private final NamedParameterJdbcTemplate jdbc;

    public EventLifecycleController(EventSnapshotService snapshotService, NamedParameterJdbcTemplate jdbc) {
        this.snapshotService = snapshotService;
        this.jdbc = jdbc;
    }

    /**
     * POST /admin/events/snapshot — trigger snapshot compaction for a tenant.
     *
     * <p>Runs three-phase compaction via {@link EventSnapshotService#compact(UUID)}. The operation
     * is non-blocking (each phase in a separate TX) and idempotent.
     *
     * @param modelId tenant UUID
     * @param jwt JWT principal — {@code tenant} claim must equal {@code modelId}
     * @return 200 with SnapshotResult as JSON map, or 403 on tenant mismatch
     */
    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> triggerSnapshot(
            @RequestParam("model_id") UUID modelId, @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        SnapshotResult result = snapshotService.compact(modelId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_id", modelId.toString());
        body.put("snapshot_boundary", result.boundary().toString());
        body.put("events_written", result.eventsWritten());
        body.put("events_deleted", result.eventsDeleted());
        return ResponseEntity.ok(body);
    }

    /**
     * GET /admin/events/retention — read current retention config for a tenant.
     *
     * @param modelId tenant UUID
     * @param jwt JWT principal — {@code tenant} claim must equal {@code modelId}
     * @return 200 with retention_days and snapshot_boundary, or 403 on tenant mismatch
     */
    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> getRetention(
            @RequestParam("model_id") UUID modelId, @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        MapSqlParameterSource p = new MapSqlParameterSource("mid", modelId.toString());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT retention_days, snapshot_boundary FROM model_config WHERE model_id = :mid::uuid", p);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_id", modelId.toString());
        if (!rows.isEmpty()) {
            body.put("retention_days", rows.get(0).get("retention_days"));
            Object sb = rows.get(0).get("snapshot_boundary");
            body.put("snapshot_boundary", sb != null ? sb.toString() : null);
        } else {
            body.put("retention_days", null);
            body.put("snapshot_boundary", null);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * PUT /admin/events/retention — update retention_days for a tenant.
     *
     * @param modelId tenant UUID
     * @param days new retention window in days (must be positive)
     * @param jwt JWT principal — {@code tenant} claim must equal {@code modelId}
     * @return 200 with updated config, or 403 on tenant mismatch
     */
    @PutMapping("/retention")
    public ResponseEntity<Map<String, Object>> updateRetention(
            @RequestParam("model_id") UUID modelId,
            @RequestParam("retention_days") Integer days,
            @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("days", days);
        p.addValue("mid", modelId.toString());
        jdbc.update("UPDATE model_config SET retention_days = :days WHERE model_id = :mid::uuid", p);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_id", modelId.toString());
        body.put("retention_days", days);
        return ResponseEntity.ok(body);
    }

    /**
     * Verify that the JWT {@code tenant} claim matches the requested model_id.
     * Returns false (→ 403) on any mismatch to prevent cross-tenant lifecycle operations.
     */
    private static boolean isTenantMatch(Jwt jwt, UUID modelId) {
        if (jwt == null) {
            return false;
        }
        String tenant = jwt.getClaimAsString("tenant");
        return modelId.toString().equals(tenant);
    }
}
