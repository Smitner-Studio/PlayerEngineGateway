package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.MotionBounds;
import com.player2.playerengine.seam.Queries;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code place(block, p)}: one block from the inventory at p. It runs as a one-cell fill, so it
 * refuses the same places (border, spawn, vetoes, liquid) for the same reasons. Done when
 * {@code block_at(p)} is the block.
 */
final class PlacePrimitive extends Base {
    PlacePrimitive() {
        super("place");
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        ActionError bad = FillPrimitive.placeable((String) args.get("block"));
        return bad != null ? bad : MotionBounds.check(ctx.region(), Calls.dimension(ctx), (AreaSpec.Pos) args.get("p"));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        AreaSpec.Pos p = (AreaSpec.Pos) args.get("p");
        AreaSpec.Box cell = new AreaSpec.Box(p.x(), p.y(), p.z(), p.x(), p.y(), p.z(), AreaSpec.Facing.NORTH);
        FillPrimitive.runArea(ctx, cell, Queries.block((String) args.get("block")), false, false, ended);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String block = (String) args.get("block");
        AreaSpec.Pos p = (AreaSpec.Pos) args.get("p");
        String at = world.blockAt(p.x(), p.y(), p.z());
        if (at == null) {
            return Calls.fail(FailureCode.NOT_LOADED, "that spot is not loaded, so I can't check it", "pos", Calls.list(p));
        }
        if (block.equals(at)) {
            return null;
        }
        int have = world.inventory().getOrDefault(Calls.itemOf(block), 0);
        FailureCode code = have == 0 ? FailureCode.MISSING_ITEM : FailureCode.UNREACHABLE;
        return Calls.fail(code, "there is " + Calls.human(at) + " at " + p.x() + " " + p.y() + " " + p.z() + ", not "
                + Calls.human(block), "pos", Calls.list(p), "at", at, "have", have);
    }
}
