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
package dev.tessera.core.schema;

import java.time.Instant;

/**
 * SCHEMA-02 property descriptor. Immutable record used by the Caffeine cache.
 *
 * <p>Phase 2 Wave 1 adds {@code encrypted} / {@code encryptedAlg} (CONTEXT
 * Decision 2). Both default to neutral values ({@code false} / {@code null}).
 * The SEC-06 startup guard refuses to boot if any row in the Schema Registry
 * has {@code encrypted=true} while the {@code tessera.security.field-encryption.enabled}
 * feature flag is off.
 */
public record PropertyDescriptor(
        String slug,
        String name,
        String dataType,
        boolean required,
        String defaultValue,
        String validationRules,
        String enumValues,
        String referenceTarget,
        Instant deprecatedAt,
        boolean encrypted,
        String encryptedAlg) {

    /**
     * Backwards-compatible constructor — defaults the Wave 1 encryption flags
     * to {@code false} / {@code null}. Lets Phase 1 call sites keep compiling
     * unchanged.
     */
    public PropertyDescriptor(
            String slug,
            String name,
            String dataType,
            boolean required,
            String defaultValue,
            String validationRules,
            String enumValues,
            String referenceTarget,
            Instant deprecatedAt) {
        this(
                slug,
                name,
                dataType,
                required,
                defaultValue,
                validationRules,
                enumValues,
                referenceTarget,
                deprecatedAt,
                false,
                null);
    }
}
