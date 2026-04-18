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
package dev.tessera.projections.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.events.EventLog;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * MCP-07: Reconstruct the state of an entity at a specific point in time.
 *
 * <p>Delegates to {@link EventLog#replayToState} which folds all {@code graph_events}
 * rows up to the given timestamp (EVENT-06). Useful for audit, debugging, and
 * understanding data evolution. Read-only; does NOT override {@link #isWriteTool()}
 * (inherits default {@code false}).
 */
@Component
public class GetStateAtTool implements ToolProvider {

    private final EventLog eventLog;
    private final ObjectMapper objectMapper;

    public GetStateAtTool(EventLog eventLog, ObjectMapper objectMapper) {
        this.eventLog = eventLog;
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolName() {
        return "get_state_at";
    }

    @Override
    public String toolDescription() {
        return "Reconstruct the state of an entity at a specific point in time by replaying its event history."
                + " Returns the entity's properties as they were at the given timestamp."
                + " Useful for auditing, debugging, or understanding how data evolved.";
    }

    @Override
    public String inputSchemaJson() {
        return """
                {"type":"object","properties":{"entity_id":{"type":"string","description":"UUID of the entity"},"timestamp":{"type":"string","description":"ISO-8601 instant, e.g. 2026-01-15T10:30:00Z"}},"required":["entity_id","timestamp"]}
                """
                .strip();
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        String entityIdStr = (String) arguments.get("entity_id");
        String timestampStr = (String) arguments.get("timestamp");

        if (entityIdStr == null || entityIdStr.isBlank()) {
            return ToolResponse.error("entity_id parameter is required");
        }
        if (timestampStr == null || timestampStr.isBlank()) {
            return ToolResponse.error("timestamp parameter is required");
        }

        UUID entityId;
        try {
            entityId = UUID.fromString(entityIdStr);
        } catch (IllegalArgumentException e) {
            return ToolResponse.error("Invalid UUID format for entity_id");
        }

        Instant at;
        try {
            at = Instant.parse(timestampStr);
        } catch (DateTimeParseException e) {
            return ToolResponse.error("Invalid timestamp format. Use ISO-8601 format, e.g. 2026-01-15T10:30:00Z");
        }

        Optional<Map<String, Object>> maybeState = eventLog.replayToState(tenant, entityId, at);
        if (maybeState.isEmpty()) {
            try {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("state", null);
                empty.put("message", "No state found for entity at the specified timestamp");
                return ToolResponse.ok(objectMapper.writeValueAsString(empty));
            } catch (JsonProcessingException e) {
                return ToolResponse.error("Failed to serialize response: " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity_id", entityIdStr);
        result.put("timestamp", timestampStr);
        result.put("state", maybeState.get());

        try {
            return ToolResponse.ok(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize state: " + e.getMessage());
        }
    }
}
