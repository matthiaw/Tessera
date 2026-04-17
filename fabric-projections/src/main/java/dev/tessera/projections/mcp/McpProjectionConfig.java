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
package dev.tessera.projections.mcp;

import org.springframework.context.annotation.Configuration;

/**
 * MCP projection configuration. The {@code McpSyncServer} bean is provided
 * automatically by {@code spring-ai-starter-mcp-server-webmvc} auto-configuration
 * ({@code McpServerAutoConfiguration} + {@code McpWebMvcServerAutoConfiguration}).
 *
 * <p>This marker configuration class enables component scanning for the
 * {@code dev.tessera.projections.mcp} package (picked up by the main app scan
 * rooted at {@code dev.tessera}). Additional MCP beans (audit log, quota service)
 * will be declared here in Plans 03+.
 */
@Configuration(proxyBeanMethods = false)
public class McpProjectionConfig {
    // McpSyncServer is auto-configured by spring-ai-starter-mcp-server-webmvc.
    // No explicit @Bean declaration is needed for the MVP.
}
