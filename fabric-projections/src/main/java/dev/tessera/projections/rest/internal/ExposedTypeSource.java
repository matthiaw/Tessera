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
package dev.tessera.projections.rest.internal;

import java.util.List;

/**
 * WAVE 0 SPIKE — thin pull-side SPI that the {@link SpringDocDynamicSpike}
 * OpenApiCustomizer consults every time SpringDoc rebuilds {@code /v3/api-docs}.
 *
 * <p>The spike intentionally does NOT bind to {@code SchemaRegistry} directly:
 * the whole point of the Wave 0 gate is to prove SpringDoc's lifecycle + cache
 * semantics (RESEARCH assumptions A1 + A7), not to validate Schema Registry
 * wiring. Wave 1/2 replaces the in-memory implementation used by the spike IT
 * with a production adapter that walks {@code SchemaRegistry.allModels()} /
 * {@code SchemaRegistry.exposedTypes(model)} and respects the
 * {@code rest_read_enabled} flag landed by Wave 1.
 */
public interface ExposedTypeSource {

    /**
     * @return the full list of (model, type) tuples that should be projected
     *         as REST paths RIGHT NOW. Order is irrelevant — the customizer
     *         keys by {@code (model, slug)}.
     */
    List<ExposedType> currentlyExposed();

    /** Minimal projection record — one entry per exposed type. */
    record ExposedType(String modelSlug, String typeSlug) {}
}
