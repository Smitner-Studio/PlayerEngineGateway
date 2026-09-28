package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.commands.AreaCommand;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.MotionBounds;
import com.player2.playerengine.seam.Queries;
import com.player2.playerengine.tasks.construction.area.AreaBuildTask;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code fill(block, box)}: place the block over every empty cell of the box. Done when no cell the
 * fill targets is left empty: players' blocks, containers and cells already holding something solid
 * are left as they are, as the area scan leaves them.
 */
final class FillPrimitive extends Base {
    FillPrimitive() {
        super("fill");
    }

    /** Every form the {@code fill} command takes: a block, then a size with anchor and facing, or two corners. */
    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        String[] err = new String[1];
        AreaSpec.Request req = AreaSpec.parse(argsText, true, err);
        if (req == null) {
            return LineArgs.failed(ActionError.of(FailureCode.BAD_ARGS, err[0]));
        }
        AreaSpec.Resolved resolved = AreaSpec.resolve(req, AreaCommand.anchors(ctx.mod()));
        if (resolved.error() != null) {
            return LineArgs.failed(ActionError.of(FailureCode.BAD_ARGS, resolved.error()));
        }
        return LineArgs.of(Map.of("block", req.block(), "box", resolved.box()), Map.of("confirm", req.confirm()));
    }

    /** Null when {@code id} names a block with an item to place, else {@code bad_args}. */
    static ActionError placeable(String id) {
        Block b = Queries.block(id);
        if (b == null || b == Blocks.AIR || b.asItem() == Items.AIR) {
            return Calls.fail(FailureCode.BAD_ARGS, "'" + id + "' is not a block I can place");
        }
        return null;
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        ActionError bad = placeable((String) args.get("block"));
        if (bad != null) {
            return bad;
        }
        AreaSpec.Box b = (AreaSpec.Box) args.get("box");
        AreaSpec.Pos centre = new AreaSpec.Pos((b.minX() + b.maxX()) / 2, b.minY(), (b.minZ() + b.maxZ()) / 2);
        return MotionBounds.check(ctx.region(), Calls.dimension(ctx), centre);
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        runArea(ctx, (AreaSpec.Box) args.get("box"), Queries.block((String) args.get("block")),
                Boolean.TRUE.equals(options.get("confirm")), true, ended);
    }

    /** Runs an area fill through the same preparation the {@code fill} command uses. */
    static void runArea(Context ctx, AreaSpec.Box box, Block block, boolean confirm, boolean setLastArea,
            Consumer<TaskEnd> ended) {
        AreaCommand.Prepared prepared = AreaCommand.prepare(ctx.mod(), AreaScan.Mode.FILL, box, block, confirm);
        if (prepared.refused()) {
            ended.accept(TaskEnd.failed(new ActionError(prepared.code(), prepared.refusal(), Map.of("box", box.corners()))));
            return;
        }
        if (prepared.task() == null) {
            ended.accept(TaskEnd.finished(prepared.doneNote()));
            return;
        }
        AreaBuildTask task = prepared.task();
        ctx.mod().runUserTask(task, () -> {
            AreaBuildTask.Outcome outcome = task.outcome();
            if (outcome == null) {
                ended.accept(TaskEnd.finished(null));
            } else if (outcome.success()) {
                if (setLastArea) {
                    ctx.mod().setLastArea(box);
                }
                ended.accept(TaskEnd.finished(outcome.message()));
            } else {
                ended.accept(TaskEnd.failed(new ActionError(
                        outcome.code() == null ? FailureCode.UNREACHABLE : outcome.code(), outcome.message(),
                        Map.of("box", box.corners()))));
            }
        });
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String block = (String) args.get("block");
        AreaSpec.Box b = (AreaSpec.Box) args.get("box");
        int left = 0;
        int total = 0;
        String liquid = null;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    AreaScan.Cell c = world.cell(x, y, z);
                    if (c == null || !c.loaded()) {
                        return Calls.fail(FailureCode.NOT_LOADED, "part of that box is not loaded, so I can't check it",
                                "box", b.corners());
                    }
                    if (block.equals(world.blockAt(x, y, z))) {
                        total++;
                        continue;
                    }
                    boolean kept = c.playerPlaced() || c.blockEntity() || c.avoidBreak() || c.unbreakable();
                    if (!c.air() && (kept || !c.replaceable())) {
                        continue;
                    }
                    total++;
                    left++;
                    if (c.liquid() != null && liquid == null) {
                        liquid = c.liquid();
                    }
                }
            }
        }
        if (left == 0) {
            return null;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("box", b.corners());
        state.put("left", left);
        state.put("cells", total);
        state.put("have", world.inventory().getOrDefault(Calls.itemOf(block), 0));
        if (liquid != null) {
            return new ActionError(FailureCode.LIQUID, "there is " + liquid + " in the box; " + left + " of " + total
                    + " cells are not filled", state);
        }
        if (world.inventory().getOrDefault(Calls.itemOf(block), 0) < left) {
            return new ActionError(FailureCode.MISSING_ITEM, left + " of " + total + " cells are still empty and I carry "
                    + world.inventory().getOrDefault(Calls.itemOf(block), 0) + " " + Calls.human(block), state);
        }
        return new ActionError(FailureCode.UNREACHABLE, left + " of " + total + " cells in the box are not filled", state);
    }
}
