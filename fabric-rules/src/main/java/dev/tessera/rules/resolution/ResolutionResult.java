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
package dev.tessera.rules.resolution;

import java.util.UUID;

/**
 * Outcome of entity resolution. Three possible results:
 * <ul>
 *   <li>{@link Match} — candidate matches an existing graph node</li>
 *   <li>{@link Create} — no match found, create a new node</li>
 *   <li>{@link ReviewQueue} — below confidence threshold, route to operator review</li>
 * </ul>
 */
public sealed interface ResolutionResult permits ResolutionResult.Match, ResolutionResult.Create, ResolutionResult.ReviewQueue {

    record Match(UUID existingNodeUuid, String tier, double score) implements ResolutionResult {}

    record Create() implements ResolutionResult {}

    record ReviewQueue(String tier, double score) implements ResolutionResult {}
}
