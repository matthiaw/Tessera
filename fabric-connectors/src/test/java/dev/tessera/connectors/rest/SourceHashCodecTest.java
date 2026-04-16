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
package dev.tessera.connectors.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.connectors.FieldMapping;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SourceHashCodec}.
 */
class SourceHashCodecTest {

    @Test
    void same_values_produce_same_hash() {
        List<FieldMapping> fields = List.of(
                new FieldMapping("name", "$.name", null, false), new FieldMapping("email", "$.email", null, false));
        Map<String, Object> values = Map.of("name", "Alice", "email", "alice@example.com");

        String hash1 = SourceHashCodec.hash(fields, values);
        String hash2 = SourceHashCodec.hash(fields, values);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex = 64 chars
    }

    @Test
    void different_values_produce_different_hash() {
        List<FieldMapping> fields = List.of(new FieldMapping("name", "$.name", null, false));

        String hash1 = SourceHashCodec.hash(fields, Map.of("name", "Alice"));
        String hash2 = SourceHashCodec.hash(fields, Map.of("name", "Bob"));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void field_order_does_not_affect_hash() {
        List<FieldMapping> fieldsAB =
                List.of(new FieldMapping("a", "$.a", null, false), new FieldMapping("b", "$.b", null, false));
        List<FieldMapping> fieldsBA =
                List.of(new FieldMapping("b", "$.b", null, false), new FieldMapping("a", "$.a", null, false));
        Map<String, Object> values = Map.of("a", "1", "b", "2");

        assertThat(SourceHashCodec.hash(fieldsAB, values)).isEqualTo(SourceHashCodec.hash(fieldsBA, values));
    }

    @Test
    void null_values_handled_gracefully() {
        List<FieldMapping> fields = List.of(new FieldMapping("name", "$.name", null, false));
        Map<String, Object> values = Map.of(); // name is missing

        String hash = SourceHashCodec.hash(fields, values);
        assertThat(hash).isNotEmpty();
    }
}
