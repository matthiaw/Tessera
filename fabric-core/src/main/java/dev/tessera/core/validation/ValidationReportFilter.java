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

import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.RedactedValidationReport.RedactedViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.jena.graph.Node;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.Path;
import org.springframework.stereotype.Component;

/**
 * VALID-04 / CRIT-6: strip literal values and neighboring-node data from a
 * Jena SHACL {@link org.apache.jena.shacl.ValidationReport} before it
 * crosses any boundary — log line, exception message, API response body.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>NO literal value from the offending payload leaves this filter.
 *   <li>NO Jena type (Node, Graph, Shape, Path) appears in the output.
 *   <li>The only identifier kept is the Tessera-local focus node UUID, which
 *       is tenant-scoped by construction.
 * </ul>
 */
@Component
public class ValidationReportFilter {

    /**
     * Filter a raw Jena report into a {@link RedactedValidationReport} safe
     * for logs and API responses.
     *
     * @param report raw Jena report (never leaked outside this method)
     * @param ctx    tenant context — used only for scoping assertions
     * @param focusNodeUuid the Tessera UUID of the mutated node
     */
    public RedactedValidationReport redact(
            org.apache.jena.shacl.ValidationReport report, TenantContext ctx, UUID focusNodeUuid) {
        if (report == null) {
            return new RedactedValidationReport(true, List.of());
        }
        List<RedactedViolation> out = new ArrayList<>();
        for (ReportEntry entry : report.getEntries()) {
            out.add(new RedactedViolation(
                    focusNodeUuid == null ? "" : focusNodeUuid.toString(),
                    pathShortName(entry.resultPath()),
                    nodeShortName(entry.sourceConstraintComponent()),
                    entry.severity() == null ? "" : entry.severity().toString()));
        }
        return new RedactedValidationReport(report.conforms(), out);
    }

    /**
     * Safe string representation of a filtered report. NEVER emits literal
     * payload values — only shape structural metadata. Used in log lines.
     */
    public String toSafeString(RedactedValidationReport redacted) {
        return "ValidationReport{conforms=" + redacted.conforms() + ", violations="
                + redacted.violations().size() + "}";
    }

    private static String pathShortName(Path path) {
        if (path == null) {
            return "";
        }
        if (path instanceof P_Link link) {
            return nodeShortName(link.getNode());
        }
        // Fallback: any other path shape — emit the type name only, never
        // toString() which may include IRIs/literals.
        return path.getClass().getSimpleName();
    }

    private static String nodeShortName(Node n) {
        if (n == null) {
            return "";
        }
        if (n.isURI()) {
            String uri = n.getURI();
            int hash = uri.lastIndexOf('#');
            int slash = uri.lastIndexOf('/');
            int colon = uri.lastIndexOf(':');
            int cut = Math.max(Math.max(hash, slash), colon);
            return cut >= 0 && cut < uri.length() - 1 ? uri.substring(cut + 1) : uri;
        }
        // Defensively: never emit literal lexical form — this would leak data.
        return n.isBlank() ? "_:b" : "";
    }
}
