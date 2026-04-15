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
package dev.tessera.core.support;

/**
 * Reusable test builders for Phase 1 schema registry shapes. Wave 2 may
 * replace the DTOs below with the real schema registry records; Wave 0 ships
 * thin placeholders so later tests have a stable import site.
 */
public final class SchemaFixtures {

    private SchemaFixtures() {}

    public static NodeTypeDraft nodeType(String slug) {
        return new NodeTypeDraft(slug, slug, slug);
    }

    public static PropertyDraft property(String typeSlug, String slug, String dataType) {
        return new PropertyDraft(typeSlug, slug, dataType, false);
    }

    public static EdgeTypeDraft edgeType(String slug, String from, String to) {
        return new EdgeTypeDraft(slug, from, to, "MANY_TO_MANY");
    }

    public record NodeTypeDraft(String slug, String name, String label) {}

    public record PropertyDraft(String typeSlug, String slug, String dataType, boolean required) {}

    public record EdgeTypeDraft(String slug, String sourceTypeSlug, String targetTypeSlug, String cardinality) {}
}
