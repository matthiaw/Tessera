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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * A {@link StructuredOutputConverter} that uses a runtime JSON schema derived
 * from the Schema Registry to instruct the LLM and parse its output.
 *
 * <p>Unlike {@code BeanOutputConverter}, this converter does not require compile-time
 * types -- the schema is built dynamically from the tenant's registered node and
 * edge types. Per AI-SPEC Section 4b.
 */
public class DynamicSchemaOutputConverter implements StructuredOutputConverter<List<ExtractionCandidate>> {

    private static final TypeReference<List<ExtractionCandidate>> TYPE_REF = new TypeReference<>() {};

    private final String jsonSchema;
    private final ObjectMapper objectMapper;

    public DynamicSchemaOutputConverter(String jsonSchema) {
        this.jsonSchema = jsonSchema;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getFormat() {
        return """
                Respond with a JSON array of extracted entities matching this schema:
                %s
                Each entity MUST include sourceSpan (character offset and length in source text).
                Each entity MUST include confidence (0.0-1.0).
                If no entities found, return an empty array [].
                """
                .formatted(jsonSchema);
    }

    @Override
    public List<ExtractionCandidate> convert(String text) {
        String cleaned = text.strip();
        // Strip markdown code fences that LLMs often wrap around JSON output
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```json?\\n?", "")
                    .replaceAll("```\\s*$", "")
                    .strip();
        }
        try {
            return objectMapper.readValue(cleaned, TYPE_REF);
        } catch (Exception e) {
            throw new ExtractionException("Failed to parse LLM extraction output as JSON", e);
        }
    }
}
