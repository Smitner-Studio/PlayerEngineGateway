package com.player2.playerengine.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Companions force-load the chunks around them. Vanilla keeps one forced flag per chunk with no
 * owner, so this controller only ever un-forces a chunk it forced itself: a chunk that was already
 * forced when a companion arrived (an operator's {@code /forceload}, the smoke arena, another mod)
 * is never claimed. Holds are keyed by dimension and chunk, and released on the level they were
 * forced in.
 *
 * <p>Stopgap limits (the ticket-based fix is SMITNERPAK-3): a chunk forced by someone else AFTER a
 * companion claimed it is still released when the companion leaves, and the holds live in memory
 * only, so {@link #releaseEverything} must run before the world saves. Otherwise the chunks stay
 * forced into the next start, where they look foreign and are never released.
 */
public class ChunkController
{
    public static ChunkController instance = new ChunkController();

    /** The world side of forcing, so the ownership rules are testable without a server. */
    public interface ForcedChunks {
        /** Identity of the dimension: equal for the same level. */
        Object dimension();

        boolean isForced(int xChunk, int zChunk);

        void setForced(int xChunk, int zChunk, boolean forced);
    }

    private record Key(Object dimension, long chunk) {
    }

    private static final class Held {
        final ForcedChunks world;
        final int x;
        final int z;
        final Set<UUID> holders = new HashSet<>();

        Held(ForcedChunks world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }
    }

    private final Map<Key, Held> held = new HashMap<>();

    public ChunkController(){
        instance = this;
    }

    public static ForcedChunks of(ServerLevel level) {
        return new ForcedChunks() {
            @Override
            public Object dimension() {
                return level.dimension();
            }

            @Override
            public boolean isForced(int xChunk, int zChunk) {
                return level.getForcedChunks().contains(ChunkPos.asLong(xChunk, zChunk));
            }

            @Override
            public void setForced(int xChunk, int zChunk, boolean forced) {
                level.setChunkForced(xChunk, zChunk, forced);
            }
        };
    }

    public void load(ServerLevel world, UUID id, int xChunk, int zChunk) {
        load(of(world), id, xChunk, zChunk);
    }

    public void unload(ServerLevel world, UUID id, int xChunk, int zChunk) {
        unload(of(world), id, xChunk, zChunk);
    }

    /** Holds the chunk for {@code id}; idempotent. A chunk someone else forced is left alone. */
    public synchronized void load(ForcedChunks world, UUID id, int xChunk, int zChunk) {
        Key key = new Key(world.dimension(), ChunkPos.asLong(xChunk, zChunk));
        Held h = held.get(key);
        if (h == null) {
            if (world.isForced(xChunk, zChunk)) {
                return;
            }
            h = new Held(world, xChunk, zChunk);
            held.put(key, h);
            world.setForced(xChunk, zChunk, true);
        }
        h.holders.add(id);
    }

    public synchronized void unload(ForcedChunks world, UUID id, int xChunk, int zChunk) {
        Key key = new Key(world.dimension(), ChunkPos.asLong(xChunk, zChunk));
        Held h = held.get(key);
        if (h != null && h.holders.remove(id) && h.holders.isEmpty()) {
            held.remove(key);
            h.world.setForced(xChunk, zChunk, false);
        }
    }

    /** Drops every chunk {@code id} holds, in every dimension. @return chunks un-forced */
    public synchronized int releaseAll(UUID id) {
        int released = 0;
        for (Iterator<Held> it = held.values().iterator(); it.hasNext(); ) {
            Held h = it.next();
            if (h.holders.remove(id) && h.holders.isEmpty()) {
                it.remove();
                h.world.setForced(h.x, h.z, false);
                released++;
            }
        }
        return released;
    }

    /** Un-forces every chunk this controller forced, for server stop. @return chunks un-forced */
    public synchronized int releaseEverything() {
        int released = held.size();
        for (Held h : held.values()) {
            h.world.setForced(h.x, h.z, false);
        }
        held.clear();
        return released;
    }

    public synchronized int size() {
        return held.size();
    }
}
