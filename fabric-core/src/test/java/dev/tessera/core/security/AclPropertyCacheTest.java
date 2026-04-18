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

import dev.tessera.core.schema.PropertyDescriptor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AclPropertyCacheTest {

    private AclPropertyCache cache;
    private final UUID modelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cache = new AclPropertyCache();
    }

    private PropertyDescriptor prop(String slug, List<String> readRoles) {
        return new PropertyDescriptor(
                slug, slug, "STRING", false, null, null, null, null, null, false, null, readRoles, List.of());
    }

    @Test
    void getAllowedPropertySlugs_cacheHit_loaderNotCalledTwice() {
        AtomicInteger loadCount = new AtomicInteger();
        var props = List.of(prop("name", List.of()));

        cache.getAllowedPropertySlugs(modelId, "Person", Set.of("USER"), () -> {
            loadCount.incrementAndGet();
            return props;
        });
        cache.getAllowedPropertySlugs(modelId, "Person", Set.of("USER"), () -> {
            loadCount.incrementAndGet();
            return props;
        });

        assertThat(loadCount.get()).isEqualTo(1);
    }

    @Test
    void invalidateAll_clearsMatchingModelId() {
        UUID otherModelId = UUID.randomUUID();
        var props = List.of(prop("name", List.of()));
        AtomicInteger loadCount = new AtomicInteger();

        cache.getAllowedPropertySlugs(modelId, "Person", Set.of("USER"), () -> {
            loadCount.incrementAndGet();
            return props;
        });
        cache.getAllowedPropertySlugs(otherModelId, "Person", Set.of("USER"), () -> {
            loadCount.incrementAndGet();
            return props;
        });
        assertThat(loadCount.get()).isEqualTo(2);

        cache.invalidateAll(modelId);

        cache.getAllowedPropertySlugs(modelId, "Person", Set.of("USER"), () -> {
            loadCount.incrementAndGet();
            return props;
        });
        cache.getAllowedPropertySlugs(otherModelId, "Person", Set.of("USER"), () -> {
            loadCount.incrementAndGet();
            return props;
        });

        assertThat(loadCount.get()).isEqualTo(3);
    }

    @Test
    void canonicalizeRoles_sortedAndJoined() {
        assertThat(AclPropertyCache.canonicalizeRoles(Set.of("AGENT", "ADMIN"))).isEqualTo("ADMIN,AGENT");
    }

    @Test
    void differentRoleOrder_sameCacheKey() {
        var props = List.of(prop("name", List.of()));
        AtomicInteger loadCount = new AtomicInteger();

        cache.getAllowedPropertySlugs(modelId, "Person", Set.of("ADMIN", "AGENT"), () -> {
            loadCount.incrementAndGet();
            return props;
        });
        cache.getAllowedPropertySlugs(modelId, "Person", Set.of("AGENT", "ADMIN"), () -> {
            loadCount.incrementAndGet();
            return props;
        });

        assertThat(loadCount.get()).isEqualTo(1);
    }
}
