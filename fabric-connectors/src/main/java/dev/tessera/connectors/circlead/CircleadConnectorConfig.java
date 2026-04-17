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
package dev.tessera.connectors.circlead;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.MappingDefinition;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * CIRC-01 / CIRC-02: Spring configuration that loads the circlead MappingDefinition
 * JSON resources from the classpath, resolves Spring placeholders in {@code sourceUrl},
 * exposes the mappings as named beans, and registers the three circlead connector rows
 * in the {@code connectors} DB table at startup.
 *
 * <p>Each bean's {@code sourceUrl} is resolved via {@link Environment#resolvePlaceholders}
 * so that {@code URI.create()} never receives a raw {@code ${...}} placeholder string.
 *
 * <p>The {@code @PostConstruct} upsert uses {@code ON CONFLICT DO NOTHING} and is
 * idempotent across restarts.
 */
@Configuration
public class CircleadConnectorConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CircleadConnectorConfig.class);

    @Value("classpath:connectors/circlead-role-mapping.json")
    private Resource roleMappingResource;

    @Value("classpath:connectors/circlead-circle-mapping.json")
    private Resource circleMappingResource;

    @Value("classpath:connectors/circlead-activity-mapping.json")
    private Resource activityMappingResource;

    private final Environment env;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Value("${tessera.connectors.circlead.model-id:00000000-0000-0000-0000-000000000001}")
    private String circleadModelId;

    @Value("${tessera.connectors.circlead.credentials-ref:vault:secret/tessera/circlead/api-token}")
    private String circleadCredentialsRef;

    @Value("${tessera.connectors.circlead.poll-interval-seconds:300}")
    private int circleadPollIntervalSeconds;

    public CircleadConnectorConfig(Environment env, NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.env = env;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * MappingDefinition for circlead Role entities, with {@code sourceUrl} placeholder resolved.
     * Identity field: {@code circlead_id} (maps to circlead WorkItem UUID).
     */
    @Bean
    public MappingDefinition circleadRoleMapping() throws IOException {
        MappingDefinition raw = objectMapper.readValue(roleMappingResource.getInputStream(), MappingDefinition.class);
        return withResolvedUrl(raw, env.resolvePlaceholders(raw.sourceUrl()));
    }

    /**
     * MappingDefinition for circlead Circle entities, with {@code sourceUrl} placeholder resolved.
     * Identity field: {@code circlead_id} (maps to circlead WorkItem UUID).
     */
    @Bean
    public MappingDefinition circleadCircleMapping() throws IOException {
        MappingDefinition raw = objectMapper.readValue(circleMappingResource.getInputStream(), MappingDefinition.class);
        return withResolvedUrl(raw, env.resolvePlaceholders(raw.sourceUrl()));
    }

    /**
     * MappingDefinition for circlead Activity entities, with {@code sourceUrl} placeholder resolved.
     * Identity field: {@code circlead_id} (maps to circlead WorkItem UUID).
     */
    @Bean
    public MappingDefinition circleadActivityMapping() throws IOException {
        MappingDefinition raw =
                objectMapper.readValue(activityMappingResource.getInputStream(), MappingDefinition.class);
        return withResolvedUrl(raw, env.resolvePlaceholders(raw.sourceUrl()));
    }

    /**
     * Registers the three circlead connector rows in the {@code connectors} table.
     * Uses {@code ON CONFLICT DO NOTHING} for idempotence across restarts.
     *
     * <p>Must run BEFORE {@code ConnectorRegistry.loadAll()} — enforced via
     * {@code @DependsOn("circleadConnectorConfig")} on {@code ConnectorRegistry}.
     *
     * <p>Loads mappings directly from classpath resources (not via {@code @Bean} methods)
     * to avoid circular-bean-creation errors during {@code @PostConstruct}.
     */
    @PostConstruct
    void registerCircleadConnectors() {
        try {
            List<MappingDefinition> mappings = loadAndResolveMappings();
            for (MappingDefinition m : mappings) {
                jdbc.update(
                        """
                        INSERT INTO connectors (model_id, type, mapping_def, auth_type, credentials_ref,
                                                poll_interval_seconds, enabled)
                        VALUES (:modelId::uuid, 'rest-poll', :mappingJson::jsonb, 'BEARER',
                                :credentialsRef, :interval, true)
                        ON CONFLICT DO NOTHING
                        """,
                        Map.of(
                                "modelId", circleadModelId,
                                "mappingJson", objectMapper.writeValueAsString(m),
                                "credentialsRef", circleadCredentialsRef,
                                "interval", circleadPollIntervalSeconds));
            }
            LOG.info("CircleadConnectorConfig: registered {} circlead connector(s)", mappings.size());
        } catch (Exception e) {
            LOG.warn(
                    "CircleadConnectorConfig: failed to register circlead connectors"
                            + " (table may not exist yet): {}",
                    e.getMessage());
        }
    }

    /**
     * Loads and resolves all three circlead mapping definitions directly from classpath
     * resources, bypassing the Spring bean factory to avoid circular-creation issues in
     * {@code @PostConstruct}.
     */
    private List<MappingDefinition> loadAndResolveMappings() throws Exception {
        Resource[] resources = {roleMappingResource, circleMappingResource, activityMappingResource};
        List<MappingDefinition> result = new java.util.ArrayList<>();
        for (Resource r : resources) {
            MappingDefinition raw = objectMapper.readValue(r.getInputStream(), MappingDefinition.class);
            result.add(withResolvedUrl(raw, env.resolvePlaceholders(raw.sourceUrl())));
        }
        return result;
    }

    /**
     * Returns a copy of {@code raw} with the {@code sourceUrl} replaced by {@code resolvedUrl}.
     * Package-private for unit testing.
     */
    static MappingDefinition withResolvedUrl(MappingDefinition raw, String resolvedUrl) {
        return new MappingDefinition(
                raw.sourceEntityType(),
                raw.targetNodeTypeSlug(),
                raw.rootPath(),
                raw.fields(),
                raw.identityFields(),
                resolvedUrl,
                raw.folderPath(),
                raw.globPattern(),
                raw.chunkStrategy(),
                raw.chunkOverlapChars(),
                raw.confidenceThreshold(),
                raw.provider());
    }
}
