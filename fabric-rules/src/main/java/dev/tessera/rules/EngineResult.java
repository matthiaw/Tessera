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
 * End-to-end result of running the four-chain pipeline per ADR-7 §RULE-02.
 * Carries final enriched property state, accumulated routing hints, conflict
 * records, and (if rejected) the rejection reason + rule id that short-
 * circuited the pipeline in the VALIDATE chain.
 */
public record EngineResult(
        boolean rejected,
        String rejectReason,
        String rejectingRuleId,
        Map<String, Object> finalProperties,
        Map<String, Object> routingHints,
        List<ConflictRecord> conflicts) {

    public EngineResult {
        finalProperties = finalProperties == null ? Map.of() : Map.copyOf(finalProperties);
        routingHints = routingHints == null ? Map.of() : Map.copyOf(routingHints);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
