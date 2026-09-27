package com.player2.playerengine.tasks.container;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetWithinRangeOfBlockTask;
import net.minecraft.core.BlockPos;

/**
 * How a container task walks up to its block. The goal is any standing spot within
 * {@link #GOAL_RANGE} blocks, never the block itself: pathing will not break a chest (Baritone's
 * blocksToAvoidBreaking) or a player-placed block, so a goal inside the container is unreachable.
 * Baritone then returns only partial paths whose plan-ahead search fails every five seconds; in
 * play the companion covered about a block per cycle and ran out the deposit budgets.
 *
 * <p>Every spot in the goal is within the 4.5-block reach the container tasks test before they
 * open the block, including their {@code (int)} truncation of negative coordinates.
 */
public final class ContainerApproach {
    static final int GOAL_RANGE = 3;

    private ContainerApproach() {
    }

    public static Task task(BlockPos container) {
        return new GetWithinRangeOfBlockTask(container, GOAL_RANGE);
    }
}
