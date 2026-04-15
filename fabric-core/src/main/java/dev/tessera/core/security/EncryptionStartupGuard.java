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
package dev.tessera.core.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SEC-06 / CONTEXT Decision 2: fail-closed startup guard for field-level
 * encryption.
 *
 * <p>If {@code tessera.security.field-encryption.enabled=false} AND any
 * {@code schema_properties} row has {@code property_encrypted=true}, the
 * Spring context refuses to start. This closes the "ships with encryption
 * disabled but schema declares it" footgun — silent-skip is explicitly
 * rejected by Decision 2 as security theater.
 *
 * <p>The guard runs on {@code @PostConstruct} so it fires after the
 * {@link NamedParameterJdbcTemplate} + Flyway migrations but before
 * application-ready listeners. If the flag is enabled, the guard becomes a
 * no-op. Wave-later work ships the real encryption machinery that removes
 * the bypass.
 */
@Component
public class EncryptionStartupGuard {

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean fieldEncryptionEnabled;

    public EncryptionStartupGuard(
            NamedParameterJdbcTemplate jdbc,
            @Value("${tessera.security.field-encryption.enabled:false}") boolean fieldEncryptionEnabled) {
        this.jdbc = jdbc;
        this.fieldEncryptionEnabled = fieldEncryptionEnabled;
    }

    @PostConstruct
    public void verify() {
        if (fieldEncryptionEnabled) {
            // Encryption machinery is on; a property_encrypted=true row is fine.
            return;
        }
        Integer count = jdbc.getJdbcTemplate()
                .queryForObject(
                        "SELECT COUNT(*) FROM schema_properties WHERE property_encrypted = TRUE", Integer.class);
        if (count != null && count > 0) {
            throw new IllegalStateException(
                    "Field-level encryption is disabled (tessera.security.field-encryption.enabled=false)"
                            + " but schema_properties contains "
                            + count
                            + " row(s) with property_encrypted=TRUE. Enable the flag or remove the marker."
                            + " See CONTEXT.md Decision 2.");
        }
    }
}
