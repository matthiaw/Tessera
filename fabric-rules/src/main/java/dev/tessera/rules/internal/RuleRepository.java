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
package dev.tessera.rules.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tessera.rules.Chain;
import dev.tessera.rules.Rule;
import dev.tessera.rules.RuleContext;
import dev.tessera.rules.RuleOutcome;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-ADR-7 §RULE-04 hybrid rule loader. Rule logic lives as Spring-DI
 * beans (auto-discovered by constructor-injected {@code List<Rule>}); the
 * {@code reconciliation_rules} table carries per-tenant
 * enable/disable + optional {@code priority_override}.
 *
 * <p>Result is Caffeine-cached keyed by {@code model_id} with a 1h TTL.
 * {@link #invalidate(UUID)} is called by {@code RuleAdminController}'s
 * hot-reload endpoint.
 */
@Component
public class RuleRepository {

    private final List<Rule> allRules;
    private final NamedParameterJdbcTemplate jdbc;
    private final Cache<UUID, List<Rule>> cache;

    public RuleRepository(List<Rule> allRules, NamedParameterJdbcTemplate jdbc) {
        this.allRules = allRules == null ? List.of() : List.copyOf(allRules);
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1024)
                .expireAfterWrite(Duration.ofHours(1))
                .build();
    }

    /**
     * Load the active rule list for the given tenant. Joins Spring-discovered
     * {@link Rule} beans against {@code reconciliation_rules} by {@code rule_id};
     * drops rows where {@code enabled = false}; wraps each matching rule with a
     * {@code priority_override} decorator when present.
     *
     * <p>Rules that have NO row in {@code reconciliation_rules} for this tenant
     * are treated as enabled with their default priority — this keeps
     * built-in hygiene rules (echo-loop suppression, source authority) always-
     * on without requiring a per-tenant insert at bootstrap.
     */
    public List<Rule> activeRulesFor(UUID modelId) {
        return cache.get(modelId, this::load);
    }

    public void invalidate(UUID modelId) {
        cache.invalidate(modelId);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    private List<Rule> load(UUID modelId) {
        Map<String, RuleConfigRow> byRuleId = loadConfigRows(modelId);
        return allRules.stream()
                .map(rule -> {
                    RuleConfigRow cfg = byRuleId.get(rule.id());
                    if (cfg == null) {
                        return rule; // default: enabled, default priority
                    }
                    if (!cfg.enabled) {
                        return null;
                    }
                    if (cfg.priorityOverride != null) {
                        return new OverridePriorityRule(rule, cfg.priorityOverride);
                    }
                    return rule;
                })
                .filter(r -> r != null)
                .collect(Collectors.toUnmodifiableList());
    }

    private Map<String, RuleConfigRow> loadConfigRows(UUID modelId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        List<RuleConfigRow> rows = jdbc.query(
                """
                SELECT rule_id, enabled, priority_override
                  FROM reconciliation_rules
                 WHERE model_id = :model_id::uuid
                """,
                p,
                (rs, rowNum) -> {
                    String ruleId = rs.getString("rule_id");
                    boolean enabled = rs.getBoolean("enabled");
                    int prio = rs.getInt("priority_override");
                    Integer priorityOverride = rs.wasNull() ? null : prio;
                    return new RuleConfigRow(ruleId, enabled, priorityOverride);
                });
        Map<String, RuleConfigRow> map = new HashMap<>();
        for (RuleConfigRow r : rows) {
            map.put(r.ruleId, r);
        }
        return Collections.unmodifiableMap(map);
    }

    private record RuleConfigRow(String ruleId, boolean enabled, Integer priorityOverride) {}

    /** Decorator applying a per-tenant priority_override. */
    private static final class OverridePriorityRule implements Rule {
        private final Rule delegate;
        private final int effectivePriority;

        OverridePriorityRule(Rule delegate, int effectivePriority) {
            this.delegate = delegate;
            this.effectivePriority = effectivePriority;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public Chain chain() {
            return delegate.chain();
        }

        @Override
        public int priority() {
            return effectivePriority;
        }

        @Override
        public boolean applies(RuleContext ctx) {
            return delegate.applies(ctx);
        }

        @Override
        public RuleOutcome evaluate(RuleContext ctx) {
            return delegate.evaluate(ctx);
        }
    }
}
