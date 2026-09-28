package com.player2.playerengine.chains;

import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.executor.TaskStepExecutorAdapterSelfTest;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskChain;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.tasks.base.TaskSuspensionCause;
import com.player2.playerengine.tasks.base.TaskRunnerSelfTest;
import com.player2.playerengine.tasks.base.TransientlyResumableTask;
import com.player2.playerengine.tasks.LookAtOwnerTask;
import com.player2.playerengine.tasks.movement.BodyLanguageTask;
import com.player2.playerengine.tasks.movement.FollowPlayerTask;
import com.player2.playerengine.tasks.movement.IdleTask;

import java.util.concurrent.atomic.AtomicInteger;

/** Controller-free ownership checks for user-task overlays, replacement, and priority resume. */
public final class UserTaskChainSelfTest {
    private UserTaskChainSelfTest() {
    }

    public static void runAll() {
        preTickCancelResolvesAndClearsAssignment();
        preTickReplacementResolvesOldAndRetainsNew();
        gestureBeforeFirstTickPreservesRoot();
        repeatedSameGesturePreservesSuspendedRoot();
        followDuringGestureAbandonsSuspendedRoot();
        replacementCallbackReentryRetainsNewestSubmission();
        policyIdleInstalledByGestureCallbackLosesToSuspendedRoot();
        configuredIdleFromGestureCompletionPreservesTrackedResume();
        higherPriorityChainRestartsSameRoot();
        idlePolicyInstallIsAtomic();
        policyIdleCannotReplaceLiveRoot();
        policyIdleCannotReplaceTerminalAwaitingReap();
        policyIdleReplacementKeepsTaskCallbackAssociation();
        clearingPolicyIdleNeverTouchesRealWork();
        UnstuckChainSelfTest.runAll();
        TaskRunnerSelfTest.runAll();
    }

    private static void preTickCancelResolvesAndClearsAssignment() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        AtomicInteger callbacks = new AtomicInteger();
        chain.runTaskReplacing(null, root, callbacks::incrementAndGet);
        require(root.isAssigned() && chain.getCurrentTask() == root,
                "fresh submission is owned before its first tick");
        chain.cancel(null);
        require(chain.getCurrentTask() == null && root.stopped(),
                "pre-tick cancel clears and seals the assignment");
        require(root.abandonCalls == 1 && callbacks.get() == 1,
                "pre-tick cancel terminalizes and resolves exactly once");
    }

    private static void preTickReplacementResolvesOldAndRetainsNew() {
        UserTaskChain chain = chain();
        FakeResumableTask old = new FakeResumableTask();
        FakeResumableTask replacement = new FakeResumableTask();
        AtomicInteger oldCallbacks = new AtomicInteger();
        chain.runTaskReplacing(null, old, oldCallbacks::incrementAndGet);
        chain.runTaskReplacing(null, replacement, () -> { });
        require(chain.getCurrentTask() == replacement && replacement.isAssigned(),
                "replacement retains exact incoming identity");
        require(old.stopped() && old.abandonCalls == 1 && oldCallbacks.get() == 1,
                "replaced pre-tick root cannot be orphaned");
    }

    private static void gestureBeforeFirstTickPreservesRoot() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        AtomicInteger rootCallbacks = new AtomicInteger();
        FakeGesture gesture = new FakeGesture();
        chain.runTaskReplacing(null, root, rootCallbacks::incrementAndGet);
        chain.runTaskReplacing(null, gesture, () -> { });
        require(chain.getCurrentTask() == gesture && root.isAssigned() && !root.stopped(),
                "pre-start root is detached without a fake terminal stop");
        chain.tick();
        gesture.finish();
        chain.tick();
        require(chain.getCurrentTask() == root && rootCallbacks.get() == 0,
                "gesture completion restores the exact pre-start root and callback");
        chain.tick();
        require(root.startCalls == 1, "pre-start overlay starts the root exactly once afterward");
    }

    private static void repeatedSameGesturePreservesSuspendedRoot() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        AtomicInteger rootCallbacks = new AtomicInteger();
        AtomicInteger firstGestureCallbacks = new AtomicInteger();
        FakeGesture first = new FakeGesture();
        FakeGesture second = new FakeGesture();
        chain.runTaskReplacing(null, root, rootCallbacks::incrementAndGet);
        chain.tick();
        chain.runTaskReplacing(null, first, firstGestureCallbacks::incrementAndGet);
        chain.runTaskReplacing(null, second, () -> { });
        require(chain.getCurrentTask() == second && first.stopped(),
                "equal repeated gesture force-replaces by identity");
        require(firstGestureCallbacks.get() == 1 && root.prepareCalls == 1,
                "repeated overlay resolves only the replaced gesture");
        chain.tick();
        second.finish();
        chain.tick();
        require(chain.getCurrentTask() == root && rootCallbacks.get() == 0,
                "repeated gesture retains the original suspended root");
        chain.tick();
        require(root.startCalls == 2, "active root restarts once after repeated overlays");
    }

    private static void followDuringGestureAbandonsSuspendedRoot() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        AtomicInteger rootCallbacks = new AtomicInteger();
        AtomicInteger gestureCallbacks = new AtomicInteger();
        FakeGesture gesture = new FakeGesture();
        FollowPlayerTask follow = new FollowPlayerTask("self-test");
        chain.runTaskReplacing(null, root, rootCallbacks::incrementAndGet);
        chain.tick();
        chain.runTaskReplacing(null, gesture, gestureCallbacks::incrementAndGet);
        chain.runTaskReplacing(null, follow, () -> { });
        require(chain.getCurrentTask() == follow,
                "follow is a genuine replacement while a gesture is active");
        require(root.abandonCalls == 1 && root.stopped() && rootCallbacks.get() == 1,
                "follow terminalizes and resolves the suspended root");
        require(gesture.stopped() && gestureCallbacks.get() == 1,
                "follow also resolves the displaced gesture submission");
    }

    private static void replacementCallbackReentryRetainsNewestSubmission() {
        UserTaskChain chain = chain();
        FakeResumableTask old = new FakeResumableTask();
        FakeResumableTask incoming = new FakeResumableTask();
        FakeResumableTask staleReentry = new FakeResumableTask();
        AtomicInteger oldCallbacks = new AtomicInteger();
        AtomicInteger staleCallbacks = new AtomicInteger();
        boolean[] guardedDuringCallback = {false};
        chain.runTaskReplacing(null, old, () -> {
            oldCallbacks.incrementAndGet();
            guardedDuringCallback[0] = chain.rejectsReentrantTrackedSubmission();
            chain.runTask(null, staleReentry, staleCallbacks::incrementAndGet);
        });
        chain.tick();
        chain.runTaskReplacing(null, incoming, () -> { });
        require(chain.getCurrentTask() == incoming,
                "stale replacement callback cannot displace the newest submission");
        require(oldCallbacks.get() == 1 && staleReentry.stopped()
                        && staleReentry.abandonCalls == 1 && staleCallbacks.get() == 1,
                "stale untracked submission is sealed and its command callback drains exactly once");
        require(guardedDuringCallback[0],
                "tracked adapter preflight must see the stale-callback rejection window");
    }

    private static void policyIdleInstalledByGestureCallbackLosesToSuspendedRoot() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        FakeGesture gesture = new FakeGesture();
        LookAtOwnerTask policyIdle = new LookAtOwnerTask();
        AtomicInteger rootCallbacks = new AtomicInteger();
        AtomicInteger idleCallbacks = new AtomicInteger();
        chain.runTaskReplacing(null, root, rootCallbacks::incrementAndGet);
        chain.tick();
        chain.runTaskReplacing(null, gesture,
                () -> chain.runIdleTask(null, policyIdle, idleCallbacks::incrementAndGet));
        chain.tick();
        gesture.finish();
        chain.tick();

        require(chain.getCurrentTask() == root && root.isAssigned(),
                "policy idle installed by gesture completion must lose to the exact suspended root");
        require(policyIdle.stopped() && idleCallbacks.get() == 1,
                "displaced policy idle must be sealed and resolve exactly once");
        require(rootCallbacks.get() == 0 && !chain.isRunningIdleTask()
                        && chain.hasActiveNonIdleUserTask(),
                "gesture resume must preserve the root callback and non-idle ownership");
    }

    private static void configuredIdleFromGestureCompletionPreservesTrackedResume() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        FakeGesture gesture = new FakeGesture();
        FakeResumableTask configuredIdle = new FakeResumableTask();
        AtomicInteger authoritativeCallbacks = new AtomicInteger();
        AtomicInteger idleCallbacks = new AtomicInteger();
        boolean[] sawPendingResumePreflight = {false};
        StepExecution[] rejectedIdle = {null};

        TaskStepExecutorAdapterSelfTest.ChainPreflightHarness adapter =
                new TaskStepExecutorAdapterSelfTest.ChainPreflightHarness(
                        chain::rejectsReentrantTrackedSubmission,
                        chain::rejectsConfiguredIdleTrackedSubmission);
        StepExecution authoritative = adapter.createRunningExecution(
                "A", "setup_farm", "installed_authoritatively");
        adapter.seedAuthoritative(
                authoritative, root, authoritativeCallbacks::incrementAndGet);

        chain.runTaskReplacing(
                null, root, () -> adapter.finishAuthoritative(authoritative));
        chain.tick();
        chain.runTaskReplacing(null, gesture, () -> chain.runIdleCommand(() -> {
            sawPendingResumePreflight[0] =
                    chain.rejectsConfiguredIdleTrackedSubmission();
            rejectedIdle[0] = adapter.submit(
                    "idle-B", "setup_farm", configuredIdle, RollbackPolicy.NONE,
                    idleCallbacks::incrementAndGet);
        }));
        chain.tick();
        gesture.finish();
        chain.tick();

        require(sawPendingResumePreflight[0],
                "configured idle adapter preflight must see snapshotted resume ownership");
        require(chain.getCurrentTask() == root && root.isAssigned()
                        && root.startCalls == 1 && root.prepareCalls == 1,
                "gesture completion must restore the exact root without a duplicate start");
        require(authoritative.getState() == StepState.RUNNING
                        && adapter.ownsAuthoritative(authoritative, root)
                        && authoritativeCallbacks.get() == 0,
                "configured idle submission must not mutate suspended execution/task/callback ownership");
        require(rejectedIdle[0] != null
                        && rejectedIdle[0].getState() == StepState.BLOCKED
                        && rejectedIdle[0].getLastLogEntry().contains(
                                "configured_idle_preserved_active_task")
                        && configuredIdle.stopped() && idleCallbacks.get() == 1,
                "configured idle submission must terminalize only itself exactly once");

        chain.tick();
        require(root.startCalls == 2 && authoritativeCallbacks.get() == 0,
                "suspended root must restart exactly once and retain its original callback");
        root.finish();
        chain.tick();
        chain.tick();
        require(authoritative.getState() == StepState.SUCCEEDED
                        && authoritativeCallbacks.get() == 1
                        && !adapter.ownsAuthoritative(authoritative, root),
                "resumed root must resolve the original tracked completion exactly once");
    }

    private static void higherPriorityChainRestartsSameRoot() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        chain.runTaskReplacing(null, root, () -> { });
        chain.tick();
        chain.onInterrupt(new PassiveChain());
        require(root.prepareCalls == 1 && root.isTransientlySuspended(),
                "priority interruption checkpoints the active root");
        chain.tick();
        require(chain.getCurrentTask() == root && root.startCalls == 2,
                "priority return restarts the same logical root");
        require(root.abandonCalls == 0 && root.ordinaryStopCalls == 0,
                "hunger/defense pause does not invoke terminal hooks");
    }

    private static void idlePolicyInstallIsAtomic() {
        UserTaskChain chain = chain();
        LookAtOwnerTask lookAtOwner = new LookAtOwnerTask();
        chain.runIdleTask(null, lookAtOwner, () -> { });
        require(chain.getCurrentTask() == lookAtOwner && chain.isRunningIdleTask()
                        && !chain.hasActiveNonIdleUserTask(),
                "LookAtOwner policy task must be idle from the instant it is installed");

        IdleTask idle = new IdleTask();
        chain.runIdleTask(null, idle, () -> { });
        require(chain.getCurrentTask() == idle && chain.isRunningIdleTask()
                        && !chain.hasActiveNonIdleUserTask(),
                "IdleTask policy task must not appear as active user work");

        FakeResumableTask realTask = new FakeResumableTask();
        chain.runTaskReplacing(null, realTask, () -> { });
        require(chain.getCurrentTask() == realTask && !chain.isRunningIdleTask()
                        && chain.hasActiveNonIdleUserTask(),
                "real task must clear idle classification atomically");

        UserTaskChain configuredIdleChain = chain();
        FakeResumableTask configuredIdleTask = new FakeResumableTask();
        configuredIdleChain.runIdleCommand(() -> configuredIdleChain.runTask(
                null, configuredIdleTask, () -> { }));
        require(configuredIdleChain.getCurrentTask() == configuredIdleTask
                        && configuredIdleChain.isRunningIdleTask()
                        && !configuredIdleChain.hasActiveNonIdleUserTask(),
                "configured idle command must classify its synchronous task atomically");

        UserTaskChain emptyIdleCommandChain = chain();
        emptyIdleCommandChain.runIdleCommand(() -> { });
        FakeResumableTask afterEmptyIdle = new FakeResumableTask();
        emptyIdleCommandChain.runTaskReplacing(null, afterEmptyIdle, () -> { });
        require(emptyIdleCommandChain.hasActiveNonIdleUserTask(),
                "idle command that installs nothing must not taint the next real task");
    }

    private static void policyIdleCannotReplaceLiveRoot() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        AtomicInteger rootCallbacks = new AtomicInteger();
        chain.runTaskReplacing(null, root, rootCallbacks::incrementAndGet);
        chain.tick();

        LookAtOwnerTask rejectedIdle = new LookAtOwnerTask();
        AtomicInteger idleCallbacks = new AtomicInteger();
        chain.runIdleTask(null, rejectedIdle, idleCallbacks::incrementAndGet);

        require(chain.getCurrentTask() == root && root.isActive() && !root.stopped(),
                "post-command policy idle must not stop or replace a live root");
        require(rootCallbacks.get() == 0 && chain.hasActiveNonIdleUserTask()
                        && !chain.isRunningIdleTask(),
                "rejected policy idle must preserve live-root callback ownership and classification");
        require(rejectedIdle.stopped() && idleCallbacks.get() == 1,
                "rejected policy idle submission must be sealed and resolved exactly once");

        FakeResumableTask configuredIdle = new FakeResumableTask();
        AtomicInteger configuredIdleCallbacks = new AtomicInteger();
        boolean[] sawTrackedPreflight = {false};
        chain.runIdleCommand(() -> {
            sawTrackedPreflight[0] = chain.rejectsConfiguredIdleTrackedSubmission();
            chain.runTask(
                    null, configuredIdle, configuredIdleCallbacks::incrementAndGet);
        });
        require(chain.getCurrentTask() == root && root.isActive() && !root.stopped()
                        && rootCallbacks.get() == 0 && sawTrackedPreflight[0],
                "configured/re-entrant idle installation must preserve the live root");
        require(configuredIdle.stopped() && configuredIdleCallbacks.get() == 1,
                "configured idle rejection must seal only its own task and callback");
    }

    private static void policyIdleCannotReplaceTerminalAwaitingReap() {
        UserTaskChain chain = chain();
        FakeResumableTask root = new FakeResumableTask();
        AtomicInteger rootCallbacks = new AtomicInteger();
        chain.runTaskReplacing(null, root, rootCallbacks::incrementAndGet);
        chain.tick();
        root.terminal = true;

        LookAtOwnerTask rejectedIdle = new LookAtOwnerTask();
        AtomicInteger idleCallbacks = new AtomicInteger();
        chain.runIdleTask(null, rejectedIdle, idleCallbacks::incrementAndGet);

        require(chain.getCurrentTask() == root && root.isFinished() && !root.stopped(),
                "policy idle must not detach a terminal non-idle root before its reap tick");
        require(rootCallbacks.get() == 0 && rejectedIdle.stopped()
                        && idleCallbacks.get() == 1,
                "terminal-root and rejected-idle callbacks must remain correctly associated");

        chain.tick();
        require(chain.getCurrentTask() == null && rootCallbacks.get() == 1,
                "preserved terminal root must reap and resolve its original callback exactly once");
    }

    private static void policyIdleReplacementKeepsTaskCallbackAssociation() {
        UserTaskChain chain = chain();
        LookAtOwnerTask firstIdle = new LookAtOwnerTask();
        LookAtOwnerTask secondIdle = new LookAtOwnerTask();
        AtomicInteger firstCallbacks = new AtomicInteger();
        AtomicInteger secondCallbacks = new AtomicInteger();
        boolean[] staleClearResult = {true};

        chain.runIdleTask(null, firstIdle, () -> {
            firstCallbacks.incrementAndGet();
            staleClearResult[0] = chain.clearPolicyIdleTask();
        });
        chain.runIdleTask(null, secondIdle, secondCallbacks::incrementAndGet);

        require(chain.getCurrentTask() == secondIdle && firstIdle.stopped()
                        && chain.isRunningIdleTask(),
                "equal policy-idle instances must replace by identity");
        require(firstCallbacks.get() == 1 && secondCallbacks.get() == 0
                        && !staleClearResult[0] && chain.getCurrentTask() == secondIdle,
                "displaced idle callback must not clear the authoritative replacement");

        require(chain.clearPolicyIdleTask(),
                "newest policy idle must remain independently clearable");
        require(secondIdle.stopped() && firstCallbacks.get() == 1
                        && secondCallbacks.get() == 1,
                "clearing replacement idle must resolve only its own callback exactly once");
        require(!chain.clearPolicyIdleTask() && secondCallbacks.get() == 1,
                "repeated idle clear must not duplicate the replacement callback");
    }

    private static void clearingPolicyIdleNeverTouchesRealWork() {
        UserTaskChain chain = chain();
        LookAtOwnerTask policyIdle = new LookAtOwnerTask();
        AtomicInteger idleCallbacks = new AtomicInteger();
        chain.runIdleTask(null, policyIdle, idleCallbacks::incrementAndGet);
        require(chain.clearPolicyIdleTask(),
                "installed policy idle must be clearable");
        require(chain.getCurrentTask() == null && policyIdle.stopped()
                        && idleCallbacks.get() == 1,
                "policy idle clear must seal and resolve only that idle task");

        FakeResumableTask realTask = new FakeResumableTask();
        AtomicInteger realCallbacks = new AtomicInteger();
        chain.runTaskReplacing(null, realTask, realCallbacks::incrementAndGet);
        require(!chain.clearPolicyIdleTask(),
                "policy idle clear must reject a real user task");
        require(chain.getCurrentTask() == realTask && !realTask.stopped()
                        && realCallbacks.get() == 0,
                "policy idle clear must leave real work and its callback untouched");
    }

    private static UserTaskChain chain() {
        return new UserTaskChain(
                new TaskRunner(null), UserTaskChain.RuntimeMode.DETACHED_SELF_TEST);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeResumableTask extends Task
            implements TransientlyResumableTask {
        private int startCalls;
        private int prepareCalls;
        private int abandonCalls;
        private int ordinaryStopCalls;
        private boolean terminal;

        private void finish() {
            terminal = true;
        }

        @Override
        protected void onStart() {
            startCalls++;
            terminal = false;
        }

        @Override
        protected Task onTick() {
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
            ordinaryStopCalls++;
            terminal = true;
        }

        @Override
        public boolean prepareForTransientResume(TaskSuspensionCause cause) {
            prepareCalls++;
            return !terminal;
        }

        @Override
        public void onTransientResumeAbandoned() {
            abandonCalls++;
            terminal = true;
        }

        @Override
        public boolean isFinished() {
            return terminal;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "user-chain resumable self-test";
        }
    }

    private static final class FakeGesture extends BodyLanguageTask {
        private boolean finished;

        private FakeGesture() {
            super(Type.NOD_HEAD);
        }

        private void finish() {
            finished = true;
        }

        @Override
        protected void onStart() {
        }

        @Override
        protected Task onTick() {
            return null;
        }

        @Override
        protected void onStop(Task next) {
        }

        @Override
        public boolean isFinished() {
            return finished;
        }
    }

    private static final class PassiveChain extends TaskChain {
        private PassiveChain() {
            super(new TaskRunner(null));
        }

        @Override
        protected void onStop() {
        }

        @Override
        public void onInterrupt(TaskChain other) {
        }

        @Override
        protected void onTick() {
        }

        @Override
        public float getPriority() {
            return 100.0F;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public String getName() {
            return "priority self-test";
        }
    }
}
