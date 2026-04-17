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
package dev.tessera.core.security;

import dev.tessera.core.graph.NodeState;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class AclFilterService {

    private final AclPropertyCache cache;

    public AclFilterService(AclPropertyCache cache) {
        this.cache = cache;
    }

    public Map<String, Object> filterProperties(
            NodeState node, NodeTypeDescriptor descriptor, Set<String> callerRoles) {
        Set<String> allowedSlugs = cache.getAllowedPropertySlugs(
                descriptor.modelId(), descriptor.slug(), callerRoles, descriptor::properties);
        LinkedHashMap<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.properties().entrySet()) {
            if (allowedSlugs.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    public boolean isTypeVisible(NodeTypeDescriptor descriptor, Set<String> callerRoles) {
        if (descriptor.readRoles() == null || descriptor.readRoles().isEmpty()) {
            return true;
        }
        return !Collections.disjoint(callerRoles, new HashSet<>(descriptor.readRoles()));
    }

    public void checkWriteRoles(
            NodeTypeDescriptor descriptor, Map<String, Object> payload, Set<String> callerRoles) {
        for (String key : payload.keySet()) {
            for (PropertyDescriptor prop : descriptor.properties()) {
                if (prop.slug().equals(key)
                        && prop.writeRoles() != null
                        && !prop.writeRoles().isEmpty()
                        && Collections.disjoint(callerRoles, new HashSet<>(prop.writeRoles()))) {
                    throw new AccessDeniedException(
                            "Caller lacks write role for property '" + key + "'");
                }
            }
        }
    }
}
