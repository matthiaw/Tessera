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
package dev.tessera.core.benchcheck;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.bench.SeedGenerator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Surefire-side determinism check for {@link SeedGenerator}.
 *
 * <p>Pure Java — no JDBC, no Docker. Asserts the contract that backs D-02:
 * same {@code (count, seed)} → byte-identical UUID list. This test lives in a
 * separate package from {@code SeedGenerator} on purpose so the cross-source-root
 * import is real (Spotless strips unused same-package imports). The import
 * proves that {@code src/jmh/java} is wired onto the test source path via
 * {@code build-helper-maven-plugin add-test-source} from plan 00-01.
 */
class SeedGeneratorTest {

    @Test
    void same_seed_produces_identical_uuid_list() {
        List<UUID> a = SeedGenerator.deterministicUuidList(1000, SeedGenerator.DEFAULT_SEED);
        List<UUID> b = SeedGenerator.deterministicUuidList(1000, SeedGenerator.DEFAULT_SEED);

        assertThat(a).hasSize(1000);
        assertThat(b).hasSize(1000);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void different_seeds_produce_different_lists() {
        List<UUID> a = SeedGenerator.deterministicUuidList(100, SeedGenerator.DEFAULT_SEED);
        List<UUID> b = SeedGenerator.deterministicUuidList(100, SeedGenerator.DEFAULT_SEED + 1);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void deterministic_list_size_matches_request() {
        assertThat(SeedGenerator.deterministicUuidList(0, 42L)).isEmpty();
        assertThat(SeedGenerator.deterministicUuidList(1, 42L)).hasSize(1);
        assertThat(SeedGenerator.deterministicUuidList(12_345, 42L)).hasSize(12_345);
    }

    @Test
    void labels_constant_covers_four_node_kinds() {
        assertThat(SeedGenerator.LABELS).containsExactly("Person", "Org", "Doc", "Tag");
    }
}
