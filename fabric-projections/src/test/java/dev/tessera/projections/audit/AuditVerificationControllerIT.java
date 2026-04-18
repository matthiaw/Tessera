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
package dev.tessera.projections.audit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import dev.tessera.core.events.EventLog;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.rest.JwtTestHelper;
import dev.tessera.projections.rest.ProjectionItApplication;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AUDIT-02 / T-04-S2: Integration tests for {@link AuditVerificationController}.
 *
 * <p>Verifies that the POST /admin/audit/verify endpoint returns correct JSON
 * for valid/tampered chains, and enforces JWT tenant isolation (403 on mismatch).
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class AuditVerificationControllerIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera")
            .withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    GraphService graphService;

    @Autowired
    EventLog eventLog;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void postVerify_returnsValidTrue_forIntactChain() {
        UUID modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);
        enableHashChain(modelId);
        eventLog.invalidateHashChainConfig(modelId);

        for (int i = 0; i < 5; i++) {
            graphService.apply(buildCreate(ctx, "Event-" + i));
        }

        given().port(port)
                .header("Authorization", "Bearer " + JwtTestHelper.mintAdmin(modelId.toString()))
                .when()
                .post("/admin/audit/verify?model_id=" + modelId)
                .then()
                .statusCode(200)
                .body("valid", equalTo(true))
                .body("events_checked", equalTo(5));
    }

    @Test
    void postVerify_returns403_forTenantMismatch() {
        UUID modelId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();

        given().port(port)
                .header("Authorization", "Bearer " + JwtTestHelper.mintAdmin(otherTenant.toString()))
                .when()
                .post("/admin/audit/verify?model_id=" + modelId)
                .then()
                .statusCode(403)
                .body("error", notNullValue());
    }

    @Test
    void postVerify_returnsValidFalse_forTamperedChain() {
        UUID modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);
        enableHashChain(modelId);
        eventLog.invalidateHashChainConfig(modelId);

        for (int i = 0; i < 4; i++) {
            graphService.apply(buildCreate(ctx, "Event-" + i));
        }

        // Tamper: overwrite prev_hash of the 2nd event
        Map<String, Object> secondEvent = jdbc.queryForList(
                        "SELECT sequence_nr FROM graph_events WHERE model_id = :mid::uuid "
                                + "ORDER BY sequence_nr ASC LIMIT 4",
                        new MapSqlParameterSource("mid", modelId.toString()))
                .get(1);
        long tamperedSeq = ((Number) secondEvent.get("sequence_nr")).longValue();

        jdbc.update(
                "UPDATE graph_events SET prev_hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "
                        + "WHERE model_id = :mid::uuid AND sequence_nr = :seq",
                new MapSqlParameterSource("mid", modelId.toString()).addValue("seq", tamperedSeq));

        given().port(port)
                .header("Authorization", "Bearer " + JwtTestHelper.mintAdmin(modelId.toString()))
                .when()
                .post("/admin/audit/verify?model_id=" + modelId)
                .then()
                .statusCode(200)
                .body("valid", equalTo(false))
                .body("broken_at_seq", notNullValue())
                .body("expected_hash", notNullValue())
                .body("actual_hash", equalTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
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
                .sourceId("ctrl-test")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .originConnectorId("test-conn")
                .originChangeId(UUID.randomUUID().toString())
                .build();
    }
}
