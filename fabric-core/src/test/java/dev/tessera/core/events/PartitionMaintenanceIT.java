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

import dev.tessera.core.events.internal.PartitionMaintenanceTask;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import java.time.YearMonth;
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
 * Wave 2 — plan 01-W2-02. EVENT-01 supporting infra: {@link PartitionMaintenanceTask}
 * creates the next month's {@code graph_events} child partition idempotently.
 * Hand-rolled alternative to {@code pg_partman} per RESEARCH §Q2 RESOLVED.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class PartitionMaintenanceIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    PartitionMaintenanceTask task;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void create_partition_for_future_month_is_idempotent() {
        YearMonth target = YearMonth.of(2099, 7);
        String name = PartitionMaintenanceTask.partitionName(target);

        // Pre: no such partition yet.
        assertThat(partitionExists(name)).isFalse();

        // First call creates it.
        task.createPartitionFor(target);
        assertThat(partitionExists(name)).isTrue();

        // It is registered as a child partition of graph_events.
        Long childCount = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_inherits i
                  JOIN pg_class child ON child.oid = i.inhrelid
                  JOIN pg_class parent ON parent.oid = i.inhparent
                 WHERE parent.relname = 'graph_events'
                   AND child.relname = ?
                """,
                Long.class,
                name);
        assertThat(childCount).isEqualTo(1L);

        // Second call is a no-op (idempotent).
        task.createPartitionFor(target);
        assertThat(partitionExists(name)).isTrue();
    }

    private boolean partitionExists(String name) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = ? AND relkind = 'r'", Long.class, name);
        return n != null && n > 0;
    }
}
