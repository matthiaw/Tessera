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

import dev.tessera.core.tenant.TenantContext;
import java.util.UUID;

/**
 * A fully resolved connector instance ready for scheduling. Bundles the
 * DB row, the resolved {@link Connector} implementation, the parsed
 * {@link MappingDefinition}, and the {@link TenantContext}.
 */
public record ConnectorInstance(
        UUID id,
        TenantContext tenant,
        Connector connector,
        MappingDefinition mapping,
        String credentialsRef,
        int pollIntervalSeconds,
        boolean enabled) {}
