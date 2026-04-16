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
package dev.tessera.connectors.rest;

import dev.tessera.connectors.FieldMapping;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CONN-05 / CONTEXT Decision 18: per-row {@code _source_hash} computation.
 * SHA-256 over a sorted path-value tuple list (NOT Jackson JSON
 * canonicalization -- stable against serializer version drift).
 */
public final class SourceHashCodec {

    private SourceHashCodec() {}

    /**
     * Compute the SHA-256 hash of the mapped field values for a source row.
     * Fields are sorted by target name, values are stringified via
     * {@link Objects#toString}.
     *
     * @param fields    the field mappings
     * @param rowValues the extracted row values keyed by target name
     * @return hex-encoded SHA-256 hash
     */
    public static String hash(List<FieldMapping> fields, Map<String, Object> rowValues) {
        List<String> tuples = fields.stream()
                .sorted(Comparator.comparing(FieldMapping::target))
                .map(f -> f.target() + "=" + Objects.toString(rowValues.get(f.target()), ""))
                .toList();
        String joined = String.join("\n", tuples);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
