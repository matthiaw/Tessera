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
package dev.tessera.projections.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SQL-01: Unit tests for {@link SqlViewNameResolver}.
 *
 * <p>Verifies the naming convention, hyphen-to-underscore normalization, Postgres
 * 63-character identifier limit enforcement, and error handling.
 */
class SqlViewNameResolverTest {

    private static final UUID MODEL_ID =
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    /**
     * Basic case: resolve("550e8400-...", "person") returns "v_550e8400_person".
     * The first 8 hex chars of the UUID (without hyphens) form the prefix.
     */
    @Test
    void basicResolutionReturnsExpectedName() {
        String name = SqlViewNameResolver.resolve(MODEL_ID, "person");
        assertThat(name).isEqualTo("v_550e8400_person");
    }

    /**
     * Hyphens in the type slug must be replaced with underscores.
     */
    @Test
    void hyphensInTypeSlugReplacedWithUnderscores() {
        String name = SqlViewNameResolver.resolve(MODEL_ID, "work-item");
        assertThat(name).isEqualTo("v_550e8400_work_item");
    }

    /**
     * Result must never exceed 63 characters (Postgres identifier limit).
     */
    @Test
    void resultNeverExceeds63Characters() {
        String longSlug = "a-very-long-type-slug-that-would-exceed-the-postgres-identifier-limit-for-sure";
        String name = SqlViewNameResolver.resolve(MODEL_ID, longSlug);
        assertThat(name.length()).isLessThanOrEqualTo(63);
    }

    /**
     * Truncated names must still be deterministic (same inputs → same output).
     */
    @Test
    void truncatedNameIsDeterministic() {
        String longSlug = "a-very-long-type-slug-that-would-exceed-the-postgres-identifier-limit-for-sure";
        String name1 = SqlViewNameResolver.resolve(MODEL_ID, longSlug);
        String name2 = SqlViewNameResolver.resolve(MODEL_ID, longSlug);
        assertThat(name1).isEqualTo(name2);
    }

    /**
     * Short names are returned as-is without truncation.
     */
    @Test
    void shortNameNotTruncated() {
        String name = SqlViewNameResolver.resolve(MODEL_ID, "contact");
        assertThat(name).isEqualTo("v_550e8400_contact");
        assertThat(name.length()).isLessThan(63);
    }

    /**
     * Null modelId throws IllegalArgumentException.
     */
    @Test
    void nullModelIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> SqlViewNameResolver.resolve(null, "person"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId");
    }

    /**
     * Null typeSlug throws IllegalArgumentException.
     */
    @Test
    void nullTypeSlugThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> SqlViewNameResolver.resolve(MODEL_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typeSlug");
    }

    /**
     * Blank typeSlug throws IllegalArgumentException.
     */
    @Test
    void blankTypeSlugThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> SqlViewNameResolver.resolve(MODEL_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typeSlug");
    }

    /**
     * The view name prefix uses only the first 8 hex chars of the UUID (without hyphens).
     * For UUID "550e8400-e29b-41d4-a716-446655440000" the hex-stripped form starts
     * "550e8400e29b..." — prefix is "550e8400".
     */
    @Test
    void prefixIsFirst8HexCharsOfUuidWithoutHyphens() {
        // UUID hex (no hyphens): 550e8400e29b41d4a716446655440000
        // First 8: 550e8400
        String name = SqlViewNameResolver.resolve(MODEL_ID, "x");
        assertThat(name).startsWith("v_550e8400_");
    }

    /**
     * Different UUID produces different prefix, avoiding cross-tenant collisions.
     */
    @Test
    void differentUuidsProduceDifferentPrefixes() {
        UUID other = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String name1 = SqlViewNameResolver.resolve(MODEL_ID, "person");
        String name2 = SqlViewNameResolver.resolve(other, "person");
        assertThat(name1).isNotEqualTo(name2);
    }
}
