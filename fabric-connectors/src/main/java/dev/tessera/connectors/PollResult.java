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

import java.util.List;

/**
 * Result of a single {@link Connector#poll} call.
 *
 * @param candidates candidate mutations to flow through GraphService.apply
 * @param nextState  state to persist for the next poll cycle
 * @param outcome    sync outcome (SUCCESS, PARTIAL, FAILED, NO_CHANGE)
 * @param dlq        rows that failed mapping (before reaching the write funnel)
 */
public record PollResult(
        List<CandidateMutation> candidates, ConnectorState nextState, SyncOutcome outcome, List<DlqEntry> dlq) {

    /** Convenience factory for the 304 / no-change case. */
    public static PollResult unchanged(ConnectorState state) {
        return new PollResult(List.of(), state, SyncOutcome.NO_CHANGE, List.of());
    }

    /** Convenience factory for a connection/auth failure. */
    public static PollResult failed(ConnectorState state) {
        return new PollResult(List.of(), state, SyncOutcome.FAILED, List.of());
    }
}
