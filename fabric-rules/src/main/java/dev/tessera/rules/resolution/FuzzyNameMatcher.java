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

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Component;

/**
 * Tier 3 fuzzy name matcher using normalized Levenshtein distance from
 * Apache Commons Text. Computes similarity as
 * {@code 1.0 - (distance / max(a.length(), b.length()))}.
 *
 * <p>Implemented as a standalone {@code @Component} for MVP. Per CONTEXT.md
 * Decision 5, this can be wrapped in a {@code Rule} implementation later
 * without changing core logic.
 */
@Component
public class FuzzyNameMatcher {

    private static final LevenshteinDistance LEVENSHTEIN = LevenshteinDistance.getDefaultInstance();

    /**
     * Compute normalized similarity between two strings.
     *
     * @return similarity in [0.0, 1.0]; 0.0 if either string is null or empty
     */
    public double similarity(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return 0.0;
        }
        int distance = LEVENSHTEIN.apply(a, b);
        int maxLen = Math.max(a.length(), b.length());
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Check if two strings match above the given threshold.
     */
    public boolean matches(String a, String b, double threshold) {
        return similarity(a, b) >= threshold;
    }
}
