package com.player2.playerengine.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Companion chunk holds as owner-tagged, persistent tickets (ruling R9, design §10.1), without the
 * platform: the {@link Store} adds and removes one companion's ticket on one chunk of one dimension,
 * and nothing else. Every hold is keyed by (dimension, chunk, companion), so a companion releases
 * only its own tickets: an operator's {@code /forceload}, another mod's ticket or another
 * companion's hold on the same chunk is never touched.
 *
 * <p>Holds survive a restart. During server stop nothing is released (Player2NPC dismisses every
 * companion as its owner is disconnected); the platform saves the tickets and hands them back
 * through {@link #loaded} when the level loads. Those holds are unclaimed until their companion
 * ticks again. Entities are not loaded when the platform's load callback runs, so liveness is
 * decided later by {@link #sweep}: a hold with no recorded owner is released at once, and one whose
 * owner has been online for {@link #STALE_SWEEPS} sweeps without the companion coming back is
 * released as a deleted companion's. A hold whose owner stays offline is kept (R9 accepts this).
 */
public final class TicketBook {
    /** Sweeps an online owner's companion may stay away before its old hold is released. */
    public static final int STALE_SWEEPS = 3;

    /** One companion's ticket on one chunk; the platform's ticket store. */
    public interface Store {
        void add(Object dimension, UUID companion, long chunk);

        void remove(Object dimension, UUID companion, long chunk);
    }

    /** Which player each holding companion belongs to, persisted with the world. */
    public interface Owners {
        UUID get(UUID companion);

        void put(UUID companion, UUID owner);

        void remove(UUID companion);
    }

    private final Store store;
    private final Owners owners;
    /** companion -> dimension -> chunks it holds a ticket on. */
    private final Map<UUID, Map<Object, Set<Long>>> held = new HashMap<>();
    /** Holds loaded from the save whose companion has not ticked since; value = sweeps seen away. */
    private final Map<UUID, Integer> unclaimed = new HashMap<>();
    private boolean stopping;

    public TicketBook(Store store, Owners owners) {
        this.store = store;
        this.owners = owners;
    }

    /** The platform's load callback: tickets the save held for {@code companion} in {@code dimension}. */
    public synchronized void loaded(Object dimension, UUID companion, Collection<Long> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        held.computeIfAbsent(companion, c -> new HashMap<>()).computeIfAbsent(key(dimension), d -> new HashSet<>())
                .addAll(chunks);
        unclaimed.putIfAbsent(companion, 0);
    }

    /**
     * Makes {@code companion}'s hold exactly {@code wanted} in {@code dimension}: every other ticket
     * it holds, in any dimension, is released. A hold loaded from the save is claimed.
     */
    public synchronized void holdExactly(Object dimension, UUID companion, UUID owner, Collection<Long> wanted) {
        if (stopping) {
            return;
        }
        unclaimed.remove(companion);
        if (owner != null && !owner.equals(owners.get(companion))) {
            owners.put(companion, owner);
        }
        Object here = key(dimension);
        Map<Object, Set<Long>> mine = held.computeIfAbsent(companion, c -> new HashMap<>());
        for (Iterator<Map.Entry<Object, Set<Long>>> dims = mine.entrySet().iterator(); dims.hasNext(); ) {
            Map.Entry<Object, Set<Long>> e = dims.next();
            for (Iterator<Long> it = e.getValue().iterator(); it.hasNext(); ) {
                long chunk = it.next();
                if (!e.getKey().equals(here) || !wanted.contains(chunk)) {
                    store.remove(e.getKey(), companion, chunk);
                    it.remove();
                }
            }
            if (e.getValue().isEmpty()) {
                dims.remove();
            }
        }
        Set<Long> there = mine.computeIfAbsent(here, d -> new HashSet<>());
        for (long chunk : wanted) {
            if (there.add(chunk)) {
                store.add(here, companion, chunk);
            }
        }
    }

    /** Releases every ticket {@code companion} holds, in every dimension. @return tickets released */
    public synchronized int releaseAll(UUID companion) {
        if (stopping) {
            return 0;
        }
        unclaimed.remove(companion);
        owners.remove(companion);
        Map<Object, Set<Long>> mine = held.remove(companion);
        if (mine == null) {
            return 0;
        }
        int released = 0;
        for (Map.Entry<Object, Set<Long>> e : mine.entrySet()) {
            for (long chunk : e.getValue()) {
                store.remove(e.getKey(), companion, chunk);
                released++;
            }
        }
        return released;
    }

    /** Server stop: holds are kept from here on, for the next start. */
    public synchronized void stopping() {
        stopping = true;
    }

    /**
     * Releases the unclaimed holds that belong to no one, and those whose owner has been online
     * for {@link #STALE_SWEEPS} sweeps without the companion ticking. @return companions released
     */
    public synchronized List<UUID> sweep(Predicate<UUID> ownerOnline) {
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : unclaimed.entrySet()) {
            UUID owner = owners.get(e.getKey());
            if (owner == null) {
                stale.add(e.getKey());
            } else if (ownerOnline.test(owner)) {
                e.setValue(e.getValue() + 1);
                if (e.getValue() >= STALE_SWEEPS) {
                    stale.add(e.getKey());
                }
            }
        }
        for (UUID companion : stale) {
            releaseAll(companion);
        }
        return stale;
    }

    /** Tickets {@code companion} holds in {@code dimension}, as this book records them. */
    public synchronized Set<Long> held(Object dimension, UUID companion) {
        Map<Object, Set<Long>> mine = held.get(companion);
        Set<Long> chunks = mine == null ? null : mine.get(key(dimension));
        return chunks == null ? Set.of() : Set.copyOf(chunks);
    }

    /** The companions holding tickets, loaded or live. */
    public synchronized Set<UUID> companions() {
        return Set.copyOf(held.keySet());
    }

    /** The dimension part of a hold's key. */
    private static Object key(Object dimension) {
        return dimension;
    }
}
