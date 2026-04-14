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
package dev.tessera.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * D-09 image-pin enforcement across all three sites: docker-compose.yml, the
 * Testcontainers helper, and the README. Any drift is a CI failure.
 */
class ImagePinningTest {

    private static final Pattern DIGEST = Pattern.compile("apache/age@sha256:([a-f0-9]{64})");

    @Test
    void docker_compose_uses_sha256_digest() throws Exception {
        String content = Files.readString(repoRoot().resolve("docker-compose.yml"));
        Matcher m = DIGEST.matcher(content);
        assertThat(m.find())
                .as("docker-compose.yml must pin apache/age by sha256 digest (D-09)")
                .isTrue();
    }

    @Test
    void docker_compose_does_not_use_floating_tag() throws Exception {
        String content = Files.readString(repoRoot().resolve("docker-compose.yml"));
        assertThat(content).doesNotContain(":PG16_latest").doesNotContain(":latest");
    }

    @Test
    void testcontainers_helper_digest_matches_docker_compose() throws Exception {
        String compose = Files.readString(repoRoot().resolve("docker-compose.yml"));
        String helper = Files.readString(
                repoRoot().resolve("fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java"));
        Matcher mc = DIGEST.matcher(compose);
        Matcher mh = DIGEST.matcher(helper);
        assertThat(mc.find()).isTrue();
        assertThat(mh.find()).isTrue();
        assertThat(mh.group(1))
                .as("D-09: same digest in compose and Testcontainers helper")
                .isEqualTo(mc.group(1));
    }

    @Test
    void readme_documents_same_digest() throws Exception {
        String compose = Files.readString(repoRoot().resolve("docker-compose.yml"));
        String readme = Files.readString(repoRoot().resolve("README.md"));
        Matcher mc = DIGEST.matcher(compose);
        Matcher mr = DIGEST.matcher(readme);
        assertThat(mc.find()).isTrue();
        assertThat(mr.find()).isTrue();
        assertThat(mr.group(1)).isEqualTo(mc.group(1));
    }

    private Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("docker-compose.yml"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repo root not found");
        }
        return p;
    }
}
