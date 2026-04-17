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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * AUDIT-01: Pure SHA-256 hash-chain computation helper.
 *
 * <p>No Spring annotations — this is a stateless, side-effect-free utility class.
 * It forms the cryptographic core of the tamper-evident audit trail for
 * compliance-sensitive tenants (GxP, SOX, BSI C5).
 *
 * <p>Chain invariant: {@code event[i].prev_hash = SHA-256(event[i-1].prev_hash || event[i].payload)}
 *
 * <p>First event uses {@link #GENESIS_INPUT} as the conceptual predecessor.
 */
public final class HashChain {

    /** Well-known genesis sentinel — SHA-256(GENESIS_INPUT) is the predecessor of the first event. */
    public static final String GENESIS_INPUT = "TESSERA_GENESIS";

    private HashChain() {}

    /**
     * Compute the genesis hash: {@code SHA-256("TESSERA_GENESIS")}.
     *
     * @return 64-character lowercase hex SHA-256 digest
     */
    public static String genesis() {
        return sha256(GENESIS_INPUT);
    }

    /**
     * Compute one link in the chain: {@code SHA-256(prevHash + payloadJson)}.
     *
     * @param prevHash    the {@code prev_hash} value of the immediately preceding event,
     *                    or the result of {@link #genesis()} for the first event.
     *                    Must not be {@code null}.
     * @param payloadJson JSON-serialized payload of the event being appended.
     *                    Must not be {@code null}.
     * @return 64-character lowercase hex SHA-256 digest
     * @throws IllegalArgumentException if {@code prevHash} is {@code null}
     */
    public static String compute(String prevHash, String payloadJson) {
        if (prevHash == null) {
            throw new IllegalArgumentException("prevHash must not be null");
        }
        if (payloadJson == null) {
            throw new IllegalArgumentException("payloadJson must not be null");
        }
        return sha256(prevHash + payloadJson);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec — this cannot happen
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
