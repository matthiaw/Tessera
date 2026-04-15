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
package dev.tessera.rules;

/**
 * Per-property conflict decision emitted by a RECONCILE chain
 * {@link RuleOutcome.Override}. Persisted to {@code reconciliation_conflicts}
 * by {@code ReconciliationConflictsRepository} inside the same Postgres TX as
 * the Cypher write (D-C3).
 */
public record ConflictRecord(
        String typeSlug,
        String propertySlug,
        String losingSourceId,
        String losingSourceSystem,
        Object losingValue,
        String winningSourceId,
        String winningSourceSystem,
        Object winningValue,
        String ruleId,
        String reason) {}
