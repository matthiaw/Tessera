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
package dev.tessera.core.graph;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side view of a single graph node, scoped by {@link dev.tessera.core.tenant.TenantContext}
 * upstream. Carries the CORE-06 system properties plus the typed payload.
 *
 * <p>Phase 2 Wave 1 adds {@code seq} — the monotonic BIGINT stamped on every
 * write as the node's {@code _seq} property (CONTEXT Decision 12). A sentinel
 * value of {@code 0} indicates "no sequence allocated" (legacy read paths that
 * reconstruct a NodeState from the stored agtype without an allocator, or
 * tests constructed pre-seq).
 */
public record NodeState(
        UUID uuid, String typeSlug, Map<String, Object> properties, Instant createdAt, Instant updatedAt, long seq) {

    public NodeState {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /**
     * Backwards-compatible 5-arg constructor — Phase 1 call sites that do not
     * thread a sequence (tests that build NodeState directly, legacy read-back
     * helpers that only see agtype rows). Defaults {@code seq} to {@code 0}.
     */
    public NodeState(UUID uuid, String typeSlug, Map<String, Object> properties, Instant createdAt, Instant updatedAt) {
        this(uuid, typeSlug, properties, createdAt, updatedAt, 0L);
    }
}
