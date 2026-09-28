package com.player2.playerengine.util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;

/**
 * A companion un-forces only what it forced: never a chunk someone else forced first, never a chunk
 * in another dimension at the same coordinates, and all of its 3x3 hold when it is removed.
 */
public final class ChunkControllerSelfTest {
    private static int checks;

    private ChunkControllerSelfTest() {
    }

    /** One level's forced-chunk set, as vanilla keeps it: a flag per chunk, no owner. */
    private static final class World implements ChunkController.ForcedChunks {
        final String dimension;
        final Set<Long> forced = new HashSet<>();

        World(String dimension) {
            this.dimension = dimension;
        }

        @Override
        public Object dimension() {
            return dimension;
        }

        @Override
        public boolean isForced(int x, int z) {
            return forced.contains(ChunkPos.asLong(x, z));
        }

        @Override
        public void setForced(int x, int z, boolean on) {
            if (on) {
                forced.add(ChunkPos.asLong(x, z));
            } else {
                forced.remove(ChunkPos.asLong(x, z));
            }
        }
    }

    public static int runAll() {
        checks = 0;
        foreignChunkIsNeverClaimed();
        dimensionsAreSeparate();
        removalReleasesTheWholeHold();
        serverStopReleasesOnlyOurs();
        return checks;
    }

    private static void foreignChunkIsNeverClaimed() {
        ChunkController c = new ChunkController();
        World w = new World("overworld");
        UUID bot = UUID.randomUUID();
        w.setForced(3, 4, true);
        c.load(w, bot, 3, 4);
        c.unload(w, bot, 3, 4);
        require(w.isForced(3, 4), "leaving an operator-forced chunk keeps it forced");
        c.load(w, bot, 3, 4);
        c.releaseAll(bot);
        require(w.isForced(3, 4), "removing the companion keeps an operator-forced chunk forced");
        c.load(w, bot, 5, 5);
        require(w.isForced(5, 5), "a free chunk is forced");
        c.load(w, bot, 5, 5);
        c.unload(w, bot, 5, 5);
        require(!w.isForced(5, 5), "the companion's own chunk is released on leaving, however often it was loaded");
    }

    private static void dimensionsAreSeparate() {
        ChunkController c = new ChunkController();
        World overworld = new World("overworld");
        World nether = new World("the_nether");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        c.load(overworld, a, 7, 7);
        c.load(nether, b, 7, 7);
        require(nether.isForced(7, 7), "the nether chunk at the same coordinates is forced too");
        c.unload(nether, b, 7, 7);
        require(!nether.isForced(7, 7), "the nether chunk is released");
        require(overworld.isForced(7, 7), "the overworld chunk at the same coordinates stays forced");
        c.load(nether, b, 8, 8);
        c.releaseAll(b);
        require(!nether.isForced(8, 8), "release-all un-forces in the level the hold was taken in");
        require(overworld.isForced(7, 7), "release-all leaves the other companion's overworld hold");
    }

    private static void removalReleasesTheWholeHold() {
        ChunkController c = new ChunkController();
        World w = new World("overworld");
        UUID bot = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                c.load(w, bot, 10 + dx, 10 + dz);
            }
        }
        c.load(w, other, 11, 11);
        require(w.forced.size() == 9, "the 3x3 hold is forced");
        require(c.releaseAll(bot) == 8, "release-all un-forces the 8 chunks only this companion held");
        require(w.forced.equals(Set.of(ChunkPos.asLong(11, 11))), "only the chunk another companion holds stays forced");
    }

    private static void serverStopReleasesOnlyOurs() {
        ChunkController c = new ChunkController();
        World w = new World("overworld");
        w.setForced(0, 0, true);
        c.load(w, UUID.randomUUID(), 0, 0);
        c.load(w, UUID.randomUUID(), 1, 0);
        require(c.releaseEverything() == 1, "server stop releases the one chunk the companions forced");
        require(w.forced.equals(Set.of(ChunkPos.asLong(0, 0))), "the operator's chunk survives the server stop");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("chunk controller self-test failed: " + message);
        }
    }
}
