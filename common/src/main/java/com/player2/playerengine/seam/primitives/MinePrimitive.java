package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.FailureCode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code mine(block, n)}: break n exposed blocks and collect the drops. Done when the inventory gained
 * at least n of what the block yields (every block yields at least one of its drops to the right
 * tool), so a mine whose drops fell into lava, or that found nothing in range, is not a success.
 */
final class MinePrimitive extends Base {
    /** The {@code mine} command's own cap on one order. */
    static final int MAX_BLOCKS = 256;

    MinePrimitive() {
        super("mine");
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        int n = (Integer) args.get("n");
        return n > MAX_BLOCKS ? Calls.fail(FailureCode.BAD_ARGS, "I mine at most " + MAX_BLOCKS
                + " blocks per order; split it", "n", n) : Calls.standingInRegion(ctx);
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) throws Coercion.Failure {
        String block = (String) args.get("block");
        List<String> drops = world.drops(block);
        if (drops.isEmpty()) {
            throw new Coercion.Failure(Calls.fail(FailureCode.BAD_ARGS, "mining " + Calls.human(block)
                    + " yields nothing to collect; excavate clears it instead", "block", block));
        }
        Map<String, Integer> have = new LinkedHashMap<>();
        Map<String, Integer> inv = world.inventory();
        for (String d : drops) {
            have.put(d, Calls.count(inv, d));
        }
        return Map.of("drops", have);
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Calls.runCommand(ctx, "mine " + args.get("block") + " " + args.get("n"), ended);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        int n = (Integer) args.get("n");
        Map<String, Integer> before = Calls.counts(pre, "drops");
        Map<String, Integer> now = world.inventory();
        int gained = 0;
        Map<String, Object> got = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int d = Calls.count(now, e.getKey()) - e.getValue();
            if (d > 0) {
                gained += d;
                got.put(e.getKey(), d);
            }
        }
        if (gained >= n) {
            return null;
        }
        String block = (String) args.get("block");
        return Calls.fail(gained == 0 ? FailureCode.NOT_FOUND : FailureCode.UNREACHABLE, "I collected " + gained
                        + " from mining " + Calls.human(block) + ", short of " + n, "block", block, "needed", n,
                "gained", got, "drops", List.copyOf(before.keySet()));
    }
}
