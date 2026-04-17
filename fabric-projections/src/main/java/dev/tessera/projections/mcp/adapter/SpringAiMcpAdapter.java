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

import dev.tessera.core.schema.SchemaChangeEvent;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import dev.tessera.projections.mcp.interceptor.ToolResponseWrapper;
import dev.tessera.projections.mcp.quota.AgentQuotaService;
import dev.tessera.projections.mcp.quota.QuotaExceededException;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 * context on each invocation (MCP-01, T-03-01). Every invocation is audited via
 * {@link McpAuditLog} (MCP-09) and write tools are gated by {@link AgentQuotaService}
 * (SEC-07).
 */
@Component
public class SpringAiMcpAdapter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiMcpAdapter.class);

    private final List<ToolProvider> tools;
    private final McpSyncServer mcpServer;
    private final McpAuditLog mcpAuditLog;
    private final AgentQuotaService agentQuotaService;

    public SpringAiMcpAdapter(
            List<ToolProvider> tools,
            McpSyncServer mcpServer,
            McpAuditLog mcpAuditLog,
            AgentQuotaService agentQuotaService) {
        this.tools = tools;
        this.mcpServer = mcpServer;
        this.mcpAuditLog = mcpAuditLog;
        this.agentQuotaService = agentQuotaService;
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
            McpServerFeatures.SyncToolSpecification spec = new McpServerFeatures.SyncToolSpecification(
                    mcpTool, (exchange, params) -> invokeTool(tool, params));
            mcpServer.addTool(spec);
            log.info("Registered MCP tool: {}", tool.toolName());
        }
        log.info("Registered {} MCP tool(s)", tools.size());
    }

    /**
     * Notify connected MCP clients that the tools list has changed.
     * Call this when the schema changes to trigger client re-discovery.
     */
    public void notifySchemaChanged() {
        mcpServer.notifyToolsListChanged();
        log.debug("Notified MCP clients of tools list change");
    }

    /**
     * MCP-08: Notify connected MCP clients that the tools list may have changed after a schema
     * mutation. Uses AFTER_COMMIT to ensure the notification fires only once the schema change is
     * durably persisted. Resolves the previous TODO in notifySchemaChanged().
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSchemaChange(SchemaChangeEvent event) {
        log.debug(
                "SpringAiMcpAdapter: schema change {}/{} — notifying MCP clients (model_id={})",
                event.changeType(),
                event.typeSlug(),
                event.modelId());
        notifySchemaChanged();
    }

    /**
     * Invoke a single tool: extract tenant + agent from JWT, enforce write quota
     * if applicable, execute the tool, wrap the response, and record the audit log.
     *
     * <p>Flow: authenticate -> extract tenant -> [quota check if tool.isWriteTool()]
     * -> tool.execute() -> wrap response -> mcpAuditLog.record() -> return result.
     */
    private McpSchema.CallToolResult invokeTool(ToolProvider tool, Map<String, Object> params) {
        long start = System.nanoTime();
        String outcome = "SUCCESS";
        TenantContext ctx = null;
        String agentId = "unknown";
        try {
            // Extract tenant and agent from the JWT in the security context (T-03-01).
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            UUID modelId = UUID.fromString(auth.getName());
            ctx = TenantContext.of(modelId);
            agentId = extractAgentId(auth);

            // SEC-07: gate write tools by per-agent hourly quota (T-03-10).
            if (tool.isWriteTool()) {
                try {
                    agentQuotaService.checkWriteQuota(ctx, agentId);
                } catch (QuotaExceededException qex) {
                    long durationMs = (System.nanoTime() - start) / 1_000_000;
                    mcpAuditLog.record(ctx, agentId, tool.toolName(), params, "QUOTA_EXCEEDED", durationMs);
                    String wrapped = ToolResponseWrapper.wrap("Write quota exceeded: " + qex.getMessage());
                    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(wrapped)), true);
                }
            }

            ToolResponse response = tool.execute(ctx, agentId, params != null ? params : Map.of());

            if (!response.success()) {
                outcome = "ERROR";
            }

            String wrapped = ToolResponseWrapper.wrap(response.content());

            long durationMs = (System.nanoTime() - start) / 1_000_000;
            mcpAuditLog.record(ctx, agentId, tool.toolName(), params, outcome, durationMs);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(wrapped)), !response.success());

        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String exOutcome = "EXCEPTION: " + ex.getMessage();
            log.warn("MCP tool {} failed after {}ms: {}", tool.toolName(), durationMs, ex.getMessage());
            if (ctx != null) {
                mcpAuditLog.record(ctx, agentId, tool.toolName(), params, exOutcome, durationMs);
            }
            String wrapped = ToolResponseWrapper.wrap("Tool execution failed: " + ex.getMessage());
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
