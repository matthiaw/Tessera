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
package dev.tessera.projections.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * W2a: fabric-projections classes must not import graph.internal classes
 * directly. The projection layer accesses the graph exclusively through
 * the public {@code GraphRepository} and {@code GraphService} interfaces.
 */
@AnalyzeClasses(
        packages = "dev.tessera.projections",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class ProjectionsModuleDependencyTest {

    @ArchTest
    static final ArchRule projections_must_not_import_graph_internal = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.tessera.core.graph.internal..");
}
