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
package dev.tessera.core.audit;

/**
 * AUDIT-02: Result of a hash-chain verification run for a tenant.
 *
 * <p>If {@link #valid()} is {@code true}, the entire chain is intact.
 * If {@code false}, {@link #brokenAtSeq()} identifies the first broken link.
 */
public record AuditVerificationResult(
        boolean valid,
        long eventsChecked,
        Long brokenAtSeq, // null if valid
        String expectedHash, // null if valid
        String actualHash // null if valid
        ) {

    /** All events checked, chain is intact. */
    public static AuditVerificationResult valid(long count) {
        return new AuditVerificationResult(true, count, null, null, null);
    }

    /** Chain is broken at {@code seq} — expected hash differs from actual stored hash. */
    public static AuditVerificationResult broken(long seq, String expected, String actual, long checked) {
        return new AuditVerificationResult(false, checked, seq, expected, actual);
    }
}
