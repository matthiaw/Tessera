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
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * D-15 module-direction enforcement. Only the upward dependency direction is
 * enforced here; the raw-Cypher ban (CORE-02 / graph.internal) is explicitly
 * deferred to Phase 1 where GraphSession exists.
 *
 * <p>Complements the POM-level banCircularDependencies enforcer rule (plan
 * 00-01) with a class-level cycle check (the runtime half of D-13).
 */
@AnalyzeClasses(
        packages = "dev.tessera",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class ModuleDependencyTest {

    @ArchTest
    static final ArchRule fabric_core_should_not_depend_on_others = noClasses()
            .that()
            .resideInAPackage("dev.tessera.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "dev.tessera.rules..",
                    "dev.tessera.projections..",
                    "dev.tessera.connectors..",
                    "dev.tessera.app..");

    @ArchTest
    static final ArchRule fabric_rules_should_only_depend_on_core = noClasses()
            .that()
            .resideInAPackage("dev.tessera.rules..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.tessera.projections..", "dev.tessera.connectors..", "dev.tessera.app..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule fabric_projections_should_not_depend_on_connectors_or_app = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.tessera.connectors..", "dev.tessera.app..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule fabric_connectors_should_not_depend_on_projections_or_app = noClasses()
            .that()
            .resideInAPackage("dev.tessera.connectors..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.tessera.projections..", "dev.tessera.app..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule modules_should_be_free_of_cycles =
            slices().matching("dev.tessera.(*)..").should().beFreeOfCycles();
}
