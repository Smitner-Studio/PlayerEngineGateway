package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Seam;
import com.player2.playerengine.tasks.base.Task;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code confirm(question)}: ask the owner and wait. A yes or no reply is parsed deterministically
 * ({@link com.player2.playerengine.seam.YesNo}) and returned as true or false; any other reply ends
 * the call {@code ambiguous} and goes on to the model, as §5.3 has it. The chat path hands the
 * owner's lines to {@link Seam#reply} before the model sees them.
 */
final class ConfirmPrimitive extends Base {
    ConfirmPrimitive() {
        super("confirm");
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Seam seam = ctx.mod().getCommandExecutor().seam();
        String question = (String) args.get("question");
        Seam.Confirmation asked = seam.ask(question);
        SayPrimitive.speak(ctx.mod(), question);
        ConfirmTask task = new ConfirmTask(seam, asked.id());
        ctx.mod().runUserTask(task, () -> {
            Seam.Confirmation c = seam.confirmation();
            if (c == null || c.id() != asked.id() || !c.answered()) {
                ended.accept(TaskEnd.failed(Calls.fail(FailureCode.SUPERSEDED, "the question was dropped unanswered")));
            } else if (c.answer() == null) {
                ended.accept(TaskEnd.failed(Calls.fail(FailureCode.AMBIGUOUS,
                        "my owner answered something other than yes or no", "reply", c.text())));
            } else {
                ended.accept(TaskEnd.returned(c.answer()));
            }
        });
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        Seam.Confirmation c = world.confirmation();
        if (c != null && c.answered() && c.answer() != null && c.question().equals(args.get("question"))) {
            return null;
        }
        return Calls.fail(FailureCode.SUPERSEDED, "my owner has not answered yes or no");
    }

    /** Waits for the owner's answer to one question; a stop drops the question. */
    static final class ConfirmTask extends Task {
        private final Seam seam;
        private final long id;

        ConfirmTask(Seam seam, long id) {
            this.seam = seam;
            this.id = id;
        }

        @Override
        protected void onStart() {
        }

        @Override
        protected Task onTick() {
            setDebugState("waiting for a yes or no");
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
            seam.closeConfirmation(id);
        }

        @Override
        public boolean isFinished() {
            Seam.Confirmation c = seam.confirmation();
            return c == null || c.id() != id || c.answered();
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "Confirm";
        }
    }
}
