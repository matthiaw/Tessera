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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.junit.jupiter.api.Test;

/** VALID-03 — targeted validation: single-node in-memory RDF, not full graph. */
class TargetedValidationTest {

    private ShaclValidator newValidator() {
        return new ShaclValidator(new ShapeCache(new ShapeCompiler()), new ValidationReportFilter());
    }

    private static NodeTypeDescriptor personWithRequiredName() {
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

    private static GraphMutation createMutation(TenantContext ctx, UUID target, Map<String, Object> payload) {
        return new GraphMutation(
                ctx,
                Operation.CREATE,
                "Person",
                target,
                payload,
                SourceType.SYSTEM,
                "src-1",
                "test",
                BigDecimal.ONE,
                null,
                null,
                null,
                null);
    }

    @Test
    void missing_required_property_is_rejected() {
        ShaclValidator validator = newValidator();
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeTypeDescriptor descriptor = personWithRequiredName();
        GraphMutation m = createMutation(ctx, UUID.randomUUID(), Map.of());

        assertThatThrownBy(() -> validator.validate(ctx, descriptor, m))
                .isInstanceOf(ShaclValidationException.class)
                .satisfies(t -> {
                    ShaclValidationException ex = (ShaclValidationException) t;
                    assertThat(ex.report().conforms()).isFalse();
                    assertThat(ex.report().violations()).isNotEmpty();
                    assertThat(ex.report().violations().get(0).resultPath()).isEqualTo("name");
                });
    }

    @Test
    void valid_mutation_is_accepted() {
        ShaclValidator validator = newValidator();
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeTypeDescriptor descriptor = personWithRequiredName();
        GraphMutation m = createMutation(ctx, UUID.randomUUID(), Map.of("name", "Alice"));

        // Should not throw.
        validator.validate(ctx, descriptor, m);
    }

    @Test
    void data_graph_has_exactly_one_subject() {
        ShaclValidator validator = newValidator();
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeTypeDescriptor descriptor = personWithRequiredName();
        UUID target = UUID.randomUUID();
        GraphMutation m = createMutation(ctx, target, Map.of("name", "Alice", "email", "a@example.com", "age", 42));

        Graph g = validator.buildDataGraph(ctx, descriptor, m, target);

        // Count unique subjects — must be exactly 1 (targeted validation, VALID-03).
        java.util.Set<Node> subjects = new java.util.HashSet<>();
        ExtendedIterator<Triple> it = g.find();
        while (it.hasNext()) {
            subjects.add(it.next().getSubject());
        }
        assertThat(subjects).hasSize(1);
        Node expectedSubject = NodeFactory.createURI("urn:tessera:node:" + target);
        assertThat(subjects).containsExactly(expectedSubject);

        // rdf:type + 3 payload triples = 4
        assertThat(g.size()).isEqualTo(4);
    }
}
