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

import dev.tessera.connectors.MappingDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * CIRC-02: Unit tests proving that Spring placeholder resolution in
 * {@link CircleadConnectorConfig} produces a valid URI from a raw {@code ${...}} sourceUrl.
 *
 * <p>No Spring context, no DB, no Docker required.
 */
class CircleadPlaceholderResolutionTest {

    @Test
    void sourceUrl_placeholder_resolved_before_uri_create() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(new MapPropertySource(
                        "test", Map.of("tessera.connectors.circlead.base-url", "http://localhost:9090")));

        MappingDefinition raw = new MappingDefinition(
                "role",
                "Role",
                "$.data[*]",
                List.of(),
                List.of("circlead_id"),
                "${tessera.connectors.circlead.base-url}/circlead/workitem/list?type=ROLE&details=true",
                null,
                null,
                null,
                null,
                null,
                null);

        String resolved = env.resolvePlaceholders(raw.sourceUrl());
        MappingDefinition fixed = CircleadConnectorConfig.withResolvedUrl(raw, resolved);

        assertThatCode(() -> URI.create(fixed.sourceUrl())).doesNotThrowAnyException();
        assertThat(fixed.sourceUrl()).startsWith("http://localhost:9090/circlead");
        assertThat(fixed.sourceUrl()).doesNotContain("${");
    }

    @Test
    void sourceUrl_with_default_value_resolves_correctly() {
        // No property set — default value in placeholder should be used
        StandardEnvironment env = new StandardEnvironment();

        MappingDefinition raw = new MappingDefinition(
                "circle",
                "Circle",
                "$.data[*]",
                List.of(),
                List.of("circlead_id"),
                "${tessera.connectors.circlead.base-url:http://fallback:8080}/circlead/workitem/list",
                null,
                null,
                null,
                null,
                null,
                null);

        String resolved = env.resolvePlaceholders(raw.sourceUrl());
        MappingDefinition fixed = CircleadConnectorConfig.withResolvedUrl(raw, resolved);

        assertThatCode(() -> URI.create(fixed.sourceUrl())).doesNotThrowAnyException();
        assertThat(fixed.sourceUrl()).startsWith("http://fallback:8080");
        assertThat(fixed.sourceUrl()).doesNotContain("${");
    }
}
