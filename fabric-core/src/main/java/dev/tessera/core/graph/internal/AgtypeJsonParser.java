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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON parser for AGE agtype result strings. Handles the
 * subset AGE emits: objects, arrays, strings, numbers, booleans, null. Kept
 * package-private and in {@code graph.internal} so CORE-02 holds — nothing
 * outside this package touches AGE output directly.
 *
 * <p>Not a general-purpose JSON parser; it targets AGE's well-defined output
 * shape and trades completeness for zero-dependency determinism.
 */
final class AgtypeJsonParser {

    private final String src;
    private int pos;

    private AgtypeJsonParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    static Map<String, Object> parseObject(String json) {
        AgtypeJsonParser p = new AgtypeJsonParser(json);
        p.skipWs();
        Object v = p.parseValue();
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Expected JSON object, got: " + (v == null ? "null" : v.getClass()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) m;
        return cast;
    }

    private Object parseValue() {
        skipWs();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of agtype input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObj();
            case '[' -> parseArr();
            case '"' -> parseStr();
            case 't', 'f' -> parseBool();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObj() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            String key = parseStr();
            skipWs();
            expect(':');
            Object v = parseValue();
            out.put(key, v);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw new IllegalArgumentException("expected ',' or '}' at pos " + pos + " in: " + src);
        }
    }

    private List<Object> parseArr() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(parseValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw new IllegalArgumentException("expected ',' or ']' at pos " + pos + " in: " + src);
        }
    }

    private String parseStr() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("unterminated escape in: " + src);
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("bad escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string in: " + src);
    }

    private Boolean parseBool() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("expected boolean at pos " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("expected null at pos " + pos);
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String s = src.substring(start, pos);
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.parseDouble(s);
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return Double.parseDouble(s);
        }
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at pos " + pos + " in: " + src);
        }
        pos++;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of agtype input");
        }
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
