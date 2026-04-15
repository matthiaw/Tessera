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
package dev.tessera.rules;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Built-in VALIDATE-chain rule for RULE-08 echo-loop suppression. A mutation
 * whose {@code (originConnectorId, originChangeId)} pair has already been
 * recorded for any prior event in this tenant is rejected with reason
 * "echo loop detected".
 *
 * <p>Uses a short-TTL Caffeine cache to avoid hitting {@code graph_events}
 * for every mutation; the cache is a positive hit cache only — negative
 * lookups fall through to the DB query. Cache TTL is 5 minutes.
 *
 * <p>This rule runs at a very high default priority (10_000) so that it is
 * the first rule to execute in the VALIDATE chain — an echo loop must be
 * rejected before any downstream rule runs.
 */
@Component
public class EchoLoopSuppressionRule implements Rule {

    public static final String RULE_ID = "core.validate.echo-loop-suppression";

    private final NamedParameterJdbcTemplate jdbc;
    private final Cache<OriginKey, Boolean> seen;

    public EchoLoopSuppressionRule(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.seen = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Chain chain() {
        return Chain.VALIDATE;
    }

    @Override
    public int priority() {
        return 10_000;
    }

    @Override
    public boolean applies(RuleContext ctx) {
        return ctx.mutation().originConnectorId() != null && ctx.mutation().originChangeId() != null;
    }

    @Override
    public RuleOutcome evaluate(RuleContext ctx) {
        UUID modelId = ctx.tenantContext().modelId();
        String connectorId = ctx.mutation().originConnectorId();
        String changeId = ctx.mutation().originChangeId();
        OriginKey key = new OriginKey(modelId, connectorId, changeId);

        Boolean cached = seen.getIfPresent(key);
        if (Boolean.TRUE.equals(cached)) {
            return new RuleOutcome.Reject(
                    "echo loop detected (origin_connector_id=" + connectorId + ", origin_change_id=" + changeId + ")");
        }

        boolean exists = checkDb(modelId, connectorId, changeId);
        if (exists) {
            seen.put(key, Boolean.TRUE);
            return new RuleOutcome.Reject(
                    "echo loop detected (origin_connector_id=" + connectorId + ", origin_change_id=" + changeId + ")");
        }
        return RuleOutcome.Commit.INSTANCE;
    }

    /** Called by {@code GraphServiceImpl} after successful write to seed the positive cache. */
    public void markRecorded(UUID modelId, String connectorId, String changeId) {
        if (connectorId == null || changeId == null) {
            return;
        }
        seen.put(new OriginKey(modelId, connectorId, changeId), Boolean.TRUE);
    }

    private boolean checkDb(UUID modelId, String connectorId, String changeId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        p.addValue("connector_id", connectorId);
        p.addValue("change_id", changeId);
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM graph_events
                 WHERE model_id = :model_id::uuid
                   AND origin_connector_id = :connector_id
                   AND origin_change_id = :change_id
                """,
                p,
                Integer.class);
        return count != null && count > 0;
    }

    private record OriginKey(UUID modelId, String connectorId, String changeId) {}
}
