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
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import dev.tessera.projections.rest.CursorCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * MCP-03: Query entities of a specific type with optional property filters and cursor pagination.
 *
 * <p>Reuses {@link CursorCodec} from the REST projection for stable opaque cursors.
 * Limit defaults to 20, capped at 100 (T-03-09 DoS mitigation). Read-only; does NOT override
 * {@link #isWriteTool()} (inherits default {@code false}).
 */
@Component
public class QueryEntitiesTool implements ToolProvider {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final GraphRepository graphRepository;
    private final ObjectMapper objectMapper;

    public QueryEntitiesTool(GraphRepository graphRepository, ObjectMapper objectMapper) {
        this.graphRepository = graphRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolName() {
        return "query_entities";
    }

    @Override
    public String toolDescription() {
        return "Query entities of a specific type with optional property filters and cursor-based pagination."
                + " Returns a page of entities and a next_cursor for pagination.";
    }

    @Override
    public String inputSchemaJson() {
        return """
                {"type":"object","properties":{"type":{"type":"string","description":"Entity type slug"},"filter":{"type":"object","description":"Property key-value pairs to filter on"},"cursor":{"type":"string","description":"Opaque pagination cursor from a previous response"},"limit":{"type":"integer","description":"Number of results per page (default 20, max 100)","default":20,"minimum":1,"maximum":100}},"required":["type"]}
                """.strip();
    }

    @Override
    public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
        String type = (String) arguments.get("type");
        if (type == null || type.isBlank()) {
            return ToolResponse.error("type parameter is required");
        }

        // Parse limit (default 20, max 100 — T-03-09)
        int limit = DEFAULT_LIMIT;
        Object limitArg = arguments.get("limit");
        if (limitArg != null) {
            if (limitArg instanceof Number n) {
                limit = n.intValue();
            } else {
                try {
                    limit = Integer.parseInt(limitArg.toString());
                } catch (NumberFormatException e) {
                    return ToolResponse.error("limit must be an integer");
                }
            }
            if (limit < 1) limit = 1;
            if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        }

        // Decode cursor for afterSeq
        long afterSeq = 0L;
        String cursorArg = (String) arguments.get("cursor");
        if (cursorArg != null && !cursorArg.isBlank()) {
            try {
                CursorCodec.CursorPosition pos = CursorCodec.decode(cursorArg);
                afterSeq = pos.lastSeq();
            } catch (Exception e) {
                return ToolResponse.error("Invalid cursor: " + e.getMessage());
            }
        }

        // Fetch limit+1 to determine if there is a next page
        List<NodeState> raw = graphRepository.queryAllAfter(tenant, type, afterSeq, limit + 1);

        // Apply in-memory filter if provided
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) arguments.get("filter");
        List<NodeState> filtered;
        if (filter != null && !filter.isEmpty()) {
            filtered = new ArrayList<>();
            for (NodeState node : raw) {
                if (matchesFilter(node, filter)) {
                    filtered.add(node);
                }
            }
        } else {
            filtered = new ArrayList<>(raw);
        }

        boolean hasMore = filtered.size() > limit;
        if (hasMore) {
            filtered = filtered.subList(0, limit);
        }

        String nextCursor = null;
        if (hasMore && !filtered.isEmpty()) {
            NodeState last = filtered.get(filtered.size() - 1);
            nextCursor = CursorCodec.encode(tenant.modelId(), type, last.seq(), last.uuid());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", filtered.stream().map(ToolNodeSerializer::toMap).toList());
        result.put("next_cursor", nextCursor);

        try {
            return ToolResponse.ok(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize query results: " + e.getMessage());
        }
    }

    private boolean matchesFilter(NodeState node, Map<String, Object> filter) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object nodeValue = node.properties().get(entry.getKey());
            if (nodeValue == null) return false;
            if (!nodeValue.toString().equals(entry.getValue().toString())) return false;
        }
        return true;
    }
}
