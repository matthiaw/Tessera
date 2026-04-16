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
package dev.tessera.connectors.review;

import java.util.UUID;

/**
 * Request body for review queue decision endpoints (reject and override).
 *
 * @param reason optional reason for rejection
 * @param targetNodeUuid target node UUID for override (merge into existing node)
 */
public record ReviewDecisionRequest(String reason, UUID targetNodeUuid) {}
