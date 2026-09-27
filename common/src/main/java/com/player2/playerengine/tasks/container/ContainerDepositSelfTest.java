package com.player2.playerengine.tasks.container;

import com.player2.playerengine.automaton.api.pathing.goals.GoalBlock;
import com.player2.playerengine.automaton.api.pathing.goals.GoalNear;
import com.player2.playerengine.tasks.movement.GetWithinRangeOfBlockTask;
import com.player2.playerengine.util.ItemTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Deposits conserve items, reach containers the companion will not break, and get a budget for
 * the walk. Needs a bootstrapped registry; run through companionSelfTest.
 */
public final class ContainerDepositSelfTest {
    private static int checks;

    private ContainerDepositSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        mergingIntoAPartStackConservesItems();
        simulatedInsertLeavesTheContainerAlone();
        depositIntoAChestThatHoldsTheItemMovesAllOwed();
        fullContainerMovesNothingAndLosesNothing();
        approachGoalIsReachableAndWithinReach();
        depositBudgetCoversTheWalk();
        return checks;
    }

    /** The live bug: a simulated merge grew the chest's stack, then the real insert grew it again. */
    private static void mergingIntoAPartStackConservesItems() {
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        SimpleContainer bot = new SimpleContainer(36);
        bot.setItem(0, new ItemStack(Items.COBBLESTONE, 5));

        int moved = ContainerDeposit.moveOneStack(bot, chest, new ItemTarget(Items.COBBLESTONE, 5), 5);

        require(moved == 5, "all 5 cobblestone move, got " + moved);
        require(count(chest, Items.COBBLESTONE) == 15, "chest holds 10 + 5, got " + count(chest, Items.COBBLESTONE));
        require(count(bot, Items.COBBLESTONE) == 0, "bot keeps none, got " + count(bot, Items.COBBLESTONE));
    }

    private static void simulatedInsertLeavesTheContainerAlone() {
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 30));
        ItemStack offer = new ItemStack(Items.RAW_IRON, 40);

        ItemStack rest = ContainerDeposit.insert(chest, offer, true);

        require(rest.isEmpty(), "40 raw iron fit (34 on the stack, 6 in a new slot)");
        require(count(chest, Items.RAW_IRON) == 30, "simulation left the chest at 30, got " + count(chest, Items.RAW_IRON));
        require(offer.getCount() == 40, "simulation left the offered stack alone");
    }

    /** A chest that already has the item used to count as "done": the owed count is what must move. */
    private static void depositIntoAChestThatHoldsTheItemMovesAllOwed() {
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
        SimpleContainer bot = new SimpleContainer(36);
        bot.setItem(3, new ItemStack(Items.COBBLESTONE, 64));
        bot.setItem(7, new ItemStack(Items.COBBLESTONE, 23));
        ItemTarget target = new ItemTarget(Items.COBBLESTONE, 87);

        int owed = target.getTargetCount();
        for (int step = 0; step < 10 && owed > 0; step++) {
            owed -= ContainerDeposit.moveOneStack(bot, chest, target, owed);
        }

        require(owed == 0, "every owed cobblestone moved, " + owed + " left");
        require(count(chest, Items.COBBLESTONE) == 128 + 87, "chest gained exactly 87");
        require(count(bot, Items.COBBLESTONE) == 0, "bot emptied");
    }

    private static void fullContainerMovesNothingAndLosesNothing() {
        SimpleContainer chest = new SimpleContainer(3);
        for (int i = 0; i < 3; i++) {
            chest.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        SimpleContainer bot = new SimpleContainer(36);
        bot.setItem(0, new ItemStack(Items.STICK, 2));

        int moved = ContainerDeposit.moveOneStack(bot, chest, new ItemTarget(Items.STICK, 2), 2);

        require(moved == 0, "nothing fits into a full chest");
        require(count(bot, Items.STICK) == 2 && count(chest, Items.STICK) == 0, "no stick lost or made");
    }

    /**
     * The old goal was the container block itself, which pathing will not break. The approach goal
     * takes a spot beside it, and every spot it takes passes the tasks' 4.5-block reach test even
     * when (int) truncation of negative coordinates shifts the companion by a block.
     */
    private static void approachGoalIsReachableAndWithinReach() {
        BlockPos chest = new BlockPos(-182, 68, -10);
        require(!new GoalBlock(chest).isInGoal(chest.east()), "old goal: only the chest's own block (witness)");

        GetWithinRangeOfBlockTask task = (GetWithinRangeOfBlockTask) ContainerApproach.task(chest);
        GoalNear goal = new GoalNear(task.blockPos, task.range);
        require(task.blockPos.equals(chest), "approach targets the container");
        require(goal.isInGoal(chest.east()) && goal.isInGoal(chest.above()), "a spot beside or on it is in the goal");

        int r = ContainerApproach.GOAL_RANGE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos feet = chest.offset(dx, dy, dz);
                    if (!goal.isInGoal(feet)) {
                        continue;
                    }
                    // (int) of a negative fractional x or z lands one block toward +infinity.
                    for (int sx = 0; sx <= 1; sx++) {
                        for (int sz = 0; sz <= 1; sz++) {
                            Vec3i seen = new Vec3i(feet.getX() + sx, feet.getY(), feet.getZ() + sz);
                            require(chest.closerThan(seen, 4.5), "in-goal spot " + feet + " is within reach");
                        }
                    }
                }
            }
        }
    }

    private static void depositBudgetCoversTheWalk() {
        double cap = StoreInAnyContainerTask.travelCapBlocks();
        double atCap = StoreInAnyContainerTask.depositBudgetSeconds(cap);
        require(atCap >= 45.0 + cap / 2.0, "a chest at the travel cap gets 2 blocks/s of walking");
        require(StoreInAnyContainerTask.overallTimeoutSeconds() >= atCap, "the overall budget covers the per-chest budget");
        // Rivet's chest was 38 blocks away: the flat 45 s budget ran out mid-walk.
        require(StoreInAnyContainerTask.depositBudgetSeconds(38.0) > 45.0 + 18.0, "a 38-block walk adds 19 s");
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).is(item)) {
                n += c.getItem(i).getCount();
            }
        }
        return n;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("container deposit self-test failed: " + message);
        }
    }
}
