package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.MotionBounds;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import com.player2.playerengine.tasks.movement.GetToEntityTask;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * {@code follow_owner(until_near_blocks, timeout_s)}: walk to the owner until within the range; done
 * when the companion stands that near, and {@code timeout} when the time ran out first. The
 * {@code follow} command's own line (follow indefinitely, or follow someone else) stays the command.
 */
final class FollowOwnerPrimitive extends Base {
    FollowOwnerPrimitive() {
        super("follow_owner");
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        Player o = ctx.mod().getOwner();
        if (o == null || o.level() != ctx.mod().getWorld()) {
            return Calls.fail(FailureCode.NOT_FOUND, "my owner is not here");
        }
        BlockPos b = o.blockPosition();
        return MotionBounds.check(ctx.region(), Calls.dimension(ctx), new AreaSpec.Pos(b.getX(), b.getY(), b.getZ()));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        FollowOwnerTask task = new FollowOwnerTask((Integer) args.get("until_near_blocks"),
                (Integer) args.get("timeout_s") * 20);
        ctx.mod().runUserTask(task, () -> ended.accept(task.lost
                ? TaskEnd.failed(Calls.fail(FailureCode.NOT_FOUND, "my owner left while I was following"))
                : TaskEnd.finished(null)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        Vec3 owner = world.ownerPosition();
        if (owner == null) {
            return Calls.fail(FailureCode.NOT_FOUND, "my owner is not here");
        }
        int near = (Integer) args.get("until_near_blocks");
        double d = world.position().distanceTo(owner);
        if (d <= near) {
            return null;
        }
        return Calls.fail(FailureCode.TIMEOUT, "after " + args.get("timeout_s") + " s I was still " + Math.round(d)
                + " blocks from my owner", "distance", Math.round(d), "until_near_blocks", near);
    }

    /** Walks toward the owner until near, the owner is gone, or the time is up. */
    static final class FollowOwnerTask extends Task {
        private final int near;
        private final int timeoutTicks;
        private int ticks;
        boolean lost;

        FollowOwnerTask(int near, int timeoutTicks) {
            this.near = near;
            this.timeoutTicks = timeoutTicks;
        }

        private Player owner() {
            Player o = controller == null ? null : controller.getOwner();
            return o != null && o.isAlive() && !o.isRemoved() && o.level() == controller.getWorld() ? o : null;
        }

        private boolean near(Player o) {
            return o != null && controller.getPlayer().position().distanceTo(o.position()) <= near;
        }

        @Override
        protected void onStart() {
            ticks = 0;
        }

        @Override
        protected Task onTick() {
            ticks++;
            Player o = owner();
            if (o == null) {
                lost = true;
                return null;
            }
            if (near(o)) {
                return null;
            }
            setDebugState("following the owner, " + ticks + "/" + timeoutTicks + " ticks");
            return new GetToEntityTask(o, Math.max(1, near - 1));
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        public boolean isFinished() {
            return controller != null && (lost || ticks >= timeoutTicks || near(owner()));
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof FollowOwnerTask f && f.near == near && f.timeoutTicks == timeoutTicks;
        }

        @Override
        protected String toDebugString() {
            return "Follow owner to " + near + " blocks";
        }
    }
}
