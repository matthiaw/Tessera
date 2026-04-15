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
package dev.tessera.core.events;

import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Minimal JSON serializer for {@code Map<String,Object>} payloads written
 * into Postgres {@code jsonb} columns ({@code graph_events.payload},
 * {@code graph_events.delta}, {@code graph_outbox.payload}). Hand-rolled to
 * avoid dragging Jackson into the hot write path and to keep output
 * deterministic (sorted keys) for test stability.
 */
final class JsonMaps {

    private JsonMaps() {}

    static String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(64 + map.size() * 24);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : new TreeMap<>(map).entrySet()) {
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

    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            appendString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof UUID u) {
            appendString(sb, u.toString());
        } else if (v instanceof Temporal t) {
            appendString(sb, t.toString());
        } else if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            sb.append(toJson(typed));
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
        } else {
            appendString(sb, v.toString());
        }
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
