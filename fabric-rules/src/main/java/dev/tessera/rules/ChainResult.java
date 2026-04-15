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
package dev.tessera.rules;

import java.util.List;
import java.util.Map;

/**
 * Result of executing one {@link Chain}. Carries the accumulated property
 * state (mutations from MERGE/OVERRIDE/ADD), routing hints (from ROUTE), and
 * any conflict records (from OVERRIDE). A rejected chain halts the pipeline
 * and reports which rule rejected it.
 */
public record ChainResult(
        boolean rejected,
        String rejectReason,
        String rejectingRuleId,
        Map<String, Object> properties,
        Map<String, Object> routingHints,
        List<ConflictRecord> conflicts) {

    public ChainResult {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        routingHints = routingHints == null ? Map.of() : Map.copyOf(routingHints);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public static ChainResult rejected(String reason, String ruleId, Map<String, Object> properties) {
        return new ChainResult(true, reason, ruleId, properties, Map.of(), List.of());
    }

    public static ChainResult ok(
            Map<String, Object> properties, Map<String, Object> routingHints, List<ConflictRecord> conflicts) {
        return new ChainResult(false, null, null, properties, routingHints, conflicts);
    }
}
