package com.player2.playerengine.player2api;

import java.util.HashMap;
import java.util.Map;

/**
 * How many model turns a job may spend on repairs (§6.3: 8 model calls per job). A repair turn is a
 * lint failure sent back to the model, or a job paused on an uncaught error. The same failure twice
 * in a row ends the repairs early: the model is not asked a third time to fix what it could not fix.
 * Keyed by the job's goal, since a repaired program replaces the job it repairs.
 */
final class RepairLimit {
    static final int MAX_REPAIRS = 8;

    enum Decision { REPAIR, GIVE_UP }

    private record Chain(int repairs, String lastFailure) {
    }

    private final Map<String, Chain> chains = new HashMap<>();

    /** Records one more failure of the job with {@code goal}, and says whether to ask for a repair. */
    synchronized Decision record(String goal, String failure) {
        Chain c = chains.getOrDefault(goal, new Chain(0, null));
        String f = failure == null ? "" : failure.trim().replaceAll("\\s+", " ");
        if (f.equals(c.lastFailure) || c.repairs >= MAX_REPAIRS) {
            chains.remove(goal);
            return Decision.GIVE_UP;
        }
        chains.put(goal, new Chain(c.repairs + 1, f));
        return Decision.REPAIR;
    }

    synchronized void forget(String goal) {
        chains.remove(goal);
    }

    synchronized void reset() {
        chains.clear();
    }
}
