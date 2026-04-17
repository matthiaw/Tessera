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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.metrics.MetricsPort;
import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.internal.ShapeCache;
import dev.tessera.core.validation.internal.ShapeCompiler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * VALID-01..04 — synchronous SHACL pre-commit validator. Called by
 * {@code GraphServiceImpl.apply} between the rule engine's ENRICH chain and
 * the Cypher write, inside the same {@code @Transactional} boundary.
 *
 * <p>Key properties:
 *
 * <ul>
 *   <li>Compiled shapes are fetched from {@link ShapeCache} (VALID-02).
 *   <li>Validation runs against an in-memory Jena {@link Graph} holding ONLY
 *       the mutated node — NOT the full tenant graph (VALID-03).
 *   <li>Any rejection is surfaced as a {@link ShaclValidationException}
 *       carrying a {@link RedactedValidationReport} — the raw Jena report
 *       never crosses this boundary (VALID-04).
 * </ul>
 */
@Service
public class ShaclValidator {

    private final ShapeCache shapeCache;
    private final ValidationReportFilter reportFilter;

    @Autowired(required = false)
    private MetricsPort metricsPort;

    public ShaclValidator(ShapeCache shapeCache, ValidationReportFilter reportFilter) {
        this.shapeCache = shapeCache;
        this.reportFilter = reportFilter;
    }

    /**
     * Validate the mutation against the descriptor's compiled shapes.
     * Throws {@link ShaclValidationException} if the mutation does not
     * conform. On success, returns silently.
     */
    public void validate(TenantContext ctx, NodeTypeDescriptor descriptor, GraphMutation mutation) {
        Shapes shapes = shapeCache.shapesFor(ctx, descriptor);
        UUID focusUuid = effectiveUuid(mutation);
        Graph dataGraph = buildDataGraph(ctx, descriptor, mutation, focusUuid);
        long start = (metricsPort != null) ? System.nanoTime() : 0L;
        ValidationReport raw = org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph);
        if (metricsPort != null) {
            metricsPort.recordShaclValidationNanos(System.nanoTime() - start);
        }
        if (!raw.conforms()) {
            RedactedValidationReport redacted = reportFilter.redact(raw, ctx, focusUuid);
            throw new ShaclValidationException(
                    "SHACL validation failed for " + descriptor.slug() + " " + reportFilter.toSafeString(redacted),
                    redacted);
        }
    }

    /**
     * VALID-03 — build a single-node RDF graph containing ONLY the mutated
     * entity's triples. The resulting graph has exactly ONE subject URI
     * (the focus) plus an {@code rdf:type} triple and one triple per
     * payload entry.
     *
     * <p>Package-private to let {@code TargetedValidationTest} inspect the
     * shape of the data graph.
     */
    Graph buildDataGraph(TenantContext ctx, NodeTypeDescriptor descriptor, GraphMutation m, UUID focusUuid) {
        Graph g = GraphFactory.createDefaultGraph();
        Node subject = ShapeCompiler.nodeIri(focusUuid);
        Node typeIri = NodeFactory.createURI(ShapeCompiler.TESSERA_TYPE_NS + descriptor.slug());
        g.add(Triple.create(subject, RDF.type.asNode(), typeIri));

        if (m.payload() != null) {
            for (Map.Entry<String, Object> e : m.payload().entrySet()) {
                Node predicate = NodeFactory.createURI(ShapeCompiler.TESSERA_NS + e.getKey());
                Node object = literalFor(e.getValue());
                if (object != null) {
                    g.add(Triple.create(subject, predicate, object));
                }
            }
        }
        return g;
    }

    private static UUID effectiveUuid(GraphMutation m) {
        return m.targetNodeUuid() != null ? m.targetNodeUuid() : UUID.randomUUID();
    }

    private static Node literalFor(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return NodeFactory.createLiteral(s, XSDDatatype.XSDstring);
        }
        if (v instanceof Integer || v instanceof Long) {
            return NodeFactory.createLiteral(v.toString(), XSDDatatype.XSDinteger);
        }
        if (v instanceof Boolean b) {
            return NodeFactory.createLiteral(b.toString(), XSDDatatype.XSDboolean);
        }
        if (v instanceof BigDecimal bd) {
            return NodeFactory.createLiteral(bd.toPlainString(), XSDDatatype.XSDdecimal);
        }
        if (v instanceof Instant i) {
            return NodeFactory.createLiteral(i.toString(), XSDDatatype.XSDdateTime);
        }
        // Fallback: opaque string representation as xsd:string. Unknown types
        // may not satisfy datatype constraints, which is the correct signal.
        return NodeFactory.createLiteral(v.toString(), XSDDatatype.XSDstring);
    }
}
