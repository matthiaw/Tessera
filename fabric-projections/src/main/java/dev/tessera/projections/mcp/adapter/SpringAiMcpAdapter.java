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
package dev.tessera.projections.mcp.adapter;

import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import dev.tessera.projections.mcp.interceptor.ToolResponseWrapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * D-A2 isolation boundary: the ONLY class in the codebase that imports Spring AI /
 * MCP SDK types. All {@link ToolProvider} implementors remain Spring AI-free.
 *
 * <p>Registers all {@link ToolProvider} beans with {@link McpSyncServer} at
 * application startup via {@link ApplicationRunner} (avoids Springdoc lifecycle
 * Pitfall 6 — runs after full context refresh). Wraps every tool response with
 * {@link ToolResponseWrapper} to mitigate prompt injection (SEC-08).
 *
 * <p>Tenant and agent identity are extracted from the JWT in the Spring Security
 * context on each invocation (MCP-01, T-03-01). Quota enforcement for write tools
 * is left as a TODO for Plan 03.
 */
@Component
public class SpringAiMcpAdapter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiMcpAdapter.class);

    private final List<ToolProvider> tools;
    private final McpSyncServer mcpServer;

    public SpringAiMcpAdapter(List<ToolProvider> tools, McpSyncServer mcpServer) {
        this.tools = tools;
        this.mcpServer = mcpServer;
    }

    /**
     * Register all {@link ToolProvider} beans as MCP tools on application startup.
     * Runs after the full Spring context is ready (ApplicationRunner guarantee).
     */
    @Override
    public void run(ApplicationArguments args) {
        for (ToolProvider tool : tools) {
            McpSchema.Tool mcpTool =
                    new McpSchema.Tool(tool.toolName(), tool.toolDescription(), tool.inputSchemaJson());
            McpServerFeatures.SyncToolSpecification spec =
                    new McpServerFeatures.SyncToolSpecification(mcpTool, (exchange, params) -> invokeTool(tool, params));
            mcpServer.addTool(spec);
            log.info("Registered MCP tool: {}", tool.toolName());
        }
        log.info("Registered {} MCP tool(s)", tools.size());
    }

    /**
     * Notify connected MCP clients that the tools list has changed.
     * Call this when the schema changes to trigger client re-discovery.
     *
     * <p>TODO Plan 03: wire to SchemaChangeEvent via ApplicationListener once
     * the event type is defined in fabric-core.
     */
    public void notifySchemaChanged() {
        mcpServer.notifyToolsListChanged();
        log.debug("Notified MCP clients of tools list change");
    }

    /**
     * Invoke a single tool: extract tenant + agent from JWT, call the tool,
     * wrap the response in data markers, and return a {@link McpSchema.CallToolResult}.
     */
    private McpSchema.CallToolResult invokeTool(ToolProvider tool, Map<String, Object> params) {
        long start = System.nanoTime();
        String outcome = "SUCCESS";
        try {
            // Extract tenant and agent from the JWT in the security context (T-03-01).
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            UUID modelId = UUID.fromString(auth.getName());
            TenantContext ctx = TenantContext.of(modelId);
            String agentId = extractAgentId(auth);

            // TODO Plan 03: if (tool.isWriteTool()) agentQuotaService.checkWriteQuota(ctx, agentId);

            ToolResponse response = tool.execute(ctx, agentId, params != null ? params : Map.of());

            if (!response.success()) {
                outcome = "ERROR";
            }

            String wrapped = ToolResponseWrapper.wrap(response.content());

            // TODO Plan 03: mcpAuditLog.record(ctx, agentId, tool.toolName(), params, outcome, durationMs);

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(wrapped)), !response.success());

        } catch (Exception ex) {
            outcome = "EXCEPTION";
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("MCP tool {} failed after {}ms: {}", tool.toolName(), durationMs, ex.getMessage());
            String wrapped = ToolResponseWrapper.wrap("Tool execution failed: " + ex.getMessage());
            // TODO Plan 03: mcpAuditLog.record(ctx, agentId, tool.toolName(), params, outcome, durationMs);
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(wrapped)), true);
        }
    }

    private static String extractAgentId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtToken) {
            String sub = jwtToken.getToken().getSubject();
            return sub != null ? sub : auth.getName();
        }
        return auth.getName();
    }
}
