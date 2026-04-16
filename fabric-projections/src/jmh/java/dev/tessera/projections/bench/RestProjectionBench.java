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
package dev.tessera.projections.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.tessera.core.events.EventLog;
import dev.tessera.core.events.Outbox;
import dev.tessera.core.events.internal.SequenceAllocator;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.graph.internal.GraphRepositoryImpl;
import dev.tessera.core.graph.internal.GraphServiceImpl;
import dev.tessera.core.graph.internal.GraphSession;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * REST projection list-endpoint benchmark. Seeds {@code jmh.dataset} (default
 * 100k) nodes via {@code GraphService.apply}, then measures the hot path
 * that the GET list endpoint exercises: {@link GraphRepository#queryAllAfter}
 * with cursor pagination (limit=50, seek after _seq).
 *
 * <p>This benchmarks the database query layer that the REST controller
 * delegates to, which is the dominant cost in a list request. The HTTP
 * serialization overhead is negligible compared to the Cypher round-trip.
 *
 * <p>Target: p95 &lt; 50 ms (warn-only, not build-breaking).
 *
 * <p>Run via {@code ./mvnw -pl fabric-projections -Pjmh verify}.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class RestProjectionBench {

    /** Same digest as AgePostgresContainer (D-09). */
    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    private PostgreSQLContainer<?> container;
    private HikariDataSource ds;
    private GraphRepository graphRepository;
    private TransactionTemplate txTemplate;
    private TenantContext ctx;
    private long maxSeq;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        int datasetSize = Integer.parseInt(System.getProperty("jmh.dataset", "100000"));

        container = new PostgreSQLContainer<>(
                        DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("tessera")
                .withUsername("tessera")
                .withPassword("tessera");
        container.start();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(container.getJdbcUrl());
        cfg.setUsername(container.getUsername());
        cfg.setPassword(container.getPassword());
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("RestProjectionBenchHikari");
        cfg.setConnectionInitSql("LOAD 'age'; SET search_path = ag_catalog, \"$user\", public;");
        ds = new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(ds)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
        GraphSession session = new GraphSession(named);
        SequenceAllocator allocator = new SequenceAllocator(named);
        EventLog log = new EventLog(named, allocator);
        Outbox outbox = new Outbox(named);
        GraphServiceImpl graphService = new GraphServiceImpl(session, log, outbox, null, null, null, null, null);
        graphRepository = new GraphRepositoryImpl(session);

        PlatformTransactionManager txm = new DataSourceTransactionManager(ds);
        txTemplate = new TransactionTemplate(txm);

        ctx = TenantContext.of(UUID.randomUUID());

        // Seed dataset
        System.out.println("[RestProjectionBench] Seeding " + datasetSize + " nodes...");
        for (int i = 0; i < datasetSize; i++) {
            final int idx = i;
            txTemplate.executeWithoutResult(status -> graphService.apply(GraphMutation.builder()
                    .tenantContext(ctx)
                    .operation(Operation.CREATE)
                    .type("Person")
                    .payload(Map.of("name", "Person-" + idx, "email", "p" + idx + "@bench.dev"))
                    .sourceType(SourceType.STRUCTURED)
                    .sourceId("bench-" + idx)
                    .sourceSystem("bench")
                    .confidence(BigDecimal.ONE)
                    .originConnectorId("bench-conn")
                    .originChangeId("bench-" + idx)
                    .build()));

            if (i > 0 && i % 10000 == 0) {
                System.out.println("[RestProjectionBench] Seeded " + i + "/" + datasetSize);
            }
        }
        maxSeq = datasetSize;
        System.out.println("[RestProjectionBench] Seeding complete. maxSeq=" + maxSeq);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (ds != null) {
            ds.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    /**
     * Simulate a GET list request: query 50 nodes after a random cursor
     * position, mimicking real pagination access patterns.
     */
    @Benchmark
    public void listWithCursorPagination(Blackhole bh) {
        int c = (cursor++ & 0x7fffffff) % Math.max(1, (int) maxSeq - 50);
        final long afterSeq = c;
        List<NodeState> nodes =
                txTemplate.execute(status -> graphRepository.queryAllAfter(ctx, "Person", afterSeq, 51));
        bh.consume(nodes);
    }
}
