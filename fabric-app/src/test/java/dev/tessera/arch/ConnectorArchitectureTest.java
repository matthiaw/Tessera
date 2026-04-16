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

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 02-W3-01: ArchUnit gate proving fabric-connectors respects module
 * boundaries. Connector implementations MUST NOT call GraphService
 * directly (only ConnectorRunner may) and MUST NOT import
 * graph.internal or rules.internal.
 */
@AnalyzeClasses(
        packages = "dev.tessera",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class ConnectorArchitectureTest {

    @ArchTest
    static final ArchRule connectors_must_not_access_graph_internal = noClasses()
            .that()
            .resideInAPackage("dev.tessera.connectors..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.tessera.core.graph.internal..");

    @ArchTest
    static final ArchRule connectors_must_not_access_rules_internal = noClasses()
            .that()
            .resideInAPackage("dev.tessera.connectors..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.tessera.rules..internal..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule connector_impls_must_not_depend_on_graph_service = noClasses()
            .that()
            .implement(dev.tessera.connectors.Connector.class)
            .should()
            .dependOnClassesThat()
            .haveSimpleName("GraphService")
            .allowEmptyShould(true);

    // Phase 2.5 EXTR-06: Extraction and resolution paths cannot bypass the write funnel.
    // Only ConnectorRunner (in connectors.internal) may call GraphService.apply().

    @ArchTest
    static final ArchRule extraction_classes_must_not_depend_on_graph_service = noClasses()
            .that()
            .resideInAPackage("dev.tessera.connectors.extraction..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("GraphService")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule resolution_classes_must_not_depend_on_graph_service = noClasses()
            .that()
            .resideInAPackage("dev.tessera.rules.resolution..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("GraphService")
            .allowEmptyShould(true);
}
