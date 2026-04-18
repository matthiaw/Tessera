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
package dev.tessera.projections.sql;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL-01 / T-04-S1: Admin endpoint for listing and monitoring SQL view projections.
 *
 * <p>Requires {@code ROLE_ADMIN} — enforced by {@code SecurityConfig} for all {@code /admin/**}
 * routes. Tenant isolation: the JWT {@code tenant} claim must equal the requested
 * {@code model_id} (T-04-S1 mitigation, same pattern as McpAuditController).
 */
@RestController
@RequestMapping("/admin/sql")
public class SqlViewAdminController {

    private final SqlViewProjection sqlViewProjection;

    public SqlViewAdminController(SqlViewProjection sqlViewProjection) {
        this.sqlViewProjection = sqlViewProjection;
    }

    /**
     * GET /admin/sql/views?model_id={uuid} — list active SQL views for a tenant.
     *
     * @param modelId tenant UUID
     * @param jwt     JWT principal for tenant validation
     * @return list of active views with metadata, or 403 on tenant mismatch
     */
    @GetMapping("/views")
    public ResponseEntity<Map<String, Object>> listViews(
            @RequestParam("model_id") UUID modelId, @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        List<SqlViewProjection.ViewMetadata> views = sqlViewProjection.listViews(modelId);
        List<Map<String, Object>> viewsList = views.stream()
                .map(v -> Map.<String, Object>of(
                        "view_name", v.viewName(),
                        "model_id", v.modelId().toString(),
                        "type_slug", v.typeSlug(),
                        "schema_version", v.schemaVersion(),
                        "generated_at", v.generatedAt().toString()))
                .toList();

        return ResponseEntity.ok(Map.of("views", viewsList, "count", viewsList.size()));
    }

    /**
     * Verify that the JWT tenant claim matches the requested model_id.
     * Returns false (→ 403) on any mismatch to prevent cross-tenant view disclosure.
     */
    private static boolean isTenantMatch(Jwt jwt, UUID modelId) {
        if (jwt == null) {
            return false;
        }
        String tenant = jwt.getClaimAsString("tenant");
        return modelId.toString().equals(tenant);
    }
}
