package com.player2.playerengine.automaton.utils.player;

import com.player2.playerengine.automaton.api.utils.BetterBlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The bottom-slab check reads the chunk under the companion's feet. It looked up
 * {@code (int)x << 4}, a chunk sixteen times further out and never loaded, so the check never ran.
 */
public final class FeetChunkSelfTest {
    private static int checks;

    private FeetChunkSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        double[][] spots = {{-220.3, 68.0, -17.6}, {-0.5, 64.0, 15.9}, {0.0, 70.0, 0.0}, {31.99, 5.0, -16.0}, {1_000_007.2, 90.0, -3.1}};
        for (double[] s : spots) {
            BetterBlockPos feet = new BetterBlockPos(s[0], s[1], s[2]);
            // BetterBlockPos.toString needs the game directory, so the message names the coordinates.
            require(EntityContext.chunkOf(feet).equals(new ChunkPos(feet)),
                    "feet at " + feet.x + "," + feet.y + "," + feet.z + " map to their own chunk");
        }
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("feet chunk self-test failed: " + message);
        }
    }
}
