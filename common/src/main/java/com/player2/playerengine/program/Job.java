package com.player2.playerengine.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Outcome;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One program run as a job (§6.6): the interpreter, its calls log, the per-job caps, and the
 * lifecycle. Everything that must survive a restart is in {@link #toJson}; a restored job comes back
 * PAUSED and reconciles its calls against the world before it runs again (§6.4).
 */
public final class Job {
    public enum State { RUNNING, PAUSED, SHELVED, DONE, FAILED, CANCELLED }

    /** Why a job is PAUSED. */
    public enum Pause {
        NONE,
        /** Its owner, or a stop, paused it. */
        STOPPED,
        /** A foreign dispatch moved the seq (gateway.6 supersession). */
        SUPERSEDED,
        /** The server restarted; it resumes when the owner is online (R19). */
        RESTART,
        /** Reconcile cannot tell whether a call happened, and asks the initiator. */
        CONFIRM,
        /** An uncaught error: the program needs a repair before it can go on. */
        REPAIR
    }

    public static final int VERSION = 1;

    /**
     * Where the job may change the world (§4.2): a sphere around its initiator at creation, in plain
     * values so it persists with the job. Fixed for the job's life: resuming never moves it.
     */
    public record Region(String dimension, int x, int y, int z, int radius) {
    }

    final String id;
    final String goal;
    final String initiator;
    final Linter.Profile profile;
    private final ApiTable api;
    private Machine machine;
    private final CallLog log;
    private State state = State.RUNNING;
    private Pause pause = Pause.NONE;
    private ActionError lastError;
    private Object result;
    private String question;
    private long askSeq = -1;
    private long nextSeq = 1;
    private long pendingSeq = -1;
    private int waitTicks;
    private int primitiveCalls;
    private int cells;
    /** Done calls a restart rolled back, re-run before the program goes on. */
    private final Deque<Long> redo = new ArrayDeque<>();
    /** Set on restore: done calls are checked against the world on the next resume. */
    private boolean restored;
    /** Done calls reconcile already checked since the restore. */
    private final Set<Long> checked = new HashSet<>();
    private Region region;
    private transient ActionPort port;
    private transient Runnable onChange = () -> { };

    private Job(String id, String goal, String initiator, Linter.Profile profile, ApiTable api, Machine machine,
            CallLog log) {
        this.id = id;
        this.goal = goal;
        this.initiator = initiator;
        this.profile = profile;
        this.api = api;
        this.machine = machine;
        this.log = log;
    }

    /** A job for a program that linted clean. */
    public static Job start(String id, String goal, String initiator, Linter.Result lint, Linter.Profile profile,
            ApiTable api) {
        if (!lint.ok()) {
            throw new IllegalArgumentException("a job needs a program that lints clean: " + lint.repairMessage());
        }
        return new Job(id, goal, initiator, profile, api, new Machine(lint.program()), new CallLog());
    }

    /** Sets the region once, at creation; a second call is refused so a resume cannot re-centre it. */
    public Job region(Region r) {
        if (this.region != null) {
            throw new IllegalStateException("a job's region is fixed at creation");
        }
        this.region = r;
        return this;
    }

    /** The job's region, or null for a job that has none (outside the world, or saved before regions). */
    public Region region() {
        return region;
    }

    /** Binds the world this job acts on; required before {@link #tick} or {@link #resume}. */
    public Job bind(ActionPort port) {
        this.port = port;
        return this;
    }

    void onChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    public String id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public String initiator() {
        return initiator;
    }

    public State state() {
        return state;
    }

    public Pause pause() {
        return pause;
    }

    public ActionError lastError() {
        return lastError;
    }

    public Object result() {
        return result;
    }

    /** The reconcile question pending for the initiator, or null. */
    public String question() {
        return question;
    }

    public CallLog log() {
        return log;
    }

    public boolean ended() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    // --- running ----------------------------------------------------------------------------------

    /** One server tick: waits on the parked call, or runs up to k statements until one parks (§6.1). */
    public void tick() {
        if (state != State.RUNNING) {
            return;
        }
        if (pendingSeq >= 0) {
            CallLog.Entry e = log.get(pendingSeq);
            if (++waitTicks > port.callTicks(e.name(), e.args())) {
                port.abort(pendingSeq);
                fail(Fault.budget("per-call time", port.callTicks(e.name(), e.args()), progress()).error);
            }
            return;
        }
        if (!redo.isEmpty()) {
            CallLog.Entry original = log.get(redo.poll());
            original.rollBack();
            dispatch(original.name(), original.args(), original.seq());
            return;
        }
        Machine.Yield y = machine.run(Caps.STATEMENTS_PER_TICK);
        switch (y) {
            case Machine.Slice s -> {
            }
            case Machine.ApiCall c -> call(c);
            case Machine.Finished f -> {
                state = State.DONE;
                result = f.value();
                changed();
            }
            case Machine.Failed f -> failed(f.fault());
        }
    }

    private void failed(Fault fault) {
        if (fault.error.code() == FailureCode.BUDGET) {
            fail(fault.error);
            return;
        }
        // An uncaught error pauses the job for repair (§5.2).
        state = State.PAUSED;
        pause = Pause.REPAIR;
        lastError = fault.error;
        changed();
    }

    private void fail(ActionError error) {
        state = State.FAILED;
        lastError = error;
        changed();
    }

    private Map<String, Object> progress() {
        Map<String, Object> p = machine.progress();
        p.put("calls", primitiveCalls);
        p.put("cells", cells);
        return p;
    }

    private void call(Machine.ApiCall c) {
        ApiTable.Sig sig = api.get(c.name());
        if (sig == null) {
            machine.raise(Fault.bad(c.node(), "api." + c.name() + " does not exist"));
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < c.args().size() && i < sig.args().size(); i++) {
            args.put(sig.args().get(i).name(), Values.toHost(c.args().get(i)));
        }
        if (!sig.query() && primitiveCalls >= Caps.PRIMITIVE_CALLS) {
            fail(Fault.budget("primitive calls", Caps.PRIMITIVE_CALLS, progress()).error);
            return;
        }
        int size = machine.toJson().toString().getBytes(StandardCharsets.UTF_8).length;
        if (size > Caps.STATE_BYTES) {
            Map<String, Object> p = progress();
            p.put("bytes", size);
            fail(Fault.budget("serialised state", Caps.STATE_BYTES, p).error);
            return;
        }
        try {
            machine.checkLiveValues();
        } catch (Fault f) {
            fail(f.error);
            return;
        }
        ActionPort.Prepared prepared = port.prepare(c.name(), args);
        if (prepared.error() != null) {
            machine.raise(Fault.of(prepared.error()));
            changed();
            return;
        }
        if (!sig.query()) {
            primitiveCalls++;
        }
        dispatch(c.name(), prepared.args(), -1, prepared.coercions());
    }

    private void dispatch(String name, Map<String, Object> args, long redoOf) {
        dispatch(name, args, redoOf, List.of());
    }

    private void dispatch(String name, Map<String, Object> args, long redoOf, List<String> coercions) {
        ApiTable.Sig sig = api.get(name);
        long seq = nextSeq++;
        log.started(seq, name, sig != null && sig.query(), args, port.snapshot(name, args), coercions, redoOf);
        pendingSeq = seq;
        waitTicks = 0;
        // Write-ahead: the started entry is on disk before the world is touched.
        changed();
        port.begin(seq, name, args);
    }

    /**
     * The outcome of call {@code seq}. The seam calls it once per begun call, on the tick it began or a
     * later one; a stale seq (a call aborted by a pause) is ignored.
     *
     * @return whether the outcome was taken
     */
    public boolean deliver(long seq, Outcome outcome) {
        if (seq != pendingSeq) {
            return false;
        }
        CallLog.Entry e = log.get(seq);
        pendingSeq = -1;
        e.done(outcome, outcome.ok() && !e.query() ? port.snapshot(e.name(), e.args()) : null);
        if (outcome.ok()) {
            cells += port.cellsChanged(e, outcome);
        }
        if (cells > Caps.CELLS_CHANGED) {
            fail(Fault.budget("cells changed", Caps.CELLS_CHANGED, progress()).error);
            return true;
        }
        if (state != State.RUNNING) {
            // Delivered after a pause began: the log has it, and resume sees it as done.
            if (e.redoOf() < 0) {
                settlePending(e, outcome);
            }
            changed();
            return true;
        }
        if (e.redoOf() >= 0) {
            if (!outcome.ok()) {
                state = State.PAUSED;
                pause = Pause.REPAIR;
                lastError = outcome.error();
            }
            changed();
            return true;
        }
        settlePending(e, outcome);
        changed();
        return true;
    }

    private void settlePending(CallLog.Entry e, Outcome outcome) {
        if (outcome.ok()) {
            machine.deliver(Values.fromHost(outcome.value()));
        } else {
            machine.raise(Fault.of(outcome.error()));
        }
    }

    // --- lifecycle --------------------------------------------------------------------------------

    /** Pauses a running job. A call in flight is aborted and stays {@code started} for reconcile. */
    public void pause(Pause why) {
        if (state != State.RUNNING) {
            return;
        }
        if (pendingSeq >= 0) {
            port.abort(pendingSeq);
        }
        state = State.PAUSED;
        pause = why;
        changed();
    }

    /** Set aside by a newer job (R6). A running job pauses first. */
    void shelve() {
        if (state == State.RUNNING) {
            pause(Pause.SUPERSEDED);
        }
        if (state == State.PAUSED) {
            state = State.SHELVED;
            changed();
        }
    }

    public void cancel() {
        if (ended()) {
            return;
        }
        if (pendingSeq >= 0 && state == State.RUNNING) {
            port.abort(pendingSeq);
        }
        state = State.CANCELLED;
        changed();
    }

    /**
     * Runs a PAUSED or SHELVED job again, after reconciling its calls with the world. False when it
     * cannot run: it needs a repair, or reconcile asked the initiator a question ({@link #question}).
     */
    public boolean resume() {
        return resume(true);
    }

    /** {@code reconcile=false} re-runs every interrupted call blindly: the red witness for §6.4. */
    boolean resume(boolean reconcile) {
        if (state != State.PAUSED && state != State.SHELVED) {
            return false;
        }
        if (pause == Pause.REPAIR || pause == Pause.CONFIRM) {
            return false;
        }
        if (!reconcileCalls(reconcile)) {
            return false;
        }
        restored = false;
        checked.clear();
        state = State.RUNNING;
        pause = Pause.NONE;
        changed();
        return true;
    }

    /**
     * The initiator's answer to the reconcile question: {@code happened} settles the call as done,
     * otherwise it runs again. Then the job resumes.
     */
    public boolean answer(boolean happened) {
        if (pause != Pause.CONFIRM || askSeq < 0) {
            return false;
        }
        CallLog.Entry e = log.get(askSeq);
        apply(e, happened ? ActionPort.Reconcile.DONE : ActionPort.Reconcile.RERUN);
        askSeq = -1;
        question = null;
        pause = Pause.RESTART;
        return resume();
    }

    /** §6.4: every started-not-done call, and after a restart every done call the world no longer shows. */
    private boolean reconcileCalls(boolean reconcile) {
        for (CallLog.Entry e : new ArrayList<>(log.entries())) {
            boolean interrupted = e.status() == CallLog.Status.STARTED;
            boolean suspect = restored && e.ok() && !e.query() && !e.rolledBack() && e.reconciled() == null
                    && !checked.contains(e.seq()) && !port.stillShows(e);
            if (e.status() == CallLog.Status.DONE && !e.query()) {
                checked.add(e.seq());
            }
            if (!interrupted && !suspect) {
                continue;
            }
            ActionPort.Reconcile r;
            if (!reconcile || e.query() || idempotent(e)) {
                r = ActionPort.Reconcile.RERUN;
            } else {
                r = port.reconcile(e);
            }
            if (r == ActionPort.Reconcile.ASK) {
                askSeq = e.seq();
                question = "Did " + e.describe() + " finish before the restart? Answer yes or no.";
                state = State.PAUSED;
                pause = Pause.CONFIRM;
                changed();
                return false;
            }
            apply(e, r);
        }
        return true;
    }

    private boolean idempotent(CallLog.Entry e) {
        ApiTable.Sig sig = api.get(e.name());
        return sig != null && sig.idempotent();
    }

    private void apply(CallLog.Entry e, ActionPort.Reconcile r) {
        checked.add(e.seq());
        boolean interrupted = e.status() == CallLog.Status.STARTED;
        if (interrupted) {
            if (e.seq() == pendingSeq) {
                pendingSeq = -1;
            }
            if (r == ActionPort.Reconcile.DONE) {
                e.settle("the world shows it");
                if (e.redoOf() < 0) {
                    machine.deliver(null);
                }
            } else {
                e.abandon();
                if (e.redoOf() >= 0) {
                    redo.addFirst(e.redoOf());
                } else {
                    machine.retryPending();
                }
            }
        } else if (r != ActionPort.Reconcile.DONE) {
            redo.addLast(e.seq());
        }
    }

    // --- reporting --------------------------------------------------------------------------------

    /** The completion report (R5), from verified calls only. */
    public String report() {
        return CompletionReport.render(log);
    }

    /** The prompt tail's one line (§6.6): {@code job: <goal> | <state> | step k | last outcome}. */
    public String tailLine() {
        String last = "none";
        List<CallLog.Entry> entries = log.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            CallLog.Entry e = entries.get(i);
            if (e.status() == CallLog.Status.DONE) {
                last = e.name() + " " + (e.ok() ? "ok" : e.error().code().wire());
                break;
            }
        }
        String s = "job: " + goal + " | " + state.name().toLowerCase(java.util.Locale.ROOT)
                + (pause != Pause.NONE && state == State.PAUSED ? " (" + pause.name().toLowerCase(java.util.Locale.ROOT)
                + ")" : "") + " | step " + entries.size() + " | " + last;
        return s.length() <= 200 ? s : s.substring(0, 197) + "...";
    }

    private void changed() {
        onChange.run();
    }

    // --- persistence ------------------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("id", id);
        o.addProperty("goal", goal);
        o.addProperty("initiator", initiator);
        o.addProperty("profile", profile.name());
        o.addProperty("source", machine.program.source);
        o.addProperty("state", state.name());
        if (region != null) {
            JsonObject r = new JsonObject();
            r.addProperty("dimension", region.dimension());
            r.addProperty("x", region.x());
            r.addProperty("y", region.y());
            r.addProperty("z", region.z());
            r.addProperty("radius", region.radius());
            o.add("region", r);
        }
        o.addProperty("pause", pause.name());
        if (lastError != null) {
            o.add("lastError", CallLog.errorJson(lastError));
        }
        if (question != null) {
            o.addProperty("question", question);
            o.addProperty("askSeq", askSeq);
        }
        o.addProperty("nextSeq", nextSeq);
        o.addProperty("pendingSeq", pendingSeq);
        o.addProperty("primitiveCalls", primitiveCalls);
        o.addProperty("cells", cells);
        JsonArray r = new JsonArray();
        redo.forEach(r::add);
        o.add("redo", r);
        o.add("machine", machine.toJson());
        o.add("log", log.toJson());
        return o;
    }

    /**
     * A job from {@link #toJson}, as a restart finds it: a running or paused job comes back PAUSED
     * (restart), a shelved one stays shelved, and its calls are checked on the next resume.
     */
    public static Job fromJson(JsonObject o, ApiTable api) {
        if (o.get("version").getAsInt() != VERSION) {
            throw new IllegalArgumentException("job version " + o.get("version"));
        }
        Linter.Profile profile = Linter.Profile.valueOf(o.get("profile").getAsString());
        Program program = Parser.parse(o.get("source").getAsString());
        Job j = new Job(o.get("id").getAsString(), o.get("goal").getAsString(), o.get("initiator").getAsString(),
                profile, api, Machine.fromJson(program, o.getAsJsonObject("machine")),
                CallLog.fromJson(o.getAsJsonArray("log")));
        j.state = State.valueOf(o.get("state").getAsString());
        if (o.has("region")) {
            JsonObject r = o.getAsJsonObject("region");
            j.region = new Region(r.get("dimension").getAsString(), r.get("x").getAsInt(), r.get("y").getAsInt(),
                    r.get("z").getAsInt(), r.get("radius").getAsInt());
        }
        j.pause = Pause.valueOf(o.get("pause").getAsString());
        j.lastError = o.has("lastError") ? CallLog.errorFromJson(o.getAsJsonObject("lastError")) : null;
        if (o.has("question")) {
            j.question = o.get("question").getAsString();
            j.askSeq = o.get("askSeq").getAsLong();
        }
        j.nextSeq = o.get("nextSeq").getAsLong();
        j.pendingSeq = o.get("pendingSeq").getAsLong();
        j.primitiveCalls = o.get("primitiveCalls").getAsInt();
        j.cells = o.get("cells").getAsInt();
        o.getAsJsonArray("redo").forEach(x -> j.redo.add(x.getAsLong()));
        j.restored = true;
        if (j.state == State.RUNNING || (j.state == State.PAUSED && j.pause != Pause.REPAIR
                && j.pause != Pause.CONFIRM)) {
            j.state = State.PAUSED;
            j.pause = Pause.RESTART;
        }
        return j;
    }
}
