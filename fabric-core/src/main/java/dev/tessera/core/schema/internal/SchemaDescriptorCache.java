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
package dev.tessera.core.schema.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tessera.core.schema.NodeTypeDescriptor;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * SCHEMA-06: Caffeine-backed descriptor cache. Cache key is
 * {@code (modelId, typeSlug, schemaVersion)} so a version bump naturally
 * misses without needing to evict — but {@link #invalidateAll(UUID)} is still
 * exposed for explicit invalidation on schema change.
 */
@Component
public class SchemaDescriptorCache {

    private final Cache<DescriptorKey, NodeTypeDescriptor> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .recordStats()
            .build();

    public NodeTypeDescriptor get(DescriptorKey key, Function<DescriptorKey, NodeTypeDescriptor> loader) {
        return cache.get(key, loader);
    }

    public void invalidate(DescriptorKey key) {
        cache.invalidate(key);
    }

    public void invalidateAll(UUID modelId) {
        cache.asMap().keySet().removeIf(k -> k.modelId().equals(modelId));
    }

    public long size() {
        return cache.estimatedSize();
    }

    public long hitCount() {
        return cache.stats().hitCount();
    }

    public long missCount() {
        return cache.stats().missCount();
    }

    public record DescriptorKey(UUID modelId, String typeSlug, long schemaVersion) {}
}
