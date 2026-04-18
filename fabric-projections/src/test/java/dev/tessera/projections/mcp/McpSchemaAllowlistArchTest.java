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
 * D-D3: No MCP tool class may call schema mutation methods on SchemaRegistry.
 *
 * <p>Stub created in Wave 0; rule active immediately after Plan 02 creates tool classes. Passes
 * vacuously when no production classes exist in the scanned package. Plan 04 Task 1 will add the
 * full {@code mcp_tools_must_not_call_schema_mutations} rule alongside this one.
 */
@AnalyzeClasses(
        packages = "dev.tessera.projections.mcp",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class McpSchemaAllowlistArchTest {

    @ArchTest
    static final ArchRule mcp_tools_must_not_call_wrapper = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections.mcp.tools..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("dev.tessera.projections.mcp.interceptor.ToolResponseWrapper");
}
