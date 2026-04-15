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
package dev.tessera.rules.circuit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tessera.core.circuit.CircuitBreakerPort;
import dev.tessera.core.circuit.CircuitBreakerTrippedException;
import dev.tessera.core.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Write-amplification circuit breaker per CONTEXT §D-D2 + §D-D3 + RESEARCH
 * §"Open Questions Q5 RESOLVED" (startup grace).
 *
 * <p>Holds an in-memory 30-second rolling window per {@code (connectorId,
 * modelId)} key as an {@link AtomicLongArray} of 30 one-second buckets. The
 * current bucket is selected by
 * {@code (Instant.now().getEpochSecond() % 30)}; stale buckets are cleared
 * when their epoch-second changes. The rate is {@code sumOfAllBuckets/30}
 * events/sec.
 *
 * <p>Default threshold is {@code 500} events/sec, overridable per
 * {@code (model_id, connector_id)} via the {@code connector_limits} table
 * (a small Caffeine cache amortises the lookup).
 *
 * <p>Startup grace: during the first {@code tessera.circuit.startup-grace}
 * milliseconds of the JVM lifetime the breaker still accumulates rate
 * samples but {@link #shouldTrip} returns {@code false} unconditionally.
 * This lets the process warm up (Flyway migration, Caffeine cache fill,
 * HikariCP pool start) without false trips. Per RESEARCH §Q5 RESOLVED.
 *
 * <p>On trip the breaker (1) adds the key to an in-memory halted
 * {@code Set}, (2) DLQs any queued mutations into {@code connector_dlq}
 * with {@code reason='circuit_breaker_tripped'} — in Phase 1 this list is
 * always empty because there is no connector-side queue yet, (3)
 * increments the Micrometer counter {@code tessera.circuit.tripped} tagged
 * by {@code connector} and {@code model}, and (4) logs a WARN. The next
 * {@link #recordAndCheck} call for the halted pair throws
 * {@link CircuitBreakerTrippedException}. Admin operators clear the halt
 * via {@link #reset(String, UUID)}.
 *
 * <p>TODO(phase2): plumb the connector-side buffer into {@code trip(...)}
 * so queued-but-not-applied events land in the DLQ. In Phase 1 the list is
 * always {@link Collections#emptyList()} because connectors land in
 * Phase 2.
 */
@Component
public class WriteRateCircuitBreaker implements CircuitBreakerPort {

    /** Micrometer counter name — D-D3 load-bearing string. */
    public static final String COUNTER_TRIPPED = "tessera.circuit.tripped";

    /** DLQ table — D-D3 load-bearing string. */
    static final String TABLE_CONNECTOR_DLQ = "connector_dlq";

    /** Property key for the startup grace — {@code tessera.circuit.startup-grace}. */
    public static final String PROPERTY_STARTUP_GRACE = "tessera.circuit.startup-grace";

    static final int WINDOW_SLOTS = 30;

    private static final Logger LOG = LoggerFactory.getLogger(WriteRateCircuitBreaker.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final int defaultThreshold;
    private final long startupGraceMs;
    private final Instant startedAt;

    private final ConcurrentHashMap<BreakerKey, Bucket> windows = new ConcurrentHashMap<>();
    private final Set<BreakerKey> halted = ConcurrentHashMap.newKeySet();
    private final Cache<BreakerKey, Integer> thresholdCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .recordStats()
            .build();

    /** Primary Spring constructor. */
    public WriteRateCircuitBreaker(
            NamedParameterJdbcTemplate jdbc,
            MeterRegistry meterRegistry,
            @Value("${tessera.circuit.default-threshold:500}") int defaultThreshold,
            @Value("${" + PROPERTY_STARTUP_GRACE + ":60000}") long startupGraceMs) {
        this(jdbc, meterRegistry, defaultThreshold, startupGraceMs, Instant.now());
    }

    /** Test-visible constructor — lets unit tests inject a fixed {@code startedAt}. */
    WriteRateCircuitBreaker(
            NamedParameterJdbcTemplate jdbc,
            MeterRegistry meterRegistry,
            int defaultThreshold,
            long startupGraceMs,
            Instant startedAt) {
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
        this.defaultThreshold = defaultThreshold;
        this.startupGraceMs = startupGraceMs;
        this.startedAt = startedAt;
    }

    @Override
    public void recordAndCheck(TenantContext ctx, String connectorId) {
        // System writes with no origin connector bypass rate limiting entirely.
        if (connectorId == null || connectorId.isBlank()) {
            return;
        }
        BreakerKey key = new BreakerKey(connectorId, ctx.modelId());

        // Fail fast if already halted — next writes are rejected until reset.
        if (halted.contains(key)) {
            long events = sum(windows.get(key));
            throw new CircuitBreakerTrippedException(connectorId, ctx.modelId(), events);
        }

        record(key);

        if (shouldTrip(key)) {
            trip(key, Collections.emptyList());
            long events = sum(windows.get(key));
            throw new CircuitBreakerTrippedException(connectorId, ctx.modelId(), events);
        }
    }

    /** Package-private: accumulate the rolling window for {@code key}. */
    void record(BreakerKey key) {
        Bucket bucket = windows.computeIfAbsent(key, k -> new Bucket());
        long nowSec = nowEpochSecond();
        int slot = (int) Math.floorMod(nowSec, WINDOW_SLOTS);
        long stamp = bucket.stamps.get(slot);
        if (stamp != nowSec) {
            // Slot belongs to a past epoch — reset to this second before incrementing.
            bucket.stamps.set(slot, nowSec);
            bucket.counts.set(slot, 0L);
        }
        bucket.counts.incrementAndGet(slot);
    }

    /**
     * Return true if the rolling rate exceeds the threshold AND the startup
     * grace window has elapsed. Buckets whose epoch-second is stale (older
     * than 30 seconds from now) are ignored on the sum.
     */
    boolean shouldTrip(BreakerKey key) {
        if (!graceElapsed()) {
            return false;
        }
        Bucket bucket = windows.get(key);
        if (bucket == null) {
            return false;
        }
        long sum = sumFresh(bucket);
        int threshold = thresholdFor(key);
        // Events/sec across the window = sum / WINDOW_SLOTS. Trip when that
        // rolling rate exceeds the threshold.
        return sum > ((long) threshold) * WINDOW_SLOTS;
    }

    /**
     * Public visibility so tests and {@link CircuitBreakerAdminController}
     * can query whether a pair is halted.
     */
    public boolean isHalted(String connectorId, UUID modelId) {
        return halted.contains(new BreakerKey(connectorId, modelId));
    }

    /**
     * Trip the breaker: halt the pair, DLQ any queued mutations (empty list
     * in Phase 1), increment Micrometer counter, log WARN.
     */
    void trip(BreakerKey key, List<?> queued) {
        if (!halted.add(key)) {
            // Already halted — do not re-count the trip in the counter.
            return;
        }
        long sum = sum(windows.get(key));
        if (queued != null) {
            for (Object queuedMutation : queued) {
                dlqWrite(key, queuedMutation == null ? "null" : queuedMutation.toString());
            }
        }
        if (meterRegistry != null) {
            Counter.builder(COUNTER_TRIPPED)
                    .description("Count of write-rate circuit breaker trips (RULE-07 / D-D3)")
                    .tag("connector", key.connectorId())
                    .tag("model", key.modelId().toString())
                    .register(meterRegistry)
                    .increment();
        }
        LOG.warn(
                "Circuit breaker tripped: connector='{}' model='{}' events-in-window={} (table={})",
                key.connectorId(),
                key.modelId(),
                sum,
                TABLE_CONNECTOR_DLQ);
    }

    /** Admin reset — clears the halt flag and the rolling window for a pair. */
    public void reset(String connectorId, UUID modelId) {
        BreakerKey key = new BreakerKey(connectorId, modelId);
        halted.remove(key);
        windows.remove(key);
        thresholdCache.invalidate(key);
        LOG.info("Circuit breaker reset: connector='{}' model='{}'", connectorId, modelId);
    }

    private boolean graceElapsed() {
        if (startupGraceMs <= 0) {
            return true;
        }
        return Duration.between(startedAt, Instant.now()).toMillis() >= startupGraceMs;
    }

    private int thresholdFor(BreakerKey key) {
        Integer cached = thresholdCache.get(key, this::loadThreshold);
        return cached == null ? defaultThreshold : cached;
    }

    private Integer loadThreshold(BreakerKey key) {
        if (jdbc == null) {
            return defaultThreshold;
        }
        try {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("model_id", key.modelId().toString())
                    .addValue("connector_id", key.connectorId());
            List<Integer> rows = jdbc.query(
                    "SELECT threshold FROM connector_limits "
                            + "WHERE model_id = :model_id::uuid AND connector_id = :connector_id",
                    p,
                    (rs, rowNum) -> rs.getInt("threshold"));
            return rows.isEmpty() ? defaultThreshold : rows.get(0);
        } catch (RuntimeException ex) {
            LOG.debug("connector_limits lookup failed, using default threshold: {}", ex.getMessage());
            return defaultThreshold;
        }
    }

    private void dlqWrite(BreakerKey key, String rawPayload) {
        if (jdbc == null) {
            return;
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("model_id", key.modelId().toString())
                .addValue("connector_id", key.connectorId())
                .addValue("reason", "circuit_breaker_tripped")
                .addValue("raw_payload", rawPayload);
        jdbc.update(
                "INSERT INTO " + TABLE_CONNECTOR_DLQ
                        + " (model_id, connector_id, reason, raw_payload) "
                        + "VALUES (:model_id::uuid, :connector_id, :reason, :raw_payload::jsonb)",
                p);
    }

    private static long sum(Bucket bucket) {
        if (bucket == null) {
            return 0L;
        }
        long s = 0;
        for (int i = 0; i < WINDOW_SLOTS; i++) {
            s += bucket.counts.get(i);
        }
        return s;
    }

    private static long sumFresh(Bucket bucket) {
        if (bucket == null) {
            return 0L;
        }
        long nowSec = nowEpochSecond();
        long floor = nowSec - WINDOW_SLOTS + 1;
        long s = 0;
        for (int i = 0; i < WINDOW_SLOTS; i++) {
            long stamp = bucket.stamps.get(i);
            if (stamp >= floor && stamp <= nowSec) {
                s += bucket.counts.get(i);
            }
        }
        return s;
    }

    private static long nowEpochSecond() {
        return Instant.now().getEpochSecond();
    }

    /** Package-private key — {@code (connectorId, modelId)}. */
    record BreakerKey(String connectorId, UUID modelId) {}

    /** Rolling window of {@value #WINDOW_SLOTS} one-second buckets. */
    static final class Bucket {
        final AtomicLongArray counts = new AtomicLongArray(WINDOW_SLOTS);
        final AtomicLongArray stamps = new AtomicLongArray(WINDOW_SLOTS);
    }
}
