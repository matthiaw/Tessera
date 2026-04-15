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

/**
 * VALID-01 / VALID-04: thrown by {@link ShaclValidator#validate} when SHACL
 * rejects a mutation. The attached {@link RedactedValidationReport} has
 * already been filtered to ensure no literal values, no neighboring tenant
 * data, and no Jena object references leak via the error path
 * (see {@link ValidationReportFilter}).
 */
public class ShaclValidationException extends RuntimeException {

    private final RedactedValidationReport report;

    public ShaclValidationException(String message, RedactedValidationReport report) {
        super(message);
        this.report = report;
    }

    public RedactedValidationReport report() {
        return report;
    }
}
