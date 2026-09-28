package com.player2.playerengine.seam;

import com.player2.playerengine.tasks.construction.area.AreaSpec;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** {@code goto(p)}: walk to p; done when the companion stands within 2 blocks of it. */
public final class GotoPrimitive implements Primitive {
    /** How near counts as arrived, measured from the companion's feet to the block's centre. */
    public static final double ARRIVED = 2.0;

    @Override
    public Signature signature() {
        return SignatureTable.get("goto");
    }

    /** Takes {@code x y z} only; the dimension and partial forms stay with the {@code goto} command. */
    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        String[] t = argsText.trim().split("\\s+");
        if (t.length != 3) {
            return null;
        }
        for (String s : t) {
            if (!s.matches("-?\\d+(\\.\\d+)?")) {
                return null;
            }
        }
        return LineArgs.of(Map.of("p", String.join(" ", t)), Map.of());
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        return MotionBounds.check(ctx.region(), dimension(ctx), (AreaSpec.Pos) args.get("p"));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        AreaSpec.Pos p = (AreaSpec.Pos) args.get("p");
        ctx.mod().runUserTask(new GetToBlockTask(new BlockPos(p.x(), p.y(), p.z())), () -> ended.accept(TaskEnd.finished(null)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        AreaSpec.Pos p = (AreaSpec.Pos) args.get("p");
        Vec3 at = world.position();
        double d = at.distanceTo(new Vec3(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5));
        if (d <= ARRIVED) {
            return null;
        }
        return new ActionError(FailureCode.UNREACHABLE, "I stopped " + Math.round(d) + " blocks short of "
                + p.x() + " " + p.y() + " " + p.z(), Map.of(
                "target", List.of(p.x(), p.y(), p.z()),
                "at", List.of((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)),
                "distance", Math.round(d)));
    }

    private static String dimension(Context ctx) {
        return ctx.mod().getWorld().dimension().location().toString();
    }
}
