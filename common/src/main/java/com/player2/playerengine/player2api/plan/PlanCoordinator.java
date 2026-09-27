package com.player2.playerengine.player2api.plan;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs one companion's {@link CompanionPlan}: dispatches steps without a model call between them,
 * asks the model to repair a failed step, pauses when the owner's own command supersedes a step, and
 * resumes on request. Every mutation happens under this object's monitor; step callbacks carry the
 * plan generation and a dispatch token, so a callback from an older plan or dispatch is dropped.
 *
 * <p>No Minecraft types: the loop supplies a {@link Host}, and the self-test supplies a mock one.
 */
public final class PlanCoordinator {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final int MAX_REPAIRS = 2;
    public static final int MAX_CALLS_PER_PLAN = 8;
    /** Area steps cap their own estimate at 20 minutes; this is the backstop for every step. */
    public static final long STEP_TIMEOUT_MILLIS = 25L * 60L * 1000L;
    public static final long IDLE_TIMEOUT_MILLIS = 60L * 60L * 1000L;
    private static final int MAX_CONSECUTIVE_REFUSALS = 2;

    public enum StopKind { FINISHED, ERROR, CANCELLED }

    public interface StepListener {
        /** The step's command line parsed and is about to run; {@code seq} is the controller's dispatch seq. */
        void accepted(long seq);

        void stopped(StopKind kind, String detail);
    }

    public interface Host {
        /** Runs one command line on the server thread, reporting through {@code listener}. */
        void dispatch(String line, StepListener listener);

        /** The controller's command dispatch seq: incremented by every command line executed. */
        long currentSeq();

        /** Queues an InfoMessage that starts a model turn. */
        void enqueueModelTurn(String info);

        void cancelRunningTask();

        void persist(JsonObject planOrNull);

        boolean isIdempotent(String line);

        void publishStatus(String line);

        long now();
    }

    /** Who a decision turn belongs to. {@code ownerTurn} is decided by authenticated UUID only. */
    public record Turn(boolean userTurn, boolean ownerTurn, UUID initiator) {
    }

    private final Host host;
    private final PlanBudget budget;
    private CompanionPlan plan;
    private long nextGeneration = 1;
    private long nextToken = 1;
    private boolean resumeRequested;
    private int consecutiveRefusals;

    public PlanCoordinator(Host host, PlanBudget budget) {
        this.host = host;
        this.budget = budget;
    }

    public synchronized boolean hasPlan() {
        return plan != null;
    }

    public synchronized CompanionPlan.Status status() {
        return plan == null ? null : plan.status;
    }

    public synchronized CompanionPlan snapshotForTest() {
        return plan;
    }

    /** Called for each owner or non-owner chat line before the model turn it starts. */
    public synchronized void onUserMessage(String text, boolean fromOwner) {
        if (fromOwner && plan != null && plan.status != CompanionPlan.Status.RUNNING
                && PlanParser.isResumePhrase(text)) {
            resumeRequested = true;
        }
    }

    /**
     * Applies a decision reply's {@code plan} field.
     *
     * @return true when the plan dispatched a step, so the reply's own {@code command} must not run
     */
    public synchronized boolean onModelDecision(PlanParser.Result result, String command, Turn turn) {
        long now = host.now();
        boolean wantsResume = resumeRequested;
        resumeRequested = false;
        boolean chargedThisTurn = false;
        if (plan != null && turn.userTurn() && turn.ownerTurn()) {
            plan.lastActivityMillis = now;
            if (!charge(now)) {
                failForBudget();
                return false;
            }
            chargedThisTurn = true;
        }
        boolean commandBlank = command == null || command.isBlank() || command.trim().equalsIgnoreCase("idle");

        if (result instanceof PlanParser.Absent) {
            if (plan != null && plan.status == CompanionPlan.Status.AWAITING_REPAIR && !turn.userTurn()) {
                // The model answered the repair prompt without a plan: keep the work, but idle.
                plan.status = CompanionPlan.Status.PAUSED;
                save();
            }
            if (plan != null && plan.status == CompanionPlan.Status.PAUSED && wantsResume && commandBlank) {
                return resume(now);
            }
            return false;
        }
        // A repair prompt is the plan's own feedback turn; the owner authorised the plan it repairs.
        boolean authorised = turn.ownerTurn()
                || (!turn.userTurn() && plan != null && plan.status == CompanionPlan.Status.AWAITING_REPAIR);
        if (!authorised) {
            host.enqueueModelTurn("Only your owner can hand you a long job or change one, so nothing was "
                    + "started. Decline politely in one short line.");
            return false;
        }
        if (result instanceof PlanParser.Invalid invalid) {
            if (consecutiveRefusals >= MAX_CONSECUTIVE_REFUSALS) {
                LOGGER.warn("[Plan] dropping invalid plan without feedback (refusal limit): {}", invalid.reason());
                return false;
            }
            consecutiveRefusals++;
            host.enqueueModelTurn("Your plan was not started: " + invalid.reason() + ". Send a corrected "
                    + "`plan` (one command per step, at most " + PlanParser.MAX_STEPS
                    + " steps, only your commands), or answer without one.");
            return false;
        }
        if (result instanceof PlanParser.Cancel) {
            if (plan != null) {
                LOGGER.info("[Plan] cancelled by the model: {}", plan.goal);
                clear();
            }
            return false;
        }
        if (result instanceof PlanParser.Resume) {
            if (plan != null && plan.status != CompanionPlan.Status.RUNNING) {
                return resume(now);
            }
            return false;
        }
        PlanParser.NewPlan np = (PlanParser.NewPlan) result;
        boolean repair = plan != null && plan.status == CompanionPlan.Status.AWAITING_REPAIR && !turn.userTurn();
        UUID initiator = repair ? plan.initiator : turn.initiator();
        CompanionPlan fresh = new CompanionPlan(nextGeneration++, repair ? plan.goal : np.goal(), np.steps(),
                initiator, now);
        if (repair) {
            fresh.repairsUsed = plan.repairsUsed;
            fresh.callsCharged = plan.callsCharged;
            fresh.priorResults = plan.doneSummary();
        } else if (chargedThisTurn) {
            fresh.callsCharged = 1;
        } else {
            if (!budget.tryCharge(initiator, now)) {
                host.enqueueModelTurn("That job was not started: your owner has used this hour's allowance "
                        + "for long jobs. Say so in one short line and offer a single task instead.");
                return false;
            }
            fresh.callsCharged = 1;
        }
        LOGGER.info("[Plan] {} plan gen={} goal=\"{}\" steps={}", repair ? "repaired" : "new",
                fresh.generation, fresh.goal, fresh.steps);
        plan = fresh;
        consecutiveRefusals = 0;
        dispatchCurrent(now);
        return true;
    }

    /** The owner or the model said stop: the plan ends. The running task is stopped by the caller. */
    public synchronized void cancel(String why) {
        if (plan != null) {
            LOGGER.info("[Plan] cleared ({}): {}", why, plan.goal);
            clear();
        }
    }

    public synchronized void tick() {
        if (plan == null) {
            return;
        }
        long now = host.now();
        if (plan.stepRunning() && now - plan.stepDispatchedMillis > STEP_TIMEOUT_MILLIS) {
            long gen = plan.generation;
            long token = plan.stepToken;
            // Resolve the step first, so the stop callback that cancelling fires is stale and dropped.
            plan.stepSeq = -1;
            onStepStopped(gen, token, StopKind.ERROR, "took too long and was stopped");
            host.cancelRunningTask();
            return;
        }
        if (!plan.stepRunning() && now - plan.lastActivityMillis > IDLE_TIMEOUT_MILLIS) {
            LOGGER.info("[Plan] expired after an idle hour: {}", plan.goal);
            clear();
        }
    }

    /** Adopts a plan read from disk. It arrives PAUSED and never resumes on its own. */
    public synchronized void restore(CompanionPlan saved) {
        if (plan != null || saved == null) {
            return;
        }
        plan = saved;
        nextGeneration = Math.max(nextGeneration, saved.generation + 1);
        host.publishStatus(statusLine());
    }

    public synchronized String statusLine() {
        if (plan == null) {
            return "";
        }
        String state = switch (plan.status) {
            case RUNNING -> "running";
            case PAUSED -> "paused; resumes when the owner says continue";
            case AWAITING_REPAIR -> "failed; waiting for your revised plan";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("goal: ").append(plan.goal)
                .append(" | step ").append(plan.cursor + 1).append('/').append(plan.steps.size())
                .append(' ').append(state).append(": ").append(plan.currentStep());
        if (plan.cursor > 0) {
            sb.append(" | done: ").append(plan.cursor);
        }
        if (plan.cursor + 1 < plan.steps.size()) {
            sb.append(" | next: ").append(plan.steps.get(plan.cursor + 1));
        }
        return CompanionPlan.cap(sb.toString(), 400);
    }

    private boolean resume(long now) {
        if (plan.cursorOutcomeUnknown && !host.isIdempotent(plan.currentStep())) {
            if (!charge(now)) {
                failForBudget();
                return false;
            }
            plan.status = CompanionPlan.Status.AWAITING_REPAIR;
            plan.cursorOutcomeUnknown = false;
            save();
            host.enqueueModelTurn("The job \"" + plan.goal + "\" was interrupted by a restart during step "
                    + (plan.cursor + 1) + " `" + plan.currentStep() + "`, which may or may not have finished. "
                    + "Check your status and send a `plan` with what is left, or `\"plan\": \"cancel\"`.");
            return false;
        }
        plan.cursorOutcomeUnknown = false;
        LOGGER.info("[Plan] resuming \"{}\" at step {}", plan.goal, plan.cursor + 1);
        dispatchCurrent(now);
        return true;
    }

    private void dispatchCurrent(long now) {
        long token = nextToken++;
        long gen = plan.generation;
        plan.stepToken = token;
        plan.stepSeq = -1;
        plan.stepDispatchedMillis = now;
        plan.lastActivityMillis = now;
        plan.status = CompanionPlan.Status.RUNNING;
        save();
        String line = plan.currentStep();
        LOGGER.info("[Plan] dispatch gen={} step {}/{}: {}", gen, plan.cursor + 1, plan.steps.size(), line);
        host.dispatch(line, new StepListener() {
            @Override
            public void accepted(long seq) {
                synchronized (PlanCoordinator.this) {
                    if (plan != null && plan.generation == gen && plan.stepToken == token) {
                        plan.stepSeq = seq;
                    }
                }
            }

            @Override
            public void stopped(StopKind kind, String detail) {
                onStepStopped(gen, token, kind, detail);
            }
        });
    }

    synchronized void onStepStopped(long gen, long token, StopKind kind, String detail) {
        if (plan == null || plan.generation != gen || plan.stepToken != token) {
            LOGGER.info("[Plan] dropped stale step stop gen={} token={} kind={}", gen, token, kind);
            return;
        }
        long now = host.now();
        plan.stepToken = 0;
        plan.lastActivityMillis = now;
        // A step replaced by another command still reports Finished (UserTaskChain ACTIVE-REPLACE
        // fires the replaced terminal). Any dispatch after ours means the owner redirected the
        // companion: pause, never advance.
        if (plan.stepSeq >= 0 && host.currentSeq() != plan.stepSeq) {
            plan.results.set(plan.cursor, "superseded");
            plan.status = CompanionPlan.Status.PAUSED;
            LOGGER.info("[Plan] step {} superseded by another command; plan paused", plan.cursor + 1);
            save();
            return;
        }
        switch (kind) {
            case CANCELLED -> {
                plan.results.set(plan.cursor, "cancelled");
                plan.status = CompanionPlan.Status.PAUSED;
                save();
            }
            case FINISHED -> {
                plan.results.set(plan.cursor, detail == null || detail.isBlank()
                        ? "ok" : "ok (" + CompanionPlan.cap(detail, 120) + ")");
                plan.cursor++;
                if (plan.cursor >= plan.steps.size()) {
                    CompanionPlan done = plan;
                    String summary = done.doneSummary();
                    clear();
                    budget.tryCharge(done.initiator, now);
                    host.enqueueModelTurn("Plan \"" + done.goal + "\" is finished: " + summary + ". If the "
                            + "owner is waiting, tell them in one short line; otherwise say nothing. "
                            + "Leave command empty.");
                } else {
                    dispatchCurrent(now);
                }
            }
            case ERROR -> {
                String reason = CompanionPlan.cap(detail == null || detail.isBlank() ? "no reason given" : detail, 160);
                plan.results.set(plan.cursor, "failed: " + reason);
                int stepNo = plan.cursor + 1;
                String step = plan.currentStep();
                if (plan.repairsUsed < MAX_REPAIRS && charge(now)) {
                    plan.repairsUsed++;
                    plan.status = CompanionPlan.Status.AWAITING_REPAIR;
                    save();
                    host.enqueueModelTurn("Plan \"" + plan.goal + "\" step " + stepNo + "/" + plan.steps.size()
                            + " `" + step + "` failed: " + reason + ". Done: " + plan.doneSummary()
                            + ". Remaining: " + plan.remainingSummary(plan.cursor) + ". Reply with a `plan` of "
                            + "revised steps for the remaining work (you may put a fixing step first), or "
                            + "`\"plan\": \"cancel\"` and tell the owner in one short line what you need.");
                } else {
                    String goal = plan.goal;
                    int total = plan.steps.size();
                    clear();
                    host.enqueueModelTurn("Plan \"" + goal + "\" stopped at step " + stepNo + "/" + total
                            + " `" + step + "`: " + reason + ". Tell the owner in one short line what you "
                            + "need. Leave command empty.");
                }
            }
        }
    }

    private boolean charge(long now) {
        if (plan.callsCharged >= MAX_CALLS_PER_PLAN || !budget.tryCharge(plan.initiator, now)) {
            return false;
        }
        plan.callsCharged++;
        return true;
    }

    private void failForBudget() {
        String goal = plan.goal;
        clear();
        host.enqueueModelTurn("Plan \"" + goal + "\" was dropped: it used up its allowance of model calls. "
                + "Tell the owner in one short line and offer to take the next part as a single task.");
    }

    private void save() {
        host.persist(plan == null ? null : plan.toJson());
        host.publishStatus(statusLine());
    }

    private void clear() {
        plan = null;
        resumeRequested = false;
        host.persist(null);
        host.publishStatus("");
    }
}
