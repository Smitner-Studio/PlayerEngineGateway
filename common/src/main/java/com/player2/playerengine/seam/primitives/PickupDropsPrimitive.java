package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.agentic.steps.GatherLooseItemsParams;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.tasks.agentic.GatherLooseItemsTask;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;

/**
 * {@code pickup_drops(radius)}: pick up the items lying within radius of where the call started.
 * Done when none are left there. The {@code pickup_drops} command line, which names no radius, runs
 * with the configured gather radius, clamped to 16.
 */
final class PickupDropsPrimitive extends Base {
    PickupDropsPrimitive() {
        super("pickup_drops");
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) {
        return Map.of("centre", Calls.list(world.position()));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        var settings = ctx.mod().getModSettings();
        GatherLooseItemsParams params = new GatherLooseItemsParams((Integer) args.get("radius"),
                settings.getGatherLooseItemsMaxItems(), 256, settings.getGatherLooseItemsSettleSeconds(),
                settings.getGatherLooseItemsTimeoutSeconds(), true, List.of());
        AgenticRunRegistry.AgenticRunState runState =
                new AgenticRunRegistry.AgenticRunState("pickup", "pickup_drops", "seam");
        ctx.mod().runUserTaskTracked("pickup-drops", "gather_loose_items", new GatherLooseItemsTask(params, runState),
                RollbackPolicy.NONE, () -> ended.accept(TaskEnd.finished(null)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        int radius = (Integer) args.get("radius");
        Vec3 centre = Calls.vec(pre.get("centre"));
        Map<String, Integer> left = world.groundItems(centre, radius);
        if (left.isEmpty()) {
            return null;
        }
        int n = left.values().stream().mapToInt(Integer::intValue).sum();
        if (world.freeSlots() == 0) {
            return Calls.fail(FailureCode.CONTAINER_FULL, "my inventory is full; " + n + " items are still on the ground",
                    "left", left);
        }
        return Calls.fail(FailureCode.UNREACHABLE, n + " items are still on the ground within " + radius + " blocks",
                "left", left);
    }
}
