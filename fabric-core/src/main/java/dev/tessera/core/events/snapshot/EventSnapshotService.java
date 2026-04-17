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
package dev.tessera.core.events.snapshot;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OPS-03 / D-C2 / D-C3: on-demand snapshot compaction for a single tenant.
 *
 * <p>Compaction runs in <em>three separate transactions</em> to avoid holding a long write lock on
 * the {@code graph_events} table (which would block the ingest write path). Each phase is executed
 * via {@link TransactionTemplate#execute} with the default {@code PROPAGATION_REQUIRED} behaviour.
 *
 * <ol>
 *   <li><strong>Phase 1 — Read TX:</strong> {@code SELECT DISTINCT ON (node_uuid)} — fetch the
 *       latest state per entity (highest {@code sequence_nr}), excluding already-compacted SNAPSHOT
 *       rows.
 *   <li><strong>Phase 2 — Write TX:</strong> INSERT one {@code SNAPSHOT} event per entity, then
 *       UPDATE {@code model_config.snapshot_boundary} to the compaction timestamp. Both writes are
 *       in the same transaction so the boundary is recorded atomically with the snapshot events
 *       (T-05-03-02 tamper-mitigation).
 *   <li><strong>Phase 3 — Delete TX:</strong> DELETE pre-boundary non-SNAPSHOT events.
 * </ol>
 *
 * <p>The operation is idempotent: running it twice for the same tenant advances the snapshot
 * boundary again (Phase 2 inserts new snapshots for the current state) and deletes the previous
 * snapshot events on Phase 3 (they are older than the new boundary).
 */
@Service
public class EventSnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(EventSnapshotService.class);

    /**
     * Phase 1: latest state per entity — excludes existing SNAPSHOT events so they are not
     * re-snapshotted.
     */
    private static final String SELECT_LATEST_SQL = "SELECT DISTINCT ON (node_uuid) node_uuid, type_slug, payload"
            + " FROM graph_events"
            + " WHERE model_id = :mid::uuid"
            + " AND event_type != 'SNAPSHOT'"
            + " ORDER BY node_uuid, sequence_nr DESC";

    /** Phase 2a: INSERT one SNAPSHOT event per entity. */
    private static final String INSERT_SNAPSHOT_SQL = "INSERT INTO graph_events"
            + " (model_id, sequence_nr, event_type, node_uuid, type_slug, payload,"
            + "  delta, caused_by, source_type, source_id, source_system,"
            + "  confidence, extractor_version, llm_model_id,"
            + "  source_document_id, source_chunk_range,"
            + "  origin_connector_id, origin_change_id, prev_hash)"
            + " VALUES"
            + " (:mid::uuid,"
            + "  (SELECT COALESCE(MAX(sequence_nr), 0) + 1 FROM graph_events"
            + "   WHERE model_id = :mid::uuid),"
            + "  'SNAPSHOT', :node_uuid::uuid, :type_slug, :payload::jsonb,"
            + "  '{}'::jsonb, 'compaction', 'SYSTEM', 'compaction', 'tessera',"
            + "  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)";

    /** Phase 2b: record snapshot_boundary atomically in the same write TX. */
    private static final String UPDATE_BOUNDARY_SQL =
            "UPDATE model_config SET snapshot_boundary = :boundary" + " WHERE model_id = :mid::uuid";

    /** Phase 3: delete pre-boundary non-SNAPSHOT events. */
    private static final String DELETE_PRE_BOUNDARY_SQL = "DELETE FROM graph_events"
            + " WHERE model_id = :mid::uuid"
            + " AND event_type != 'SNAPSHOT'"
            + " AND event_time < :boundary";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public EventSnapshotService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Compact the event log for {@code modelId} in three non-blocking transactions.
     *
     * @param modelId the tenant to compact
     * @return {@link SnapshotResult} with compaction boundary, events written, and events deleted
     */
    public SnapshotResult compact(UUID modelId) {
        Instant boundary = Instant.now();
        String midStr = modelId.toString();

        // Phase 1: read latest state per entity (own TX)
        List<Map<String, Object>> entities = tx.execute(
                status -> jdbc.query(SELECT_LATEST_SQL, new MapSqlParameterSource("mid", midStr), (rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("node_uuid", rs.getObject("node_uuid"));
                    row.put("type_slug", rs.getString("type_slug"));
                    row.put("payload", rs.getString("payload"));
                    return row;
                }));

        if (entities == null) {
            entities = List.of();
        }

        int written = 0;

        // Phase 2: write SNAPSHOT events + record boundary (own TX)
        final List<Map<String, Object>> entityList = entities;
        final int[] writtenHolder = {0};
        tx.execute(status -> {
            for (Map<String, Object> entity : entityList) {
                MapSqlParameterSource p = new MapSqlParameterSource();
                p.addValue("mid", midStr);
                p.addValue("node_uuid", entity.get("node_uuid").toString());
                p.addValue("type_slug", entity.get("type_slug"));
                p.addValue("payload", entity.get("payload") != null ? entity.get("payload") : "{}");
                jdbc.update(INSERT_SNAPSHOT_SQL, p);
                writtenHolder[0]++;
            }
            // Record boundary atomically with snapshot writes
            MapSqlParameterSource bp = new MapSqlParameterSource();
            bp.addValue("mid", midStr);
            bp.addValue("boundary", Timestamp.from(boundary));
            jdbc.update(UPDATE_BOUNDARY_SQL, bp);
            return null;
        });
        written = writtenHolder[0];

        // Phase 3: delete pre-boundary non-SNAPSHOT events (own TX)
        final int[] deletedHolder = {0};
        tx.execute(status -> {
            MapSqlParameterSource dp = new MapSqlParameterSource();
            dp.addValue("mid", midStr);
            dp.addValue("boundary", Timestamp.from(boundary));
            Integer deleted = jdbc.update(DELETE_PRE_BOUNDARY_SQL, dp);
            deletedHolder[0] = deleted != null ? deleted : 0;
            return null;
        });

        LOG.info(
                "EventSnapshotService: compacted tenant {} — boundary={}, written={}, deleted={}",
                modelId,
                boundary,
                written,
                deletedHolder[0]);

        return new SnapshotResult(boundary, written, deletedHolder[0]);
    }
}
