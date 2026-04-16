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
package dev.tessera.projections.rest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor codec for REST projection pagination (W2a). The cursor
 * encodes the pagination state as a Base64 string so clients cannot
 * guess or tamper with offsets.
 *
 * <p>Wire format (plain text before Base64): {@code modelId|typeSlug|lastSeq|lastNodeId}.
 */
public final class CursorCodec {

    private CursorCodec() {}

    /** Encode pagination state into an opaque cursor string. */
    public static String encode(UUID modelId, String typeSlug, long lastSeq, UUID lastNodeId) {
        String plain = modelId + "|" + typeSlug + "|" + lastSeq + "|" + lastNodeId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Decode an opaque cursor string back to pagination state. */
    public static CursorPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", 4);
            if (parts.length != 4) {
                throw new IllegalArgumentException("malformed cursor: expected 4 parts, got " + parts.length);
            }
            return new CursorPosition(
                    UUID.fromString(parts[0]), parts[1], Long.parseLong(parts[2]), UUID.fromString(parts[3]));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cursor: " + e.getMessage(), e);
        }
    }

    /** Decoded cursor position record. */
    public record CursorPosition(UUID modelId, String typeSlug, long lastSeq, UUID lastNodeId) {}
}
