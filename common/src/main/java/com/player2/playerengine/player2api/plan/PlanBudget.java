package com.player2.playerengine.player2api.plan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Model calls charged to plans, per owner across all of that owner's companions, in a rolling hour.
 * Keyed by owner rather than companion so two companions cannot double one player's allowance.
 */
public final class PlanBudget {
    public static final int CALLS_PER_OWNER_PER_HOUR = 24;
    static final long WINDOW_MILLIS = 60L * 60L * 1000L;

    /** Shared by every companion on the server. */
    public static final PlanBudget SHARED = new PlanBudget(CALLS_PER_OWNER_PER_HOUR);

    private final int limit;
    private final Map<UUID, Deque<Long>> charges = new HashMap<>();

    public PlanBudget(int limit) {
        this.limit = limit;
    }

    /** Records one call for {@code owner} and returns true, or returns false when the hour is spent. */
    public synchronized boolean tryCharge(UUID owner, long now) {
        if (owner == null) {
            return false;
        }
        Deque<Long> q = charges.computeIfAbsent(owner, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MILLIS) {
            q.pollFirst();
        }
        if (q.size() >= limit) {
            return false;
        }
        q.addLast(now);
        return true;
    }
}
