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
package dev.tessera.core.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** OPS-04: EventRetentionJob unit tests — mocked JDBC, no DB required. */
@ExtendWith(MockitoExtension.class)
class EventRetentionJobTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private EventRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new EventRetentionJob(jdbc);
    }

    /** Helper: build a config row map that tolerates null snapshot_boundary. */
    private static Map<String, Object> configRow(UUID modelId, int retentionDays, Timestamp boundary) {
        Map<String, Object> row = new HashMap<>();
        row.put("model_id", modelId);
        row.put("retention_days", retentionDays);
        row.put("snapshot_boundary", boundary); // may be null — HashMap allows null values
        return row;
    }

    /** Test 1: sweep() with no tenants having retention_days set deletes nothing. */
    @Test
    void sweepWithNoTenantsConfiguredDeletesNothing() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        job.sweep();

        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    /** Test 2: sweep() with tenant having retention_days=30 deletes events older than 30 days. */
    @Test
    @SuppressWarnings("unchecked")
    void sweepDeletesEventsOlderThanRetentionDays() {
        UUID modelId = UUID.randomUUID();
        Map<String, Object> row = configRow(modelId, 30, null);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(row));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(5);

        job.sweep();

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(1)).update(anyString(), captor.capture());
        SqlParameterSource params = captor.getValue();
        // model_id and days must be present in the DELETE parameters
        assert params.getValue("model_id") != null;
        assert params.getValue("days") != null;
    }

    /**
     * Test 3: sweep() respects snapshot_boundary — does not delete events at or below
     * snapshot_boundary (boundary is passed as parameter, SQL handles the guard via COALESCE).
     */
    @Test
    @SuppressWarnings("unchecked")
    void sweepRespectedSnapshotBoundary() {
        UUID modelId = UUID.randomUUID();
        Timestamp boundary = Timestamp.from(Instant.now().minusSeconds(86400));
        Map<String, Object> row = configRow(modelId, 30, boundary);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(row));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(3);

        job.sweep();

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(1)).update(anyString(), captor.capture());
        SqlParameterSource params = captor.getValue();
        // snapshot_boundary is passed as a parameter so the SQL WHERE clause can use COALESCE
        assert params.getValue("snapshot_boundary") != null;
    }

    /** Test 4: sweep() with multiple tenants processes each independently. */
    @Test
    @SuppressWarnings("unchecked")
    void sweepProcessesMultipleTenantsIndependently() {
        UUID modelId1 = UUID.randomUUID();
        UUID modelId2 = UUID.randomUUID();
        Map<String, Object> row1 = configRow(modelId1, 7, null);
        Map<String, Object> row2 = configRow(modelId2, 90, null);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(row1, row2));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        job.sweep();

        // One DELETE per tenant
        verify(jdbc, times(2)).update(anyString(), any(SqlParameterSource.class));
    }

    /**
     * Test 5: sweep() does not delete SNAPSHOT event_type events — the DELETE SQL includes an
     * event_type != 'SNAPSHOT' guard clause.
     */
    @Test
    @SuppressWarnings("unchecked")
    void sweepDoesNotDeleteSnapshotEvents() {
        UUID modelId = UUID.randomUUID();
        Map<String, Object> row = configRow(modelId, 30, null);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(row));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        job.sweep();

        // Verify the DELETE SQL string contains 'SNAPSHOT' exclusion
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).update(sqlCaptor.capture(), any(SqlParameterSource.class));
        String deleteSql = sqlCaptor.getValue();
        assert deleteSql.contains("SNAPSHOT") : "DELETE SQL must exclude SNAPSHOT events";
    }
}
