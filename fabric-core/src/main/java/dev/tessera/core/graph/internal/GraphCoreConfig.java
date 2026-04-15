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

import dev.tessera.core.graph.GraphRepository;
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
}
