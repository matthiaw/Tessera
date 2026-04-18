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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicSchemaOutputConverterTest {

    private static final String SAMPLE_SCHEMA =
            """
            {
              "entityTypes": [
                {"typeSlug": "person", "properties": ["name", "email"]},
                {"typeSlug": "organization", "properties": ["name", "industry"]}
              ]
            }
            """;

    @Test
    void getFormat_includesJsonSchema() {
        var converter = new DynamicSchemaOutputConverter(SAMPLE_SCHEMA);
        String format = converter.getFormat();

        assertThat(format).contains(SAMPLE_SCHEMA.trim());
        assertThat(format).containsIgnoringCase("JSON");
    }

    @Test
    void convert_parsesValidJsonArray() {
        var converter = new DynamicSchemaOutputConverter(SAMPLE_SCHEMA);
        String json =
                """
                [
                  {
                    "typeSlug": "person",
                    "name": "Jane Smith",
                    "properties": {"email": "jane@example.com"},
                    "sourceSpan": {"charOffset": 10, "charLength": 20},
                    "confidence": 0.95,
                    "relationships": [
                      {"edgeType": "WORKS_AT", "targetName": "Acme Corp", "targetType": "organization"}
                    ]
                  }
                ]
                """;

        List<ExtractionCandidate> candidates = converter.convert(json);

        assertThat(candidates).hasSize(1);
        ExtractionCandidate candidate = candidates.get(0);
        assertThat(candidate.typeSlug()).isEqualTo("person");
        assertThat(candidate.name()).isEqualTo("Jane Smith");
        assertThat(candidate.sourceSpan().charOffset()).isEqualTo(10);
        assertThat(candidate.sourceSpan().charLength()).isEqualTo(20);
        assertThat(candidate.confidence()).isEqualByComparingTo("0.95");
        assertThat(candidate.relationships()).hasSize(1);
        assertThat(candidate.relationships().get(0).edgeType()).isEqualTo("WORKS_AT");
    }

    @Test
    void convert_stripsMarkdownCodeFences() {
        var converter = new DynamicSchemaOutputConverter(SAMPLE_SCHEMA);
        String json =
                """
                ```json
                [
                  {
                    "typeSlug": "person",
                    "name": "John",
                    "properties": {},
                    "sourceSpan": {"charOffset": 0, "charLength": 4},
                    "confidence": 0.8,
                    "relationships": []
                  }
                ]
                ```
                """;

        List<ExtractionCandidate> candidates = converter.convert(json);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).name()).isEqualTo("John");
    }

    @Test
    void convert_throwsOnInvalidJson() {
        var converter = new DynamicSchemaOutputConverter(SAMPLE_SCHEMA);
        assertThatThrownBy(() -> converter.convert("this is not json")).isInstanceOf(RuntimeException.class);
    }
}
