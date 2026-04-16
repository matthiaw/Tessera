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
package dev.tessera.connectors.extraction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A candidate entity extracted from unstructured text by the LLM.
 * Carries type, name, properties, source span, confidence, and relationships.
 *
 * <p>Annotated with {@code @JsonIgnoreProperties(ignoreUnknown = true)} for
 * forward compatibility with future LLM output fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionCandidate(
        String typeSlug,
        String name,
        Map<String, Object> properties,
        SourceSpan sourceSpan,
        BigDecimal confidence,
        List<ExtractedRelationship> relationships) {

    /** Character span in the source text where this entity was found. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceSpan(int charOffset, int charLength) {}

    /** A relationship extracted alongside the entity. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedRelationship(String edgeType, String targetName, String targetType) {}
}
