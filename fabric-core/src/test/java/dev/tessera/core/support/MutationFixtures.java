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
package dev.tessera.core.support;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Provide;

/**
 * jqwik arbitraries for {@link GraphMutation}. Seeds Wave 1
 * {@code TenantBypassPropertyIT} fuzz scenarios (D-D1).
 */
public final class MutationFixtures {

    private MutationFixtures() {}

    @Provide
    public static Arbitrary<GraphMutation> anyMutation() {
        Arbitrary<Operation> ops = Arbitraries.of(Operation.values());
        Arbitrary<SourceType> sources = Arbitraries.of(SourceType.values());
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> types = Arbitraries.of("person", "organization", "asset", "ticket");

        return Arbitraries.create(() -> {
            TenantContext ctx = TenantContext.of(UUID.randomUUID());
            return GraphMutation.builder()
                    .tenantContext(ctx)
                    .operation(ops.sample())
                    .type(types.sample())
                    .targetNodeUuid(uuids.sample())
                    .payload(Map.of("name", "sample"))
                    .sourceType(sources.sample())
                    .sourceId("src-" + uuids.sample())
                    .sourceSystem("test-system")
                    .confidence(BigDecimal.ONE)
                    .originConnectorId("test-connector")
                    .originChangeId(uuids.sample().toString())
                    .build();
        });
    }

    @Provide
    public static Arbitrary<GraphMutation> creates() {
        return anyMutation().map(m -> m.withTenant(m.tenantContext())).filter(m -> m.operation() == Operation.CREATE);
    }

    @Provide
    public static Arbitrary<List<GraphMutation>> mutationList() {
        return anyMutation().list().ofMinSize(1).ofMaxSize(20);
    }
}
