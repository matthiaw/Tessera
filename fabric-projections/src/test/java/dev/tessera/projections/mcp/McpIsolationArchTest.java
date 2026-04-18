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
package dev.tessera.projections.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * MCP-01: Only SpringAiMcpAdapter may import Spring AI classes. All tool classes implement
 * Tessera-owned ToolProvider interface (D-A2).
 *
 * <p>Stub created in Wave 0; rule active immediately after Plan 01 creates MCP classes. Passes
 * vacuously when no production classes exist in the scanned package.
 */
@AnalyzeClasses(
        packages = "dev.tessera.projections.mcp",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class McpIsolationArchTest {

    @ArchTest
    static final ArchRule only_adapter_imports_spring_ai = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections.mcp.tools..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.ai..");
}
