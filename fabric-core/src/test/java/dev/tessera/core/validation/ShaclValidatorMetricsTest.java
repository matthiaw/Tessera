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
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.metrics.MetricsPort;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.internal.ShapeCache;
import dev.tessera.core.validation.internal.ShapeCompiler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OPS-01: Unit tests for {@link ShaclValidator} metrics integration.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A valid mutation causes {@link MetricsPort#recordShaclValidationNanos(long)} to be
 *       called with a non-negative nanos value.</li>
 *   <li>When {@code metricsPort} is {@code null} (test fixtures outside Spring context),
 *       validation completes without throwing a NullPointerException.</li>
 * </ol>
 */
class ShaclValidatorMetricsTest {

    /** Simple stub that records the last call to recordShaclValidationNanos. */
    static class RecordingMetricsPort implements MetricsPort {
        final AtomicLong lastNanos = new AtomicLong(-1L);
        int ingestCount = 0;
        int ruleEvalCount = 0;
        int conflictCount = 0;

        @Override
        public void recordIngest() {
            ingestCount++;
        }

        @Override
        public void recordRuleEvaluation() {
            ruleEvalCount++;
        }

        @Override
        public void recordConflict() {
            conflictCount++;
        }

        @Override
        public void recordShaclValidationNanos(long nanos) {
            lastNanos.set(nanos);
        }
    }

    private static ShaclValidator newValidator() {
        return new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter());
    }

    private static NodeTypeDescriptor personDescriptor() {
        return new NodeTypeDescriptor(
                UUID.randomUUID(),
                "Person",
                "Person",
                "Person",
                "desc",
                1L,
                List.of(new PropertyDescriptor("name", "name", "string", true, null, null, null, null, null)),
                null);
    }

    private static GraphMutation validMutation(TenantContext ctx) {
        return new GraphMutation(
                ctx,
                Operation.CREATE,
                "Person",
                UUID.randomUUID(),
                Map.of("name", "Alice"),
                SourceType.SYSTEM,
                "src-1",
                "test",
                BigDecimal.ONE,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void valid_mutation_records_timer_observation() {
        ShaclValidator validator = newValidator();
        RecordingMetricsPort port = new RecordingMetricsPort();
        ReflectionTestUtils.setField(validator, "metricsPort", port);

        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeTypeDescriptor descriptor = personDescriptor();
        GraphMutation mutation = validMutation(ctx);

        validator.validate(ctx, descriptor, mutation);

        assertThat(port.lastNanos.get())
                .as("recordShaclValidationNanos must be called with a non-negative nanos value")
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    void null_metricsPort_does_not_throw() {
        ShaclValidator validator = newValidator();
        // metricsPort field is null by default (not injected outside Spring context)

        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeTypeDescriptor descriptor = personDescriptor();
        GraphMutation mutation = validMutation(ctx);

        assertThatCode(() -> validator.validate(ctx, descriptor, mutation))
                .as("Validation must succeed without NPE when metricsPort is null")
                .doesNotThrowAnyException();
    }
}
