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
package dev.tessera.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.internal.ShapeCache;
import dev.tessera.core.validation.internal.ShapeCompiler;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.shacl.Shapes;
import org.junit.jupiter.api.Test;

/** VALID-02 — compiled SHACL shape cache hit/miss per (modelId, schemaVersion, typeSlug). */
class ShapeCacheTest {

    private static NodeTypeDescriptor desc(String slug, long schemaVersion) {
        return new NodeTypeDescriptor(
                UUID.randomUUID(),
                slug,
                slug,
                slug,
                "desc",
                schemaVersion,
                List.of(new PropertyDescriptor("name", "name", "string", true, null, null, null, null, null)),
                null);
    }

    private static NodeTypeDescriptor descWithModel(UUID modelId, String slug, long schemaVersion) {
        return new NodeTypeDescriptor(
                modelId,
                slug,
                slug,
                slug,
                "desc",
                schemaVersion,
                List.of(new PropertyDescriptor("name", "name", "string", true, null, null, null, null, null)),
                null);
    }

    @Test
    void second_call_with_same_key_hits_cache() {
        AtomicInteger compileCount = new AtomicInteger();
        ShapeCompiler counting = new ShapeCompiler() {
            @Override
            public Shapes compile(NodeTypeDescriptor type) {
                compileCount.incrementAndGet();
                return super.compile(type);
            }
        };
        ShapeCache cache = new ShapeCache(counting);

        UUID modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);
        NodeTypeDescriptor d = descWithModel(modelId, "Person", 1L);

        cache.shapesFor(ctx, d);
        cache.shapesFor(ctx, d);

        assertThat(compileCount.get()).isEqualTo(1);
        assertThat(cache.raw().stats().hitCount()).isEqualTo(1);
        assertThat(cache.raw().stats().missCount()).isEqualTo(1);
    }

    @Test
    void different_schema_version_recompiles() {
        AtomicInteger compileCount = new AtomicInteger();
        ShapeCompiler counting = new ShapeCompiler() {
            @Override
            public Shapes compile(NodeTypeDescriptor type) {
                compileCount.incrementAndGet();
                return super.compile(type);
            }
        };
        ShapeCache cache = new ShapeCache(counting);

        UUID modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);

        cache.shapesFor(ctx, descWithModel(modelId, "Person", 1L));
        cache.shapesFor(ctx, descWithModel(modelId, "Person", 2L));

        assertThat(compileCount.get()).isEqualTo(2);
        assertThat(cache.raw().stats().missCount()).isEqualTo(2);
    }

    @Test
    void invalidate_model_clears_only_that_tenants_entries() {
        ShapeCache cache = new ShapeCache(new ShapeCompiler());
        UUID modelA = UUID.randomUUID();
        UUID modelB = UUID.randomUUID();

        cache.shapesFor(TenantContext.of(modelA), descWithModel(modelA, "Person", 1L));
        cache.shapesFor(TenantContext.of(modelB), descWithModel(modelB, "Person", 1L));
        assertThat(cache.raw().estimatedSize()).isEqualTo(2);

        cache.invalidate(modelA);
        cache.raw().cleanUp();
        assertThat(cache.raw().estimatedSize()).isEqualTo(1);
    }
}
