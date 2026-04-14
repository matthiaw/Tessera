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
package dev.tessera.core.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers helper for Apache AGE integration tests.
 *
 * <p>D-09: The image is pinned by sha256 digest. This value MUST match
 * docker-compose.yml and the README "Image pinning" section. Bumping the
 * digest requires updating all three enforcement sites — ImagePinningTest
 * fails the build otherwise.
 */
public final class AgePostgresContainer {

    /** MUST match docker-compose.yml and README (D-09). */
    public static final String AGE_IMAGE_DIGEST =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    private AgePostgresContainer() {}

    public static PostgreSQLContainer<?> create() {
        DockerImageName image = DockerImageName.parse(AGE_IMAGE_DIGEST).asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image)
                .withDatabaseName("tessera")
                .withUsername("tessera")
                .withPassword("tessera")
                .withReuse(true);
    }
}
