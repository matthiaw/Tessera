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

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 2 — plan 01-W2-02. EVENT-01: confirm {@code graph_events} table is
 * partitioned by RANGE(event_time), carries the three required indexes, and
 * has the full provenance + payload + delta column set. Tessera code never
 * issues DELETE against this table by convention (append-only), but no DB
 * trigger or RLS policy enforces it — the convention is enforced via the
 * single-write-funnel + ArchUnit raw-Cypher ban (CORE-02).
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class EventLogSchemaIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void graph_events_has_three_required_indexes() {
        Long indexCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_indexes
                 WHERE tablename LIKE 'graph_events%'
                   AND indexname IN (
                       'idx_graph_events_model_seq',
                       'idx_graph_events_node_uuid',
                       'idx_graph_events_model_type_time'
                   )
                """,
                Long.class);
        assertThat(indexCount).isEqualTo(3L);
    }

    @Test
    void graph_events_is_range_partitioned_by_event_time() {
        // pg_partitioned_table.partstrat = 'r' means RANGE partitioning.
        Long partCount = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_partitioned_table pt
                  JOIN pg_class c ON c.oid = pt.partrelid
                 WHERE c.relname = 'graph_events'
                   AND pt.partstrat = 'r'
                """,
                Long.class);
        assertThat(partCount).isEqualTo(1L);
    }

    @Test
    void graph_events_has_full_provenance_and_delta_columns() {
        List<String> columns = jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'graph_events'
                """,
                String.class);
        assertThat(Set.copyOf(columns))
                .contains(
                        "id",
                        "model_id",
                        "node_uuid",
                        "sequence_nr",
                        "event_type",
                        "event_time",
                        "type_slug",
                        "payload",
                        "delta",
                        "source_type",
                        "source_id",
                        "source_system",
                        "confidence",
                        "extractor_version",
                        "llm_model_id",
                        "origin_connector_id",
                        "origin_change_id");
    }
}
