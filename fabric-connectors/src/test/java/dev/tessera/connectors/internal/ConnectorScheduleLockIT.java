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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 02-W3-01: Integration test proving ShedLock per-connector_id isolation.
 * Two concurrent LockingTaskExecutor calls for the SAME connector-id:
 * ShedLock ensures only one executes. Different connector-ids run
 * concurrently. No Spring context needed -- pure JDBC + ShedLock.
 */
@Testcontainers
class ConnectorScheduleLockIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera");

    private static LockingTaskExecutor executor;

    @BeforeAll
    static void initSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS shedlock (
                    name       VARCHAR(64)  NOT NULL,
                    lock_until TIMESTAMP    NOT NULL,
                    locked_at  TIMESTAMP    NOT NULL,
                    locked_by  VARCHAR(255) NOT NULL,
                    PRIMARY KEY (name)
                )
                """);
        executor = new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(ds));
    }

    @Test
    void shedlock_prevents_double_poll_for_same_connector() throws Exception {
        UUID connectorId = UUID.randomUUID();
        String lockName = "connector-" + connectorId;
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch insideLock = new CountDownLatch(1);
        CountDownLatch bothDone = new CountDownLatch(2);

        Instant now = Instant.now();
        Duration atMostFor = Duration.ofSeconds(10);
        Duration atLeastFor = Duration.ofSeconds(3);
        LockConfiguration config = new LockConfiguration(now, lockName, atMostFor, atLeastFor);

        // First task: acquires lock, signals, then sleeps
        Thread.ofVirtual().start(() -> {
            try {
                executor.executeWithLock(
                        (Runnable) () -> {
                            executionCount.incrementAndGet();
                            insideLock.countDown();
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        config);
            } finally {
                bothDone.countDown();
            }
        });

        // Wait for first task to acquire lock
        assertThat(insideLock.await(5, TimeUnit.SECONDS)).isTrue();

        // Second task: tries same lock, should be skipped by ShedLock
        Thread.ofVirtual().start(() -> {
            try {
                executor.executeWithLock((Runnable) () -> executionCount.incrementAndGet(), config);
            } finally {
                bothDone.countDown();
            }
        });

        assertThat(bothDone.await(10, TimeUnit.SECONDS)).isTrue();

        // Only the first execution should have run
        assertThat(executionCount.get()).isEqualTo(1);
    }

    @Test
    void different_connectors_can_run_concurrently() throws Exception {
        UUID connectorA = UUID.randomUUID();
        UUID connectorB = UUID.randomUUID();
        AtomicInteger aCount = new AtomicInteger(0);
        AtomicInteger bCount = new AtomicInteger(0);
        CountDownLatch bothDone = new CountDownLatch(2);

        Instant now = Instant.now();
        Duration atMostFor = Duration.ofSeconds(10);
        Duration atLeastFor = Duration.ofMillis(100);

        Thread.ofVirtual().start(() -> {
            try {
                executor.executeWithLock(
                        (Runnable) aCount::incrementAndGet,
                        new LockConfiguration(now, "connector-" + connectorA, atMostFor, atLeastFor));
            } finally {
                bothDone.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                executor.executeWithLock(
                        (Runnable) bCount::incrementAndGet,
                        new LockConfiguration(now, "connector-" + connectorB, atMostFor, atLeastFor));
            } finally {
                bothDone.countDown();
            }
        });

        assertThat(bothDone.await(10, TimeUnit.SECONDS)).isTrue();

        // Both should have executed
        assertThat(aCount.get()).isEqualTo(1);
        assertThat(bCount.get()).isEqualTo(1);
    }
}
