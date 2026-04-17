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

import dev.tessera.core.graph.NodeState;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.security.AclFilterService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared serialization helper for MCP tool classes. Converts a {@link NodeState}
 * to a plain {@link Map} with consistent keys for JSON serialization.
 *
 * <p>Package-private — used only within {@code mcp.tools} to avoid leaking
 * the serialization convention outside the tools package.
 */
final class ToolNodeSerializer {

    private ToolNodeSerializer() {}

    /**
     * Convert a {@link NodeState} to a {@code Map<String, Object>} suitable for
     * Jackson serialization. Keys: {@code uuid}, {@code type}, {@code properties},
     * {@code created_at}, {@code updated_at}.
     */
    static Map<String, Object> toMap(NodeState node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", node.uuid() != null ? node.uuid().toString() : null);
        m.put("type", node.typeSlug());
        m.put("properties", node.properties());
        m.put("created_at", node.createdAt() != null ? node.createdAt().toString() : null);
        m.put("updated_at", node.updatedAt() != null ? node.updatedAt().toString() : null);
        return m;
    }

    /**
     * ACL-aware variant: filters properties through {@link AclFilterService} before
     * serialization. Used by MCP tools that need field-level access control.
     */
    static Map<String, Object> toMap(NodeState node, AclFilterService aclFilterService,
            NodeTypeDescriptor descriptor, Set<String> callerRoles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", node.uuid() != null ? node.uuid().toString() : null);
        m.put("type", node.typeSlug());
        m.put("properties", aclFilterService.filterProperties(node, descriptor, callerRoles));
        m.put("created_at", node.createdAt() != null ? node.createdAt().toString() : null);
        m.put("updated_at", node.updatedAt() != null ? node.updatedAt().toString() : null);
        return m;
    }

    /**
     * Extract caller roles from the current Spring Security context.
     * Returns the set of role names (without the {@code ROLE_} prefix).
     */
    static Set<String> extractCallerRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toSet());
    }
}
