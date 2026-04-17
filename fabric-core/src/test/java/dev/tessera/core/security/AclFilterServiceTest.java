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
package dev.tessera.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.graph.NodeState;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class AclFilterServiceTest {

    private AclPropertyCache cache;
    private AclFilterService service;
    private final UUID modelId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        cache = new AclPropertyCache();
        service = new AclFilterService(cache);
    }

    private PropertyDescriptor prop(String slug, List<String> readRoles, List<String> writeRoles) {
        return new PropertyDescriptor(slug, slug, "STRING", false, null, null, null, null, null,
                false, null, readRoles, writeRoles);
    }

    private NodeTypeDescriptor type(List<PropertyDescriptor> props,
            List<String> readRoles, List<String> writeRoles) {
        return new NodeTypeDescriptor(modelId, "Person", "Person", "Person", null, 1L,
                props, null, true, true, readRoles, writeRoles);
    }

    private NodeState node(Map<String, Object> properties) {
        return new NodeState(UUID.randomUUID(), "Person", properties, now, now);
    }

    @Test
    void filterProperties_noReadRoles_allPropertiesVisible() {
        var props = List.of(prop("name", List.of(), List.of()), prop("email", List.of(), List.of()));
        var descriptor = type(props, List.of(), List.of());
        var state = node(Map.of("name", "Alice", "email", "a@b.com"));

        Map<String, Object> result = service.filterProperties(state, descriptor, Set.of("USER"));

        assertThat(result).containsKeys("name", "email");
    }

    @Test
    void filterProperties_withReadRoles_matchingRole_visible() {
        var props = List.of(prop("salary", List.of("ADMIN"), List.of()));
        var descriptor = type(props, List.of(), List.of());
        var state = node(Map.of("salary", 100000));

        Map<String, Object> result = service.filterProperties(state, descriptor, Set.of("ADMIN"));

        assertThat(result).containsEntry("salary", 100000);
    }

    @Test
    void filterProperties_withReadRoles_nonMatchingRole_filtered() {
        var props = List.of(prop("salary", List.of("ADMIN"), List.of()));
        var descriptor = type(props, List.of(), List.of());
        var state = node(Map.of("salary", 100000));

        Map<String, Object> result = service.filterProperties(state, descriptor, Set.of("AGENT"));

        assertThat(result).doesNotContainKey("salary");
    }

    @Test
    void filterProperties_allFiltered_returnsEmptyMap() {
        var props = List.of(
                prop("salary", List.of("ADMIN"), List.of()),
                prop("ssn", List.of("ADMIN"), List.of()));
        var descriptor = type(props, List.of(), List.of());
        var state = node(Map.of("salary", 100000, "ssn", "123-45"));

        Map<String, Object> result = service.filterProperties(state, descriptor, Set.of("VIEWER"));

        assertThat(result).isEmpty();
    }

    @Test
    void filterProperties_nullReadRoles_treatedAsUnrestricted() {
        var props = List.of(new PropertyDescriptor("name", "name", "STRING", false,
                null, null, null, null, null, false, null));
        var descriptor = type(props, List.of(), List.of());
        var state = node(Map.of("name", "Alice"));

        Map<String, Object> result = service.filterProperties(state, descriptor, Set.of("ANYONE"));

        assertThat(result).containsKey("name");
    }

    @Test
    void filterProperties_mixedRestrictions() {
        var props = List.of(
                prop("name", List.of(), List.of()),
                prop("salary", List.of("ADMIN"), List.of()),
                prop("email", List.of("ADMIN", "HR"), List.of()));
        var descriptor = type(props, List.of(), List.of());
        var state = node(Map.of("name", "Alice", "salary", 100000, "email", "a@b.com"));

        Map<String, Object> result = service.filterProperties(state, descriptor, Set.of("HR"));

        assertThat(result).containsKeys("name", "email").doesNotContainKey("salary");
    }

    @Test
    void isTypeVisible_emptyReadRoles_visible() {
        var descriptor = type(List.of(), List.of(), List.of());

        assertThat(service.isTypeVisible(descriptor, Set.of("USER"))).isTrue();
    }

    @Test
    void isTypeVisible_matchingRole_visible() {
        var descriptor = type(List.of(), List.of("ADMIN"), List.of());

        assertThat(service.isTypeVisible(descriptor, Set.of("ADMIN"))).isTrue();
    }

    @Test
    void isTypeVisible_nonMatchingRole_notVisible() {
        var descriptor = type(List.of(), List.of("ADMIN"), List.of());

        assertThat(service.isTypeVisible(descriptor, Set.of("AGENT"))).isFalse();
    }

    @Test
    void checkWriteRoles_restrictedProperty_callerLacksRole_throws() {
        var props = List.of(prop("salary", List.of(), List.of("ADMIN")));
        var descriptor = type(props, List.of(), List.of());

        assertThatThrownBy(() ->
                service.checkWriteRoles(descriptor, Map.of("salary", 50000), Set.of("AGENT")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void checkWriteRoles_unrestrictedProperty_passes() {
        var props = List.of(prop("name", List.of(), List.of()));
        var descriptor = type(props, List.of(), List.of());

        service.checkWriteRoles(descriptor, Map.of("name", "Bob"), Set.of("AGENT"));
    }
}
