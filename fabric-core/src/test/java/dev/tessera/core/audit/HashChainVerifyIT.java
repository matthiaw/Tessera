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

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.events.EventLog;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AUDIT-02: Verifies {@link AuditVerificationService} — hash-chain verification.
 *
 * <p>An intact hash chain must verify as {@code true}. A chain with a tampered row
 * must return {@code false} and identify the first broken sequence number.
 * A tenant with no events must be considered valid (empty chain is trivially intact).
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class HashChainVerifyIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    GraphService graphService;

    @Autowired
    AuditVerificationService verificationService;

    @Autowired
    EventLog eventLog;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    /**
     * AUDIT-02: A tenant's event log with an unmodified hash chain must verify as
     * {@code true} — every event's stored {@code prev_hash} matches the computed
     * hash of its predecessor.
     */
    @Test
    void validChainReturnsTrue() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        enableHashChain(ctx.modelId());
        eventLog.invalidateHashChainConfig(ctx.modelId());

        for (int i = 0; i < 10; i++) {
            graphService.apply(buildCreate(ctx, "Event-" + i));
        }

        AuditVerificationResult result = verificationService.verify(ctx);

        assertThat(result.valid()).isTrue();
        assertThat(result.eventsChecked()).isEqualTo(10);
        assertThat(result.brokenAtSeq()).isNull();
        assertThat(result.expectedHash()).isNull();
        assertThat(result.actualHash()).isNull();
    }

    /**
     * AUDIT-02: When a single event row is tampered (prev_hash modified directly in DB),
     * verification must return {@code false} and report the first broken sequence
     * number so the audit trail break can be pinpointed.
     */
    @Test
    void tamperedChainReturnsBrokenAtSeq() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        enableHashChain(ctx.modelId());
        eventLog.invalidateHashChainConfig(ctx.modelId());

        for (int i = 0; i < 5; i++) {
            graphService.apply(buildCreate(ctx, "Event-" + i));
        }

        // Tamper: directly overwrite the prev_hash of the 3rd event (seq=3rd)
        // to simulate post-hoc modification
        Map<String, Object> targetEvent = jdbc.queryForList(
                        "SELECT sequence_nr FROM graph_events WHERE model_id = :mid::uuid "
                                + "ORDER BY sequence_nr ASC LIMIT 5",
                        new MapSqlParameterSource("mid", ctx.modelId().toString()))
                .get(2); // 3rd event (0-indexed)

        long tamperedSeq = ((Number) targetEvent.get("sequence_nr")).longValue();

        jdbc.update(
                "UPDATE graph_events SET prev_hash = 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef' "
                        + "WHERE model_id = :mid::uuid AND sequence_nr = :seq",
                new MapSqlParameterSource("mid", ctx.modelId().toString()).addValue("seq", tamperedSeq));

        AuditVerificationResult result = verificationService.verify(ctx);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(tamperedSeq);
        assertThat(result.expectedHash()).isNotNull();
        assertThat(result.actualHash()).isEqualTo("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
    }

    /**
     * AUDIT-02: A tenant with zero events in the log must verify as {@code true} —
     * there is nothing to break in an empty chain.
     */
    @Test
    void emptyTenantReturnsValid() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        // No events inserted, no model_config row needed

        AuditVerificationResult result = verificationService.verify(ctx);

        assertThat(result.valid()).isTrue();
        assertThat(result.eventsChecked()).isZero();
    }

    // --- helpers ---

    private void enableHashChain(UUID modelId) {
        jdbc.update(
                "INSERT INTO model_config (model_id, hash_chain_enabled) "
                        + "VALUES (:mid::uuid, true) "
                        + "ON CONFLICT (model_id) DO UPDATE SET hash_chain_enabled = true",
                new MapSqlParameterSource("mid", modelId.toString()));
    }

    private GraphMutation buildCreate(TenantContext ctx, String name) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("AuditNode")
                .payload(Map.of("name", name))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("verify-test")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .originConnectorId("test-conn")
                .originChangeId(UUID.randomUUID().toString())
                .build();
    }
}
