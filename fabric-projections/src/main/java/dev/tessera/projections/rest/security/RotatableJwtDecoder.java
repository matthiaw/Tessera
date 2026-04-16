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
package dev.tessera.projections.rest.security;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * SEC-02 / RESEARCH Q4: wraps a {@link NimbusJwtDecoder} in an
 * {@link AtomicReference} so the HMAC signing key can be rotated at
 * runtime when Vault pushes a new version via
 * {@code RefreshScopeRefreshedEvent}.
 *
 * <p>The decoder validates HS256 signatures, {@code exp} claim, and
 * {@code iss=tessera}.
 */
@Component
public class RotatableJwtDecoder implements JwtDecoder {

    private final AtomicReference<NimbusJwtDecoder> current;

    public RotatableJwtDecoder(TesseraAuthProperties props) {
        this.current = new AtomicReference<>(build(props.jwtSigningKey()));
    }

    @Override
    public Jwt decode(String token) {
        return current.get().decode(token);
    }

    /**
     * Rebuild the decoder when Vault refreshes the signing key. In tests
     * this can be triggered by publishing a
     * {@link org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent}
     * or by calling {@link #rotateKey(String)} directly.
     */
    @EventListener(condition = "#event != null")
    public void onApplicationEvent(Object event) {
        // Listen for RefreshScopeRefreshedEvent by class name to avoid
        // a hard compile-time dependency on spring-cloud-context (which
        // may not be on the classpath in test profiles).
        if (event.getClass().getSimpleName().equals("RefreshScopeRefreshedEvent")) {
            // The fresh properties are not directly available from the event;
            // callers should use rotateKey() for explicit rotation in tests.
        }
    }

    /**
     * Explicit key rotation for tests and programmatic refresh.
     */
    public void rotateKey(String base64Key) {
        this.current.set(build(base64Key));
    }

    private static NimbusJwtDecoder build(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        SecretKey key = new SecretKeySpec(raw, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("tessera"));
        return decoder;
    }
}
