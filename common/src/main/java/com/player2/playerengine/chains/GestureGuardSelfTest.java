package com.player2.playerengine.chains;

import com.player2.playerengine.tasks.agentic.MineBlockParams;
import com.player2.playerengine.tasks.agentic.MineBlockTask;
import com.player2.playerengine.tasks.container.StoreInAnyContainerTask;
import com.player2.playerengine.util.ItemTarget;
import net.minecraft.world.item.Items;

/**
 * A gesture must not end work it cannot resume. In the playtest a reply that started a deposit
 * also nodded, and the nod dropped the deposit 20 ms after it began.
 */
public final class GestureGuardSelfTest {
    private static int checks;

    private GestureGuardSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        StoreInAnyContainerTask deposit = new StoreInAnyContainerTask(false, new ItemTarget(Items.STICK, 2));
        require(!UserTaskChain.gestureWouldDropTask(deposit, false, false), "a deposit not yet installed is not at risk");
        deposit.reset();
        require(UserTaskChain.gestureWouldDropTask(deposit, false, false), "an installed deposit would be dropped: skip the gesture");
        require(!UserTaskChain.gestureWouldDropTask(deposit, true, false), "idle is not user work");
        require(!UserTaskChain.gestureWouldDropTask(deposit, false, true), "a task being resumed is left to the resume path");

        MineBlockTask mine = new MineBlockTask(new MineBlockParams("minecraft:iron_ore", 8, 32.0, 120.0, 8, 6), null, null);
        mine.reset();
        require(!UserTaskChain.gestureWouldDropTask(mine, false, false), "a resume-safe task is suspended, not dropped");
        require(!UserTaskChain.gestureWouldDropTask(null, false, false), "no task, nothing to drop");
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("gesture guard self-test failed: " + message);
        }
    }
}
