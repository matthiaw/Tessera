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
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * CIRC-01 / CIRC-02: Spring configuration that loads the circlead MappingDefinition
 * JSON resources from the classpath and exposes them as named beans.
 *
 * <p>Each bean is consumed by {@code ConnectorRunner} / {@code ConnectorRegistry}
 * when wiring a circlead connector instance. The {@code sourceUrl} field in each
 * JSON contains a {@code ${tessera.connectors.circlead.base-url}} placeholder;
 * callers must resolve this property before passing the mapping to
 * {@code GenericRestPollerConnector.poll()}.
 */
@Configuration
public class CircleadConnectorConfig {

    @Value("classpath:connectors/circlead-role-mapping.json")
    private Resource roleMappingResource;

    @Value("classpath:connectors/circlead-circle-mapping.json")
    private Resource circleMappingResource;

    @Value("classpath:connectors/circlead-activity-mapping.json")
    private Resource activityMappingResource;

    /**
     * MappingDefinition for circlead Role entities.
     * Identity field: {@code circlead_id} (maps to circlead WorkItem UUID).
     */
    @Bean
    public MappingDefinition circleadRoleMapping(ObjectMapper objectMapper) throws IOException {
        return objectMapper.readValue(roleMappingResource.getInputStream(), MappingDefinition.class);
    }

    /**
     * MappingDefinition for circlead Circle entities.
     * Identity field: {@code circlead_id} (maps to circlead WorkItem UUID).
     */
    @Bean
    public MappingDefinition circleadCircleMapping(ObjectMapper objectMapper) throws IOException {
        return objectMapper.readValue(circleMappingResource.getInputStream(), MappingDefinition.class);
    }

    /**
     * MappingDefinition for circlead Activity entities.
     * Identity field: {@code circlead_id} (maps to circlead WorkItem UUID).
     */
    @Bean
    public MappingDefinition circleadActivityMapping(ObjectMapper objectMapper) throws IOException {
        return objectMapper.readValue(activityMappingResource.getInputStream(), MappingDefinition.class);
    }
}
