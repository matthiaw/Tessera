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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * D-D3 / SEC-08: Comprehensive ArchUnit tests for the MCP tool security boundary.
 *
 * <p>Placed in the {@code arch} package alongside {@link ProjectionsModuleDependencyTest} to
 * co-locate all architecture rules. Wave 0 created a lighter {@code McpSchemaAllowlistArchTest}
 * in the {@code mcp} package; this class adds the full schema-mutation prevention rule.
 */
@AnalyzeClasses(
        packages = "dev.tessera.projections",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class McpMutationAllowlistTest {

    /**
     * D-D3: No MCP tool class may call schema mutation methods on {@code SchemaRegistry}.
     *
     * <p>The allowed read methods are {@code listNodeTypes}, {@code loadFor}, {@code findEdgeType},
     * {@code listExposedTypes}, {@code listDistinctExposedModels}, {@code getAt}, and
     * {@code resolvePropertySlug}. Any method starting with {@code create}, {@code update},
     * {@code delete}, {@code deprecate}, {@code rename}, or {@code remove} is forbidden.
     *
     * <p>Combined with the {@code ToolProvider} interface boundary (tools never see
     * {@code SchemaRegistry} mutation methods directly in normal usage), this rule provides
     * belt-and-suspenders enforcement.
     */
    @ArchTest
    static final ArchRule mcp_tools_must_not_call_schema_mutations = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections.mcp.tools..")
            .should()
            .callMethodWhere(
                    new DescribedPredicate<JavaCall<?>>( // ArchUnit 1.3.x generic form
                            "a schema mutation method on SchemaRegistry") {
                        @Override
                        public boolean test(JavaCall<?> call) {
                            if (!(call.getTarget()
                                    .getOwner()
                                    .isEquivalentTo(dev.tessera.core.schema.SchemaRegistry.class))) {
                                return false;
                            }
                            String name = call.getName();
                            return name.startsWith("create")
                                    || name.startsWith("update")
                                    || name.startsWith("delete")
                                    || name.startsWith("deprecate")
                                    || name.startsWith("rename")
                                    || name.startsWith("remove")
                                    || name.startsWith("addProperty");
                        }
                    });

    /**
     * D-D1: No MCP tool class may call {@link
     * dev.tessera.projections.mcp.interceptor.ToolResponseWrapper} directly.
     *
     * <p>Wrapping is exclusively {@code SpringAiMcpAdapter}'s responsibility. If a tool wraps its
     * own response, the content will be double-wrapped, breaking MCP clients. This rule prevents
     * that class of bug and ensures the prompt injection defence (SEC-08) is applied exactly once.
     */
    @ArchTest
    static final ArchRule mcp_tools_must_not_call_wrapper = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections.mcp.tools..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("dev.tessera.projections.mcp.interceptor.ToolResponseWrapper");

    /**
     * D-A2: No MCP tool class may import Spring AI or MCP SDK types. All Spring AI coupling is
     * isolated to {@code SpringAiMcpAdapter}.
     *
     * <p>This enforces the D-A2 isolation boundary established in Plan 01 and verified by
     * {@code McpIsolationArchTest}, duplicated here for completeness in the arch package.
     */
    @ArchTest
    static final ArchRule mcp_tools_must_not_import_spring_ai = noClasses()
            .that()
            .resideInAPackage("dev.tessera.projections.mcp.tools..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.modelcontextprotocol..",
                    "org.springframework.ai..",
                    "io.modelcontextprotocol.server..",
                    "io.modelcontextprotocol.spec..");
}
