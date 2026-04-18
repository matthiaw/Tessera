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
import java.util.UUID;

/**
 * SCHEMA-01 node type descriptor. Returned by {@link SchemaRegistry#loadFor}.
 *
 * <p>Phase 2 Wave 1 adds {@code restReadEnabled} / {@code restWriteEnabled}
 * (CONTEXT Decision 5). Both default to {@code false} — Wave 2 flips them via
 * the admin schema-expose endpoint to turn on dynamically-generated REST
 * projections for a type. A backwards-compatible 8-arg constructor lets
 * existing Phase 1 call sites (benches, unit tests) keep constructing
 * descriptors without breaking.
 *
 * <p>Phase 10 adds {@code readRoles} / {@code writeRoles} for type-level
 * ACL (SEC-04, SEC-05). {@code null} or empty means the type is visible to
 * all authenticated callers (D-02 semantics).
 */
public record NodeTypeDescriptor(
        UUID modelId,
        String slug,
        String name,
        String label,
        String description,
        long schemaVersion,
        List<PropertyDescriptor> properties,
        Instant deprecatedAt,
        boolean restReadEnabled,
        boolean restWriteEnabled,
        List<String> readRoles,
        List<String> writeRoles) {

    /**
     * Backwards-compatible 10-arg constructor — defaults the Phase 10 ACL
     * role lists to empty (visible/writable by all).
     */
    public NodeTypeDescriptor(
            UUID modelId,
            String slug,
            String name,
            String label,
            String description,
            long schemaVersion,
            List<PropertyDescriptor> properties,
            Instant deprecatedAt,
            boolean restReadEnabled,
            boolean restWriteEnabled) {
        this(
                modelId,
                slug,
                name,
                label,
                description,
                schemaVersion,
                properties,
                deprecatedAt,
                restReadEnabled,
                restWriteEnabled,
                List.of(),
                List.of());
    }

    /**
     * Backwards-compatible 8-arg constructor — defaults both exposure flags
     * and ACL role lists. Lets Phase 1 call sites keep compiling without edit.
     */
    public NodeTypeDescriptor(
            UUID modelId,
            String slug,
            String name,
            String label,
            String description,
            long schemaVersion,
            List<PropertyDescriptor> properties,
            Instant deprecatedAt) {
        this(
                modelId,
                slug,
                name,
                label,
                description,
                schemaVersion,
                properties,
                deprecatedAt,
                false,
                false,
                List.of(),
                List.of());
    }
}
