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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tessera.core.schema.PropertyDescriptor;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AclPropertyCache {

    private final Cache<AclCacheKey, Set<String>> cache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .recordStats()
            .build();

    public record AclCacheKey(UUID modelId, String typeSlug, String canonicalRoleSet) {}

    public static String canonicalizeRoles(Set<String> roles) {
        return roles.stream().sorted().collect(Collectors.joining(","));
    }

    public Set<String> getAllowedPropertySlugs(
            UUID modelId, String typeSlug, Set<String> callerRoles,
            Supplier<List<PropertyDescriptor>> propertyLoader) {
        String canonicalRoleSet = canonicalizeRoles(callerRoles);
        return cache.get(
                new AclCacheKey(modelId, typeSlug, canonicalRoleSet),
                k -> computeAllowed(callerRoles, propertyLoader.get()));
    }

    private Set<String> computeAllowed(Set<String> callerRoles, List<PropertyDescriptor> properties) {
        Set<String> allowed = new LinkedHashSet<>();
        for (PropertyDescriptor property : properties) {
            if (property.readRoles() == null || property.readRoles().isEmpty()
                    || !Collections.disjoint(callerRoles, property.readRoles())) {
                allowed.add(property.slug());
            }
        }
        return Collections.unmodifiableSet(allowed);
    }

    public void invalidateAll(UUID modelId) {
        cache.asMap().keySet().removeIf(k -> k.modelId().equals(modelId));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
