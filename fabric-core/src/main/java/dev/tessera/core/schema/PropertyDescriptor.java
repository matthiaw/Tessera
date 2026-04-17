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
import java.util.List;

/**
 * SCHEMA-02 property descriptor. Immutable record used by the Caffeine cache.
 *
 * <p>Phase 2 Wave 1 adds {@code encrypted} / {@code encryptedAlg} (CONTEXT
 * Decision 2). Both default to neutral values ({@code false} / {@code null}).
 * The SEC-06 startup guard refuses to boot if any row in the Schema Registry
 * has {@code encrypted=true} while the {@code tessera.security.field-encryption.enabled}
 * feature flag is off.
 *
 * <p>Phase 10 adds {@code readRoles} / {@code writeRoles} for field-level
 * ACL (SEC-04, SEC-05). {@code null} or empty means visible/writable by all
 * authenticated callers (D-02 semantics).
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
        String encryptedAlg,
        List<String> readRoles,
        List<String> writeRoles) {

    /**
     * Backwards-compatible 11-arg constructor — defaults the Phase 10 ACL
     * role lists to empty (visible/writable by all).
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
            Instant deprecatedAt,
            boolean encrypted,
            String encryptedAlg) {
        this(slug, name, dataType, required, defaultValue, validationRules,
             enumValues, referenceTarget, deprecatedAt, encrypted, encryptedAlg,
             List.of(), List.of());
    }

    /**
     * Backwards-compatible 9-arg constructor — defaults both encryption flags
     * and ACL role lists. Lets Phase 1 call sites keep compiling unchanged.
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
        this(slug, name, dataType, required, defaultValue, validationRules,
             enumValues, referenceTarget, deprecatedAt, false, null,
             List.of(), List.of());
    }
}
