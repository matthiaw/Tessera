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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SEC-01 / SEC-02: JWT auth properties loaded from Vault via
 * {@code spring.config.import=vault://secret/tessera/auth} in production,
 * or from {@code application-projection-it.yml} with a static key in tests.
 *
 * <p>{@code jwtSigningKey} is a Base64-encoded HMAC-SHA256 secret.
 * {@code tokenTtlMinutes} defaults to 15 (CONTEXT Decision 6).
 * {@code bootstrapToken} is a one-shot token for the first
 * {@code /admin/tokens/issue} call (CONTEXT Decision 21).
 */
@ConfigurationProperties(prefix = "tessera.auth")
public record TesseraAuthProperties(String jwtSigningKey, int tokenTtlMinutes, String bootstrapToken) {

    public TesseraAuthProperties {
        if (tokenTtlMinutes <= 0) {
            tokenTtlMinutes = 15;
        }
    }
}
