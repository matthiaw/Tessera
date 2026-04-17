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
package dev.tessera.projections.audit;

import dev.tessera.core.audit.AuditVerificationResult;
import dev.tessera.core.audit.AuditVerificationService;
import dev.tessera.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AUDIT-02 / T-04-S2: on-demand hash-chain audit verification endpoint.
 *
 * <p>Triggers a sequential chain walk for a specific tenant and returns whether
 * the chain is intact. For compliance-sensitive tenants (GxP, SOX, BSI C5) this
 * endpoint provides cryptographic evidence that audit records have not been
 * tampered with after the fact.
 *
 * <p>Tenant isolation: the JWT {@code tenant} claim must equal the requested
 * {@code model_id}. A mismatch returns 403 — same pattern as
 * {@link dev.tessera.projections.mcp.audit.McpAuditController} (T-04-S2).
 *
 * <p>CI usage: {@code curl -X POST /admin/audit/verify?model_id=<uuid>
 * -H "Authorization: Bearer <token>"} against a running instance.
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditVerificationController {

    private final AuditVerificationService verificationService;

    public AuditVerificationController(AuditVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * POST /admin/audit/verify?model_id={uuid}
     *
     * <p>Returns 200 with the verification result (valid or broken-at-seq).
     * Returns 403 if the JWT tenant claim does not match the requested model_id.
     *
     * @param modelId tenant UUID to verify
     * @param jwt     JWT principal for tenant validation
     */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestParam("model_id") UUID modelId, @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        AuditVerificationResult result = verificationService.verify(TenantContext.of(modelId));
        return ResponseEntity.ok(toResponse(result));
    }

    private static Map<String, Object> toResponse(AuditVerificationResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        body.put("events_checked", result.eventsChecked());
        if (!result.valid()) {
            body.put("broken_at_seq", result.brokenAtSeq());
            body.put("expected_hash", result.expectedHash());
            body.put("actual_hash", result.actualHash());
        }
        return body;
    }

    /**
     * Verify that the JWT tenant claim matches the requested model_id.
     * Returns false (403) on any mismatch to prevent cross-tenant audit disclosure (T-04-S2).
     */
    private static boolean isTenantMatch(Jwt jwt, UUID modelId) {
        if (jwt == null) {
            return false;
        }
        String tenant = jwt.getClaimAsString("tenant");
        return modelId.toString().equals(tenant);
    }
}
