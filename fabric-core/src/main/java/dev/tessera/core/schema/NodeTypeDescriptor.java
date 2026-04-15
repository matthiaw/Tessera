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
        boolean restWriteEnabled) {

    /**
     * Backwards-compatible constructor — defaults the Wave 1 exposure flags to
     * {@code false}. Lets Phase 1 call sites (benches, validation unit tests,
     * Phase 1 cache-invalidation tests) keep compiling without edit.
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
        this(modelId, slug, name, label, description, schemaVersion, properties, deprecatedAt, false, false);
    }
}
