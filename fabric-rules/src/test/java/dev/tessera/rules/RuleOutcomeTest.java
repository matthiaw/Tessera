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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** RULE-03 / ADR-7 §RULE-03: sealed outcome with exactly six permitted cases. */
class RuleOutcomeTest {

    @Test
    void sealed_hierarchy_has_exactly_six_permitted_subtypes() {
        Class<?>[] permitted = RuleOutcome.class.getPermittedSubclasses();
        assertThat(permitted).isNotNull();
        Set<String> names = Set.of("Commit", "Reject", "Merge", "Override", "Add", "Route");
        assertThat(permitted).hasSize(6);
        for (Class<?> c : permitted) {
            assertThat(names).contains(c.getSimpleName());
        }
    }

    @Test
    void all_six_outcomes_construct_and_exhaustive_switch_works() {
        RuleOutcome[] outcomes = {
            RuleOutcome.Commit.INSTANCE,
            new RuleOutcome.Reject("bad"),
            new RuleOutcome.Merge("email", "a@b"),
            new RuleOutcome.Override("email", "a@b", "obsidian", "c@d"),
            new RuleOutcome.Add("derived", 42),
            new RuleOutcome.Route(Map.of("topic", "dlq")),
        };
        for (RuleOutcome o : outcomes) {
            String tag =
                    switch (o) {
                        case RuleOutcome.Commit c -> "commit";
                        case RuleOutcome.Reject r -> "reject:" + r.reason();
                        case RuleOutcome.Merge m -> "merge:" + m.propertySlug();
                        case RuleOutcome.Override ov -> "override:" + ov.propertySlug();
                        case RuleOutcome.Add a -> "add:" + a.propertySlug();
                        case RuleOutcome.Route r -> "route:" + r.routingHints().size();
                    };
            assertThat(tag).isNotBlank();
        }
    }

    @Test
    void reject_records_reason() {
        RuleOutcome.Reject r = new RuleOutcome.Reject("echo loop");
        assertThat(r.reason()).isEqualTo("echo loop");
    }

    @Test
    void override_records_losing_source_and_value() {
        RuleOutcome.Override ov = new RuleOutcome.Override("name", "Alice", "obsidian", "alice");
        assertThat(ov.losingSourceSystem()).isEqualTo("obsidian");
        assertThat(ov.losingValue()).isEqualTo("alice");
    }
}
