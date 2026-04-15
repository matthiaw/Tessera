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
package dev.tessera.core.schema.internal;

import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * SCHEMA-04 helper: reconstruct a historical {@link NodeTypeDescriptor} at a
 * given schema {@code version_nr} by replaying {@code schema_change_event}
 * rows up to that version. Wave 2 pragmatic shortcut: we read the live
 * descriptor from {@code schema_node_types}/{@code schema_properties} and
 * trim its property list down to what existed at {@code versionNr}, where
 * "what existed" is computed by counting net property adds (ADD_PROPERTY
 * minus REMOVE_PROPERTY / DEPRECATE_PROPERTY) in event order up to the
 * target version.
 *
 * <p>Version-nr correlation uses {@code row_number()} over
 * {@code schema_change_event} ordered by {@code event_time}: every change
 * event corresponds to exactly one version bump (the {@code applyChange}
 * contract), so the Nth event (1-indexed) is associated with
 * {@code version_nr = N}. This avoids the earlier string-LIKE heuristic and
 * the event_time-vs-created_at clock skew window.
 */
@Component
public class SchemaChangeReplayer {

    private final SchemaRepository repo;

    public SchemaChangeReplayer(SchemaRepository repo) {
        this.repo = repo;
    }

    public Optional<NodeTypeDescriptor> getAt(TenantContext ctx, String typeSlug, long versionNr) {
        Optional<NodeTypeDescriptor> current = repo.findNodeType(ctx, typeSlug, versionNr);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NodeTypeDescriptor d = current.get();
        int propsAtVersion = netPropertyCountAtOrBefore(ctx, typeSlug, versionNr);
        List<PropertyDescriptor> truncated = d.properties()
                .subList(0, Math.min(Math.max(propsAtVersion, 0), d.properties().size()));
        return Optional.of(new NodeTypeDescriptor(
                d.modelId(), d.slug(), d.name(), d.label(), d.description(), versionNr, truncated, d.deprecatedAt()));
    }

    /**
     * Net property count for {@code typeSlug} at or before {@code versionNr}.
     * Uses row_number over schema_change_event ordered by event_time (each
     * event → exactly one version bump) and a proper JSONB extraction of
     * {@code payload->>'typeSlug'} rather than a LIKE heuristic.
     */
    private int netPropertyCountAtOrBefore(TenantContext ctx, String typeSlug, long versionNr) {
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("model_id", ctx.modelId().toString())
                .addValue("type_slug", typeSlug)
                .addValue("version_nr", versionNr);
        Long net = repo.jdbc()
                .queryForObject(
                        "SELECT COALESCE(SUM(CASE"
                                + "   WHEN change_type = 'ADD_PROPERTY' THEN 1"
                                + "   WHEN change_type IN ('REMOVE_PROPERTY','DEPRECATE_PROPERTY') THEN -1"
                                + "   ELSE 0 END), 0)"
                                + " FROM ("
                                + "   SELECT change_type,"
                                + "          payload->>'typeSlug' AS type_slug,"
                                + "          row_number() OVER (PARTITION BY model_id ORDER BY event_time, id) AS vn"
                                + "   FROM schema_change_event"
                                + "   WHERE model_id = :model_id::uuid"
                                + " ) t"
                                + " WHERE t.vn <= :version_nr"
                                + " AND t.type_slug = :type_slug",
                        params,
                        Long.class);
        return net == null ? 0 : net.intValue();
    }
}
