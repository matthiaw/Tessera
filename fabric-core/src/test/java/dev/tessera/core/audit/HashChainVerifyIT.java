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

import dev.tessera.core.support.AgePostgresContainer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * AUDIT-02: Verifies the hash-chain verification endpoint / service.
 *
 * <p>An intact hash chain must verify as {@code true}. A chain with a tampered row
 * must return {@code false} and identify the first broken sequence number. A tenant
 * with no events must be considered valid (empty chain is trivially intact).
 *
 * <p>Wave 0 stub — enabled by Plan 04-02.
 */
@Disabled("Wave 0 stub — implementation in Plan 04-02")
@Testcontainers
class HashChainVerifyIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    /**
     * AUDIT-02: A tenant's event log with an unmodified hash chain must verify as
     * {@code true} — every event's stored {@code prev_hash} matches the computed
     * hash of its predecessor.
     */
    @Test
    void validChainReturnsTrue() {
        fail("Not yet implemented — AUDIT-02: intact chain verifies as true (Plan 04-02)");
    }

    /**
     * AUDIT-02: When a single event row is tampered (payload modified directly in DB),
     * verification must return {@code false} and report the first broken sequence
     * number so the audit trail break can be pinpointed.
     */
    @Test
    void tamperedChainReturnsBrokenAtSeq() {
        fail("Not yet implemented — AUDIT-02: tampered chain detected with broken seq reported (Plan 04-02)");
    }

    /**
     * AUDIT-02: A tenant with zero events in the log must verify as {@code true} —
     * there is nothing to break in an empty chain.
     */
    @Test
    void emptyTenantReturnsValid() {
        fail("Not yet implemented — AUDIT-02: no events = valid (empty chain trivially intact) (Plan 04-02)");
    }
}
