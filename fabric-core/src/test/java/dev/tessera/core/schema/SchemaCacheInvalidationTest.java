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
package dev.tessera.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.schema.internal.SchemaDescriptorCache;
import dev.tessera.core.schema.internal.SchemaDescriptorCache.DescriptorKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * SCHEMA-06 — unit test (no DB). Proves Caffeine cache hits on repeat reads
 * and that explicit invalidation forces a reload through the loader.
 */
class SchemaCacheInvalidationTest {

    @Test
    void second_read_hits_cache_then_invalidation_forces_reload() {
        SchemaDescriptorCache cache = new SchemaDescriptorCache();
        UUID modelId = UUID.randomUUID();
        DescriptorKey key = new DescriptorKey(modelId, "Person", 1L);
        AtomicInteger loads = new AtomicInteger();

        NodeTypeDescriptor descriptor =
                new NodeTypeDescriptor(modelId, "Person", "Person", "Person", null, 1L, List.of(), null);

        NodeTypeDescriptor first = cache.get(key, k -> {
            loads.incrementAndGet();
            return descriptor;
        });
        NodeTypeDescriptor second = cache.get(key, k -> {
            loads.incrementAndGet();
            return descriptor;
        });

        assertThat(first).isSameAs(second);
        assertThat(loads.get()).isEqualTo(1); // cache hit on second call
        assertThat(cache.hitCount()).isGreaterThanOrEqualTo(1);

        // explicit invalidation forces a reload
        cache.invalidateAll(modelId);

        cache.get(key, k -> {
            loads.incrementAndGet();
            return descriptor;
        });
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void invalidate_all_only_affects_matching_model() {
        SchemaDescriptorCache cache = new SchemaDescriptorCache();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        DescriptorKey keyA = new DescriptorKey(a, "Person", 1L);
        DescriptorKey keyB = new DescriptorKey(b, "Person", 1L);
        AtomicInteger loadsA = new AtomicInteger();
        AtomicInteger loadsB = new AtomicInteger();

        NodeTypeDescriptor dA = new NodeTypeDescriptor(a, "Person", "Person", "Person", null, 1L, List.of(), null);
        NodeTypeDescriptor dB = new NodeTypeDescriptor(b, "Person", "Person", "Person", null, 1L, List.of(), null);

        cache.get(keyA, k -> {
            loadsA.incrementAndGet();
            return dA;
        });
        cache.get(keyB, k -> {
            loadsB.incrementAndGet();
            return dB;
        });

        cache.invalidateAll(a);

        cache.get(keyA, k -> {
            loadsA.incrementAndGet();
            return dA;
        });
        cache.get(keyB, k -> {
            loadsB.incrementAndGet();
            return dB;
        });

        assertThat(loadsA.get()).isEqualTo(2); // A reloaded after invalidation
        assertThat(loadsB.get()).isEqualTo(1); // B untouched
    }
}
