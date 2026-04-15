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
package dev.tessera.core.graph.internal;

import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Serializes a {@code Map<String,Object>} of Cypher property values into a
 * JSON string suitable for AGE's text-cast agtype parameter idiom (RESEARCH
 * §"Agtype Parameter Binding" — Idiom A, proven by Phase 0 AgtypeParameterIT).
 *
 * <p>Package-private: only {@link GraphSession} uses this. Anything outside
 * {@code graph.internal} is forbidden from touching raw Cypher by CORE-02
 * {@code RawCypherBanTest}.
 *
 * <p>Intentionally hand-rolled (no Jackson dependency on the core hot path)
 * so that the output is byte-deterministic (sorted keys) for tests and the
 * escape semantics are trivially auditable.
 */
final class AgtypeBinder {

    private AgtypeBinder() {}

    /**
     * Serialize {@code props} to a JSON object literal. Keys are sorted for
     * deterministic output. Unsupported value types throw
     * {@link IllegalArgumentException}.
     */
    static String toAgtypeJson(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(64 + props.size() * 32);
        sb.append('{');
        boolean first = true;
        // Sort for determinism — helps test stability and agtype equality.
        for (Map.Entry<String, Object> e : new TreeMap<>(props).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, e.getKey());
            sb.append(':');
            appendValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Serialize {@code props} as a Cypher map literal: {@code {key: value, ...}}.
     * Keys are unquoted identifiers (validated upstream via
     * {@code GraphSession.IDENT}). Values use JSON escaping for strings and
     * bare literals for numbers/booleans/null. This is the shape AGE's Cypher
     * parser expects inside {@code CREATE (n:Label {...})} and {@code MATCH
     * (n:Label {...})} clauses.
     */
    static String toCypherMap(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(64 + props.size() * 32);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : new TreeMap<>(props).entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()); // key is an identifier, not a string literal
            sb.append(": ");
            appendValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /** Serialize a single value using the same rules as map-value serialization. */
    static String valueLiteral(Object v) {
        StringBuilder sb = new StringBuilder(16);
        appendValue(sb, v);
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String s) {
            appendString(sb, s);
            return;
        }
        if (v instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
            return;
        }
        if (v instanceof Number n) {
            // AGE agtype accepts numeric literals directly.
            sb.append(n.toString());
            return;
        }
        if (v instanceof UUID u) {
            appendString(sb, u.toString());
            return;
        }
        if (v instanceof Temporal t) {
            // Instant / OffsetDateTime etc. — serialize as ISO-8601 string.
            appendString(sb, t.toString());
            return;
        }
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            sb.append(toAgtypeJson(typed));
            return;
        }
        if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                if (item == null || item instanceof String || item instanceof Number || item instanceof Boolean) {
                    appendValue(sb, item);
                } else {
                    throw new IllegalArgumentException(
                            "AgtypeBinder: list items must be primitive (String/Number/Boolean/null); got "
                                    + item.getClass().getName());
                }
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException(
                "AgtypeBinder: unsupported value type " + v.getClass().getName());
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
