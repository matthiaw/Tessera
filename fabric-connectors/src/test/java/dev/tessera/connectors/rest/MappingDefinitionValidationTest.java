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
package dev.tessera.connectors.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.connectors.FieldMapping;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.MappingDefinitionValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MappingDefinitionValidator}. No DB, no Spring context.
 */
class MappingDefinitionValidationTest {

    private static MappingDefinition validMapping() {
        return new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(new FieldMapping("name", "$.name", "lowercase", false)),
                List.of("name"),
                "http://example.com/api/customers");
    }

    @Test
    void valid_mapping_passes() {
        List<String> errors = MappingDefinitionValidator.validate(validMapping(), "BEARER", 30);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejects_non_bearer_auth_type() {
        List<String> errors = MappingDefinitionValidator.validate(validMapping(), "BASIC", 30);
        assertThat(errors).anyMatch(e -> e.contains("auth_type must be 'BEARER'"));
    }

    @Test
    void rejects_null_auth_type() {
        List<String> errors = MappingDefinitionValidator.validate(validMapping(), null, 30);
        assertThat(errors).anyMatch(e -> e.contains("auth_type must be 'BEARER'"));
    }

    @Test
    void rejects_poll_interval_below_one() {
        List<String> errors = MappingDefinitionValidator.validate(validMapping(), "BEARER", 0);
        assertThat(errors).anyMatch(e -> e.contains("poll_interval_seconds must be >= 1"));
    }

    @Test
    void rejects_invalid_root_path() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "Customer",
                "not-a-json-path[[[",
                List.of(new FieldMapping("name", "$.name", null, false)),
                List.of("name"),
                "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("rootPath is not a valid JSONPath"));
    }

    @Test
    void rejects_blank_root_path() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "Customer",
                "",
                List.of(new FieldMapping("name", "$.name", null, false)),
                List.of("name"),
                "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("rootPath must not be blank"));
    }

    @Test
    void rejects_invalid_source_path_in_field() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(new FieldMapping("name", "invalid[[[path", null, false)),
                List.of("name"),
                "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("sourcePath is not a valid JSONPath"));
    }

    @Test
    void rejects_unknown_transform() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(new FieldMapping("name", "$.name", "bazinga", false)),
                List.of("name"),
                "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("transform is unknown: bazinga"));
    }

    @Test
    void accepts_all_valid_transforms() {
        for (String t : List.of(
                "lowercase", "uppercase", "trim", "iso8601-date", "parse-int", "parse-decimal", "sha256", "none")) {
            MappingDefinition def = new MappingDefinition(
                    "customer",
                    "Customer",
                    "$.data[*]",
                    List.of(new FieldMapping("name", "$.name", t, false)),
                    List.of("name"),
                    "http://example.com/api");
            List<String> errors = MappingDefinitionValidator.validate(def, "BEARER", 30);
            assertThat(errors).as("transform '%s' should be valid", t).isEmpty();
        }
    }

    @Test
    void rejects_empty_identity_fields() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(new FieldMapping("name", "$.name", null, false)),
                List.of(),
                "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("identityFields must not be empty"));
    }

    @Test
    void rejects_empty_fields() {
        MappingDefinition bad = new MappingDefinition(
                "customer", "Customer", "$.data[*]", List.of(), List.of("name"), "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("fields must not be empty"));
    }

    @Test
    void rejects_blank_target_node_type_slug() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "",
                "$.data[*]",
                List.of(new FieldMapping("name", "$.name", null, false)),
                List.of("name"),
                "http://example.com/api");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("targetNodeTypeSlug must not be blank"));
    }

    @Test
    void rejects_null_mapping() {
        List<String> errors = MappingDefinitionValidator.validate(null, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("mapping_def must not be null"));
    }

    @Test
    void rejects_blank_source_url() {
        MappingDefinition bad = new MappingDefinition(
                "customer",
                "Customer",
                "$.data[*]",
                List.of(new FieldMapping("name", "$.name", null, false)),
                List.of("name"),
                "");
        List<String> errors = MappingDefinitionValidator.validate(bad, "BEARER", 30);
        assertThat(errors).anyMatch(e -> e.contains("sourceUrl must not be blank"));
    }
}
