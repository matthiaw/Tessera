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
package dev.tessera.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/**
 * CORE-02: the {@code GraphSession} family inside
 * {@code dev.tessera.core.graph.internal} is the ONLY place allowed to execute
 * raw Cypher or touch pgJDBC directly. Deferred from Phase 0 D-15 and owned by
 * Phase 1 per plan 01-W0-03.
 *
 * <p>Two layers of defense:
 *
 * <ol>
 *   <li>Type-based: anything outside {@code graph.internal} must not depend on
 *       pgJDBC or Spring {@code jdbc.core} classes.
 *   <li>String-pattern: scan compile-time String constants for co-occurring
 *       Cypher keywords or the literal {@code CYPHER('} AGE call.
 * </ol>
 */
@AnalyzeClasses(
        packages = "dev.tessera",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class RawCypherBanTest {

    @ArchTest
    static final ArchRule only_graph_internal_may_touch_pgjdbc = noClasses()
            .that()
            .resideOutsideOfPackage("dev.tessera.core.graph.internal..")
            .and()
            .resideOutsideOfPackage("dev.tessera.core.events..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.postgresql..", "org.springframework.jdbc.core..")
            .because("CORE-02: only graph.internal (Cypher) and events (plain SQL to graph_events/graph_outbox)"
                    + " may touch pgJDBC directly. Cypher strings are still forbidden outside graph.internal by"
                    + " the secondary no_cypher_strings_outside_internal rule below.")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_cypher_strings_outside_internal = noClasses()
            .that()
            .resideOutsideOfPackage("dev.tessera.core.graph.internal..")
            .should(containCypherStringConstant())
            .because("CORE-02: Cypher string literals must live in graph.internal")
            .allowEmptyShould(true);

    private static ArchCondition<JavaClass> containCypherStringConstant() {
        return new ArchCondition<>("contain Cypher string literal") {
            @Override
            public void check(JavaClass cls, ConditionEvents events) {
                cls.getFields().stream()
                        .filter(f -> f.getRawType().getName().equals("java.lang.String"))
                        .filter(f -> f.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC)
                                && f.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.FINAL))
                        .forEach(f -> {
                            Object constant = null;
                            try {
                                java.lang.reflect.Field jf = f.reflect();
                                jf.setAccessible(true);
                                constant = jf.get(null);
                            } catch (ReflectiveOperationException
                                    | IllegalArgumentException
                                    | NullPointerException ex) {
                                // Field is not a readable compile-time constant; skip.
                            }
                            if (constant instanceof String s && looksLikeCypher(s)) {
                                events.add(SimpleConditionEvent.violated(
                                        f, cls.getName() + "." + f.getName() + " looks like Cypher"));
                            }
                        });
            }

            private boolean looksLikeCypher(String s) {
                String upper = s.toUpperCase();
                int hits = 0;
                for (String kw : List.of(" MATCH ", " CREATE ", " MERGE ", " RETURN ", " SET ")) {
                    if (upper.contains(kw)) {
                        hits++;
                    }
                }
                return hits >= 2 || upper.contains("CYPHER('");
            }
        };
    }
}
