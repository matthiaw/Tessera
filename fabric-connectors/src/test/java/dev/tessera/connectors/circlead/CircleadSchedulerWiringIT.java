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
package dev.tessera.connectors.circlead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.MappingDefinition;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * CIRC-01: Verifies that ConnectorRegistry wiring is called for all three circlead
 * ConnectorInstances after CircleadConnectorConfig.registerCircleadConnectors() executes.
 *
 * <p>Uses ApplicationContextRunner to start a minimal Spring context with
 * CircleadConnectorConfig and a mocked NamedParameterJdbcTemplate, verifying the
 * {@code @PostConstruct} upsert path calls {@code jdbc.update()} exactly three times.
 *
 * <p>No Testcontainers, no Flyway, no Docker required — the DB interaction is mocked.
 */
class CircleadSchedulerWiringIT {

    private static final String BASE_URL = "http://test-circlead:8080";

    @Test
    void circlead_connector_config_registers_exactly_three_connectors() {
        NamedParameterJdbcTemplate mockJdbc = mock(NamedParameterJdbcTemplate.class);

        new ApplicationContextRunner()
                .withUserConfiguration(CircleadConnectorConfig.class)
                .withPropertyValues(
                        "tessera.connectors.circlead.base-url=" + BASE_URL,
                        "tessera.connectors.circlead.model-id=00000000-0000-0000-0000-000000000001",
                        "tessera.connectors.circlead.credentials-ref=vault:test/token",
                        "tessera.connectors.circlead.poll-interval-seconds=60")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NamedParameterJdbcTemplate.class, () -> mockJdbc)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // @PostConstruct must have fired and called jdbc.update() exactly 3 times
                    verify(mockJdbc, times(3)).update(anyString(), anyMap());
                });
    }

    @Test
    void circlead_role_mapping_has_resolved_url() {
        NamedParameterJdbcTemplate mockJdbc = mock(NamedParameterJdbcTemplate.class);

        new ApplicationContextRunner()
                .withUserConfiguration(CircleadConnectorConfig.class)
                .withPropertyValues(
                        "tessera.connectors.circlead.base-url=" + BASE_URL,
                        "tessera.connectors.circlead.model-id=00000000-0000-0000-0000-000000000001",
                        "tessera.connectors.circlead.credentials-ref=vault:test/token",
                        "tessera.connectors.circlead.poll-interval-seconds=60")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NamedParameterJdbcTemplate.class, () -> mockJdbc)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    MappingDefinition role = ctx.getBean("circleadRoleMapping", MappingDefinition.class);
                    assertThat(role.sourceUrl()).startsWith(BASE_URL);
                    assertThat(role.sourceUrl()).doesNotContain("${");
                    assertThatCode(() -> URI.create(role.sourceUrl())).doesNotThrowAnyException();
                });
    }

    @Test
    void all_three_mapping_beans_have_resolved_source_urls() {
        NamedParameterJdbcTemplate mockJdbc = mock(NamedParameterJdbcTemplate.class);

        new ApplicationContextRunner()
                .withUserConfiguration(CircleadConnectorConfig.class)
                .withPropertyValues(
                        "tessera.connectors.circlead.base-url=" + BASE_URL,
                        "tessera.connectors.circlead.model-id=00000000-0000-0000-0000-000000000001",
                        "tessera.connectors.circlead.credentials-ref=vault:test/token",
                        "tessera.connectors.circlead.poll-interval-seconds=60")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NamedParameterJdbcTemplate.class, () -> mockJdbc)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    for (String beanName :
                            new String[] {"circleadRoleMapping", "circleadCircleMapping", "circleadActivityMapping"}) {
                        MappingDefinition m = ctx.getBean(beanName, MappingDefinition.class);
                        assertThat(m.sourceUrl())
                                .as("sourceUrl for " + beanName + " must not contain ${")
                                .doesNotContain("${");
                        assertThat(m.sourceUrl())
                                .as("sourceUrl for " + beanName + " must start with base-url")
                                .startsWith(BASE_URL);
                        assertThatCode(() -> URI.create(m.sourceUrl()))
                                .as("URI.create must not throw for " + beanName)
                                .doesNotThrowAnyException();
                    }
                    // Total upsert calls: 3 (once per connector type)
                    verify(mockJdbc, atLeast(3)).update(anyString(), anyMap());
                });
    }
}
