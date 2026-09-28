package com.player2.playerengine.seam;

import com.player2.playerengine.util.Perception;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * The perception rule at the seam (§5.6, F1): no query or primitive target choice may use what a
 * survival player standing where the companion stands could not perceive. It applies the same
 * exposure test as the body's filters ({@link Perception#isExposed}), so the seam and the stage-0
 * scanner and status lines can never disagree about what counts as seen.
 */
public final class SeamPerception {
    /** Reads one exposure check costs: one per face. */
    public static final int EXPOSURE_READS = 6;
    /** Line-of-sight raycasts one query may spend on containers or entities. */
    public static final int MAX_RAYCASTS = 16;
    /** What {@code block_at} reports for a block with no exposed face. */
    public static final String HIDDEN = "hidden";

    private SeamPerception() {
    }

    /**
     * Whether a face of {@code pos} is exposed. A neighbour in an unloaded chunk reads as stone, as
     * {@link Perception#of} has it: an unknown face must not make a block count as seen.
     */
    public static boolean exposed(WorldReader world, BlockPos pos) {
        return Perception.isExposed(p -> world.isLoaded(p.getX() >> 4, p.getZ() >> 4)
                ? world.state(p.getX(), p.getY(), p.getZ()) : Blocks.STONE.defaultBlockState(), pos);
    }

    /** Spends raycasts for one query, up to {@link #MAX_RAYCASTS}. */
    public static final class Raycasts {
        private int left = MAX_RAYCASTS;

        public int left() {
            return left;
        }

        /** False once the query has used its raycasts, without casting. */
        boolean cast(BooleanSupplier lineOfSight) {
            if (left <= 0) {
                return false;
            }
            left--;
            return lineOfSight.getAsBoolean();
        }
    }

    /**
     * Whether a container may be listed or read: already known (opened by this companion, or in the
     * owner's recorded storage), or exposed and in line of sight from the companion's eyes.
     */
    public static boolean containerVisible(boolean known, boolean exposed, BooleanSupplier lineOfSight,
            Raycasts raycasts) {
        return known || exposed && raycasts.cast(lineOfSight);
    }
}
