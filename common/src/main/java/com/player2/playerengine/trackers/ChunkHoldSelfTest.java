package com.player2.playerengine.trackers;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

/**
 * A force-loaded chunk ticks entities only inside itself; its neighbours are loaded but frozen. The
 * companion's chunk holder runs from the companion's own tick, so a step into a chunk it does not
 * hold stops that tick for good, and nothing ever forces the chunk it is standing in. Every chunk
 * the companion can step into from where it stands must already be held.
 */
public final class ChunkHoldSelfTest {
    private static int checks;

    private ChunkHoldSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        // Near each edge and corner of a chunk, on both sides of the origin. The first spot is the
        // smoke gate's hang: half a block south of chunk z=1, which it then stepped into.
        double[][] spots = {{366.97, 32.5}, {367.0, 31.99}, {0.2, 0.2}, {15.8, 15.8}, {-0.2, -0.2},
                {-16.1, 47.9}, {8.0, 8.0}, {-220.3, -17.6}};
        for (double[] s : spots) {
            List<ChunkPos> held = ChunkLoadingTracker.chunksToHold(s[0], s[1]);
            ChunkPos own = new ChunkPos(net.minecraft.core.BlockPos.containing(s[0], 0, s[1]));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ChunkPos next = new ChunkPos(own.x + dx, own.z + dz);
                    require(held.contains(next), "at " + s[0] + "," + s[1] + " the companion can step into chunk "
                            + next.x + "," + next.z + ", so it is held (held " + held + ")");
                }
            }
        }
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("chunk hold self-test failed: " + message);
        }
    }
}
