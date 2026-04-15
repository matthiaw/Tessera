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
 */
public record NodeState(
        UUID uuid, String typeSlug, Map<String, Object> properties, Instant createdAt, Instant updatedAt) {

    public NodeState {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
