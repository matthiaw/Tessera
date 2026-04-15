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
package dev.tessera.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.events.EventLog;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CORE-08 / EVENT-03: Tessera owns timestamps and delta computation. This is
 * a pure unit test — no database — that locks down the delta semantics for
 * the three operations and verifies the package-private
 * {@link EventLog#computeDelta} contract.
 */
class TimestampOwnershipTest {

    @Test
    void create_delta_is_full_payload() {
        Map<String, Object> newState = Map.of("name", "Alice", "_type", "Person");
        Map<String, Object> delta = EventLog.computeDelta(Operation.CREATE, newState, Map.of());
        assertThat(delta).isEqualTo(newState);
    }

    @Test
    void update_delta_is_field_level_diff() {
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("name", "Alice");
        prev.put("age", 30);
        prev.put("_type", "Person");

        Map<String, Object> next = new LinkedHashMap<>();
        next.put("name", "Alice"); // unchanged
        next.put("age", 31); // changed
        next.put("_type", "Person"); // unchanged
        next.put("email", "a@example.com"); // added

        Map<String, Object> delta = EventLog.computeDelta(Operation.UPDATE, next, prev);
        assertThat(delta).containsOnlyKeys("age", "email");
        assertThat(delta).containsEntry("age", 31);
        assertThat(delta).containsEntry("email", "a@example.com");
    }

    @Test
    void tombstone_delta_is_marker_only() {
        Map<String, Object> delta =
                EventLog.computeDelta(Operation.TOMBSTONE, Map.of("name", "Alice"), Map.of("name", "Alice"));
        assertThat(delta).containsOnlyKeys("_tombstoned");
        assertThat(delta).containsEntry("_tombstoned", true);
    }
}
