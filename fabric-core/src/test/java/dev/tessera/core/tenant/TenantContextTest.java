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
package dev.tessera.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @Test
    void null_model_id_is_rejected_with_informative_message() {
        assertThatThrownBy(() -> new TenantContext(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelId");
    }

    @Test
    void of_factory_returns_record_with_same_uuid() {
        UUID id = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(id);
        assertThat(ctx.modelId()).isEqualTo(id);
    }

    @Test
    void two_instances_with_same_uuid_are_equal_and_share_hash_code() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        TenantContext a = new TenantContext(id);
        TenantContext b = TenantContext.of(id);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
