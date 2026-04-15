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
package dev.tessera.core.graph;

import dev.tessera.core.support.AgePostgresContainer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Wave 1 — plan 01-W1-02. CORE-07: tombstone-default delete, hard delete opt-in. */
@Testcontainers
@Disabled("Wave 1 — filled by plan 01-W1-02")
class TombstoneSemanticsIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @Test
    void placeholder() {}
}
