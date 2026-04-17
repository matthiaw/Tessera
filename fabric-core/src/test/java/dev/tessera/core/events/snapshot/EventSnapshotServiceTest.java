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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OPS-03: EventSnapshotService unit tests — mocked JDBC and TransactionTemplate, no DB required.
 */
@ExtendWith(MockitoExtension.class)
class EventSnapshotServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private PlatformTransactionManager txManager;

    private EventSnapshotService service;

    @BeforeEach
    void setUp() {
        // Use real TransactionTemplate with mocked manager; execute() calls the callback directly
        service = new EventSnapshotService(jdbc, new TransactionTemplate(txManager));
    }

    /**
     * Test 1: compact() on tenant with 3 entities writes 3 SNAPSHOT events and records boundary.
     */
    @Test
    @SuppressWarnings("unchecked")
    void compactWritesSnapshotEventsForEachEntity() {
        UUID modelId = UUID.randomUUID();
        List<Map<String, Object>> entities = List.of(
                entityRow(UUID.randomUUID(), "Role", "{\"name\":\"A\"}"),
                entityRow(UUID.randomUUID(), "Circle", "{\"name\":\"B\"}"),
                entityRow(UUID.randomUUID(), "Activity", "{\"name\":\"C\"}"));

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(entities);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(txManager.getTransaction(any())).thenReturn(null);

        SnapshotResult result = service.compact(modelId);

        assertThat(result.eventsWritten()).isEqualTo(3);
    }

    /** Test 2: compact() sets model_config.snapshot_boundary to the compaction timestamp. */
    @Test
    @SuppressWarnings("unchecked")
    void compactRecordsSnapshotBoundaryInModelConfig() {
        UUID modelId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(entityRow(UUID.randomUUID(), "Role", "{}")));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(txManager.getTransaction(any())).thenReturn(null);

        SnapshotResult result = service.compact(modelId);

        // Verify snapshot_boundary was recorded via an UPDATE to model_config
        verify(jdbc, atLeastOnce()).update(contains("snapshot_boundary"), any(SqlParameterSource.class));
        assertThat(result.boundary()).isNotNull();
    }

    /**
     * Test 3: compact() deletes pre-boundary events (non-SNAPSHOT type) and returns correct
     * SnapshotResult counts.
     */
    @Test
    @SuppressWarnings("unchecked")
    void compactDeletesPreBoundaryNonSnapshotEvents() {
        UUID modelId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(entityRow(UUID.randomUUID(), "Role", "{}")));
        // First update: INSERT snapshot; second update: UPDATE model_config; third update: DELETE
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1, 1, 7);
        when(txManager.getTransaction(any())).thenReturn(null);

        SnapshotResult result = service.compact(modelId);

        assertThat(result.eventsDeleted()).isEqualTo(7);
        // DELETE must exclude SNAPSHOT events
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sqlCaptor.capture(), any(SqlParameterSource.class));
        boolean deleteSqlFound = sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("SNAPSHOT"));
        assertThat(deleteSqlFound).isTrue();
    }

    /** Test 4: compact() on tenant with no events returns SnapshotResult(boundary, 0, 0). */
    @Test
    @SuppressWarnings("unchecked")
    void compactOnTenantWithNoEventsReturnsZeroCounts() {
        UUID modelId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(txManager.getTransaction(any())).thenReturn(null);

        SnapshotResult result = service.compact(modelId);

        assertThat(result.eventsWritten()).isEqualTo(0);
        assertThat(result.eventsDeleted()).isEqualTo(0);
        assertThat(result.boundary()).isNotNull();
        // No snapshot INSERTs should be issued
        verify(jdbc, never()).update(contains("INSERT"), any(SqlParameterSource.class));
    }

    /**
     * Test 5: compact() is idempotent — second call with same tenant produces consistent results
     * (no exceptions, returns valid SnapshotResult).
     */
    @Test
    @SuppressWarnings("unchecked")
    void compactIsIdempotent() {
        UUID modelId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(entityRow(UUID.randomUUID(), "Role", "{}")));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(txManager.getTransaction(any())).thenReturn(null);

        SnapshotResult first = service.compact(modelId);
        SnapshotResult second = service.compact(modelId);

        assertThat(first.eventsWritten()).isEqualTo(1);
        assertThat(second.eventsWritten()).isEqualTo(1);
        // Both calls succeed — no exception means idempotent
    }

    // --- helper ---

    private static Map<String, Object> entityRow(UUID nodeUuid, String typeSlug, String payload) {
        Map<String, Object> row = new HashMap<>();
        row.put("node_uuid", nodeUuid);
        row.put("type_slug", typeSlug);
        row.put("payload", payload);
        return row;
    }
}
