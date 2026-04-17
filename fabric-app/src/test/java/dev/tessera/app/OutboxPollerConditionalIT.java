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

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.events.OutboxPoller;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * KAFKA-02: Verifies that the {@code OutboxPoller} is conditionally registered based on
 * the {@code tessera.kafka.enabled} property.
 *
 * <p>Uses {@link ApplicationContextRunner} for lightweight context bootstrap — no
 * Testcontainers, no Flyway, no Spring Security needed. The test only verifies
 * bean presence/absence based on the conditional property.
 *
 * <p>IMPORTANT: {@code OutboxPoller.class} is registered via
 * {@code withUserConfiguration} (not {@code withBean}) so that
 * {@code @ConditionalOnProperty} is evaluated during context refresh. Using
 * {@code withBean} bypasses Spring's condition processing.
 *
 * <p>Plan 04-03 implementation replacing the Wave 0 stub.
 */
class OutboxPollerConditionalIT {

    /** Minimal configuration that wires the OutboxPoller's two constructor dependencies. */
    @Configuration
    static class MinimalConfig {

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
            // Mocked — OutboxPoller constructor only stores the reference; it is never
            // called in these context-presence tests.
            return org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
        }

        @Bean
        ApplicationEventPublisher applicationEventPublisher() {
            return org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        }
    }

    // withUserConfiguration — NOT withBean — so @ConditionalOnProperty is evaluated.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MinimalConfig.class, OutboxPoller.class);

    /**
     * KAFKA-02: With the default configuration ({@code tessera.kafka.enabled} absent or
     * {@code false}), the OutboxPoller bean must be present in the application context.
     */
    @Test
    void pollerExistsWhenKafkaDisabled() {
        runner
                // Default: property absent — matchIfMissing=true means poller is created
                .run(ctx -> assertThat(ctx).hasSingleBean(OutboxPoller.class));
    }

    /**
     * KAFKA-02: With {@code tessera.kafka.enabled=false} explicit, the poller must exist.
     */
    @Test
    void pollerExistsWhenKafkaExplicitlyFalse() {
        runner
                .withPropertyValues("tessera.kafka.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(OutboxPoller.class));
    }

    /**
     * KAFKA-02: When {@code tessera.kafka.enabled=true}, the OutboxPoller bean must NOT
     * be registered in the application context — Debezium/Kafka is responsible for
     * outbox delivery and the in-process poller must not run.
     */
    @Test
    void pollerAbsentWhenKafkaEnabled() {
        runner
                .withPropertyValues("tessera.kafka.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OutboxPoller.class));
    }
}
