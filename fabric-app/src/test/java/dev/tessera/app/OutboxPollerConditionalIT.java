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
package dev.tessera.app;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * KAFKA-02: Verifies that the {@code OutboxPoller} is conditionally registered based on
 * the {@code tessera.kafka.enabled} property.
 *
 * <p>By default (Kafka disabled), the OutboxPoller must be present in the Spring context
 * and actively polling the outbox table. When {@code tessera.kafka.enabled=true}, the
 * OutboxPoller must be absent — Debezium/Kafka takes over outbox delivery and the poller
 * must not run concurrently.
 *
 * <p>Note: Each conditional variant requires its own nested test class or separate
 * {@code @SpringBootTest} context with distinct properties. The implementation in
 * Plan 04-03 will use {@code @SpringBootTest(properties = ...)} nested classes or
 * {@code ApplicationContextRunner} to test both branches.
 *
 * <p>Wave 0 stub — enabled by Plan 04-03.
 */
@Disabled("Wave 0 stub — implementation in Plan 04-03")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPollerConditionalIT {

    /**
     * KAFKA-02: With the default configuration ({@code tessera.kafka.enabled} absent or
     * {@code false}), the OutboxPoller bean must be present in the application context
     * and functional.
     */
    @Test
    void pollerExistsWhenKafkaDisabled() {
        fail("Not yet implemented — KAFKA-02: default config = OutboxPoller active (Plan 04-03)");
    }

    /**
     * KAFKA-02: When {@code tessera.kafka.enabled=true}, the OutboxPoller bean must NOT
     * be registered in the application context — Kafka/Debezium is responsible for
     * outbox delivery and the in-process poller must not run.
     */
    @Test
    void pollerAbsentWhenKafkaEnabled() {
        fail("Not yet implemented — KAFKA-02: kafka enabled = OutboxPoller absent from context (Plan 04-03)");
    }
}
