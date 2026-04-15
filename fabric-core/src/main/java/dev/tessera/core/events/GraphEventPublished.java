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
package dev.tessera.core.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 / Wave 2 / 01-W2-03 (EVENT-05): Spring {@code ApplicationEventPublisher}
 * payload produced by {@link OutboxPoller} after successfully reading a
 * {@code graph_outbox} row and before marking it {@code DELIVERED}.
 *
 * <p><strong>At-least-once semantics.</strong> The poller publishes this event
 * and then {@code UPDATE graph_outbox SET status='DELIVERED'} inside the same
 * poll-batch transaction. If a listener throws OR the JVM crashes between
 * publish and commit, the next poll will re-deliver the same row. All
 * {@code @EventListener(GraphEventPublished.class)} handlers MUST be
 * idempotent.
 *
 * <p>The {@code routingHints} field carries the {@code graph_outbox.routing_hints}
 * JSONB column verbatim — never null; absent hints become {@link Map#of()}.
 * Phase 2+ projection connectors read this to decide which projections should
 * receive this event.
 *
 * @param modelId tenant scope of the originating mutation
 * @param eventId {@code graph_events.id} of the source event (also the outbox event_id)
 * @param aggregateType domain type (node type slug, e.g. "Person") or edge pseudo-type
 * @param aggregateId stable identifier of the aggregate affected (node uuid)
 * @param type event type (CREATE_NODE / UPDATE_NODE / TOMBSTONE_NODE / *_EDGE)
 * @param payload the post-state property map as stored in {@code graph_outbox.payload}
 * @param routingHints parsed routing-hints JSONB; never null, empty when the column is NULL
 * @param deliveredAt wall-clock timestamp of successful delivery (clock_timestamp())
 */
public record GraphEventPublished(
        UUID modelId,
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String type,
        Map<String, Object> payload,
        Map<String, Object> routingHints,
        Instant deliveredAt) {

    public GraphEventPublished {
        if (routingHints == null) {
            routingHints = Map.of();
        }
        if (payload == null) {
            payload = Map.of();
        }
    }
}
