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

import java.util.Map;
import java.util.Objects;

/**
 * Sealed rule outcome hierarchy per ADR-7 §RULE-03. Exactly six permitted
 * subtypes: COMMIT, REJECT, MERGE, OVERRIDE, ADD, ROUTE. The original
 * REQUIREMENTS.md outcomes {@code FLAG_FOR_REVIEW} and {@code DEFER} are
 * intentionally absent — per D-A2 the review queue moved to Phase 2.5 as a
 * pre-funnel layer and is NOT a rule engine terminal outcome.
 *
 * <p>Instances are case records, usable in exhaustive switch expressions.
 */
public sealed interface RuleOutcome
        permits RuleOutcome.Commit,
                RuleOutcome.Reject,
                RuleOutcome.Merge,
                RuleOutcome.Override,
                RuleOutcome.Add,
                RuleOutcome.Route {

    /** Continue to the next rule in the chain — no mutation side-effect. */
    record Commit() implements RuleOutcome {
        public static final Commit INSTANCE = new Commit();
    }

    /** Short-circuit the pipeline and reject the mutation. VALIDATE chain outcome. */
    record Reject(String reason) implements RuleOutcome {
        public Reject {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** Merge a property value with the existing one. RECONCILE chain. */
    record Merge(String propertySlug, Object value) implements RuleOutcome {
        public Merge {
            Objects.requireNonNull(propertySlug, "propertySlug must not be null");
        }
    }

    /**
     * Replace an existing property value and emit a conflict record for the
     * losing source. RECONCILE chain outcome; triggers a write to
     * {@code reconciliation_conflicts}.
     */
    record Override(String propertySlug, Object value, String losingSourceSystem, Object losingValue)
            implements RuleOutcome {
        public Override {
            Objects.requireNonNull(propertySlug, "propertySlug must not be null");
            Objects.requireNonNull(losingSourceSystem, "losingSourceSystem must not be null");
        }
    }

    /** Add a derived / enriched property. ENRICH chain outcome. */
    record Add(String propertySlug, Object value) implements RuleOutcome {
        public Add {
            Objects.requireNonNull(propertySlug, "propertySlug must not be null");
        }
    }

    /** Emit downstream routing hints onto the outbox row. ROUTE chain outcome. */
    record Route(Map<String, Object> routingHints) implements RuleOutcome {
        public Route {
            routingHints = routingHints == null ? Map.of() : Map.copyOf(routingHints);
        }
    }
}
