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
package dev.tessera.core.events.internal;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 1 / Wave 2 / 01-W2-03: wires ShedLock for the Outbox poller
 * (EVENT-05). The {@code shedlock} table is created by Flyway V10.
 *
 * <p>Lives in {@code events.internal} so both the main {@code TesseraApplication}
 * component scan AND the {@code FlywayItApplication} test component scan
 * (rooted at {@code dev.tessera.core}) pick it up automatically — no
 * per-harness duplication of bean wiring.
 *
 * <p>{@code @EnableSchedulerLock} activates the ShedLock AOP proxy around
 * {@code @SchedulerLock}-annotated methods. {@code defaultLockAtMostFor=PT1M}
 * is a safety cap; {@link dev.tessera.core.events.OutboxPoller} overrides it.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class LockProviderConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
