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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.tessera.core.schema.SchemaChangeEvent;
import dev.tessera.projections.mcp.adapter.SpringAiMcpAdapter;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import dev.tessera.projections.mcp.quota.AgentQuotaService;
import io.modelcontextprotocol.server.McpSyncServer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test verifying that SpringAiMcpAdapter.onSchemaChange() calls
 * notifySchemaChanged() (and thus mcpServer.notifyToolsListChanged()) on schema change.
 */
class SchemaChangeMcpWiringTest {

    private static final UUID MODEL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private McpSyncServer mcpServer;
    private SpringAiMcpAdapter adapter;

    @BeforeEach
    void setUp() {
        mcpServer = mock(McpSyncServer.class);
        McpAuditLog auditLog = mock(McpAuditLog.class);
        AgentQuotaService quotaService = mock(AgentQuotaService.class);
        List<ToolProvider> tools = List.of();
        adapter = new SpringAiMcpAdapter(tools, mcpServer, auditLog, quotaService);
    }

    @Test
    void onSchemaChange_callsNotifyToolsListChanged() {
        // Arrange
        SchemaChangeEvent event = new SchemaChangeEvent(MODEL_ID, "CREATE_TYPE", "widget");

        // Act
        adapter.onSchemaChange(event);

        // Assert — notifySchemaChanged() must call mcpServer.notifyToolsListChanged() exactly once
        verify(mcpServer).notifyToolsListChanged();
    }
}
