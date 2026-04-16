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

import java.time.Instant;
import java.util.Map;

/**
 * Opaque state persisted between poll cycles. The runner serializes this
 * to JSONB in {@code connector_sync_status.state_blob}. The connector
 * reads it back on the next poll.
 *
 * @param cursor       opaque cursor string (connector-defined)
 * @param etag         last ETag header from the source (Decision 18)
 * @param lastModified last Last-Modified header from the source
 * @param lastSequence last processed sequence number
 * @param customState  additional connector-specific state
 */
public record ConnectorState(
        String cursor, String etag, Instant lastModified, long lastSequence, Map<String, Object> customState) {

    public ConnectorState {
        if (customState == null) {
            customState = Map.of();
        }
    }

    public static ConnectorState empty() {
        return new ConnectorState(null, null, null, 0L, Map.of());
    }
}
