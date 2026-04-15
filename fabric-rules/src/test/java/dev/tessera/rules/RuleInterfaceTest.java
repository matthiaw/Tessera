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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * RULE-02 / ADR-7 §RULE-02 — pin the {@link Rule} interface signature. The
 * production rule engine and every built-in rule must match this contract
 * exactly; changing it breaks ADR-7 and requires a follow-up ADR.
 */
class RuleInterfaceTest {

    @Test
    void rule_interface_has_exactly_five_abstract_methods_per_ADR_7() {
        List<String> methodNames = Arrays.stream(Rule.class.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .toList();
        assertThat(methodNames).containsExactly("applies", "chain", "evaluate", "id", "priority");
    }

    @Test
    void minimal_rule_impl_compiles_and_implements_contract() {
        Rule r = new Rule() {
            @Override
            public String id() {
                return "test.minimal";
            }

            @Override
            public Chain chain() {
                return Chain.VALIDATE;
            }

            @Override
            public int priority() {
                return 10;
            }

            @Override
            public boolean applies(RuleContext ctx) {
                return true;
            }

            @Override
            public RuleOutcome evaluate(RuleContext ctx) {
                return RuleOutcome.Commit.INSTANCE;
            }
        };
        assertThat(r.id()).isEqualTo("test.minimal");
        assertThat(r.chain()).isEqualTo(Chain.VALIDATE);
        assertThat(r.priority()).isEqualTo(10);
    }

    @Test
    void chain_enum_has_fixed_order_VALIDATE_RECONCILE_ENRICH_ROUTE() {
        // D-C1 fixed pipeline order — lock this explicitly.
        assertThat(Chain.values()).containsExactly(Chain.VALIDATE, Chain.RECONCILE, Chain.ENRICH, Chain.ROUTE);
    }

    @Test
    void id_method_returns_string_type() throws NoSuchMethodException {
        Method idMethod = Rule.class.getDeclaredMethod("id");
        assertThat(idMethod.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void priority_method_returns_int_type() throws NoSuchMethodException {
        Method priorityMethod = Rule.class.getDeclaredMethod("priority");
        assertThat(priorityMethod.getReturnType()).isEqualTo(int.class);
    }

    @Test
    void rule_package_has_no_disallowed_terminal_outcome_per_D_A2() {
        // D-A2: review queue moved to Phase 2.5; no FLAG_FOR_REVIEW / DEFER outcomes.
        Class<?>[] permitted = RuleOutcome.class.getPermittedSubclasses();
        Set<String> names = Arrays.stream(permitted)
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(names).doesNotContain("FlagForReview", "Defer", "AcceptSource", "Transform");
    }
}
