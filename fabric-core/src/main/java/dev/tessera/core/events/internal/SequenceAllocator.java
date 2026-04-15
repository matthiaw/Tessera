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
package dev.tessera.core.events.internal;

import dev.tessera.core.tenant.TenantContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * EVENT-02: per-tenant monotonic sequence allocator. Backed by a dedicated
 * Postgres {@code SEQUENCE} per {@code model_id} with {@code CACHE 50}.
 *
 * <p>Per RESEARCH §"Per-Tenant Monotonic Sequence" this is lock-free at
 * allocation time (sequences are MVCC-skipping) and crash-safe with benign
 * gaps. The anti-pattern rejected by MOD-5 is {@code MAX(sequence_nr)+1};
 * this class is the engineered alternative.
 *
 * <p>The sequence is lazily created on first call per tenant and cached in
 * an in-memory {@link ConcurrentHashMap#newKeySet()} so subsequent calls
 * skip the {@code CREATE SEQUENCE IF NOT EXISTS}.
 */
@Component
public final class SequenceAllocator {

    private final JdbcTemplate jdbc;
    private final Set<String> createdSequences = ConcurrentHashMap.newKeySet();

    public SequenceAllocator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc.getJdbcTemplate();
    }

    /** Allocate the next monotonic sequence number for this tenant. */
    public long nextSequenceNr(TenantContext ctx) {
        String seq = sequenceName(ctx);
        if (createdSequences.add(seq)) {
            // First call in this JVM for this tenant — idempotent DDL.
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS " + seq + " AS BIGINT MINVALUE 1 CACHE 50");
        }
        Long value = jdbc.queryForObject("SELECT nextval('" + seq + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException("nextval returned null for " + seq);
        }
        return value;
    }

    /** Sequence name for a tenant — hex of UUID with no dashes, prefixed. */
    static String sequenceName(TenantContext ctx) {
        String hex = ctx.modelId().toString().replace("-", "");
        return "graph_events_seq_" + hex;
    }
}
