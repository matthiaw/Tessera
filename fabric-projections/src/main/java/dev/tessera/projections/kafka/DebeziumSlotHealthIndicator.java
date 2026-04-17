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
package dev.tessera.projections.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 4 Plan 03 / D-D1 (KAFKA-03): Spring Boot Actuator health indicator for the
 * Debezium replication slot lag. Registered as the {@code debezium} health component,
 * accessible at {@code /actuator/health/debezium}.
 *
 * <p>Only activated when {@code tessera.kafka.enabled=true}; when running in fallback
 * (OutboxPoller) mode the bean is absent and the health endpoint omits this component.
 *
 * <p>Lag is measured via {@code pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)}
 * on the {@code tessera_outbox_slot} replication slot. If the slot does not exist the
 * indicator reports DOWN — this is an operator error condition (Debezium not connected).
 *
 * <p>T-04-D2 mitigation: lag exceeding {@code tessera.kafka.lag-threshold-bytes} (default
 * 100 MB) causes the indicator to return DOWN, triggering alerting and operator action to
 * restart Debezium or drop/recreate the slot before PostgreSQL WAL growth causes disk
 * pressure.
 */
@Component("debezium")
@ConditionalOnProperty(name = "tessera.kafka.enabled", havingValue = "true")
public class DebeziumSlotHealthIndicator extends AbstractHealthIndicator {

    private static final String SLOT_NAME = "tessera_outbox_slot";

    // Query: returns WAL bytes between current write position and the last position
    // confirmed flushed by the Debezium consumer for the named slot.
    // Returns null if the slot does not exist (empty result set → queryForObject returns null).
    private static final String LAG_QUERY =
            """
            SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)
            FROM pg_replication_slots
            WHERE slot_name = :slot
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final long lagThresholdBytes;

    public DebeziumSlotHealthIndicator(
            NamedParameterJdbcTemplate jdbc,
            @Value("${tessera.kafka.lag-threshold-bytes:104857600}") long lagThresholdBytes) {
        this.jdbc = jdbc;
        this.lagThresholdBytes = lagThresholdBytes;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Long lagBytes = jdbc.queryForObject(
                LAG_QUERY, new MapSqlParameterSource("slot", SLOT_NAME), Long.class);

        if (lagBytes == null) {
            builder.down()
                    .withDetail("error", "replication slot '" + SLOT_NAME + "' not found")
                    .withDetail("slot", SLOT_NAME);
            return;
        }

        builder.withDetail("lag_bytes", lagBytes)
                .withDetail("threshold_bytes", lagThresholdBytes)
                .withDetail("slot", SLOT_NAME);

        if (lagBytes > lagThresholdBytes) {
            builder.down();
        } else {
            builder.up();
        }
    }
}
