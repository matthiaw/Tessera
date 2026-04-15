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
package dev.tessera.core.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.tessera.core.events.EventLog;
import dev.tessera.core.events.Outbox;
import dev.tessera.core.events.internal.SequenceAllocator;
import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.graph.internal.GraphServiceImpl;
import dev.tessera.core.graph.internal.GraphSession;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Wave 1 BASELINE — write pipeline through {@code GraphService.apply} with
 * only Cypher + event log append + outbox append. No SHACL, no rules. Used
 * to establish the cost floor against which Wave 2 (schema hook) and Wave 3
 * (SHACL + rules) can measure their overhead. Wave 3 re-runs this bench
 * with the full pipeline and gates at p95 &lt; 11 ms (build fail). Wave 1
 * target is p95 &lt; 3 ms and is warning-only.
 *
 * <p>Runs via {@code ./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench verify}.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class WritePipelineBench {

    private PostgreSQLContainer<?> container;
    private HikariDataSource ds;
    private GraphServiceImpl graphService;
    private TransactionTemplate txTemplate;
    private TenantContext ctx;
    private int counter;

    @Setup(Level.Trial)
    public void setup() {
        container = AgePostgresContainer.create();
        container.start();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(container.getJdbcUrl());
        cfg.setUsername(container.getUsername());
        cfg.setPassword(container.getPassword());
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("WritePipelineBenchHikari");
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
        graphService = new GraphServiceImpl(session, log, outbox);

        PlatformTransactionManager txm = new DataSourceTransactionManager(ds);
        txTemplate = new TransactionTemplate(txm);

        ctx = TenantContext.of(UUID.randomUUID());
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

    @Benchmark
    public void apply() {
        int n = counter++;
        // Run inside a programmatic transaction — GraphServiceImpl.apply is
        // @Transactional, but in this bench we invoke the implementation directly
        // (no Spring AOP proxy) so we open the TX explicitly here.
        txTemplate.executeWithoutResult(status -> graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Bench" + n))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("bench-" + n)
                .sourceSystem("bench")
                .confidence(BigDecimal.ONE)
                .originConnectorId("bench-conn")
                .originChangeId("bench-" + n)
                .build()));
    }
}
