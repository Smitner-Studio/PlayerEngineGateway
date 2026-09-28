package com.player2.playerengine.player2api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Model-turn caps (operator ruling of 2026-09-28): each speaking player gets
 * {@link #PER_PLAYER_PER_HOUR} model turns an hour across all companions, and each companion
 * {@link #PER_COMPANION_PER_HOUR} in total, over a rolling hour. They apply whatever endpoint a
 * companion is bound to, gx10 included, which has no {@code callsPerHour} of its own. A turn over
 * either cap makes no model call; the companion says it is tired instead.
 */
public final class TurnCaps {
    public static final int PER_PLAYER_PER_HOUR = 60;
    public static final int PER_COMPANION_PER_HOUR = 120;
    static final long HOUR_MS = 60L * 60L * 1000L;

    public static final TurnCaps SHARED = new TurnCaps(PER_PLAYER_PER_HOUR, PER_COMPANION_PER_HOUR,
            System::currentTimeMillis);

    private final LongSupplier clock;
    private int perPlayer;
    private int perCompanion;
    private final Map<UUID, Deque<Long>> byPlayer = new HashMap<>();
    private final Map<UUID, Deque<Long>> byCompanion = new HashMap<>();

    TurnCaps(int perPlayer, int perCompanion, LongSupplier clock) {
        this.perPlayer = perPlayer;
        this.perCompanion = perCompanion;
        this.clock = clock;
    }

    /**
     * Charges one model turn to {@code player} (null for a turn no player caused, such as another
     * companion's chat) and {@code companion}, or neither when either is over its cap.
     *
     * @return whether the turn may call the model
     */
    public synchronized boolean tryCharge(UUID player, UUID companion) {
        long now = clock.getAsLong();
        Deque<Long> p = player == null ? null : window(byPlayer, player, now);
        Deque<Long> c = window(byCompanion, companion, now);
        if ((p != null && p.size() >= perPlayer) || c.size() >= perCompanion) {
            return false;
        }
        if (p != null) {
            p.addLast(now);
        }
        c.addLast(now);
        return true;
    }

    private static Deque<Long> window(Map<UUID, Deque<Long>> map, UUID key, long now) {
        Deque<Long> d = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!d.isEmpty() && now - d.peekFirst() >= HOUR_MS) {
            d.pollFirst();
        }
        return d;
    }

    /** Smoke harness only: other limits, and every window emptied. */
    public synchronized void resetForSmoke(int perPlayer, int perCompanion) {
        this.perPlayer = perPlayer;
        this.perCompanion = perCompanion;
        byPlayer.clear();
        byCompanion.clear();
    }
}
