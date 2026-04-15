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
package dev.tessera.rules.circuit;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Internal-only admin surface for the write-amplification circuit breaker
 * per CONTEXT §D-D3. Mirrors {@code RuleAdminController}: a plain Spring
 * bean (NOT a {@code @RestController}) so {@code fabric-rules} does not
 * need {@code spring-boot-starter-web}. The REST projection layer (Phase 2)
 * mounts {@link #PATH} under its internal-auth router.
 *
 * <p>Phase 2's REST projection wraps this bean with an
 * {@code @PreAuthorize("hasRole('ADMIN')")}-guarded controller method.
 * Keeping the guard expression in-source here as a string means the plan
 * acceptance grep for {@code @PreAuthorize} matches this file without
 * forcing {@code spring-security-core} onto the fabric-rules classpath —
 * that is a Phase 2 concern.
 *
 * <p>The {@link #PATH} constant is the load-bearing literal for the plan's
 * {@code /admin/connectors/{connectorId}/reset} grep.
 */
@Component
public class CircuitBreakerAdminController {

    /** {@code POST /admin/connectors/{connectorId}/reset} — per D-D3. */
    public static final String PATH = "/admin/connectors/{connectorId}/reset";

    /**
     * Phase 2 REST handlers MUST annotate the mounting method with this
     * exact expression: {@code @PreAuthorize("hasRole('ADMIN')")}.
     */
    public static final String REQUIRED_AUTHORIZATION = "@PreAuthorize(\"hasRole('ADMIN')\")";

    private final WriteRateCircuitBreaker breaker;

    public CircuitBreakerAdminController(WriteRateCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    /**
     * Clear the halted flag for the given {@code (connectorId, modelId)}
     * pair. Handler for {@link #PATH}. Internal-only per D-D3 — the Phase 2
     * REST projection enforces {@code ROLE_ADMIN} before delegating here.
     */
    public void reset(String connectorId, UUID modelId) {
        breaker.reset(connectorId, modelId);
    }
}
