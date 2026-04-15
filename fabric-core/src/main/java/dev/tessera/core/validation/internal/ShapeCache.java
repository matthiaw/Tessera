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
package dev.tessera.core.validation.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.tenant.TenantContext;
import java.time.Duration;
import java.util.UUID;
import org.apache.jena.shacl.Shapes;
import org.springframework.stereotype.Component;

/**
 * VALID-02 — Caffeine-backed cache for compiled Jena {@link Shapes},
 * keyed by {@code (model_id, schema_version, type_slug)}. Configuration is
 * copied verbatim from RESEARCH §"SHACL Shape Caching":
 *
 * <pre>
 * maximumSize(10_000) / expireAfterAccess(1h) / recordStats()
 * </pre>
 */
@Component
public class ShapeCache {

    private final Cache<ShapeKey, Shapes> shapeCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .recordStats()
            .build();

    private final ShapeCompiler compiler;

    public ShapeCache(ShapeCompiler compiler) {
        this.compiler = compiler;
    }

    /** Cache key per VALID-02. */
    public record ShapeKey(UUID modelId, long schemaVersion, String typeSlug) {}

    /**
     * Returns the compiled {@link Shapes} for the given descriptor. Looks up
     * the cache; compiles on miss. Cache-hit is the expected hot path.
     */
    public Shapes shapesFor(TenantContext ctx, NodeTypeDescriptor descriptor) {
        ShapeKey key = new ShapeKey(ctx.modelId(), descriptor.schemaVersion(), descriptor.slug());
        return shapeCache.get(key, k -> compiler.compile(descriptor));
    }

    /** Test hook / Wave 2 schema-change listener. */
    public void invalidateAll() {
        shapeCache.invalidateAll();
    }

    /** Invalidate all entries for a single tenant. */
    public void invalidate(UUID modelId) {
        shapeCache.asMap().keySet().removeIf(k -> k.modelId().equals(modelId));
    }

    /** Test / metrics accessor. */
    public Cache<ShapeKey, Shapes> raw() {
        return shapeCache;
    }
}
