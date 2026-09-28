package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.tasks.base.Task;
import java.util.Map;
import java.util.function.Consumer;

/** {@code wait(ticks)}: stand still; done once that many server ticks have passed since the call started. */
final class WaitPrimitive extends Base {
    WaitPrimitive() {
        super("wait");
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) {
        return Map.of("tick", world.gameTime());
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        ctx.mod().runUserTask(new WaitTicksTask((Integer) args.get("ticks")), () -> ended.accept(TaskEnd.finished(null)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        int ticks = (Integer) args.get("ticks");
        long waited = world.gameTime() - ((Number) pre.get("tick")).longValue();
        return waited >= ticks ? null : Calls.fail(FailureCode.SUPERSEDED, "I stopped waiting after " + waited
                + " of " + ticks + " ticks", "waited", waited, "ticks", ticks);
    }

    /** Idles for a number of its own ticks. */
    static final class WaitTicksTask extends Task {
        private final int ticks;
        private int elapsed;

        WaitTicksTask(int ticks) {
            this.ticks = ticks;
        }

        @Override
        protected void onStart() {
            elapsed = 0;
        }

        @Override
        protected Task onTick() {
            elapsed++;
            setDebugState("waiting " + elapsed + "/" + ticks);
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        public boolean isFinished() {
            return elapsed >= ticks;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof WaitTicksTask w && w.ticks == ticks;
        }

        @Override
        protected String toDebugString() {
            return "Wait " + ticks + " ticks";
        }
    }
}
