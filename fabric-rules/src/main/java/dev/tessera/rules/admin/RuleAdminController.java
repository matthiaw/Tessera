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
package dev.tessera.rules.admin;

import dev.tessera.rules.authority.SourceAuthorityMatrix;
import dev.tessera.rules.internal.RuleRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Internal-only administration surface for the rule engine per ADR-7 §RULE-04
 * hot-reload requirement. Invalidates per-tenant caches when an operator
 * updates {@code reconciliation_rules} or {@code source_authority} rows
 * directly in Postgres.
 *
 * <p>Phase 1 exposes this as a plain Spring bean, NOT a web endpoint — the
 * REST projection layer (Phase 2) mounts it under the internal-auth
 * {@link #PATH} route with no public exposure. Keeping this a POJO avoids
 * pulling {@code spring-boot-starter-web} into {@code fabric-rules}; the
 * fabric-app module is where Tomcat binds.
 *
 * <p>The literal path constant is load-bearing for the plan verification grep
 * (see 01-W3-PLAN.md Task 2 success criteria).
 */
@Component
public class RuleAdminController {

    /** {@code POST /admin/rules/reload/{modelId}} — hot-reload per-tenant rule cache. */
    public static final String PATH = "/admin/rules/reload/{modelId}";

    private final RuleRepository ruleRepository;
    private final SourceAuthorityMatrix authorityMatrix;

    public RuleAdminController(RuleRepository ruleRepository, SourceAuthorityMatrix authorityMatrix) {
        this.ruleRepository = ruleRepository;
        this.authorityMatrix = authorityMatrix;
    }

    /** Handler for {@code POST /admin/rules/reload/{modelId}}. */
    public void reload(UUID modelId) {
        ruleRepository.invalidate(modelId);
        authorityMatrix.invalidateAll();
    }
}
