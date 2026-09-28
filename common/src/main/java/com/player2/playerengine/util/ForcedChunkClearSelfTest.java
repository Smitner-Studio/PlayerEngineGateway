package com.player2.playerengine.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

/** The one-time clear: every vanilla forced chunk on the first run, nothing on any later run. */
public final class ForcedChunkClearSelfTest {
    private static int checks;

    private ForcedChunkClearSelfTest() {
    }

    private static final class Level implements ForcedChunkClear.ForcedLevel {
        final String name;
        final Set<Long> forced = new HashSet<>();

        Level(String name, long... chunks) {
            this.name = name;
            for (long c : chunks) {
                forced.add(c);
            }
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Long> forced() {
            return new ArrayList<>(forced);
        }

        @Override
        public void unforce(long chunk) {
            forced.remove(chunk);
        }
    }

    private static final class Flag implements ForcedChunkClear.Marker {
        boolean done;

        @Override
        public boolean done() {
            return done;
        }

        @Override
        public void markDone() {
            done = true;
        }
    }

    public static int runAll() {
        checks = 0;
        Level overworld = new Level("minecraft:overworld", ChunkPos.asLong(0, 0), ChunkPos.asLong(-3, 7));
        Level nether = new Level("minecraft:the_nether", ChunkPos.asLong(0, 0));
        Flag flag = new Flag();
        List<String> log = new ArrayList<>();
        int first = ForcedChunkClear.runOnce(List.of(overworld, nether), flag, log::add);
        require(first == 3 && overworld.forced.isEmpty() && nether.forced.isEmpty(),
                "the first start un-forces every vanilla forced chunk in every level (" + first + ")");
        require(log.stream().filter(l -> l.contains("un-forced")).count() == 3, "each cleared chunk is logged: " + log);
        require(flag.done, "the world records the clear");

        overworld.forced.add(ChunkPos.asLong(5, 5));
        log.clear();
        int second = ForcedChunkClear.runOnce(List.of(overworld, nether), flag, log::add);
        require(second == -1 && overworld.forced.contains(ChunkPos.asLong(5, 5)),
                "a later start clears nothing: a chunk forced after the first start survives");
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("forced-chunk clear self-test failed: " + message);
        }
    }
}
