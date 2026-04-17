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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * MCP-09: McpAuditLog.record() must INSERT a row to mcp_audit_log with all required fields.
 *
 * <p>Stub created in Wave 0; fleshed out after Plan 03 creates McpAuditLog.
 */
class McpAuditLogTest {

    @Test
    @Disabled("Stub: enable after Plan 03 creates McpAuditLog")
    void records_successful_invocation() {
        // Call record() with SUCCESS outcome, assert row in DB via JDBC
    }

    @Test
    @Disabled("Stub: enable after Plan 03 creates McpAuditLog")
    void records_failed_invocation() {
        // Call record() with ERROR outcome, assert row in DB via JDBC
    }

    @Test
    @Disabled("Stub: enable after Plan 03 creates McpAuditLog")
    void count_for_agent_since_returns_correct_count() {
        // Insert 3 audit rows, call countForAgentSince(), assert 3
    }
}
