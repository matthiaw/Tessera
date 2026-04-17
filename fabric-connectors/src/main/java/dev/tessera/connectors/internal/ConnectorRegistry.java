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
package dev.tessera.connectors.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.Connector;
import dev.tessera.connectors.ConnectorInstance;
import dev.tessera.connectors.ConnectorMutatedEvent;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.MappingDefinitionValidator;
import dev.tessera.core.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CONN-03 / CONTEXT Decision 7: in-memory registry of connector instances.
 * Loaded from the {@code connectors} table on startup and hot-reloaded
 * on {@link ConnectorMutatedEvent} from admin CRUD endpoints.
 *
 * <p>CIRC-01: {@code @DependsOn("circleadConnectorConfig")} guarantees that
 * {@code CircleadConnectorConfig.registerCircleadConnectors()} has upserted the
 * three circlead connector rows before {@link #loadAll()} queries the table.
 */
// IMPORTANT: the string below must match the Spring-derived bean name of
// CircleadConnectorConfig (class name with first letter lowercased).
// If that class is renamed, this string must be updated too — there is no
// compile-time validation and a mismatch causes a silent startup ordering race.
@org.springframework.context.annotation.DependsOn("circleadConnectorConfig")
@Component
public class ConnectorRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorRegistry.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Map<String, Connector> connectorsByType;
    private final ConcurrentHashMap<UUID, ConnectorInstance> instances = new ConcurrentHashMap<>();
    private final SyncStatusRepository syncStatusRepo;

    public ConnectorRegistry(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            List<Connector> connectors,
            SyncStatusRepository syncStatusRepo) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.connectorsByType = connectors.stream().collect(Collectors.toMap(Connector::type, c -> c));
        this.syncStatusRepo = syncStatusRepo;
    }

    @PostConstruct
    void loadAll() {
        try {
            List<Map<String, Object>> rows =
                    jdbc.queryForList("SELECT * FROM connectors WHERE enabled = TRUE", Map.of());
            for (Map<String, Object> row : rows) {
                try {
                    loadRow(row);
                } catch (Exception e) {
                    LOG.warn("Failed to load connector {}: {}", row.get("id"), e.getMessage());
                }
            }
            LOG.info("ConnectorRegistry loaded {} connector instances", instances.size());
        } catch (Exception e) {
            LOG.warn("ConnectorRegistry startup load failed (table may not exist yet): {}", e.getMessage());
        }
    }

    @EventListener
    public void onConnectorMutated(ConnectorMutatedEvent event) {
        if (event.deleted()) {
            instances.remove(event.connectorId());
            LOG.info("Removed connector {} from registry", event.connectorId());
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM connectors WHERE id = :id::uuid",
                    new MapSqlParameterSource("id", event.connectorId().toString()));
            if (rows.isEmpty()) {
                instances.remove(event.connectorId());
                return;
            }
            loadRow(rows.get(0));
            LOG.info("Reloaded connector {} in registry", event.connectorId());
        } catch (Exception e) {
            LOG.warn("Failed to reload connector {}: {}", event.connectorId(), e.getMessage());
        }
    }

    /**
     * Returns connector instances whose {@code next_poll_at} has elapsed
     * and are enabled. Uses the pitfall-safe approach: returns each
     * connector ONCE, not per missed interval.
     */
    public List<ConnectorInstance> dueAt(Instant now) {
        return instances.values().stream()
                .filter(ConnectorInstance::enabled)
                .filter(inst -> {
                    Instant nextPoll = syncStatusRepo.getNextPollAt(inst.id());
                    return nextPoll == null || !now.isBefore(nextPoll);
                })
                .toList();
    }

    public ConnectorInstance get(UUID id) {
        return instances.get(id);
    }

    private void loadRow(Map<String, Object> row) {
        UUID id = UUID.fromString(row.get("id").toString());
        String type = (String) row.get("type");
        UUID modelId = UUID.fromString(row.get("model_id").toString());
        String authType = (String) row.get("auth_type");
        String credentialsRef = (String) row.get("credentials_ref");
        int pollInterval = ((Number) row.get("poll_interval_seconds")).intValue();
        boolean enabled = (Boolean) row.get("enabled");

        Connector connector = connectorsByType.get(type);
        if (connector == null) {
            LOG.warn("No connector implementation for type '{}', skipping connector {}", type, id);
            return;
        }

        try {
            String mappingJson = row.get("mapping_def").toString();
            MappingDefinition mapping = objectMapper.readValue(mappingJson, MappingDefinition.class);

            List<String> errors = MappingDefinitionValidator.validate(mapping, authType, pollInterval, type);
            if (!errors.isEmpty()) {
                LOG.warn("Connector {} has invalid mapping: {}", id, errors);
                return;
            }

            ConnectorInstance instance = new ConnectorInstance(
                    id, TenantContext.of(modelId), connector, mapping, credentialsRef, pollInterval, enabled);
            instances.put(id, instance);
        } catch (Exception e) {
            LOG.warn("Failed to parse mapping for connector {}: {}", id, e.getMessage());
        }
    }
}
