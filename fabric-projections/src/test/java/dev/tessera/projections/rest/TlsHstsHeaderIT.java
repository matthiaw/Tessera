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

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.config.SSLConfig;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-01 TLS 1.3 + HSTS header verification IT.
 *
 * <p>Boots the application with a self-signed PKCS12 keystore generated at
 * test time via {@code keytool}, makes an HTTPS request to
 * {@code /actuator/health}, and asserts:
 * <ul>
 *   <li>TLS connection succeeds over HTTPS (TLSv1.3 enabled-protocols)</li>
 *   <li>{@code Strict-Transport-Security} header is present with
 *       {@code max-age=31536000; includeSubDomains}</li>
 * </ul>
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class TlsHstsHeaderIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    private static final String KEYSTORE_PASSWORD = "changeit";

    private static Path keystorePath;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera")
            .withReuse(true);

    @BeforeAll
    static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("tessera-test-keystore-", ".p12");
        keystorePath.toFile().deleteOnExit();
        // Delete the file so keytool can create it fresh
        Files.delete(keystorePath);

        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias",
                "tessera",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystorePath.toString(),
                "-storepass",
                KEYSTORE_PASSWORD,
                "-validity",
                "1",
                "-dname",
                "CN=localhost, O=Tessera, L=Test");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("keytool failed (exit " + exitCode + "): " + output);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // Enable TLS for this test
        r.add("server.ssl.enabled", () -> "true");
        r.add("server.ssl.key-store", () -> keystorePath.toString());
        r.add("server.ssl.key-store-password", () -> KEYSTORE_PASSWORD);
        r.add("server.ssl.key-store-type", () -> "PKCS12");
        r.add("server.ssl.enabled-protocols", () -> "TLSv1.3");
        r.add("server.ssl.protocol", () -> "TLS");
    }

    @LocalServerPort
    int port;

    @Test
    void https_request_returns_hsts_header() {
        Response response = RestAssured.given()
                .config(RestAssured.config().sslConfig(new SSLConfig().relaxedHTTPSValidation()))
                .baseUri("https://localhost:" + port)
                .when()
                .get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);

        String hsts = response.header("Strict-Transport-Security");
        assertThat(hsts).isNotNull();
        assertThat(hsts).contains("max-age=31536000");
        assertThat(hsts).contains("includeSubDomains");
    }
}
