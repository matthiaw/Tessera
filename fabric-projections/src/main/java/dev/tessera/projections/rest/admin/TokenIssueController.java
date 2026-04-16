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
package dev.tessera.projections.rest.admin;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.tessera.projections.rest.security.TesseraAuthProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONTEXT Decision 21: bootstrap token issuance endpoint. Mints HS256
 * JWTs signed with the same HMAC key used by the resource server.
 *
 * <p>Protected by the {@code X-Tessera-Bootstrap} header which must match
 * the {@code tessera.auth.bootstrap-token} config value (from Vault in
 * production, static in tests).
 *
 * <p>This is NOT a full OAuth2 authorization server. It covers the
 * bootstrap flow: operator calls this once with the bootstrap token to
 * get an admin JWT, then uses that JWT for everything else.
 */
@RestController
@RequestMapping("/admin/tokens")
public class TokenIssueController {

    private final TesseraAuthProperties authProperties;

    public TokenIssueController(TesseraAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/issue")
    public ResponseEntity<Map<String, Object>> issueToken(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Tessera-Bootstrap", required = false) String bootstrapHeader) {

        // Validate bootstrap token
        if (authProperties.bootstrapToken() == null
                || authProperties.bootstrapToken().isBlank()
                || !authProperties.bootstrapToken().equals(bootstrapHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing bootstrap token"));
        }

        String tenant = (String) body.get("tenant");
        List<String> roles = (List<String>) body.getOrDefault("roles", List.of());

        if (tenant == null || tenant.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenant is required"));
        }

        try {
            byte[] rawKey = Base64.getDecoder().decode(authProperties.jwtSigningKey());
            JWSSigner signer = new MACSigner(rawKey);

            Instant expiresAt = Instant.now().plus(authProperties.tokenTtlMinutes(), ChronoUnit.MINUTES);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("tessera")
                    .subject(tenant)
                    .claim("tenant", tenant)
                    .claim("roles", roles)
                    .expirationTime(Date.from(expiresAt))
                    .issueTime(new Date())
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);

            return ResponseEntity.ok(Map.of("token", jwt.serialize(), "expires_at", expiresAt.toString()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Token signing failed"));
        }
    }
}
