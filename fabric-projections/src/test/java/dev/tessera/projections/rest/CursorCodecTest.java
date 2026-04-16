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
package dev.tessera.projections.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    @Test
    void encode_decode_round_trip() {
        UUID modelId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        String cursor = CursorCodec.encode(modelId, "Person", 42L, nodeId);

        CursorCodec.CursorPosition pos = CursorCodec.decode(cursor);
        assertThat(pos.modelId()).isEqualTo(modelId);
        assertThat(pos.typeSlug()).isEqualTo("Person");
        assertThat(pos.lastSeq()).isEqualTo(42L);
        assertThat(pos.lastNodeId()).isEqualTo(nodeId);
    }

    @Test
    void decode_rejects_blank_cursor() {
        assertThatThrownBy(() -> CursorCodec.decode(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void decode_rejects_malformed_cursor() {
        assertThatThrownBy(() -> CursorCodec.decode("bm90LWEtY3Vyc29y")).isInstanceOf(IllegalArgumentException.class);
    }
}
