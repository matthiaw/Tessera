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
package dev.tessera.projections.rest.problem;

import dev.tessera.core.rules.RuleRejectException;
import dev.tessera.core.validation.ShaclValidationException;
import dev.tessera.projections.rest.CrossTenantException;
import dev.tessera.projections.rest.InvalidCursorException;
import dev.tessera.projections.rest.NotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 7807 problem+json handler (CONTEXT Decision 8 + 11).
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@link NotFoundException}, {@link CrossTenantException},
 *       {@link AccessDeniedException} -> 404 (Decision 11: NEVER 403 for cross-tenant)</li>
 *   <li>{@link ShaclValidationException} -> 422 with {@code errors[]} from
 *       the tenant-filtered {@code RedactedValidationReport}</li>
 *   <li>{@link RuleRejectException} -> 422 with {@code code=TESSERA_RULE_REJECTED}</li>
 *   <li>{@link InvalidCursorException} -> 400</li>
 *   <li>Generic {@link Exception} -> 500</li>
 * </ul>
 *
 * <p><b>NEVER</b> echo caller input verbatim in {@code detail} (T-02-W2-06).
 *
 * <p>Returns {@link ResponseEntity} (not raw {@link ProblemDetail}) so Spring
 * does not auto-populate the {@code instance} field from the request URI --
 * the request path can leak tenant UUIDs and type slugs (T-02-W2-05).
 */
@RestControllerAdvice
public class TesseraProblemHandler extends ResponseEntityExceptionHandler {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler({NotFoundException.class, CrossTenantException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex) {
        return notFoundResponse();
    }

    /**
     * Decision 11: AccessDeniedException (Spring Security) maps to 404, not 403,
     * to prevent leaking tenant/resource existence to cross-tenant callers.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return notFoundResponse();
    }

    /**
     * T-02-W2-05: constant 404 response -- no path, no tenant, no enumeration signal.
     */
    private static ResponseEntity<ProblemDetail> notFoundResponse() {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(URI.create("https://tessera.dev/problems/not-found"));
        pd.setTitle("Not Found");
        pd.setDetail("Resource not found.");
        // T-02-W2-05: suppress request URI -- it can leak tenant UUIDs and type slugs
        pd.setInstance(URI.create("/api/v1/entities"));
        pd.setProperty("code", "TESSERA_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(ShaclValidationException.class)
    public ResponseEntity<ProblemDetail> handleShaclValidation(
            ShaclValidationException ex, @AuthenticationPrincipal Jwt jwt) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(URI.create("https://tessera.dev/problems/validation"));
        pd.setTitle("Validation Failed");
        pd.setDetail("One or more properties failed schema validation.");
        pd.setProperty("code", "TESSERA_VALIDATION_FAILED");
        if (jwt != null) {
            pd.setProperty("tenant", jwt.getClaimAsString("tenant"));
        }
        // Map from RedactedValidationReport -- already tenant-filtered by Phase 1.
        List<Map<String, String>> errors = ex.report().violations().stream()
                .map(v -> Map.of(
                        "property", v.resultPath() != null ? v.resultPath() : "unknown",
                        "message", v.constraintComponent() != null ? v.constraintComponent() : "validation failed"))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(RuleRejectException.class)
    public ResponseEntity<ProblemDetail> handleRuleReject(RuleRejectException ex, @AuthenticationPrincipal Jwt jwt) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(URI.create("https://tessera.dev/problems/rule-rejected"));
        pd.setTitle("Rule Rejected");
        pd.setDetail("A business rule rejected the mutation.");
        pd.setProperty("code", "TESSERA_RULE_REJECTED");
        if (jwt != null) {
            pd.setProperty("tenant", jwt.getClaimAsString("tenant"));
        }
        if (ex.ruleId() != null) {
            pd.setProperty("ruleId", ex.ruleId());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCursor(InvalidCursorException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("https://tessera.dev/problems/invalid-cursor"));
        pd.setTitle("Bad Request");
        pd.setDetail("The cursor parameter is malformed.");
        pd.setProperty("code", "TESSERA_INVALID_CURSOR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setType(URI.create("https://tessera.dev/problems/internal"));
        pd.setTitle("Internal Server Error");
        pd.setDetail("Internal error.");
        pd.setProperty("code", "TESSERA_INTERNAL");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(PROBLEM_JSON)
                .body(pd);
    }

    /**
     * Ensure Spring's built-in exception handlers also emit problem+json.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body == null || !(body instanceof ProblemDetail)) {
            ProblemDetail pd = ProblemDetail.forStatus(statusCode);
            pd.setTitle(HttpStatus.valueOf(statusCode.value()).getReasonPhrase());
            pd.setDetail("Request processing failed.");
            pd.setProperty("code", "TESSERA_INTERNAL");
            body = pd;
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
