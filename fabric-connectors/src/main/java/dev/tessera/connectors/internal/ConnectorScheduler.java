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

import dev.tessera.connectors.ConnectorInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CONN-03 / CONTEXT Decision 9: central 1-second tick dispatching due
 * connectors via per-{@code connector_id} ShedLock. Virtual-thread
 * dispatch so slow connectors don't block the tick.
 */
@Component
public class ConnectorScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorScheduler.class);

    private final ConnectorRegistry registry;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final ConnectorRunner runner;
    private final Clock clock;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ConnectorScheduler(
            ConnectorRegistry registry, LockingTaskExecutor lockingTaskExecutor, ConnectorRunner runner, Clock clock) {
        this.registry = registry;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000L)
    public void tick() {
        Instant now = clock.instant();
        for (ConnectorInstance instance : registry.dueAt(now)) {
            virtualThreadExecutor.submit(() -> {
                try {
                    String lockName = "connector-" + instance.id();
                    Duration atMostFor = Duration.ofSeconds(instance.pollIntervalSeconds() * 3L);
                    Duration atLeastFor = Duration.ofMillis(100);
                    lockingTaskExecutor.executeWithLock(
                            (Runnable) () -> runner.runOnce(instance),
                            new LockConfiguration(now, lockName, atMostFor, atLeastFor));
                } catch (Exception e) {
                    LOG.error("Connector {} dispatch failed: {}", instance.id(), e.getMessage(), e);
                }
            });
        }
    }
}
