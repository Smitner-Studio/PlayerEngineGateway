package com.player2.playerengine.executor;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TrackedTaskOutcomeProvider;
import com.player2.playerengine.util.Debug;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal implementation of IStepExecutorAdapter wired to UserTaskChain.
 *
 * Lifecycle per submitted step:
 *   1. PENDING  — execution created; preconditions checked.
 *   2. RUNNING  — task submitted to UserTaskChain via runTask().
 *   3. Terminal — determined inside the onFinish callback:
 *        task.isFinished() == true  -> SUCCEEDED
 *        task.isFinished() == false -> FAILED (stopped/cancelled/errored)
 *        FAILED + non-NONE policy   -> ROLLED_BACK (stub in Phase A2)
 *
 * Why task.isFinished() works without modifying UserTaskChain:
 *   SingleTaskChain.onTick() reaches onTaskFinish() via two paths —
 *   the normal-finish path (isFinished() was true before stop) and the
 *   forced-stop path (stopped() == true, isFinished() still false).
 *   The captured task reference retains its isFinished() value after stop(),
 *   so the onFinish callback can distinguish success from failure.
 *
 * onComplete is always called at terminal state so CommandExecutor's
 * sequential chain callbacks are never silently dropped.
 *
 * Phase A2 rollback: logs the policy and transitions to ROLLED_BACK.
 * Actual compensating tasks are deferred to Phase A3+.
 */
public class TaskStepExecutorAdapter implements IStepExecutorAdapter {

    private final PlayerEngineController mod;
    private StepExecution activeExecution;
    /** Exact task paired with {@link #activeExecution}; used to preserve an unreaped terminal. */
    private Task activeTask;
    /** Most recently completed (terminal) execution; never null after the first step finishes. */
    private StepExecution lastCompletedExecution;
    /** Consumed when the UserTaskChain onFinish callback runs after a cancel/stop. */
    private volatile String pendingFinishReason;
    private final CompletionTracker completionTracker = new CompletionTracker();

    public TaskStepExecutorAdapter(PlayerEngineController mod) {
        this.mod = mod;
    }

    /**
     * Arms {@link #pendingFinishReason} when a tracked step is RUNNING and the user task chain
     * still holds an active task — used by {@link PlayerEngineController#stop(StopReason)} so
     * {@code stop()} / disconnect produce deterministic {@link StopReason} labels.
     *
     * <p>First-writer-wins: if a reason has already been armed (e.g. an operator
     * {@code stop(CANCELLED_OPERATOR)} that races a follow self-stop), it is kept and not
     * overwritten, so the originally-intended label is recorded rather than the later one.
     */
    public void armPendingChainCancel(StopReason reason) {
        if (reason == null) {
            return;
        }
        if (this.pendingFinishReason != null) {
            return;
        }
        if (activeExecution != null && activeExecution.getState() == StepState.RUNNING
                && mod.getUserTaskChain().isActive()) {
            this.pendingFinishReason = reason.name();
        }
    }

    @Override
    public StepExecution submit(String stepId, String stepKind,
                                Task task, RollbackPolicy rollbackPolicy,
                                Runnable onComplete) {
        Runnable completion = once(onComplete);
        // UserTaskChain drains a superseded task's callback after the authoritative replacement is
        // installed. A stale callback can synchronously submit the old command's next tracked task.
        // Reject it before touching activeExecution/completionTracker/pendingFinishReason; otherwise
        // that stale submission terminalizes the authoritative replacement even though the chain
        // correctly refuses to install it.
        if (rejectsReentrantTrackedSubmission()) {
            StepExecution rejected = sealStaleReentrantSubmission(
                    stepId, stepKind, task, mod);
            this.lastCompletedExecution = rejected;
            completion.run();
            return rejected;
        }
        // A task synchronously emitted by the configured idle command is policy work. If real user
        // ownership already exists, reject the incoming idle step before touching A's execution,
        // completion token, pending finish reason, or task reference.
        if (rejectsConfiguredIdleTrackedSubmission()) {
            StepExecution rejected = sealConfiguredIdleTrackedSubmission(
                    stepId, stepKind, task, mod);
            this.lastCompletedExecution = rejected;
            completion.run();
            return rejected;
        }

        pendingFinishReason = null;
        Runnable supersededCompletion = null;

        if (activeExecution != null && !activeExecution.isTerminal()) {
            Optional<TerminalDecision> unreapedTerminal =
                    terminalDecisionBeforeReplacement(this.activeTask);
            if (unreapedTerminal.isPresent()) {
                TerminalDecision decision = unreapedTerminal.get();
                Debug.logInternal(
                    "[StepExecutorAdapter] Preserving terminal outcome for unreaped execution "
                    + activeExecution.getStepId() + " before installing step " + stepId + "."
                );
                activeExecution.transitionTo(decision.state(), decision.detail());
            } else {
                Debug.logInternal(
                    "[StepExecutorAdapter] Overwriting non-terminal execution "
                    + activeExecution.getStepId() + " (state=" + activeExecution.getState()
                    + ") with new step " + stepId + ". Marking previous as FAILED."
                );
                activeExecution.transitionTo(StepState.FAILED,
                        StopReason.CANCELLED_SUPERSEDED.name() + ":superseded_by_step=" + stepId);
            }
            this.lastCompletedExecution = activeExecution;
            supersededCompletion = completionTracker.capture(activeExecution);
        }

        StepExecution exec = new StepExecution(stepId, stepKind);
        this.activeExecution = exec;
        this.activeTask = task;
        this.completionTracker.install(exec, completion);

        if (!PlayerEngineController.inGame()) {
            exec.transitionTo(StepState.BLOCKED,
                    StopReason.USER_ACTION_REQUIRED.name() + ":not_in_game");
            this.lastCompletedExecution = exec;
            if (supersededCompletion != null) {
                supersededCompletion.run();
            }
            finishCompletion(exec, completion);
            return exec;
        }

        exec.transitionTo(StepState.RUNNING, "submitted_to_chain");

        mod.getUserTaskChain().runTaskReplacing(mod, task, () -> {
            if (exec.isTerminal()) {
                finishCompletion(exec, completion);
                return;
            }
            String pending = pendingFinishReason;
            pendingFinishReason = null;
            if (pending != null) {
                exec.transitionTo(StepState.FAILED, pending);
            } else {
                TerminalDecision decision = mapTerminalOutcome(task);
                exec.transitionTo(decision.state(), decision.detail());
                if (decision.state() == StepState.FAILED && rollbackPolicy != RollbackPolicy.NONE) {
                    handleRollbackStub(exec, rollbackPolicy);
                }
            }
            this.lastCompletedExecution = exec;
            boolean typedTerminalFailure = task instanceof TrackedTaskOutcomeProvider typed
                    && typed.isTerminal() && !typed.isSuccessful();
            if (exec.getState() == StepState.FAILED && !typedTerminalFailure) {
                String lastEntry = exec.getLastLogEntry();
                if (lastEntry.contains(StopReason.FOLLOWED_TARGET_GONE.name())) {
                    // Expected, graceful termination of a follow: the followed player died/left.
                    // Never leak the raw state-machine string to the player \u2014 send a concise human line.
                    Player owner = mod.getOwner();
                    if (owner instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(Component.translatable(
                                "message.playerengine.follow.target_lost"));
                    }
                } else if (!lastEntry.contains("CANCELLED_")) {
                    String msg = "[PlayerEngine] Step '" + exec.getStepKind()
                            + "' stopped unexpectedly \u2014 " + lastEntry;
                    mod.log(msg);
                    Player owner = mod.getOwner();
                    if (owner instanceof ServerPlayer sp
                            && com.player2.playerengine.companion.CompanionRules.get().progressChat().shows(true)) {
                        sp.sendSystemMessage(
                                Component.translatable("message.playerengine.executor.step_stopped_unexpectedly",
                                        exec.getStepKind(), lastEntry)
                                        .withStyle(ChatFormatting.RED));
                    }
                }
            }
            finishCompletion(exec, completion);
        });
        Task retainedByChain = mod.getUserTaskChain().getCurrentTask();
        RetentionDecision retention = retentionDecision(task, retainedByChain, exec.isTerminal());
        if (retention == RetentionDecision.DISPLACED) {
            exec.transitionTo(StepState.FAILED,
                    StopReason.CANCELLED_SUPERSEDED.name() + ":displaced_during_install");
            this.lastCompletedExecution = exec;
            finishCompletion(exec, completion);
        }
        // UserTaskChain normally fires the replaced task's terminal after installing this task.
        // The fallback covers an inconsistent/inactive chain; the once wrapper prevents duplicates.
        if (supersededCompletion != null) {
            supersededCompletion.run();
        }

        return exec;
    }

    /**
     * Seals a stale callback submission without mutating the adapter's authoritative active slot.
     * Package-visible for the detached A -> B / stale-C ownership regression test.
     */
    static StepExecution sealStaleReentrantSubmission(
            String stepId,
            String stepKind,
            Task task,
            PlayerEngineController mod) {
        Task checked = java.util.Objects.requireNonNull(task, "task");
        checked.controller = mod;
        checked.reset();
        checked.stop(null);
        StepExecution rejected = new StepExecution(stepId, stepKind);
        rejected.transitionTo(StepState.BLOCKED,
                StopReason.CANCELLED_SUPERSEDED.name() + ":stale_reentrant_submission");
        return rejected;
    }

    private static StepExecution sealConfiguredIdleTrackedSubmission(
            String stepId,
            String stepKind,
            Task task,
            PlayerEngineController mod) {
        Task checked = java.util.Objects.requireNonNull(task, "task");
        checked.controller = mod;
        checked.reset();
        checked.stop(null);
        StepExecution rejected = new StepExecution(stepId, stepKind);
        rejected.transitionTo(StepState.BLOCKED,
                StopReason.CANCELLED_SUPERSEDED.name()
                        + ":configured_idle_preserved_active_task");
        return rejected;
    }

    /** Overridable package seam keeps the real submit preflight deterministic in detached tests. */
    boolean rejectsReentrantTrackedSubmission() {
        return mod.getUserTaskChain().rejectsReentrantTrackedSubmission();
    }

    /** Overridable package seam; production delegates through the controller to UserTaskChain. */
    boolean rejectsConfiguredIdleTrackedSubmission() {
        return mod.rejectsConfiguredIdleTrackedSubmission();
    }

    @Override
    public void cancel(String reason) {
        if (activeExecution == null || activeExecution.getState() != StepState.RUNNING) {
            return;
        }
        String detail = (reason == null || reason.isBlank()) ? "" : ":" + reason;
        pendingFinishReason = StopReason.CANCELLED_OPERATOR.name() + detail;
        // userTaskChain.cancel() calls onTaskFinish() synchronously, which fires
        // the wrapped onFinish callback above, which transitions RUNNING -> FAILED
        // and invokes onComplete.
        mod.getUserTaskChain().cancel(mod);
    }

    @Override
    public Optional<StepExecution> getActiveExecution() {
        return Optional.ofNullable(activeExecution);
    }

    /** Seeds one running ownership slot for the detached real-submit regression test. */
    void seedActiveExecutionForSelfTest(
            StepExecution execution,
            Task task,
            Runnable completion) {
        if (execution == null || execution.getState() != StepState.RUNNING) {
            throw new IllegalArgumentException("self-test active execution must be RUNNING");
        }
        this.activeExecution = execution;
        this.activeTask = java.util.Objects.requireNonNull(task, "task");
        this.completionTracker.install(execution, once(completion));
    }

    boolean ownsActiveSlotForSelfTest(StepExecution execution, Task task) {
        return this.activeExecution == execution
                && this.activeTask == task
                && this.completionTracker.isActive(execution);
    }

    void finishActiveCompletionForSelfTest(StepExecution execution) {
        Runnable completion = this.completionTracker.capture(execution);
        if (completion == null) {
            throw new IllegalStateException("self-test execution does not own a completion");
        }
        this.completionTracker.finish(execution, completion);
    }

    /** Running-only view for controller status/diagnostics; the broader adapter API stays unchanged. */
    public Optional<StepExecution> getRunningExecution() {
        return runningExecution(activeExecution);
    }

    /** Package-visible pure lifecycle filter exercised by {@link TaskStepExecutorAdapterSelfTest}. */
    static Optional<StepExecution> runningExecution(StepExecution execution) {
        return execution != null && execution.getState() == StepState.RUNNING
                ? Optional.of(execution)
                : Optional.empty();
    }

    /**
     * Returns the most recently completed (terminal) execution, or empty if no step has
     * reached a terminal state yet. Unlike {@link #getActiveExecution()}, this always
     * refers to a finished step even when a new step is currently running.
     */
    public Optional<StepExecution> getLastCompletedExecution() {
        return Optional.ofNullable(lastCompletedExecution);
    }

    /** Package-visible pure terminal mapper exercised by {@link TaskStepExecutorAdapterSelfTest}. */
    static TerminalDecision mapTerminalOutcome(Task task) {
        if (task instanceof TrackedTaskOutcomeProvider outcome) {
            if (!outcome.isTerminal()) {
                return new TerminalDecision(StepState.FAILED,
                        StopReason.FATAL.name() + ":task_stopped_before_terminal_outcome");
            }
            if (outcome.isSuccessful()) {
                return new TerminalDecision(StepState.SUCCEEDED, "task_finished_successfully");
            }
            return new TerminalDecision(StepState.FAILED,
                    StopReason.FATAL.name() + ":" + boundedReason(outcome.controlledReason()));
        }
        if (task.isFinished()) {
            return new TerminalDecision(StepState.SUCCEEDED, "task_finished_normally");
        }
        return new TerminalDecision(StepState.FAILED,
                StopReason.FATAL.name() + ":task_stopped_without_finish");
    }

    /** Detects a typed or framework terminal that completed before UserTaskChain's reap callback. */
    static Optional<TerminalDecision> terminalDecisionBeforeReplacement(Task task) {
        if (task instanceof TrackedTaskOutcomeProvider outcome && outcome.isTerminal()) {
            return Optional.of(mapTerminalOutcome(task));
        }
        return task != null && task.isFinished()
                ? Optional.of(mapTerminalOutcome(task)) : Optional.empty();
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "internal_contract_failure";
        }
        String singleLine = reason.strip().replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160);
    }

    /** Package-visible once wrapper exercised by the deterministic adapter self-test. */
    static Runnable once(Runnable delegate) {
        Runnable checked = java.util.Objects.requireNonNull(delegate, "delegate");
        AtomicBoolean fired = new AtomicBoolean();
        return () -> {
            if (fired.compareAndSet(false, true)) {
                checked.run();
            }
        };
    }

    enum RetentionDecision {
        SUBMITTED_INSTALLED,
        DISPLACED,
        EXECUTION_TERMINAL
    }

    static RetentionDecision retentionDecision(
            Task submitted,
            Task retainedByChain,
            boolean executionTerminal) {
        Task checked = java.util.Objects.requireNonNull(submitted, "submitted");
        if (executionTerminal) {
            return RetentionDecision.EXECUTION_TERMINAL;
        }
        return retainedByChain == checked
                ? RetentionDecision.SUBMITTED_INSTALLED
                : RetentionDecision.DISPLACED;
    }

    private void finishCompletion(StepExecution exec, Runnable completion) {
        completionTracker.finish(exec, completion);
    }

    /** Package-visible pure completion slot exercised across replacement paths by the self-test. */
    static final class CompletionTracker {
        private Object activeToken;
        private Runnable activeCompletion;

        void install(Object token, Runnable completion) {
            activeToken = java.util.Objects.requireNonNull(token, "token");
            activeCompletion = java.util.Objects.requireNonNull(completion, "completion");
        }

        Runnable capture(Object token) {
            return activeToken == token ? activeCompletion : null;
        }

        void finish(Object token, Runnable completion) {
            if (activeToken == token) {
                activeToken = null;
                activeCompletion = null;
            }
            java.util.Objects.requireNonNull(completion, "completion").run();
        }

        boolean isActive(Object token) {
            return activeToken == token && activeCompletion != null;
        }
    }

    record TerminalDecision(StepState state, String detail) {
    }

    private void handleRollbackStub(StepExecution exec, RollbackPolicy policy) {
        Debug.logInternal(
            "[StepExecutorAdapter] Rollback stub: policy=" + policy
            + " for step " + exec.getStepId() + " [" + exec.getStepKind()
            + "]. Full rollback deferred to Phase A3+."
        );
        exec.transitionTo(StepState.ROLLED_BACK,
                StopReason.COMPENSATABLE.name() + ":rollback_stub_" + policy.name().toLowerCase());
    }
}
