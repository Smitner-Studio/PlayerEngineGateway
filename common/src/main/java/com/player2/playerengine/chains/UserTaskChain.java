package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.agentic.GatherLooseItemsTask;
import com.player2.playerengine.tasks.agentic.MineBlockTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.tasks.base.TaskSuspensionCause;
import com.player2.playerengine.tasks.base.TransientlyResumableTask;
import com.player2.playerengine.tasks.movement.BodyLanguageTask;
import com.player2.playerengine.tasks.movement.FollowPlayerTask;
import com.player2.playerengine.util.time.Stopwatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UserTaskChain extends SingleTaskChain {
   private static final Logger LOGGER = LogManager.getLogger();

   enum RuntimeMode {
      LIVE,
      DETACHED_SELF_TEST
   }

   private final Stopwatch taskStopwatch = new Stopwatch();
   private final RuntimeMode runtimeMode;
   private Runnable currentOnFinish = null;
   private boolean runningIdleTask;
   private boolean installingIdleCommandTask;

   /**
    * A user task suspended by a TRANSIENT body-language gesture (e.g. an inline {@code [bl:nod_head]}
    * marker or the {@code bodylang} command). A gesture is a short overlay, not a real replacement of the
    * active task: we stash the active task here at gesture-start and resume it when the gesture finishes,
    * instead of idling. Any genuine non-gesture task that overwrites the suspended task clears this
    * (a real cancel). Null when no task is suspended. We re-run the same task object (it is {@code reset()}
    * on re-assign); FollowPlayerTask self-terminates gracefully if the target has gone offline.
    * For ResourceTask subclasses (e.g. the task backing 'get'), reset() + re-run causes onResourceStart()
    * to reinitialize FSM state from live inventory, which re-checks already-met targets and skips
    * completed work — safe restart semantics.
    */
   private Task suspendedTask = null;
   /**
    * The {@code onFinish} callback that was registered when {@link #suspendedTask} was originally submitted.
    * Must be restored alongside the task on resume so the StepExecution terminal callback (and therefore
    * the CommandExecutor sequential chain) is not lost during the gesture overlay.
    */
   private Runnable suspendedOnFinish = null;
   /** True only when the suspended task was certified and successfully prepared for restart. */
   private boolean suspendedTaskResumePrepared = false;
   /**
    * True while a gesture-finish frame owns a snapshotted suspended root locally. During this
    * interval {@link #suspendedTask} and {@link #mainTask} may both be null while the gesture's
    * completion callback runs, but the root task, tracked execution, and original callback are
    * still authoritative and are about to be restored or terminalized by that frame.
    */
   private boolean pendingGestureResumeOwnership = false;
   /** True while we are re-assigning {@link #suspendedTask} so the resume path is not re-suspended. */
   private boolean resumingSuspended = false;
   /**
    * True while we are firing a replaced ACTIVE task's stashed onFinish (the ACTIVE-REPLACE-FINISH
    * fail-clean). That onFinish can synchronously re-enter {@link #runTask} (e.g. an agentic step's
    * onComplete advancing to the next step, or a non-tracked command's CommandExecutor chaining its
    * next part). When it does, the re-entrant inner call installs its own task; this flag suppresses
    * the inner call's ACTIVE-REPLACE-FINISH so it does not fire the just-installed (never-actually-run)
    * incoming task's terminal, and — because the fire is the LAST statement of the outer call — the
    * outer frame has no remaining installation tail to overwrite the inner call's task. Mirrors
    * {@link #resumingSuspended}.
    */
   private boolean firingReplacedTerminal = false;

   public UserTaskChain(TaskRunner runner) {
      this(runner, RuntimeMode.LIVE);
   }

   UserTaskChain(TaskRunner runner, RuntimeMode runtimeMode) {
      super(runner);
      this.runtimeMode = java.util.Objects.requireNonNull(runtimeMode, "runtimeMode");
   }

   /**
    * The certified-safe-to-resume set (audit 2026-06). A gesture-suspended user task is only stashed and
    * resumed when it is one of these — every other task type falls through to the original overwrite-drop
    * (no resume) behaviour. These four re-initialize correctly from {@code onStart()} on a {@code reset()}
    * re-run and have no non-idempotent world-mutation side effects on re-run:
    * <ul>
    *   <li>{@link ResourceTask} (incl. {@code CraftMacroResourceTask} and the {@code CollectMeat}/
    *       {@code CollectFood} subclasses) — {@code isFinished()} re-checks live inventory every tick;
    *       already-met targets complete on the first tick, never duplicating work.</li>
    *   <li>{@link FollowPlayerTask} — empty {@code onStart()}; self-terminates if the target is gone.</li>
    *   <li>{@link MineBlockTask} — {@code onStart()} re-arms {@code Phase.RESOLVING}; re-resolves the block
    *       from the live world.</li>
    *   <li>{@link GatherLooseItemsTask} — {@code onStart()} re-arms {@code Phase.SCANNING}; rescans drops
    *       from the current position.</li>
    * </ul>
    * Stateful tracked tasks (smith/smelt/resolve-storage/deposit/label) and every container/build/combat
    * write task are deliberately excluded — resuming them would freeze the StepExecution chain, double-apply
    * a side effect, or report a false success.
    */
   private static boolean isResumeSafe(Task task) {
      return task instanceof TransientlyResumableTask
            || task instanceof ResourceTask
            || task instanceof FollowPlayerTask
            || task instanceof MineBlockTask
            || task instanceof GatherLooseItemsTask;
   }

   /**
    * Whether a gesture installed now would drop {@code existing}: a live user task outside the
    * resume-safe set (idle and a resumed task are not user work in progress).
    */
   static boolean gestureWouldDropTask(Task existing, boolean runningIdleTask, boolean resumingSuspended) {
      if (existing == null || runningIdleTask || resumingSuspended || existing instanceof BodyLanguageTask) {
         return false;
      }
      boolean live = (existing.isActive() || existing.isAssigned()) && !existing.stopped();
      return live && !isResumeSafe(existing) && !existing.isFinished();
   }

   /** Terminalizes and resolves a detached submission without letting callback re-entry orphan work. */
   private void abandonAndFinishSuspended(Task task, Runnable onFinish) {
      if (task != null && !task.stopped()) {
         if (task.isTransientlySuspended()) {
            task.abandonTransientResume();
         } else {
            task.stop(null);
         }
      }
      if (onFinish == null) {
         return;
      }
      boolean alreadyFiring = this.firingReplacedTerminal;
      this.firingReplacedTerminal = true;
      try {
         onFinish.run();
      } finally {
         this.firingReplacedTerminal = alreadyFiring;
      }
   }

   private static Runnable once(Runnable delegate) {
      if (delegate == null) {
         return null;
      }
      java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
      return () -> {
         if (fired.compareAndSet(false, true)) {
            delegate.run();
         }
      };
   }

   private static String prettyPrintTimeDuration(double seconds) {
      int minutes = (int)(seconds / 60.0);
      int hours = minutes / 60;
      int days = hours / 24;
      String result = "";
      if (days != 0) {
         result = result + result + " days ";
      }

      if (hours != 0) {
         result = result + result + " hours ";
      }

      if (minutes != 0) {
         result = result + result + " minutes ";
      }

      if (!result.isEmpty()) {
         result = result + "and ";
      }

      return result + result;
   }

   @Override
   protected void onTick() {
      if (this.runtimeMode == RuntimeMode.DETACHED_SELF_TEST
            || PlayerEngineController.inGame()) {
         super.onTick();
      }
   }

   public void cancel(PlayerEngineController mod) {
      Task abandonedTask = this.suspendedTask;
      Runnable abandonedOnFinish = this.suspendedOnFinish;
      // An explicit cancel is a genuine stop of the user task — drop any task suspended for a
      // transient gesture so it is not silently resumed after the user cancelled everything.
      this.suspendedTask = null;
      this.suspendedOnFinish = null;
      this.suspendedTaskResumePrepared = false;
      if (this.mainTask != null) {
         this.stop();
         this.onTaskFinish(mod);
      }
      this.abandonAndFinishSuspended(abandonedTask, abandonedOnFinish);
   }

   @Override
   public float getPriority() {
      return 50.0F;
   }

   @Override
   public String getName() {
      return "User Tasks";
   }

   public void runTask(PlayerEngineController mod, Task task, Runnable onFinish) {
      if (this.installingIdleCommandTask) {
         this.runIdleTask(mod, task, onFinish);
         return;
      }
      this.runTaskInternal(mod, task, onFinish, false);
   }

   /** Tracked-step install path: equality must not retain a task whose callback closes over another instance. */
   public void runTaskReplacing(PlayerEngineController mod, Task task, Runnable onFinish) {
      if (this.installingIdleCommandTask) {
         this.runIdleTask(mod, task, onFinish);
         return;
      }
      this.runTaskInternal(mod, task, onFinish, true);
   }

   /**
    * Atomically installs a policy-idle task only when the chain is empty or already owns policy
    * idle work. A live, assigned, suspended, or terminal-awaiting-reap non-idle root remains
    * authoritative: neither its task nor its callback is touched by an opportunistic idle install.
    */
   public void runIdleTask(PlayerEngineController mod, Task task, Runnable onFinish) {
      Task incomingIdle = java.util.Objects.requireNonNull(task, "task");
      Runnable incomingOnFinish = once(onFinish);

      // A superseded callback cannot use policy idle to displace the task that superseded it. A
      // suspended root is also authoritative even if a future lifecycle change temporarily leaves
      // the visible slot empty. Reject only the incoming idle submission and drain its callback.
      if ((this.firingReplacedTerminal && !this.resumingSuspended)
            || this.hasProtectedNonIdleOwnership()) {
         this.rejectPolicyIdleSubmission(mod, incomingIdle, incomingOnFinish);
         return;
      }

      Task replacedIdle = this.mainTask;
      Runnable replacedIdleOnFinish = replacedIdle == null ? null : this.currentOnFinish;
      this.currentOnFinish = incomingOnFinish;
      this.runningIdleTask = true;
      this.taskStopwatch.begin();

      // Force identity replacement even when two idle task instances compare equal. Retaining the
      // old task while storing the new callback would associate completion with the wrong object.
      this.replaceTask(incomingIdle);
      if (this.runtimeMode == RuntimeMode.LIVE) {
         mod.getTaskRunner().enable();
         if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the file to re-load working settings.");
         }
      }

      // Fire only after the new idle task and callback are fully installed. The shared stale guard
      // prevents the displaced idle callback from replacing the newer policy task.
      if (replacedIdle != null) {
         this.abandonAndFinishSuspended(replacedIdle, replacedIdleOnFinish);
      }
   }

   private void rejectPolicyIdleSubmission(
         PlayerEngineController mod,
         Task incomingIdle,
         Runnable incomingOnFinish) {
      incomingIdle.controller = mod;
      incomingIdle.reset();
      this.abandonAndFinishSuspended(incomingIdle, incomingOnFinish);
   }

   /**
    * Scopes a configurable idle command's synchronous task installation. Any task it submits is
    * routed through {@link #runIdleTask}, so a re-entrant/configured idle command cannot replace
    * real work; no marker remains when the command installs no task or fails validation.
    */
   public void runIdleCommand(Runnable commandInstaller) {
      Runnable checked = java.util.Objects.requireNonNull(commandInstaller, "commandInstaller");
      boolean previous = this.installingIdleCommandTask;
      this.installingIdleCommandTask = true;
      try {
         checked.run();
      } finally {
         this.installingIdleCommandTask = previous;
      }
   }

   /**
    * True only while a superseded task's terminal callback is being drained. A tracked task
    * submitted from that callback is stale ownership and must be rejected before its adapter can
    * supersede the already-installed authoritative execution.
    */
   public boolean rejectsReentrantTrackedSubmission() {
      return this.firingReplacedTerminal && !this.resumingSuspended;
   }

   /**
    * Adapter preflight for a tracked task emitted synchronously by the configured idle command.
    * It must be queried before the adapter mutates its active execution/completion slot.
    */
   public boolean rejectsConfiguredIdleTrackedSubmission() {
      return this.installingIdleCommandTask && this.hasProtectedNonIdleOwnership();
   }

   private boolean hasProtectedNonIdleOwnership() {
      return this.pendingGestureResumeOwnership
            || this.suspendedTask != null
            || this.mainTask != null && !this.runningIdleTask;
   }

   /**
    * Clears only the currently installed policy-idle task. Real user work is never touched. The
    * detached idle callback is resolved exactly once without invoking the normal auto-idle fallback.
    */
   public boolean clearPolicyIdleTask() {
      // A displaced idle callback no longer owns the replacement idle slot. It may resolve its own
      // terminal, but cannot clear policy work installed after it.
      if (this.firingReplacedTerminal && !this.resumingSuspended) {
         return false;
      }
      if (this.mainTask == null || !this.runningIdleTask) {
         return false;
      }
      Task idleTask = this.mainTask;
      Runnable idleOnFinish = this.currentOnFinish;
      this.mainTask = null;
      this.currentOnFinish = null;
      this.runningIdleTask = false;
      this.abandonAndFinishSuspended(idleTask, idleOnFinish);
      return true;
   }

   private void runTaskInternal(
         PlayerEngineController mod,
         Task task,
         Runnable onFinish,
         boolean forceReplace) {
      // A callback belonging to a task that has just been superseded is stale ownership. If it
      // synchronously tries to submit the old command's next step, keep the already-installed new
      // task authoritative. Tracked callers detect the non-retention on return and resolve their
      // submission fail-clean; terminalizing here also gives typed tasks a truthful outcome.
      if (this.firingReplacedTerminal && !this.resumingSuspended) {
         task.controller = mod;
         task.reset();
         task.stop(null);
         Runnable rejectedOnFinish = once(onFinish);
         if (rejectedOnFinish != null) {
            rejectedOnFinish.run();
         }
         return;
      }
      if (task instanceof BodyLanguageTask
            && gestureWouldDropTask(this.mainTask, this.runningIdleTask, this.resumingSuspended)) {
         // A gesture over a task that cannot resume would end that task (a deposit the same reply
         // started died this way). The gesture is decoration; the task is the work, so skip the gesture.
         LOGGER.info("[FollowDiag] GESTURE-SKIP: '{}' not shown over non-resume-safe task '{}'",
               task.toString(), this.mainTask.toString());
         task.controller = mod;
         task.reset();
         task.stop(null);
         Runnable skippedOnFinish = once(onFinish);
         if (skippedOnFinish != null) {
            skippedOnFinish.run();
         }
         return;
      }
      this.runningIdleTask = false;
      Debug.logMessage("User Task Set: " + task.toString());

      boolean incomingIsFollow = task instanceof FollowPlayerTask;
      boolean incomingIsGesture = task instanceof BodyLanguageTask;
      Task replacedGestureTask = this.mainTask instanceof BodyLanguageTask
            && !this.mainTask.stopped()
            ? this.mainTask
            : null;
      // Captured BEFORE setTask() overwrites this.mainTask — the diag flag must reflect reality at
      // the moment of overwrite, not after the task has already been cleared (the old bug logged
      // followWasActive=false at gesture-start because the overwrite ran first).
      boolean existingIsFollow = this.mainTask instanceof FollowPlayerTask
            && !this.mainTask.stopped();
      Task finishedExistingTask = this.mainTask != null
            && this.mainTask.isFinished()
            && !(this.mainTask instanceof BodyLanguageTask)
            ? this.mainTask
            : null;
      boolean existingIsActive = this.mainTask != null
            && (this.mainTask.isActive() || this.mainTask.isAssigned())
            && !this.mainTask.stopped()
            && !this.mainTask.isFinished()
            && !(this.mainTask instanceof BodyLanguageTask);
      Task existingTask = existingIsActive ? this.mainTask : null;
      boolean installAsTransientOverlay = false;

      // Transient-overlay rule: a body-language gesture overwriting an active non-gesture user task
      // stashes that task and its onFinish callback here. ONLY a task in the certified-safe set
      // (see {@link #isResumeSafe}) is actually re-run on gesture finish; every OTHER task type is
      // dropped (overwrite-drop, pre-fix behaviour, no re-run). We still stash the non-safe task's
      // onFinish so the gesture-finish path can fire it (fail-clean) — a tracked non-safe task carries
      // the StepExecution adapter lambda, and if it were simply lost on overwrite (setTask does NOT
      // fire the old onFinish) the StepExecution would never resolve and the CommandExecutor chain
      // would freeze. Firing it resolves the step truthfully (the adapter records a FAILED stop —
      // the gesture interrupted the step). For untracked non-safe tasks the stashed onFinish is a
      // harmless no-op, so firing it is equivalent to the pre-fix drop. Any OTHER genuine non-gesture
      // user task is a real replacement and clears any stashed task.
      Task droppedSuspendedTask = null;
      Runnable droppedSuspendedOnFinish = null;
      if (!this.resumingSuspended && !this.runningIdleTask) {
         if (incomingIsGesture && existingTask != null) {
            this.suspendedTask = existingTask;
            this.suspendedOnFinish = this.currentOnFinish;  // save BEFORE overwrite below
             boolean willResume = isResumeSafe(existingTask);
             if (willResume && existingTask instanceof TransientlyResumableTask) {
                if (existingTask.isActive()) {
                   existingTask.interrupt(task, TaskSuspensionCause.GESTURE_OVERLAY);
                   willResume = existingTask.isTransientlySuspended();
                } else {
                   // It has been assigned but has not started, so there is no state to checkpoint
                   // and no terminal stop hook should run merely to display a gesture overlay.
                   willResume = existingTask.isAssigned();
                }
                installAsTransientOverlay = willResume;
             } else if (willResume && existingTask.isAssigned()) {
                installAsTransientOverlay = true;
             }
            this.suspendedTaskResumePrepared = willResume;
            if (existingIsFollow) {
               LOGGER.info("[FollowDiag] FOLLOW-SUSPEND: active follow '{}' suspended by transient gesture '{}' (will resume on finish)",
                     existingTask.toString(), task.toString());
            } else if (willResume) {
               LOGGER.info("[FollowDiag] TASK-SUSPEND: resume-safe task '{}' suspended by transient gesture '{}' (will resume on finish)",
                     existingTask.toString(), task.toString());
            } else {
               // Non-safe: NOT resumed. Stashed only so its onFinish can be fired fail-clean on
               // gesture finish (a tracked step would otherwise freeze the CommandExecutor chain).
               LOGGER.info("[FollowDiag] TASK-DROP: non-resume-safe task '{}' dropped by transient gesture '{}' (will NOT resume; step resolved fail-clean on finish)",
                     existingTask.toString(), task.toString());
            }
         } else if (!incomingIsGesture) {
            // Genuine replacement task: cancel any suspended task for good (matches pre-regression behaviour).
            if (this.suspendedTask != null) {
               if (this.suspendedTask instanceof FollowPlayerTask) {
                  LOGGER.info("[FollowDiag] FOLLOW-SUSPEND-CLEAR: genuine task '{}' replaces follow; suspended follow discarded",
                        task.toString());
               } else {
                  LOGGER.info("[FollowDiag] TASK-SUSPEND-CLEAR: genuine task '{}' replaces suspended task '{}'; discarded",
                        task.toString(), this.suspendedTask.toString());
               }
            }
            // FAIL-CLEAN (mirror of TASK-DROP-FINISH in onTaskFinish): the suspended task carries a
            // stashed StepExecution terminal (suspendedOnFinish). Pre-fix we nulled it WITHOUT firing,
            // so the suspended step's StepExecution never left RUNNING and hung forever (live: a follow
            // suspended by a gesture, then discarded by a genuine 'give' — follow_player stuck RUNNING
            // 571s while User task <Idle>). Fire the stashed onFinish so the dropped step resolves to a
            // truthful FAILED stop (the dropped task has isFinished()==false, stopped()==true, so the
            // adapter records FATAL:task_stopped_without_finish; untracked tasks' stashed onFinish is a
            // harmless no-op). RE-ENTRANCY: capture into a local and null BOTH fields BEFORE .run(), so
            // the chain sees a clean suspend slot if firing re-enters runTask/onTaskFinish.
            droppedSuspendedTask = this.suspendedTask;
            droppedSuspendedOnFinish = this.suspendedOnFinish;
            this.suspendedTask = null;
            this.suspendedOnFinish = null;
            this.suspendedTaskResumePrepared = false;
         }
      }

      // TWIN of the SUSPEND-DROP-FINISH fix: a genuine task that DIRECTLY replaces an ACTIVE
      // (non-suspended) tracked task orphans that active task's StepExecution the same way. setTask()
      // below calls oldTask.stop(task) (-> stopped()==true, isFinished()==false) but never invokes
      // onTaskFinish for it, and currentOnFinish (the active task's terminal) is overwritten with the
      // incoming onFinish WITHOUT firing it — so a tracked active step (e.g. a 'get'/'mine'/'follow')
      // replaced mid-flight by another genuine command would hang RUNNING forever. We fire the
      // about-to-be-lost currentOnFinish fail-clean (the adapter records FATAL:task_stopped_without_
      // finish; untracked tasks' onFinish is a harmless no-op). EXCLUDED, deliberately:
      //   - gesture suspend (incomingIsGesture): currentOnFinish was just stashed into suspendedOnFinish
      //     above and is restored/fired on gesture finish — firing here would double-resolve the step.
      //   - resume (resumingSuspended): the restored terminal must survive the re-run.
      //   - idle (runningIdleTask): the idle task carries no tracked step terminal to resolve.
      //   - firingReplacedTerminal: this is a RE-ENTRANT inner call triggered by an outer fire (below);
      //     the "existingTask" it sees is the outer's just-installed, never-actually-run incoming task,
      //     whose terminal must NOT be fired here — that would orphan/double-resolve the chain.
      //
      // ORDERING (the re-entrancy fix): the replaced terminal is fired LAST, only AFTER the incoming
      // task is fully installed (currentOnFinish=onFinish AND setTask(task) committed). The old code
      // fired it FIRST, while mainTask still pointed at the replaced task and before the incoming task
      // was installed; a synchronous re-entry (agentic onComplete -> next step, or CommandExecutor ->
      // next part) then ran a nested runTask that installed its own task, only for the OUTER tail
      // (currentOnFinish=onFinish; setTask(task)) to overwrite it — orphaning the inner step RUNNING
      // forever (the exact failure this fix prevents). Firing last means the outer frame has no
      // installation tail after the fire, so a re-entrant inner install survives.
      Runnable replacedActiveOnFinish = null;
      if (existingTask != null && !this.runningIdleTask && !this.resumingSuspended
            && !incomingIsGesture && !this.firingReplacedTerminal) {
         replacedActiveOnFinish = this.currentOnFinish;
      }
      Runnable finishedExistingOnFinish = null;
      if (finishedExistingTask != null && !this.runningIdleTask
            && !this.resumingSuspended && !this.firingReplacedTerminal) {
         finishedExistingOnFinish = this.currentOnFinish;
      }
      Runnable replacedGestureOnFinish = null;
      if (replacedGestureTask != null && !this.runningIdleTask
            && !this.resumingSuspended && !this.firingReplacedTerminal) {
         replacedGestureOnFinish = this.currentOnFinish;
      }

      // Overwrite currentOnFinish AFTER the suspend logic has had a chance to save it above (and after
      // the replaced-active terminal has been captured into the local above).
      this.currentOnFinish = once(onFinish);

      if (existingIsFollow && !this.runningIdleTask && !(incomingIsGesture && this.suspendedTask != null)) {
         LOGGER.info("[FollowDiag] FOLLOW-OVERWRITE: active follow '{}' being replaced by '{}' (incoming isFollow={})",
               this.mainTask.toString(), task.toString(), incomingIsFollow);
      }
      if (incomingIsFollow && !this.runningIdleTask) {
         LOGGER.info("[FollowDiag] FOLLOW-START: FollowPlayerTask assigned to UserTaskChain (existingFollowActive={})",
               existingIsFollow);
      }

      this.taskStopwatch.begin();
      if (installAsTransientOverlay) {
         this.replaceTaskAfterTransientSuspend(task);
      } else if (forceReplace || replacedGestureTask != null) {
         this.replaceTask(task);
      } else {
         this.setTask(task);
      }
      if (this.runtimeMode == RuntimeMode.LIVE) {
         mod.getTaskRunner().enable();
         if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the file to re-load working settings.");
         }
      }

      // SUSPEND-DROP-FINISH, also fired LAST: the incoming task is fully installed before a dropped
      // suspended terminal can synchronously submit another task. The shared guard prevents that
      // inner install from firing the never-run incoming terminal, and there is no outer install tail.
      if (droppedSuspendedTask != null) {
         LOGGER.info("[FollowDiag] SUSPEND-DROP-FINISH: genuine task '{}' discards a suspended task; firing its stashed onFinish to resolve the orphaned step (no resume)",
               task.toString());
         this.abandonAndFinishSuspended(droppedSuspendedTask, droppedSuspendedOnFinish);
      }

      // ACTIVE-REPLACE-FINISH, fired LAST: the incoming task is now fully installed (currentOnFinish and
      // mainTask both point at it). Fire the replaced task's stashed terminal to resolve its orphaned
      // StepExecution. firingReplacedTerminal guards the re-entrancy: if this fire synchronously
      // re-enters runTask, that inner call installs its own task and its ACTIVE-REPLACE-FINISH is
      // suppressed (it would otherwise fire THIS incoming task's never-run terminal); because this fire
      // is the last statement here, the inner call's installed task is not subsequently overwritten.
      if (replacedActiveOnFinish != null) {
         LOGGER.info("[FollowDiag] ACTIVE-REPLACE-FINISH: genuine task '{}' directly replaces active task '{}'; firing the replaced task's onFinish to resolve its step (no resume)",
               task.toString(), existingTask.toString());
         this.firingReplacedTerminal = true;
         try {
            replacedActiveOnFinish.run();
         } finally {
            this.firingReplacedTerminal = false;
         }
      }
      if (finishedExistingOnFinish != null) {
         LOGGER.info("[FollowDiag] ACTIVE-FINISH-DURING-INSTALL: task '{}' reached terminal state before chain reap; firing its onFinish exactly once after installing '{}'",
               finishedExistingTask.toString(), task.toString());
         this.abandonAndFinishSuspended(finishedExistingTask, finishedExistingOnFinish);
      }
      if (replacedGestureOnFinish != null) {
         LOGGER.info("[FollowDiag] GESTURE-REPLACE-FINISH: gesture '{}' replaced by '{}'; firing the old gesture callback exactly once",
               replacedGestureTask.toString(), task.toString());
         this.abandonAndFinishSuspended(replacedGestureTask, replacedGestureOnFinish);
      }
   }

   @Override
   protected void onTaskFinish(PlayerEngineController mod) {
      boolean shouldIdle = this.runtimeMode == RuntimeMode.LIVE
            && mod.getModSettings().shouldRunIdleCommandWhenNotActive();
      double seconds = this.taskStopwatch.time();
      Task oldTask = this.mainTask;

      // Snapshot the transient-overlay resume intent BEFORE anything below can clear it. When
      // shouldIdle==false, mod.stop() (called further down) re-enters this chain's cancel(), which
      // nulls suspendedTask/suspendedOnFinish — so we must capture both here, up front, while they
      // still reflect the gesture that is finishing.
      Task resumeTask = null;
      Runnable resumeOnFinish = null;
      boolean resumePrepared = false;
      if (oldTask instanceof BodyLanguageTask && this.suspendedTask != null
            && !this.runningIdleTask && !this.resumingSuspended) {
         resumeTask = this.suspendedTask;
         resumeOnFinish = this.suspendedOnFinish;
         resumePrepared = this.suspendedTaskResumePrepared;
      }
      // Keep protected ownership visible after the suspended fields are cleared below. In
      // particular, a configured idle command submitted by the gesture's completion callback must
      // fail adapter preflight before it can terminalize the still-authoritative tracked execution.
      boolean previousPendingGestureResumeOwnership = this.pendingGestureResumeOwnership;
      this.pendingGestureResumeOwnership = previousPendingGestureResumeOwnership
            || resumeTask != null;
      try {
      this.suspendedTask = null;
      this.suspendedOnFinish = null;
      this.suspendedTaskResumePrepared = false;

      if (oldTask instanceof FollowPlayerTask && !this.runningIdleTask) {
         // String.format here is intentional: Log4j lazy-eval doesn't apply but the call is
         // at most once per task completion (not a hot path), and "%.1f" is required to keep
         // the output to 1 decimal place (raw double via {} would emit full precision).
         LOGGER.info("[FollowDiag] FOLLOW-FINISH: FollowPlayerTask ended after {}s; task.isFinished={} task.stopped={} shouldIdle={}",
               String.format("%.1f", seconds), oldTask.isFinished(), oldTask.stopped(), shouldIdle);
      }
      this.mainTask = null;
      if (this.runtimeMode == RuntimeMode.LIVE) {
         if (!shouldIdle) {
            mod.stop();
         } else {
            mod.getBaritone().getPathingBehavior().forceCancel();
            mod.getBaritone().getInputOverrideHandler().clearAllKeys();
         }
      }

      if (this.currentOnFinish != null) {
         this.currentOnFinish.run();
      }

      // Transient-overlay finish: a body-language gesture just finished and a non-gesture task was
      // stashed for it. The onFinish callback above (the gesture's finish()) has already run its
      // executor cleanup. A genuine non-idle task installed by that callback wins; a policy-idle
      // LookAtOwner/Idle install loses to the exact suspended root below.
      boolean callbackInstalledPolicyIdle = resumeTask != null
            && this.mainTask != null
            && this.runningIdleTask;
      if (resumeTask != null && (this.mainTask == null || callbackInstalledPolicyIdle)) {
         boolean readyToResume = resumePrepared && isResumeSafe(resumeTask);
         if (readyToResume) {
            // A post-command LookAtOwner/Idle install is policy, not a genuine replacement. The
            // exact suspended root and its original callback remain authoritative. Capture the
            // idle callback before runTask overwrites it, let the resume replace/stop the idle task,
            // then resolve that displaced idle callback under the stale-submission guard.
            Task displacedPolicyIdle = callbackInstalledPolicyIdle ? this.mainTask : null;
            Runnable displacedPolicyIdleOnFinish = callbackInstalledPolicyIdle
                  ? this.currentOnFinish : null;
            // RESUME path (certified-safe set only): re-run the same task object. reset() (via setTask
            // inside runTask) re-arms it from onStart().
            //   - FollowPlayerTask: self-terminates gracefully if target is gone (FOLLOWED_TARGET_GONE).
            //   - ResourceTask (e.g. 'get'): onResourceStart() re-initializes FSM state from live
            //     inventory; already-met item targets cause isFinished() to return true on the first
            //     tick and the task completes cleanly.
            //   - MineBlockTask / GatherLooseItemsTask: onStart() re-arms the resolving/scanning phase.
            // resumeOnFinish restores the original StepExecution terminal callback so the
            // CommandExecutor sequential chain is not dropped.
            if (resumeTask instanceof FollowPlayerTask) {
               LOGGER.info("[FollowDiag] FOLLOW-RESUME: gesture finished, resuming suspended follow '{}'",
                     resumeTask.toString());
            } else {
               LOGGER.info("[FollowDiag] TASK-RESUME: gesture finished, resuming suspended task '{}'",
                     resumeTask.toString());
            }
            this.resumingSuspended = true;
            try {
               Runnable onFinishToRestore = (resumeOnFinish != null) ? resumeOnFinish : () -> {};
               this.runTask(mod, resumeTask, onFinishToRestore);
            } finally {
               this.resumingSuspended = false;
            }
            if (displacedPolicyIdle != null) {
               this.abandonAndFinishSuspended(
                     displacedPolicyIdle, displacedPolicyIdleOnFinish);
            }
            return;
         } else {
            // FAIL-CLEAN path (non-safe tasks): do NOT re-run the task — it is dropped (overwrite-drop,
            // pre-fix behaviour). But fire its stashed onFinish so a tracked StepExecution resolves
            // instead of hanging the CommandExecutor chain. The stashed callback is the
            // TaskStepExecutorAdapter lambda; the dropped task has isFinished()==false and stopped()==
            // true, so the adapter records a truthful FAILED stop (the gesture interrupted the step)
            // rather than a silent hang or a false success. For an untracked task the stashed onFinish
            // is a harmless no-op, so this is equivalent to the pre-fix drop.
            LOGGER.info("[FollowDiag] TASK-DROP-FINISH: gesture finished, non-resume-safe task '{}' dropped; firing its onFinish to resolve the step (no resume)",
                  resumeTask.toString());
            this.abandonAndFinishSuspended(resumeTask, resumeOnFinish);
            // Fall through to the idle/done bookkeeping below (mainTask stays null unless the fired
            // callback installed a fresh task).
         }
      } else if (resumeTask != null) {
         LOGGER.info("[FollowDiag] TASK-SUSPEND-CLEAR: task installed during gesture completion; suspended task '{}' discarded",
               resumeTask.toString());
         this.abandonAndFinishSuspended(resumeTask, resumeOnFinish);
      }
      } finally {
         this.pendingGestureResumeOwnership = previousPendingGestureResumeOwnership;
      }

      boolean actuallyDone = this.mainTask == null;
      if (actuallyDone) {
         if (!this.runningIdleTask) {
            Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
         }

         if (shouldIdle && this.runtimeMode == RuntimeMode.LIVE) {
            // Guard mirrors FOLLOW-FINISH at line 104: exclude the case where the idle task
            // itself is a follow invocation, which would emit a spurious "will NOT auto-resume".
            if (oldTask instanceof FollowPlayerTask && !this.runningIdleTask) {
               LOGGER.info("[FollowDiag] FOLLOW-IDLE-FALLBACK: follow ended, chain executing idleCommand='{}' - follow will NOT auto-resume",
                     mod.getModSettings().getIdleCommand());
            }
            this.runIdleCommand(() -> this.controller.getCommandExecutor()
                  .executeWithPrefix(mod.getModSettings().getIdleCommand()));
            this.runningIdleTask = true;
         }
      }
   }

   public boolean isRunningIdleTask() {
      return this.isActive() && this.runningIdleTask;
   }

   /** True when a non-idle user task is active (Part C0 idle guard). */
   public boolean hasActiveNonIdleUserTask() {
      return this.isActive() && !this.runningIdleTask && this.mainTask != null;
   }
}
