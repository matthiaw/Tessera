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
package dev.tessera.core.graph.internal;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.rules.RuleEnginePort;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Spring wiring for the graph core. {@link GraphSession} is deliberately
 * NOT annotated with {@code @Component} — it lives in {@code graph.internal}
 * and is instantiated only here so bean creation is observable and the
 * package-private visibility story stays coherent.
 */
@Configuration
public class GraphCoreConfig {

    @Bean
    public GraphSession graphSession(NamedParameterJdbcTemplate jdbc) {
        return new GraphSession(jdbc);
    }

    @Bean
    public GraphRepository graphRepository(GraphSession session) {
        return new GraphRepositoryImpl(session);
    }

    /**
     * No-op {@link RuleEnginePort} fallback active only when no real
     * implementation is on the classpath. In production {@code fabric-rules}
     * provides {@code RuleEngine} which wins by {@link ConditionalOnMissingBean}.
     * Pure {@code fabric-core} integration tests (which never pull fabric-rules
     * onto their test classpath) get this no-op and the rule phase of
     * {@code GraphServiceImpl.apply} becomes a pass-through.
     */
    @Bean
    @ConditionalOnMissingBean(RuleEnginePort.class)
    public RuleEnginePort noOpRuleEnginePort() {
        return new RuleEnginePort() {
            @Override
            public Outcome run(
                    TenantContext tenantContext,
                    NodeTypeDescriptor descriptor,
                    Map<String, Object> currentProperties,
                    Map<String, String> currentSourceSystem,
                    GraphMutation mutation) {
                // Pass-through: return the incoming payload unchanged as
                // finalProperties so GraphServiceImpl does not overwrite
                // the mutation payload with an empty map. A real RuleEngine
                // would run the four chains; this fallback behaves as if no
                // rules exist.
                Map<String, Object> payload = mutation.payload() == null ? Map.of() : mutation.payload();
                return new Outcome(false, null, null, payload, Map.of(), List.of());
            }
        };
    }
}
