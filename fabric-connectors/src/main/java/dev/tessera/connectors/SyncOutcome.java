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

/**
 * Outcome of a single connector poll cycle. Stored in
 * {@code connector_sync_status.last_outcome}.
 */
public enum SyncOutcome {
    /** All rows processed successfully. */
    SUCCESS,
    /** Some rows processed, some sent to DLQ. */
    PARTIAL,
    /** Poll failed entirely (network, auth, parse error). */
    FAILED,
    /** Server returned 304 -- nothing changed. */
    NO_CHANGE
}
