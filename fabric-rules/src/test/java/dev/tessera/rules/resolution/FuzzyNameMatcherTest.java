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
package dev.tessera.rules.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FuzzyNameMatcherTest {

    private final FuzzyNameMatcher matcher = new FuzzyNameMatcher();

    @Test
    void identical_strings_return_score_1() {
        assertThat(matcher.similarity("Acme Corp", "Acme Corp")).isCloseTo(1.0, within(0.001));
    }

    @Test
    void ibm_vs_full_name_below_threshold() {
        // "IBM" vs "International Business Machines" — very different lengths,
        // Levenshtein distance is high relative to max length
        double score = matcher.similarity("IBM", "International Business Machines");
        assertThat(score).isLessThan(0.85);
    }

    @Test
    void jon_vs_john_smith_above_threshold() {
        // One character difference: "Jon Smith" vs "John Smith"
        double score = matcher.similarity("Jon Smith", "John Smith");
        // Levenshtein distance = 1, max length = 10 -> similarity = 0.9
        assertThat(score).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void empty_string_returns_zero() {
        assertThat(matcher.similarity("", "anything")).isEqualTo(0.0);
        assertThat(matcher.similarity("anything", "")).isEqualTo(0.0);
        assertThat(matcher.similarity("", "")).isEqualTo(0.0);
    }

    @Test
    void null_string_returns_zero() {
        assertThat(matcher.similarity(null, "anything")).isEqualTo(0.0);
        assertThat(matcher.similarity("anything", null)).isEqualTo(0.0);
    }

    @Test
    void matches_returns_true_above_threshold() {
        assertThat(matcher.matches("Jon Smith", "John Smith", 0.85)).isTrue();
        assertThat(matcher.matches("IBM", "International Business Machines", 0.85))
                .isFalse();
    }
}
