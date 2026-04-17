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

import dev.tessera.core.support.AgePostgresContainer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * AUDIT-01: Verifies hash-chain append behaviour on the event log.
 *
 * <p>When hash chaining is enabled for a tenant, every appended event must carry a
 * {@code prev_hash} value linking it to the preceding event. Disabled tenants
 * must have a {@code null} prev_hash. Concurrent appends from multiple threads
 * must still produce a valid, strictly-ordered chain.
 *
 * <p>Wave 0 stub — enabled by Plan 04-02.
 */
@Disabled("Wave 0 stub — implementation in Plan 04-02")
@Testcontainers
class HashChainAppendIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    /**
     * AUDIT-01: For a tenant with hash chaining enabled, every event appended to the
     * log must have a non-null {@code prev_hash} equal to the SHA-256 hash of the
     * preceding event's payload (or a well-known genesis sentinel for the first event).
     */
    @Test
    void hashChainEnabledTenantHasPrevHash() {
        fail("Not yet implemented — AUDIT-01: enabled tenant events have prev_hash (Plan 04-02)");
    }

    /**
     * AUDIT-01: For a tenant with hash chaining disabled, appended events must have
     * {@code null} in the {@code prev_hash} column — no unnecessary hashing overhead.
     */
    @Test
    void hashChainDisabledTenantHasNullPrevHash() {
        fail("Not yet implemented — AUDIT-01: disabled tenant has null prev_hash (Plan 04-02)");
    }

    /**
     * AUDIT-01: Ten concurrent threads appending to the same tenant's event log must
     * all succeed, and the resulting chain must be valid (each event's prev_hash equals
     * the hash of the immediately preceding event in sequence order).
     */
    @Test
    void concurrentAppendsProduceValidChain() {
        fail("Not yet implemented — AUDIT-01: 10 concurrent threads produce valid chain (Plan 04-02)");
    }
}
