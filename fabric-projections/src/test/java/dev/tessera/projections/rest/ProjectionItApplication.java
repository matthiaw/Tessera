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
package dev.tessera.projections.rest;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Minimal Spring Boot configuration for fabric-projections integration tests.
 * Scans {@code dev.tessera.core}, {@code dev.tessera.projections}, and
 * {@code dev.tessera.rules} so the REST controller, dispatcher, schema
 * registry, graph core, and event log all wire up.
 *
 * <p>The exclude filter prevents the Wave 0 spike's {@code SpikeApp} inner
 * class (which carries {@code @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration)})
 * from contaminating this context.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableTransactionManagement
@EnableScheduling
@ComponentScan(
        basePackages = {"dev.tessera.core", "dev.tessera.projections"},
        excludeFilters =
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SchemaVersionBumpIT.SpikeApp.class))
public class ProjectionItApplication {}
