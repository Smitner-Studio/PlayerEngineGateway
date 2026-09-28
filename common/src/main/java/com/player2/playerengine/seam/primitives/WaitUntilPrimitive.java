package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Outcome;
import com.player2.playerengine.seam.Queries;
import com.player2.playerengine.seam.QueryQueue;
import com.player2.playerengine.seam.ReadBudget;
import com.player2.playerengine.seam.Seam;
import com.player2.playerengine.seam.Signature;
import com.player2.playerengine.seam.SignatureTable;
import com.player2.playerengine.tasks.base.Task;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@code wait_until(query_name, args, predicate, timeout_s)}: re-run a query at most every 20 ticks
 * until the predicate holds of its value. The predicate is the interpreter's (a
 * {@link Predicate}); the postcondition re-runs the query once and tests it again, so the query must
 * fit one tick's read budget, and {@code admit} refuses one that cannot.
 */
final class WaitUntilPrimitive extends Base {
    static final int PERIOD_TICKS = 20;

    WaitUntilPrimitive() {
        super("wait_until");
    }

    /** The query's own arguments, coerced against its signature: a map by name, or a list by position. */
    static Coercion.Result queryArgs(Map<String, Object> args) {
        Signature q = SignatureTable.get((String) args.get("query_name"));
        Object raw = args.get("args");
        Map<String, Object> byName = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> byName.put(String.valueOf(k), v));
        } else if (raw instanceof List<?> l) {
            for (int i = 0; i < l.size() && i < q.args().size(); i++) {
                byName.put(q.args().get(i).name(), l.get(i));
            }
        } else if (raw != null && !q.args().isEmpty()) {
            byName.put(q.args().get(0).name(), raw);
        }
        return Coercion.coerce(q, byName, Coercion.Ids.REGISTRIES);
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        if (!(args.get("predicate") instanceof Predicate<?>)) {
            return Calls.fail(FailureCode.BAD_ARGS, "wait_until needs a predicate function of the query's value");
        }
        Coercion.Result q = queryArgs(args);
        if (!q.ok()) {
            return q.error().with("query", args.get("query_name"));
        }
        if ("find_blocks".equals(args.get("query_name"))) {
            int side = 2 * (Integer) q.args().get("radius") + 1;
            long worst = (long) side * side * side * (1 + 6);
            if (worst > ReadBudget.PER_TICK) {
                return Calls.fail(FailureCode.BUDGET, "wait_until can re-check find_blocks only up to radius 3; "
                        + "use a loop of find_blocks and wait instead", "radius", q.args().get("radius"));
            }
        }
        return null;
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Seam seam = ctx.mod().getCommandExecutor().seam();
        Signature sig = SignatureTable.get((String) args.get("query_name"));
        Map<String, Object> qargs = queryArgs(args).args();
        @SuppressWarnings("unchecked")
        Predicate<Object> predicate = (Predicate<Object>) args.get("predicate");
        WaitUntilTask task = new WaitUntilTask(seam, sig, qargs, predicate, (Integer) args.get("timeout_s") * 20);
        ctx.mod().runUserTask(task, () -> ended.accept(task.failure != null ? TaskEnd.failed(task.failure)
                : TaskEnd.finished(null)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        Signature sig = SignatureTable.get((String) args.get("query_name"));
        QueryQueue.Query q = Queries.build(sig, queryArgs(args).args(), world);
        Outcome o = q.step(world.reader(), new ReadBudget(ReadBudget.PER_TICK), world.gameTime());
        if (o == null) {
            return Calls.fail(FailureCode.BUDGET, "that query is too big to re-check in one tick");
        }
        if (!o.ok()) {
            return o.error();
        }
        return test(args, o.value()) ? null : Calls.fail(FailureCode.TIMEOUT, "the condition on "
                + sig.name() + " did not hold within " + args.get("timeout_s") + " s", "last", String.valueOf(o.value()));
    }

    @SuppressWarnings("unchecked")
    private static boolean test(Map<String, Object> args, Object value) {
        try {
            return ((Predicate<Object>) args.get("predicate")).test(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Submits the query every {@link #PERIOD_TICKS} ticks and ends when the predicate holds. */
    static final class WaitUntilTask extends Task {
        private final Seam seam;
        private final Signature sig;
        private final Map<String, Object> qargs;
        private final Predicate<Object> predicate;
        private final int timeoutTicks;
        private int ticks;
        private boolean pending;
        private boolean held;
        ActionError failure;

        WaitUntilTask(Seam seam, Signature sig, Map<String, Object> qargs, Predicate<Object> predicate, int timeoutTicks) {
            this.seam = seam;
            this.sig = sig;
            this.qargs = qargs;
            this.predicate = predicate;
            this.timeoutTicks = timeoutTicks;
        }

        @Override
        protected void onStart() {
            ticks = 0;
        }

        @Override
        protected Task onTick() {
            ticks++;
            if (!pending && (ticks == 1 || ticks % PERIOD_TICKS == 0)) {
                pending = true;
                seam.queries().submit(Queries.build(sig, qargs, seam.world()), this::answered);
            }
            if (ticks >= timeoutTicks && !held && failure == null) {
                failure = Calls.fail(FailureCode.TIMEOUT, "the condition on " + sig.name() + " did not hold within "
                        + timeoutTicks / 20 + " s");
            }
            return null;
        }

        private void answered(Outcome o) {
            pending = false;
            if (!o.ok()) {
                failure = o.error();
                return;
            }
            try {
                held = predicate.test(o.value());
            } catch (RuntimeException e) {
                failure = Calls.fail(FailureCode.BAD_ARGS, "the predicate failed: " + e.getMessage());
            }
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        public boolean isFinished() {
            return held || failure != null;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "Wait until " + sig.name();
        }
    }
}
