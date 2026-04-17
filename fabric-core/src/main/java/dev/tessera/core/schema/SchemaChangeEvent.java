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
package dev.tessera.core.schema;

import java.util.UUID;

/**
 * Spring {@code ApplicationEventPublisher} payload produced by {@link SchemaRegistry}
 * after every schema mutation. Consumed by projection components that need to
 * react to schema changes (SQL view regeneration, MCP tools-list notification).
 *
 * <p>In-process only — this event is never serialized to the network or persisted.
 * The payload carries only public schema metadata (no PII, no credentials).
 *
 * <p>See PLAN 07-01: SchemaChangeEvent infrastructure.
 *
 * @param modelId    tenant scope of the schema mutation
 * @param changeType one of: CREATE_TYPE, UPDATE_TYPE, DEPRECATE_TYPE,
 *                   ADD_PROPERTY, DEPRECATE_PROPERTY, REMOVE_PROPERTY,
 *                   RENAME_PROPERTY, CREATE_EDGE_TYPE
 * @param typeSlug   slug of the affected node or edge type
 */
public record SchemaChangeEvent(UUID modelId, String changeType, String typeSlug) {}
