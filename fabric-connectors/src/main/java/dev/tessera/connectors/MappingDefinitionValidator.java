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
package dev.tessera.connectors;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates connector configuration at CRUD time and on startup load.
 * Rejects invalid JSONPath expressions, unknown transforms, and
 * unsupported auth types.
 */
public final class MappingDefinitionValidator {

    private MappingDefinitionValidator() {}

    /**
     * Validate a connector's configuration. Returns a list of error messages
     * (empty if valid).
     */
    public static List<String> validate(MappingDefinition mapping, String authType, int pollIntervalSeconds) {
        List<String> errors = new ArrayList<>();

        // Auth type: only BEARER in Phase 2
        if (authType == null || !authType.equalsIgnoreCase("BEARER")) {
            errors.add("auth_type must be 'BEARER', got: " + authType);
        }

        // Poll interval
        if (pollIntervalSeconds < 1) {
            errors.add("poll_interval_seconds must be >= 1, got: " + pollIntervalSeconds);
        }

        if (mapping == null) {
            errors.add("mapping_def must not be null");
            return errors;
        }

        // Root path must be a valid JSONPath
        if (mapping.rootPath() == null || mapping.rootPath().isBlank()) {
            errors.add("rootPath must not be blank");
        } else {
            try {
                JsonPath.compile(mapping.rootPath());
            } catch (InvalidPathException e) {
                errors.add("rootPath is not a valid JSONPath: " + mapping.rootPath());
            }
        }

        // Target type slug
        if (mapping.targetNodeTypeSlug() == null || mapping.targetNodeTypeSlug().isBlank()) {
            errors.add("targetNodeTypeSlug must not be blank");
        }

        // Identity fields
        if (mapping.identityFields() == null || mapping.identityFields().isEmpty()) {
            errors.add("identityFields must not be empty");
        }

        // Field mappings
        if (mapping.fields() == null || mapping.fields().isEmpty()) {
            errors.add("fields must not be empty");
        } else {
            for (int i = 0; i < mapping.fields().size(); i++) {
                FieldMapping fm = mapping.fields().get(i);
                if (fm.target() == null || fm.target().isBlank()) {
                    errors.add("fields[" + i + "].target must not be blank");
                }
                if (fm.sourcePath() == null || fm.sourcePath().isBlank()) {
                    errors.add("fields[" + i + "].sourcePath must not be blank");
                } else {
                    try {
                        JsonPath.compile(fm.sourcePath());
                    } catch (InvalidPathException e) {
                        errors.add("fields[" + i + "].sourcePath is not a valid JSONPath: " + fm.sourcePath());
                    }
                }
                if (fm.transform() != null && !fm.transform().isEmpty() && !TransformRegistry.isValid(fm.transform())) {
                    errors.add("fields[" + i + "].transform is unknown: " + fm.transform());
                }
            }
        }

        // Source URL
        if (mapping.sourceUrl() == null || mapping.sourceUrl().isBlank()) {
            errors.add("sourceUrl must not be blank");
        }

        return errors;
    }
}
