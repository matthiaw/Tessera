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
package dev.tessera.core.graph.internal;

import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link GraphRepository} implementation — delegates every call to
 * {@link GraphSession} so all Cypher lives in one place (CORE-02). The
 * {@link TenantContext} argument is mandatory on every method; the session
 * injects {@code model_id} into the Cypher WHERE clause.
 */
public final class GraphRepositoryImpl implements GraphRepository {

    private final GraphSession session;

    public GraphRepositoryImpl(GraphSession session) {
        this.session = session;
    }

    @Override
    public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) {
        return session.findNode(ctx, typeSlug, nodeUuid);
    }

    @Override
    public List<NodeState> queryAll(TenantContext ctx, String typeSlug) {
        return session.queryAllNodes(ctx, typeSlug);
    }
}
