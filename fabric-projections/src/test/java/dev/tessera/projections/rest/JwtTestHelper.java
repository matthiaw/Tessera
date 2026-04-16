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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Test utility to mint HS256 JWTs for integration tests. Uses the same
 * static test signing key from {@code application-projection-it.yml}.
 */
public final class JwtTestHelper {

    /** Same key as in application-projection-it.yml */
    public static final String TEST_SIGNING_KEY = "dGVzc2VyYS10ZXN0LWtleS0xMjM0NTY3ODkwYWJjZGVm";

    private JwtTestHelper() {}

    /** Mint a JWT for the given tenant with the given roles. */
    public static String mint(String tenant, List<String> roles) {
        return mintWithKey(TEST_SIGNING_KEY, tenant, roles);
    }

    /** Mint a JWT for the given tenant with ADMIN role. */
    public static String mintAdmin(String tenant) {
        return mint(tenant, List.of("ADMIN"));
    }

    /** Mint a JWT with a specific signing key (for rotation tests). */
    public static String mintWithKey(String base64Key, String tenant, List<String> roles) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64Key);
            MACSigner signer = new MACSigner(raw);

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("tessera")
                    .subject(tenant)
                    .claim("tenant", tenant)
                    .claim("roles", roles)
                    .expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                    .issueTime(new Date())
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint test JWT", e);
        }
    }

    /** Mint an expired JWT for testing rejection. */
    public static String mintExpired(String tenant, List<String> roles) {
        try {
            byte[] raw = Base64.getDecoder().decode(TEST_SIGNING_KEY);
            MACSigner signer = new MACSigner(raw);

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("tessera")
                    .subject(tenant)
                    .claim("tenant", tenant)
                    .claim("roles", roles)
                    .expirationTime(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                    .issueTime(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint expired test JWT", e);
        }
    }
}
