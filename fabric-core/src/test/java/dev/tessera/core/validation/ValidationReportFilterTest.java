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
package dev.tessera.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.internal.ShapeCache;
import dev.tessera.core.validation.internal.ShapeCompiler;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** VALID-04 / CRIT-6 — ValidationReport / Violation leak no literal payload values. */
class ValidationReportFilterTest {

    @Test
    void redacted_violation_has_no_jena_types() {
        Class<?> v = RedactedValidationReport.RedactedViolation.class;
        for (Method m : v.getDeclaredMethods()) {
            if (m.getName().equals("equals")
                    || m.getName().equals("hashCode")
                    || m.getName().equals("toString")) {
                continue;
            }
            String rt = m.getReturnType().getName();
            assertThat(rt).doesNotContain("org.apache.jena");
            // Accessors must be primitive or String only.
            assertThat(rt).matches("java\\.lang\\.String|boolean|int|long|void");
        }
    }

    @Test
    void redacted_report_has_no_jena_types() {
        Class<?> r = RedactedValidationReport.class;
        for (Method m : r.getDeclaredMethods()) {
            String rt = m.getReturnType().getName();
            assertThat(rt).doesNotContain("org.apache.jena");
        }
    }

    @Test
    void exception_message_does_not_leak_literal_payload_values() {
        ShaclValidator validator =
                new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter());
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeTypeDescriptor descriptor = new NodeTypeDescriptor(
                ctx.modelId(),
                "Person",
                "Person",
                "Person",
                "desc",
                1L,
                List.of(
                        new PropertyDescriptor("age", "age", "integer", true, null, null, null, null, null),
                        new PropertyDescriptor("name", "name", "string", true, null, null, null, null, null)),
                null);
        // Use a datatype-wrong value to force a violation referencing "TENANT_A_SECRET".
        String secret = "TENANT_A_SECRET_42";
        GraphMutation m = new GraphMutation(
                ctx,
                Operation.CREATE,
                "Person",
                UUID.randomUUID(),
                Map.of("age", secret, "name", "alice"),
                SourceType.SYSTEM,
                "src",
                "test",
                BigDecimal.ONE,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> validator.validate(ctx, descriptor, m))
                .isInstanceOf(ShaclValidationException.class)
                .satisfies(t -> {
                    ShaclValidationException ex = (ShaclValidationException) t;
                    // Exception message and redacted report must not contain the literal value.
                    assertThat(ex.getMessage()).doesNotContain(secret);
                    assertThat(ex.report().toString()).doesNotContain(secret);
                    for (var viol : ex.report().violations()) {
                        assertThat(viol.toString()).doesNotContain(secret);
                    }
                });
    }

    @Test
    void toSafeString_does_not_serialize_violations() {
        ValidationReportFilter filter = new ValidationReportFilter();
        RedactedValidationReport report = new RedactedValidationReport(
                false,
                List.of(new RedactedValidationReport.RedactedViolation(
                        "uuid", "name", "MinCountConstraintComponent", "Violation")));
        String safe = filter.toSafeString(report);
        assertThat(safe).contains("conforms=false");
        assertThat(safe).contains("violations=1");
        // Does not leak violation internals verbatim.
        assertThat(safe).doesNotContain("MinCount");
    }
}
