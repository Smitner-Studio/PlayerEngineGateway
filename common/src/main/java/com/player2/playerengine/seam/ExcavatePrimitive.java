package com.player2.playerengine.seam;

import com.player2.playerengine.commands.AreaCommand;
import com.player2.playerengine.tasks.construction.area.AreaBuildTask;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code excavate(box)}: dig out a box; done when every cell in it is air except the ones the dig
 * must leave (players' blocks, containers, unbreakable and avoided blocks).
 */
public final class ExcavatePrimitive implements Primitive {
    @Override
    public Signature signature() {
        return SignatureTable.get("excavate");
    }

    /** Every form the {@code excavate} command takes: a size with anchor and facing, or two corners. */
    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        String[] err = new String[1];
        AreaSpec.Request req = AreaSpec.parse(argsText, false, err);
        if (req == null) {
            return LineArgs.failed(ActionError.of(FailureCode.BAD_ARGS, err[0]));
        }
        AreaSpec.Resolved resolved = AreaSpec.resolve(req, AreaCommand.anchors(ctx.mod()));
        if (resolved.error() != null) {
            return LineArgs.failed(ActionError.of(FailureCode.BAD_ARGS, resolved.error()));
        }
        return LineArgs.of(Map.of("box", resolved.box()), Map.of("confirm", req.confirm()));
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        AreaSpec.Box b = (AreaSpec.Box) args.get("box");
        AreaSpec.Pos centre = new AreaSpec.Pos((b.minX() + b.maxX()) / 2, b.minY(), (b.minZ() + b.maxZ()) / 2);
        return MotionBounds.check(ctx.region(), ctx.mod().getWorld().dimension().location().toString(), centre);
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Context ctx, Consumer<TaskEnd> ended) {
        AreaSpec.Box box = (AreaSpec.Box) args.get("box");
        AreaCommand.Prepared prepared = AreaCommand.prepare(ctx.mod(), AreaScan.Mode.EXCAVATE, box, null,
                Boolean.TRUE.equals(options.get("confirm")));
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
                // Stopped from outside; the seam tells a stop or a newer order apart from a dropped task.
                ended.accept(TaskEnd.finished(null));
            } else if (outcome.success()) {
                ctx.mod().setLastArea(box);
                ended.accept(TaskEnd.finished(outcome.message()));
            } else {
                ended.accept(TaskEnd.failed(new ActionError(
                        outcome.code() == null ? FailureCode.UNREACHABLE : outcome.code(), outcome.message(),
                        Map.of("box", box.corners()))));
            }
        });
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, World world) {
        AreaSpec.Box b = (AreaSpec.Box) args.get("box");
        int left = 0;
        int total = 0;
        String liquid = null;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    AreaScan.Cell c = world.cell(x, y, z);
                    if (c == null || !c.loaded()) {
                        return new ActionError(FailureCode.NOT_LOADED, "part of that box is not loaded, so I can't "
                                + "check it", Map.of("box", b.corners()));
                    }
                    boolean kept = c.playerPlaced() || c.blockEntity() || c.avoidBreak() || c.unbreakable();
                    if (kept && !c.air()) {
                        continue;
                    }
                    total++;
                    if (c.liquid() != null && liquid == null) {
                        liquid = c.liquid();
                    }
                    if (!c.air()) {
                        left++;
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
        if (liquid != null) {
            return new ActionError(FailureCode.LIQUID, liquid + " came into the box; " + left + " of " + total
                    + " cells are not clear", state);
        }
        return new ActionError(FailureCode.UNREACHABLE, left + " of " + total + " cells in the box are not dug out",
                state);
    }
}
