package com.player2.playerengine.seam;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * One companion's pending queries (§6.2). Each tick serves at most one of them, the oldest, as far as
 * the shared {@link ReadBudget} allows; a query that is not finished stays at the head and resumes
 * next tick from where it stopped.
 */
public final class QueryQueue {
    /** A query that reads in slices. */
    public interface Query {
        /**
         * Reads what {@code budget} allows this tick.
         *
         * @return the outcome once finished, or null to be served again next tick
         */
        Outcome step(WorldReader world, ReadBudget budget, long tick);
    }

    private record Pending(Query query, Consumer<Outcome> done) {
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    public synchronized void submit(Query query, Consumer<Outcome> done) {
        pending.add(new Pending(query, done));
    }

    public synchronized int size() {
        return pending.size();
    }

    /** Drops every pending query without an outcome, as when the companion stops or despawns. */
    public synchronized void clear() {
        pending.clear();
    }

    public void tick(WorldReader world, ReadBudget budget, long tick) {
        Pending head;
        synchronized (this) {
            head = pending.peek();
        }
        if (head == null) {
            return;
        }
        Outcome outcome = head.query().step(world, budget, tick);
        if (outcome != null) {
            synchronized (this) {
                pending.remove(head);
            }
            head.done().accept(outcome);
        }
    }
}
