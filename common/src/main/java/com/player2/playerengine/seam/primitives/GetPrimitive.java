package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.FailureCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code get(item, n)}: gather or craft until the companion carries n. Count-based, so a re-run after
 * an interruption asks only for what is still missing. Done when {@code count(item) >= n}.
 *
 * <p>The {@code get} command line means "n more" and names catalogue groups ({@code planks},
 * {@code log}) that are not item ids. A line with one registered item becomes {@code get(item,
 * have + n)}, the same order; a group or a list stays with the command.
 */
final class GetPrimitive extends Base {
    GetPrimitive() {
        super("get");
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        return Calls.standingInRegion(ctx);
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) {
        return Map.of("have", Calls.count(world.inventory(), (String) args.get("item")));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        int need = (Integer) args.get("n") - ((Number) pre.get("have")).intValue();
        if (need <= 0) {
            ended.accept(TaskEnd.finished("already carrying " + pre.get("have")));
            return;
        }
        // The command gets that many more than it carries.
        Calls.runCommand(ctx, "get " + args.get("item") + " " + need, ended);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String item = (String) args.get("item");
        int n = (Integer) args.get("n");
        int have = Calls.count(world.inventory(), item);
        return have >= n ? null : Calls.fail(FailureCode.MISSING_ITEM, "I have " + have + " " + Calls.human(item)
                + " of " + n, "item", item, "needed", n, "have", have);
    }
}
