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
package dev.tessera.connectors.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.SyncOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC CRUD for {@code connector_sync_status}. Runs on a
 * {@link Propagation#REQUIRES_NEW} transaction so sync bookkeeping
 * does not couple to the graph mutation TX (Q9 pitfall).
 */
@Component
public class SyncStatusRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SyncStatusRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Update sync status after a poll cycle. Upserts the row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAfterPoll(
            UUID connectorId,
            UUID modelId,
            SyncOutcome outcome,
            String etag,
            String lastModified,
            long eventsDelta,
            long dlqDelta,
            Instant nextPollAt,
            ConnectorState stateBlob) {
        try {
            String stateBlobJson = stateBlob != null ? objectMapper.writeValueAsString(stateBlob) : null;
            Instant now = Instant.now();

            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("connector_id", connectorId.toString());
            p.addValue("model_id", modelId.toString());
            p.addValue("last_poll_at", Timestamp.from(now));
            p.addValue("last_outcome", outcome.name());
            p.addValue("last_etag", etag);
            p.addValue("last_modified", lastModified);
            p.addValue("events_delta", eventsDelta);
            p.addValue("dlq_delta", dlqDelta);
            p.addValue("next_poll_at", nextPollAt != null ? Timestamp.from(nextPollAt) : null);
            p.addValue("state_blob", stateBlobJson);
            p.addValue(
                    "last_success_at",
                    outcome == SyncOutcome.SUCCESS || outcome == SyncOutcome.NO_CHANGE ? Timestamp.from(now) : null);

            jdbc.update(
                    """
                    INSERT INTO connector_sync_status
                        (connector_id, model_id, last_poll_at, last_success_at, last_outcome,
                         last_etag, last_modified, events_processed, dlq_count, next_poll_at, state_blob)
                    VALUES
                        (:connector_id::uuid, :model_id::uuid, :last_poll_at, :last_success_at, :last_outcome,
                         :last_etag, :last_modified, :events_delta, :dlq_delta, :next_poll_at, :state_blob::jsonb)
                    ON CONFLICT (connector_id) DO UPDATE SET
                        last_poll_at = EXCLUDED.last_poll_at,
                        last_success_at = COALESCE(EXCLUDED.last_success_at, connector_sync_status.last_success_at),
                        last_outcome = EXCLUDED.last_outcome,
                        last_etag = EXCLUDED.last_etag,
                        last_modified = EXCLUDED.last_modified,
                        events_processed = connector_sync_status.events_processed + EXCLUDED.events_processed,
                        dlq_count = connector_sync_status.dlq_count + EXCLUDED.dlq_count,
                        next_poll_at = EXCLUDED.next_poll_at,
                        state_blob = EXCLUDED.state_blob
                    """,
                    p);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update sync status for connector " + connectorId, e);
        }
    }

    /**
     * Get the next poll time for a connector. Returns null if no status
     * row exists (first poll).
     */
    public Instant getNextPollAt(UUID connectorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT next_poll_at FROM connector_sync_status WHERE connector_id = :id::uuid",
                new MapSqlParameterSource("id", connectorId.toString()));
        if (rows.isEmpty() || rows.get(0).get("next_poll_at") == null) {
            return null;
        }
        Object val = rows.get(0).get("next_poll_at");
        if (val instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return Instant.parse(val.toString());
    }

    /**
     * Get the full sync status for a connector. Returns null if no row exists.
     */
    public Map<String, Object> getStatus(UUID connectorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM connector_sync_status WHERE connector_id = :id::uuid",
                new MapSqlParameterSource("id", connectorId.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Get the connector state blob for a connector. Returns empty state
     * if no row exists.
     */
    public ConnectorState getState(UUID connectorId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state_blob, last_etag, last_modified FROM connector_sync_status WHERE connector_id = :id::uuid",
                new MapSqlParameterSource("id", connectorId.toString()));
        if (rows.isEmpty()) {
            return ConnectorState.empty();
        }
        Map<String, Object> row = rows.get(0);
        String etag = (String) row.get("last_etag");
        String lastModified = (String) row.get("last_modified");
        // Parse state_blob if present
        Object blob = row.get("state_blob");
        if (blob != null) {
            try {
                ConnectorState state = objectMapper.readValue(blob.toString(), ConnectorState.class);
                // Overlay etag/lastModified from the status row
                return new ConnectorState(
                        state.cursor(),
                        etag != null ? etag : state.etag(),
                        state.lastModified(),
                        state.lastSequence(),
                        state.customState());
            } catch (Exception e) {
                // Fall through to default
            }
        }
        return new ConnectorState(null, etag, null, 0L, Map.of());
    }
}
