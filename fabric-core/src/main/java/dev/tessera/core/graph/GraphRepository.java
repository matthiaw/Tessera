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

import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-aware read entry point to the graph. {@link TenantContext} is a
 * mandatory explicit first parameter on every call (CORE-03, D-16) — never a
 * ThreadLocal, never a session-scoped bean.
 */
public interface GraphRepository {

    Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid);

    List<NodeState> queryAll(TenantContext ctx, String typeSlug);
}
