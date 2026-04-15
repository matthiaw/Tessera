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
package dev.tessera.core.validation.internal;

import dev.tessera.core.schema.NodeTypeDescriptor;
import dev.tessera.core.schema.PropertyDescriptor;
import java.util.UUID;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;

/**
 * Wave 3 Task 1 — compile a {@link NodeTypeDescriptor} (from the Schema
 * Registry) into a Jena {@link Shapes} object using SHACL-Core constraints
 * only (D-C4: no {@code sh:sparql} / SHACL-SPARQL). The compiler is
 * stateless and referentially transparent — {@link ShapeCache} relies on
 * that property.
 *
 * <p>Property mapping (VALID-01..03):
 *
 * <ul>
 *   <li>{@code required == true}                   → {@code sh:minCount 1}
 *   <li>Always                                     → {@code sh:maxCount 1}
 *   <li>{@code dataType == "string"}               → {@code sh:datatype xsd:string}
 *   <li>{@code dataType == "int(eger)"}            → {@code sh:datatype xsd:integer}
 *   <li>{@code dataType == "boolean"}              → {@code sh:datatype xsd:boolean}
 *   <li>{@code dataType == "instant" / "datetime"} → {@code sh:datatype xsd:dateTime}
 *   <li>{@code dataType == "uuid"}                 → {@code sh:pattern} UUID regex
 * </ul>
 *
 * <p>JSONB-based {@code validationRules} / {@code enumValues} /
 * {@code referenceTarget} mappings are intentionally deferred — Task 1 only
 * covers datatype + cardinality + required, which is enough to close
 * VALID-01..05.
 */
@Component
public class ShapeCompiler {

    public static final String TESSERA_NS = "urn:tessera:prop:";
    public static final String TESSERA_TYPE_NS = "urn:tessera:type:";
    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public Shapes compile(NodeTypeDescriptor type) {
        Graph g = GraphFactory.createDefaultGraph();

        // Node shape: urn:tessera:type:{slug}#NodeShape targets class urn:tessera:type:{slug}.
        Node nodeShape = NodeFactory.createURI(TESSERA_TYPE_NS + type.slug() + "#NodeShape");
        Node targetClass = NodeFactory.createURI(TESSERA_TYPE_NS + type.slug());
        g.add(Triple.create(nodeShape, RDF.type.asNode(), SHACL.NodeShape));
        g.add(Triple.create(nodeShape, SHACL.targetClass, targetClass));

        if (type.properties() != null) {
            int idx = 0;
            for (PropertyDescriptor pd : type.properties()) {
                Node propShape = NodeFactory.createBlankNode("ps-" + type.slug() + "-" + idx++);
                g.add(Triple.create(propShape, RDF.type.asNode(), SHACL.PropertyShape));
                Node path = NodeFactory.createURI(TESSERA_NS + pd.slug());
                g.add(Triple.create(propShape, SHACL.path, path));
                // Cardinality: single-valued in Phase 1.
                g.add(Triple.create(propShape, SHACL.maxCount, intLiteral(1)));
                if (pd.required()) {
                    g.add(Triple.create(propShape, SHACL.minCount, intLiteral(1)));
                }
                applyDataType(g, propShape, pd);
                g.add(Triple.create(nodeShape, SHACL.property, propShape));
            }
        }

        return Shapes.parse(g);
    }

    private static void applyDataType(Graph g, Node propShape, PropertyDescriptor pd) {
        String dt = pd.dataType() == null ? "" : pd.dataType().toLowerCase();
        switch (dt) {
            case "string" -> g.add(Triple.create(propShape, SHACL.datatype, iri(XSDDatatype.XSDstring.getURI())));
            case "int", "integer" -> g.add(
                    Triple.create(propShape, SHACL.datatype, iri(XSDDatatype.XSDinteger.getURI())));
            case "boolean" -> g.add(Triple.create(propShape, SHACL.datatype, iri(XSDDatatype.XSDboolean.getURI())));
            case "instant", "datetime" -> g.add(
                    Triple.create(propShape, SHACL.datatype, iri(XSDDatatype.XSDdateTime.getURI())));
            case "uuid" -> {
                g.add(Triple.create(propShape, SHACL.datatype, iri(XSDDatatype.XSDstring.getURI())));
                g.add(Triple.create(propShape, SHACL.pattern, NodeFactory.createLiteralString(UUID_REGEX)));
            }
            default -> {
                // Unknown datatype — apply only structural constraints (min/max count).
            }
        }
    }

    private static Node iri(String uri) {
        return NodeFactory.createURI(uri);
    }

    private static Node intLiteral(int v) {
        return NodeFactory.createLiteralDT(Integer.toString(v), XSDDatatype.XSDinteger);
    }

    /** Resource URI for a Tessera node in a SHACL data graph. */
    public static Node nodeIri(UUID uuid) {
        return NodeFactory.createURI("urn:tessera:node:" + uuid);
    }
}
