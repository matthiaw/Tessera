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
package dev.tessera.projections.mcp.api;

import dev.tessera.core.tenant.TenantContext;
import java.util.Map;

/**
 * Tessera-owned tool contract. Spring AI is never imported by implementors.
 * The {@link dev.tessera.projections.mcp.adapter.SpringAiMcpAdapter} bridges
 * to {@code McpSyncServer} (D-A2 isolation boundary, MCP-01).
 *
 * <p>All Phase 3 tools are read-only; {@link #isWriteTool()} defaults to
 * {@code false}. Future write tools override it to trigger quota enforcement
 * in the adapter (SEC-07).
 */
public interface ToolProvider {

    /** Stable, unique tool name exposed to MCP clients (e.g. {@code query_entities}). */
    String toolName();

    /** Human-readable description of what this tool does, shown to agents. */
    String toolDescription();

    /**
     * JSON Schema string describing the tool's input parameters.
     * Must be valid JSON conforming to JSON Schema draft-07 or later.
     */
    String inputSchemaJson();

    /**
     * Execute the tool for the given tenant and agent.
     *
     * @param tenant    the resolved tenant context (model_id)
     * @param agentId   the authenticated agent subject from the JWT
     * @param arguments the parsed tool arguments from the MCP invocation
     * @return the tool result; never {@code null}
     */
    ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments);

    /**
     * Returns {@code true} if this tool performs write operations. Used by
     * {@link dev.tessera.projections.mcp.adapter.SpringAiMcpAdapter} to decide
     * whether to call {@code AgentQuotaService.checkWriteQuota()} (SEC-07).
     *
     * <p>Default is {@code false} — all Phase 3 tools are read-only.
     */
    default boolean isWriteTool() {
        return false;
    }
}
