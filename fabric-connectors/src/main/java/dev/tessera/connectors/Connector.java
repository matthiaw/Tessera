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
package dev.tessera.connectors;

import dev.tessera.core.tenant.TenantContext;
import java.time.Clock;

/**
 * CONN-01: Stateless connector SPI. Implementations MUST NOT call
 * {@code GraphService} directly -- only {@code ConnectorRunner} may do that
 * (enforced by ArchUnit). The {@link #poll} method is a pure function of its
 * inputs; side-effects (persistence, DLQ, status updates) are the runner's
 * responsibility.
 *
 * <p>Phase 2 ships one implementation: {@code GenericRestPollerConnector}
 * ({@code type = "rest-poll"}). Phase 2.5 adds unstructured extraction.
 */
public interface Connector {

    /** Stable identifier of the source system type (e.g. "rest-poll"). */
    String type();

    /** Static capabilities of this connector type. */
    ConnectorCapabilities capabilities();

    /**
     * Pull one batch from the source and return candidate mutations.
     *
     * @param clock      Tessera-owned clock for mutation timestamps (CORE-08)
     * @param mapping    per-instance mapping definition, parsed by ConnectorRegistry
     * @param state      cursor / ETag / last-modified from the previous poll
     * @param tenant     tenant context for this connector instance
     * @return poll result containing candidates, next state, outcome, and DLQ entries
     */
    PollResult poll(Clock clock, MappingDefinition mapping, ConnectorState state, TenantContext tenant);
}
