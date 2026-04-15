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
package dev.tessera.rules.support;

import com.zaxxer.hikari.HikariDataSource;
import dev.tessera.core.events.EventLog;
import dev.tessera.core.events.Outbox;
import dev.tessera.core.events.internal.SequenceAllocator;
import dev.tessera.core.graph.internal.GraphServiceImpl;
import dev.tessera.core.graph.internal.GraphSession;
import dev.tessera.core.rules.ReconciliationConflictsRepository;
import dev.tessera.core.rules.RuleEnginePort;
import dev.tessera.rules.EchoLoopSuppressionRule;
import dev.tessera.rules.Rule;
import dev.tessera.rules.RuleEngine;
import dev.tessera.rules.authority.AuthorityReconciliationRule;
import dev.tessera.rules.authority.SourceAuthorityMatrix;
import dev.tessera.rules.internal.ChainExecutor;
import dev.tessera.rules.internal.RuleRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One-stop fixture that wires a real AGE container + pooled DataSource +
 * rule engine + {@link GraphServiceImpl} for fabric-rules ITs. Tests pick
 * which extra {@link Rule} instances they want (e.g. a test-specific REJECT
 * rule) and the fixture registers them alongside the always-on built-ins
 * (echo-loop suppression, authority reconciliation).
 */
public final class PipelineFixture implements AutoCloseable {

    public final PostgreSQLContainer<?> pg;
    public final HikariDataSource ds;
    public final NamedParameterJdbcTemplate jdbc;
    public final GraphServiceImpl graphService;
    public final RuleRepository ruleRepository;
    public final SourceAuthorityMatrix authorityMatrix;
    public final EchoLoopSuppressionRule echoLoopRule;
    public final ReconciliationConflictsRepository conflictsRepository;
    public final RuleEngine ruleEngine;
    public final TransactionTemplate tx;

    private PipelineFixture(
            PostgreSQLContainer<?> pg,
            HikariDataSource ds,
            NamedParameterJdbcTemplate jdbc,
            GraphServiceImpl graphService,
            RuleRepository ruleRepository,
            SourceAuthorityMatrix authorityMatrix,
            EchoLoopSuppressionRule echoLoopRule,
            ReconciliationConflictsRepository conflictsRepository,
            RuleEngine ruleEngine,
            TransactionTemplate tx) {
        this.pg = pg;
        this.ds = ds;
        this.jdbc = jdbc;
        this.graphService = graphService;
        this.ruleRepository = ruleRepository;
        this.authorityMatrix = authorityMatrix;
        this.echoLoopRule = echoLoopRule;
        this.conflictsRepository = conflictsRepository;
        this.ruleEngine = ruleEngine;
        this.tx = tx;
    }

    public static PipelineFixture boot(List<Rule> extraRules) {
        PostgreSQLContainer<?> pg = AgePostgresContainer.create();
        pg.start();
        HikariDataSource ds = RulesTestHarness.dataSourceFor(pg);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        GraphSession session = new GraphSession(jdbc);
        SequenceAllocator alloc = new SequenceAllocator(jdbc);
        EventLog log = new EventLog(jdbc, alloc);
        Outbox outbox = new Outbox(jdbc);

        SourceAuthorityMatrix matrix = new SourceAuthorityMatrix(jdbc);
        AuthorityReconciliationRule authorityRule = new AuthorityReconciliationRule(matrix);
        EchoLoopSuppressionRule echoLoop = new EchoLoopSuppressionRule(jdbc);
        ChainExecutor executor = new ChainExecutor();

        List<Rule> allRules = new ArrayList<>();
        allRules.add(echoLoop);
        allRules.add(authorityRule);
        if (extraRules != null) {
            allRules.addAll(extraRules);
        }

        RuleRepository repo = new RuleRepository(allRules, jdbc);
        RuleEngine engine = new RuleEngine(repo, executor, echoLoop);
        ReconciliationConflictsRepository conflicts = new ReconciliationConflictsRepository(jdbc);
        RuleEnginePort port = engine;

        GraphServiceImpl graphService = new GraphServiceImpl(session, log, outbox, null, null, port, conflicts);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return new PipelineFixture(pg, ds, jdbc, graphService, repo, matrix, echoLoop, conflicts, engine, tx);
    }

    @Override
    public void close() {
        if (ds != null) {
            ds.close();
        }
        if (pg != null) {
            pg.stop();
        }
    }
}
