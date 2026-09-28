package com.player2.playerengine.seam;

/**
 * Block-state reads the queries may make in one server tick, across every companion (§6.2). A query
 * that needs more parks and carries on next tick, so a radius-32 {@code find_blocks} (about 270k
 * cells) spans about 66 ticks instead of stalling one. Each exposure check costs 6 reads (§5.6).
 */
public final class ReadBudget {
    public static final int PER_TICK = 4096;

    /** The server-wide budget every companion's query queue draws on. */
    public static final ReadBudget SERVER = new ReadBudget(PER_TICK);

    private final int cap;
    private long tick = Long.MIN_VALUE;
    private int used;
    private int peak;

    public ReadBudget(int cap) {
        this.cap = cap;
    }

    /** No cap: only for the self-test's red witness, which shows the cap is what bounds a tick. */
    static ReadBudget unbounded() {
        return new ReadBudget(Integer.MAX_VALUE);
    }

    public synchronized int available(long now) {
        roll(now);
        return cap - used;
    }

    public synchronized void charge(long now, int reads) {
        roll(now);
        used += reads;
        peak = Math.max(peak, used);
    }

    /** The most reads any single tick has used so far. */
    public synchronized int peak() {
        return peak;
    }

    private void roll(long now) {
        if (now != tick) {
            tick = now;
            used = 0;
        }
    }
}
