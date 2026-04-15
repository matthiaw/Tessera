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
package dev.tessera.core.validation;

import java.util.List;

/**
 * VALID-04 / CRIT-6: tenant-filtered SHACL validation result. Carries NO
 * Jena types, NO literal values, NO neighboring node data — only the shape
 * IRI, constraint component, and the Tessera-local focus UUID of the
 * offending node. Safe to log and safe to return to API callers.
 */
public record RedactedValidationReport(boolean conforms, List<RedactedViolation> violations) {

    public RedactedValidationReport {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * Single filtered violation. No literal value, no message that could
     * contain cross-tenant data — only structural metadata.
     */
    public record RedactedViolation(
            String focusNodeUuid, String resultPath, String constraintComponent, String severity) {}
}
