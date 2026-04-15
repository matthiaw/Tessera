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
package dev.tessera.rules.authority;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Caffeine-cached runtime view over the {@code source_authority} table
 * (D-C2 exact DDL — see V6 migration). Given a tenant × type × property
 * triple, returns the priority order used by
 * {@link AuthorityReconciliationRule} to decide which source wins a
 * contested write.
 */
@Component
public class SourceAuthorityMatrix {

    private final NamedParameterJdbcTemplate jdbc;
    private final Cache<Key, List<String>> cache;

    public SourceAuthorityMatrix(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /**
     * Return the priority index of {@code sourceSystem} for the given
     * {@code (modelId, typeSlug, propertySlug)}. Lower index = higher
     * authority. Returns {@link Integer#MAX_VALUE} if either the row is
     * missing or the source is not in the list.
     */
    public int rank(UUID modelId, String typeSlug, String propertySlug, String sourceSystem) {
        List<String> order = cache.get(new Key(modelId, typeSlug, propertySlug), this::load);
        if (order == null || order.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int idx = order.indexOf(sourceSystem);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /** Returns true iff a row exists for this (modelId, typeSlug, propertySlug). */
    public boolean hasMatrixFor(UUID modelId, String typeSlug, String propertySlug) {
        List<String> order = cache.get(new Key(modelId, typeSlug, propertySlug), this::load);
        return order != null && !order.isEmpty();
    }

    public void invalidate(UUID modelId, String typeSlug, String propertySlug) {
        cache.invalidate(new Key(modelId, typeSlug, propertySlug));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    private List<String> load(Key key) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", key.modelId.toString());
        p.addValue("type_slug", key.typeSlug);
        p.addValue("property_slug", key.propertySlug);
        List<List<String>> results = jdbc.query(
                """
                SELECT priority_order
                  FROM source_authority
                 WHERE model_id = :model_id::uuid
                   AND type_slug = :type_slug
                   AND property_slug = :property_slug
                """,
                p,
                (rs, rowNum) -> {
                    java.sql.Array arr = rs.getArray("priority_order");
                    if (arr == null) {
                        return List.<String>of();
                    }
                    Object raw = arr.getArray();
                    if (raw instanceof String[] s) {
                        return Arrays.asList(s);
                    }
                    return List.<String>of();
                });
        return results.isEmpty() ? List.of() : results.get(0);
    }

    private record Key(UUID modelId, String typeSlug, String propertySlug) {}
}
