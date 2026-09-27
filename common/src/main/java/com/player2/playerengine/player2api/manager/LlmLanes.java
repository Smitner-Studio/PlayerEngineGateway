package com.player2.playerengine.player2api.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.player2.playerengine.player2api.LLMCompleter;
import com.player2.playerengine.player2api.gateway.GatewayConfig;
import com.player2.playerengine.player2api.gateway.GatewayRouter;

/**
 * LLM dispatch lanes, one per (billing key, endpoint profile). Each lane is an {@link LLMCompleter}
 * and so carries at most one request in flight; lanes for different profiles run concurrently.
 *
 * <p>Ordering per companion holds because a companion's lane key is a function of its billing key
 * and its character's profile, and its follow-up calls (retries, deep checks) hand off on the lane
 * that started the turn. Conversation-history summarization runs synchronously inside a response
 * callback on that same worker thread, so it stays on the companion's lane and finishes before the
 * lane is released. Hourly caps ({@code callsPerHour}, per profile and billing key) and the
 * per-billing budget are enforced where the call is made, not here; lanes only bound concurrency.
 *
 * <p>Lanes are created on first dispatch and dropped after {@link #IDLE_NANOS} without a request,
 * or when their billing key goes away. Dispatch, sweep and removal run on the server thread.
 */
public final class LlmLanes {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(10);
    public static final long SWEEP_EVERY_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** {@code profile} is "" when the gateway is off, so every call of a billing key shares one lane. */
    public record LaneKey(String billingKey, String profile) {
        public static LaneKey of(String billingKey, String characterId) {
            String profile = GatewayConfig.isEnabled() ? GatewayRouter.profileNameFor(characterId) : "";
            return new LaneKey(billingKey, profile);
        }

        @Override
        public String toString() {
            return profile.isEmpty() ? billingKey : billingKey + "|" + profile;
        }
    }

    private static final class Lane {
        final LLMCompleter completer = new LLMCompleter();
        long lastUsedNanos;

        Lane(long now) {
            lastUsedNanos = now;
        }
    }

    private final ConcurrentHashMap<LaneKey, Lane> lanes = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private long lastSweepNanos;

    public LlmLanes() {
        this(System::nanoTime);
    }

    /** {@code clock} is in nanoseconds; self-tests pass a fake one to drive the idle sweep. */
    public LlmLanes(LongSupplier clock) {
        this.clock = clock;
        this.lastSweepNanos = clock.getAsLong();
    }

    /**
     * Starts at most one candidate per idle lane: the highest-priority ready candidate of each lane
     * whose previous request has finished. Busy lanes are skipped so they never hold up the others.
     */
    public <T> int dispatch(Collection<T> ready, Function<T, LaneKey> keyOf, ToLongFunction<T> priority,
            BiConsumer<T, LLMCompleter> start) {
        Map<LaneKey, T> best = new HashMap<>();
        for (T candidate : ready) {
            LaneKey key = keyOf.apply(candidate);
            T current = best.get(key);
            if (current == null || priority.applyAsLong(candidate) > priority.applyAsLong(current)) {
                best.put(key, candidate);
            }
        }
        int started = 0;
        long now = clock.getAsLong();
        for (Map.Entry<LaneKey, T> entry : best.entrySet()) {
            Lane lane = lanes.computeIfAbsent(entry.getKey(), k -> {
                LOGGER.info("LlmLanes: creating lane {}", k);
                return new Lane(now);
            });
            if (!lane.completer.isAvailible()) {
                continue;
            }
            lane.lastUsedNanos = now;
            start.accept(entry.getValue(), lane.completer);
            started++;
        }
        sweepIdle(now);
        return started;
    }

    private void sweepIdle(long now) {
        if (now - lastSweepNanos < SWEEP_EVERY_NANOS) {
            return;
        }
        lastSweepNanos = now;
        Iterator<Map.Entry<LaneKey, Lane>> it = lanes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LaneKey, Lane> entry = it.next();
            Lane lane = entry.getValue();
            if (!lane.completer.isAvailible()) {
                // A long call counts as use; the lane idles from when it finishes, not when it began.
                lane.lastUsedNanos = now;
            } else if (now - lane.lastUsedNanos >= IDLE_NANOS) {
                it.remove();
                LOGGER.info("LlmLanes: dropping idle lane {}", entry.getKey());
                lane.completer.shutdown();
            }
        }
    }

    /** Shuts down every lane of a billing key; returns how many there were. */
    public int shutdownBilling(String billingKey) {
        if (billingKey == null) {
            return 0;
        }
        List<Lane> removed = new ArrayList<>();
        lanes.entrySet().removeIf(e -> {
            if (billingKey.equals(e.getKey().billingKey())) {
                removed.add(e.getValue());
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            LOGGER.info("LlmLanes: shutting down {} lane(s) of billing key {}", removed.size(), billingKey);
        }
        for (Lane lane : removed) {
            lane.completer.shutdown();
        }
        return removed.size();
    }

    /** Shuts down every lane; returns how many there were. */
    public int shutdownAll() {
        List<Lane> removed = new ArrayList<>(lanes.values());
        lanes.clear();
        for (Lane lane : removed) {
            lane.completer.shutdown();
        }
        return removed.size();
    }

    public int size() {
        return lanes.size();
    }
}
