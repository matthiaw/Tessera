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
package dev.tessera.projections.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SQL-01 / D-D3: Resolves the Postgres view name for a given tenant and type slug.
 *
 * <p>Naming convention: {@code v_{first8HexOfUUID}_{typeSlug_underscored}}.
 * Postgres identifier maximum is 63 characters; names exceeding this are
 * truncated with a 4-char hash suffix to remain unique.
 *
 * <p>Pure utility — no Spring dependencies.
 */
public final class SqlViewNameResolver {

    /** Postgres maximum identifier length. */
    private static final int PG_IDENT_MAX = 63;

    private SqlViewNameResolver() {}

    /**
     * Resolve the view name for the given model and type.
     *
     * @param modelId  tenant UUID — first 8 hex chars used as prefix
     * @param typeSlug node type slug — hyphens replaced with underscores
     * @return view name of the form {@code v_{8chars}_{slug}}, max 63 chars
     * @throws IllegalArgumentException if either argument is null or blank
     */
    public static String resolve(UUID modelId, String typeSlug) {
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (typeSlug == null || typeSlug.isBlank()) {
            throw new IllegalArgumentException("typeSlug must not be null or blank");
        }

        // First 8 hex chars of UUID (strip hyphens from UUID string first)
        String uuidHex = modelId.toString().replace("-", "");
        String prefix = uuidHex.substring(0, 8);

        // Normalize slug: replace hyphens with underscores, lower-case
        String normalizedSlug = typeSlug.toLowerCase().replace("-", "_");

        String candidate = "v_" + prefix + "_" + normalizedSlug;

        if (candidate.length() <= PG_IDENT_MAX) {
            return candidate;
        }

        // Name too long: truncate type slug and append 4-char hash suffix to ensure uniqueness.
        // Overhead: "v_" (2) + prefix (8) + "_" (1) + "_" (1) + hash (4) = 16 fixed chars.
        // Remaining budget for slug: 63 - 16 = 47 chars.
        int slugBudget = PG_IDENT_MAX - 2 - 8 - 1 - 1 - 4;
        String truncatedSlug = normalizedSlug.substring(0, slugBudget);
        String hashSuffix = shortHash(normalizedSlug);
        return "v_" + prefix + "_" + truncatedSlug + "_" + hashSuffix;
    }

    /**
     * 4-character hex suffix derived from the first 2 bytes of SHA-256 of the input.
     * Used to differentiate truncated names that share the same prefix.
     */
    private static String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 2); // 4 hex chars
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec to always be available.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
