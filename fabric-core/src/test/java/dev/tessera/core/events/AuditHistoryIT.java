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
package dev.tessera.core.events;

import dev.tessera.core.support.AgePostgresContainer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Wave 2 — plan 01-W2-02. EVENT-07: full mutation history per node with origin attribution. */
@Testcontainers
@Disabled("Wave 2 — filled by plan 01-W2-02")
class AuditHistoryIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @Test
    void placeholder() {}
}
