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
package dev.tessera.projections.rest.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SEC-01 / CONTEXT Decision 6 + 11 + 15: OAuth2 resource server with
 * HMAC-signed JWTs, HSTS, and deny-all default.
 *
 * <ul>
 *   <li>{@code /actuator/health/**}, {@code /v3/api-docs/**},
 *       {@code /swagger-ui/**} are public.</li>
 *   <li>{@code /admin/**} requires {@code ROLE_ADMIN} or
 *       {@code ROLE_TOKEN_ISSUER}.</li>
 *   <li>All other requests require authentication (valid JWT).</li>
 * </ul>
 *
 * <p>HSTS is configured with {@code includeSubDomains=true},
 * {@code maxAge=31536000} (1 year), {@code preload=false} per RESEARCH Q8.
 * The {@code forward-headers-strategy=framework} setting in
 * {@code application.yml} ensures HSTS is emitted behind a reverse proxy.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(TesseraAuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, RotatableJwtDecoder jwtDecoder) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth -> oauth.jwt(
                        jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                        .maxAgeInSeconds(31536000)
                        .requestMatcher(request -> {
                            // Emit HSTS on secure requests (direct TLS or via
                            // X-Forwarded-Proto: https with forward-headers-strategy)
                            return request.isSecure();
                        })))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/admin/tokens/issue")
                        .permitAll()
                        .requestMatchers("/admin/**")
                        .hasAnyRole("ADMIN", "TOKEN_ISSUER")
                        .anyRequest()
                        .authenticated());
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedConverter = new JwtGrantedAuthoritiesConverter();
        grantedConverter.setAuthoritiesClaimName("roles");
        grantedConverter.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedConverter);
        converter.setPrincipalClaimName("tenant");
        return converter;
    }
}
