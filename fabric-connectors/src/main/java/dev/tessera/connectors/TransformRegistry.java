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
package dev.tessera.connectors;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * CONN-02 / CONTEXT Decision 16: closed transform registry. No expression
 * language -- operators pick from a fixed set of transforms. Unknown
 * transform names are rejected at validation time (not runtime).
 */
public final class TransformRegistry {

    /** The set of valid transform names. */
    public static final Set<String> VALID_TRANSFORMS =
            Set.of("lowercase", "uppercase", "trim", "iso8601-date", "parse-int", "parse-decimal", "sha256", "none");

    private TransformRegistry() {}

    /**
     * Returns true if the given transform name is recognized.
     */
    public static boolean isValid(String transform) {
        return transform == null || transform.isEmpty() || VALID_TRANSFORMS.contains(transform.toLowerCase());
    }

    /**
     * Apply the named transform to a raw value. Returns null passthrough
     * for null input. Unknown transforms throw IllegalArgumentException
     * (should have been caught at validation time).
     */
    public static Object apply(String transform, Object raw) {
        if (raw == null) {
            return null;
        }
        if (transform == null || transform.isEmpty() || "none".equalsIgnoreCase(transform)) {
            return raw;
        }
        return switch (transform.toLowerCase()) {
            case "lowercase" -> raw.toString().toLowerCase();
            case "uppercase" -> raw.toString().toUpperCase();
            case "trim" -> raw.toString().trim();
            case "iso8601-date" -> parseIso8601(raw.toString());
            case "parse-int" -> parseInt(raw);
            case "parse-decimal" -> parseDecimal(raw);
            case "sha256" -> sha256(raw.toString());
            default -> throw new IllegalArgumentException("Unknown transform: " + transform);
        };
    }

    private static String parseIso8601(String value) {
        try {
            Instant parsed = DateTimeFormatter.ISO_DATE_TIME.parse(value.trim(), Instant::from);
            return parsed.toString();
        } catch (DateTimeParseException e) {
            // Try ISO_DATE (date-only)
            try {
                return DateTimeFormatter.ISO_DATE.parse(value.trim()).toString();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Cannot parse ISO 8601 date: " + value, e);
            }
        }
    }

    private static Object parseInt(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(raw.toString().trim());
    }

    private static Object parseDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(raw.toString().trim());
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
