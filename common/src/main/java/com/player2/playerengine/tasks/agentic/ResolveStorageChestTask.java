package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStorageTarget;
import com.player2.playerengine.agentic.StorageTargetSource;
import com.player2.playerengine.agentic.elliegps.WaypointOriginClassifier;
import com.player2.playerengine.agentic.elliegps.WaypointOriginEvidence;
import com.player2.playerengine.agentic.steps.ResolveStorageChestParams;
import com.player2.playerengine.agentic.storage.ChestPlacementCandidate;
import com.player2.playerengine.agentic.storage.ChestPlacementSelector;
import com.player2.playerengine.agentic.storage.StorageChestCandidate;
import com.player2.playerengine.agentic.storage.StorageChestScanner;
import com.player2.playerengine.agentic.storage.StorageChestValidation;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.PlaceItemBlockAtPosTask;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.tasks.crafting.CraftMacroPhase;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.tasks.container.ContainerApproach;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Resolves a storage chest by scan or single placement (C2; no deposit). */
public final class ResolveStorageChestTask extends Task implements DescribesProgress {

    private enum Phase {
        SCANNING_EXISTING,
        MOVING_TO_EXISTING,
        ENSURING_CHEST_ITEM,
        SELECTING_PLACEMENT,
        PLACING_CHEST,
        VERIFYING_TARGET,
        DONE,
        FAILED
    }

    private final ResolveStorageChestParams params;
    private final AgenticExecutionContext context;
    private Phase phase = Phase.SCANNING_EXISTING;
    private boolean finished;
    private long startMs;
    // ISSUE 1 (from-scratch chest-craft timeout): the absolute params.timeoutSeconds() clock starts at
    // onStart and includes the scan/overhead phase, so a legitimate from-scratch chest craft (gather 3
    // logs from the surface in a void world + craft table + craft chest + place) could exhaust the budget
    // while the underlying CraftMacro was still validly progressing -- aborting the whole agentic chain
    // (and with it the lost label step). Fix: a PROGRESS-REFRESHED deadline. lastProgressMs is reset to
    // "now" whenever the obtain CraftMacro advances a phase OR its still-needed external held count rises;
    // the timeout only fires when no such progress has occurred for params.timeoutSeconds(). A genuine
    // stall is still bounded -- the CraftMacro's own collectNoGain (~60s) / collect-stall (~15s) /
    // table-approach (~15s) / attempt-cap terminations fire first in practice, and MAX_OBTAIN_ATTEMPTS
    // caps the obtain loop -- so this never introduces an infinite hang; it only stops a PROGRESSING
    // craft from being killed by the absolute wall clock. Outside ENSURING_CHEST_ITEM (scan/move/place)
    // the original absolute clock is unchanged. lastObservedMacroPhase / lastObservedHeld are the
    // change-detection trackers.
    private long lastProgressMs;
    private CraftMacroPhase lastObservedMacroPhase;
    private int lastObservedHeld = -1;
    private Task child;
    private StorageChestCandidate existingCandidate;
    private ChestPlacementCandidate placementCandidate;
    private BlockPos targetPos;
    private StorageTargetSource targetSource = StorageTargetSource.EXISTING_UNKNOWN;
    private int placementAttempts = 0;
    private int obtainAttempts = 0;
    private static final int MAX_OBTAIN_ATTEMPTS = 4;
    // Give-up bound for a CONTINUOUS stuck wander while obtaining chest materials (Fix 1). A
    // productive mine->craft cycle resets this timer; only an uninterrupted ~25s wander trips it.
    //
    // Stopgap reconciliation (see masterplan/mine-collect-aggregate-count-and-wander-bound-plan.md
    // WS5 / decision 9): the global fixes shipped in that plan -- the type-aware aggregate count
    // (MaterialAvailability sufficiency axis, so "held enough logs already" stops the obtain child
    // promptly without over-mining) and the bounded MineOrCollectTask wander
    // (wanderBoundDefaultSeconds, default 90s, which self-terminates the obtain subtree's wander)
    // -- make this 25s give-up redundant in the common case. The guard is KEPT as a local backstop
    // for the ENSURING_CHEST_ITEM phase (the global bound fires first in the normal case; only a
    // genuinely-unobtainable material trips this local guard). Ordering: 25s continuous-wander
    // give-up fires first if truly stuck; the global 90s deadline on the bounded wander fires next;
    // params.timeoutSeconds() (default 180s absolute) is the final backstop. No triple-timeout
    // off-by-one: the three clocks are independent and the first to fire terminates the child. The
    // wander timer resets whenever any child is returned, so the step always terminates finitely.
    // The 25s clock measures only uninterrupted wander time and resets on any mining/crafting
    // progress, so it never collides with (or pre-empts) the absolute deadline's independent
    // total-elapsed clock.
    private static final double OBTAIN_STUCK_GIVE_UP_SECONDS = 25.0;
    private long obtainWanderStartMs = -1;
    // ISSUE 2 (DESIGN.md §3 truthfulness): the most-specific real reason from a chest-obtain CraftMacro
    // child that stopped WITHOUT producing a chest (e.g. "Failed to collect required materials for craft
    // macro (materials unreachable)"). Captured BEFORE the child is nulled, and surfaced by terminateFailed
    // to BOTH the player chat line and the model command-completion feedback instead of the generic
    // "no_chest_item" / "could not obtain a chest". Keep the LAST/most-specific reason across the up-to-4
    // obtain attempts; only read when the obtain ultimately fails (irrelevant if a later attempt succeeds).
    private String lastObtainFailureReason = null;

    public ResolveStorageChestTask(ResolveStorageChestParams params, AgenticExecutionContext context) {
        this.params = params;
        this.context = context;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String describeProgress() {
        String target = targetPos != null
                ? String.format(Locale.ROOT, "%d %d %d", targetPos.getX(), targetPos.getY(), targetPos.getZ())
                : "none";
        return String.format(Locale.ROOT, "phase=%s target=%s source=%s",
                phase.name().toLowerCase(Locale.ROOT), target, targetSource);
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
        this.lastProgressMs = this.startMs;
        updateProgress("scanning for nearby chest");
        report("scanning for a nearby chest", false);
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        if (phase == Phase.FAILED) {
            return null;
        }
        // Enforce the overall deadline before (potentially) returning an active child, so the
        // resolve step always terminates finitely even while a child task is running.
        //
        // ISSUE 1: the deadline is PROGRESS-REFRESHED while an obtain CraftMacro is genuinely advancing,
        // so a legitimate from-scratch chest craft (slow surface gather + table + chest + place) is not
        // killed by the absolute wall clock. detectAndRefreshMacroProgress resets lastProgressMs on each
        // CraftMacro phase advance or external held-count gain. The timeout fires only when no such
        // progress has occurred for params.timeoutSeconds(); a genuine stall stays bounded by the macro's
        // own collectNoGain/collect-stall/table-approach/attempt-cap terminations (which fire first) and
        // by MAX_OBTAIN_ATTEMPTS. Outside ENSURING_CHEST_ITEM the timer is never refreshed, so the
        // scan/move/place phases keep the original absolute behavior.
        boolean macroMakingProgress = phase == Phase.ENSURING_CHEST_ITEM
                && child instanceof CraftMacroResourceTask macro
                && macro.getPhase() != CraftMacroPhase.DONE
                && detectAndRefreshMacroProgress(macro);
        if (!macroMakingProgress) {
            double idleSec = (System.currentTimeMillis() - lastProgressMs) / 1000.0;
            if (idleSec >= params.timeoutSeconds()) {
                terminateFailed("timeout");
                return null;
            }
        }
        if (child != null) {
            if (child.isActive() && !child.stopped()) {
                if (phase == Phase.ENSURING_CHEST_ITEM) {
                    if (!isChestObtainChildComplete()) {
                        // Fix 1: bound a CONTINUOUS stuck wander inside the obtain subtree. If the
                        // child is mining/crafting (making progress) the wander timer resets; only an
                        // uninterrupted wander for OBTAIN_STUCK_GIVE_UP_SECONDS gives up fast. The
                        // absolute params.timeoutSeconds() check above stays as the backstop.
                        if (child.containsTask(t -> t
                                instanceof com.player2.playerengine.tasks.movement.TimeoutWanderTask)) {
                            long now = System.currentTimeMillis();
                            if (obtainWanderStartMs < 0) {
                                obtainWanderStartMs = now;
                            } else if ((now - obtainWanderStartMs) / 1000.0
                                    >= OBTAIN_STUCK_GIVE_UP_SECONDS) {
                                terminateFailed("could_not_obtain_chest_materials");
                                return null;
                            }
                        } else {
                            obtainWanderStartMs = -1;
                        }
                        return child;
                    }
                } else if (!child.isFinished()) {
                    return child;
                }
            }
            // ISSUE 2: before discarding the obtain child, if it was a CraftMacro that stopped WITHOUT
            // producing a chest, capture its REAL failure reason so terminateFailed can report the true
            // cause (e.g. "materials unreachable") rather than the generic "no_chest_item". Keep the
            // last/most-specific non-blank reason across obtain attempts.
            captureObtainFailureReason();
            child = null;
        }

        return switch (phase) {
            case SCANNING_EXISTING -> tickScanExisting();
            case MOVING_TO_EXISTING -> tickMoveExisting();
            case ENSURING_CHEST_ITEM -> tickEnsureChestItem();
            case SELECTING_PLACEMENT -> tickSelectPlacement();
            case PLACING_CHEST -> tickPlaceChest();
            case VERIFYING_TARGET -> tickVerify();
            case DONE, FAILED -> null;
        };
    }

    private Task tickScanExisting() {
        if (!params.preferExisting()) {
            if (!params.allowPlacement()) {
                // Do NOT pre-set phase=FAILED here: terminateFailed sets it and then self-stops,
                // and its guard short-circuits if phase is already FAILED (which would skip the
                // self-stop and leave the task in an infinite-idle hang).
                terminateFailed("no_storage_chest_found");
            } else {
                phase = Phase.ENSURING_CHEST_ITEM;
                updateProgress("no existing chest; obtaining chest item");
                report("no chest nearby; obtaining materials", false);
            }
            return null;
        }
        StorageChestScanner.ScanResult scan = StorageChestScanner.scan(this.controller, params);
        if (scan.best().isPresent()
                && !beyondTravelCap(scan.best().get().pos())) {
            existingCandidate = scan.best().get();
            targetPos = existingCandidate.pos();
            targetSource = existingCandidate.source();
            phase = Phase.MOVING_TO_EXISTING;
            updateProgress("moving to existing chest at " + formatPos(targetPos));
            report("found existing chest at " + formatPos(targetPos), false);
            child = ContainerApproach.task(targetPos);
            return child;
        }
        if (!params.allowPlacement()) {
            terminateFailed("no_storage_chest_found");
            return null;
        }
        phase = Phase.ENSURING_CHEST_ITEM;
        updateProgress("no existing chest; obtaining chest item");
        report("no chest nearby; obtaining materials", false);
        return null;
    }

    private Task tickMoveExisting() {
        if (targetPos == null) {
            terminateFailed("no_target");
            return null;
        }
        Optional<String> reject = StorageChestValidation.validateExistingCandidate(
                this.controller, targetPos, params.searchRadius() * params.searchRadius(), params.avoidLootChests());
        if (reject.isPresent()) {
            existingCandidate = null;
            targetPos = null;
            phase = Phase.SCANNING_EXISTING;
            updateProgress("existing chest became invalid; rescanning");
            return null;
        }
        recordTarget();
        succeed("resolved existing chest at " + formatPos(targetPos) + " (" + targetSource + ")");
        return null;
    }

    private Task tickEnsureChestItem() {
        if (this.controller.getItemStorage().hasItem(Items.CHEST)) {
            phase = Phase.SELECTING_PLACEMENT;
            updateProgress("selecting placement site");
            return null;
        }
        if (child != null) {
            return child;
        }
        // Bound the number of obtain attempts so a child that keeps finishing without producing a
        // chest cannot respawn indefinitely (the overall timeout is absolute and never reset).
        if (obtainAttempts >= MAX_OBTAIN_ATTEMPTS) {
            terminateFailed("no_chest_item");
            return null;
        }
        obtainAttempts++;
        ItemTarget chestTarget = new ItemTarget(Items.CHEST, 1);
        // WS4: thread the run's chain-scoped reservation ledger so this craft respects sibling agentic
        // steps' pre-seeded reservations (additive floor; can only lower perceived free stock).
        Task macro = CraftMacroTasks.tryCreateMacroTask(this.controller, chestTarget,
                context != null ? context.reservations() : null);
        if (macro != null) {
            updateProgress("obtaining chest item (craft macro)");
            child = macro;
            return child;
        }
        updateProgress("obtaining chest item (catalogue)");
        child = TaskCatalogue.getItemTask(Items.CHEST, 1);
        return child;
    }

    /**
     * ISSUE 2: when the current obtain child is a CraftMacro that has stopped/finished WITHOUT producing a
     * chest, record its specific {@link CraftMacroResourceTask#getFailureReason()} into
     * {@link #lastObtainFailureReason} so the eventual {@link #terminateFailed} can surface the true cause
     * to both the player and the model. No-op when a chest WAS produced (the obtain succeeded) or the
     * macro recorded no reason (fall back to the generic vocabulary). Keeps the last non-blank reason.
     */
    private void captureObtainFailureReason() {
        if (child instanceof CraftMacroResourceTask macro
                && !this.controller.getItemStorage().hasItem(Items.CHEST)) {
            String reason = macro.getFailureReason();
            if (reason != null && !reason.isBlank()) {
                this.lastObtainFailureReason = reason;
            }
        }
    }

    /** Craft-macro children must actually produce a chest, not merely satisfy {@link com.player2.playerengine.tasks.ResourceTask#isFinished()}. */
    private boolean isChestObtainChildComplete() {
        if (child instanceof CraftMacroResourceTask macro) {
            return macro.getPhase() == CraftMacroPhase.DONE
                    && this.controller.getItemStorage().hasItem(Items.CHEST);
        }
        return child.isFinished() && this.controller.getItemStorage().hasItem(Items.CHEST);
    }

    private Task tickSelectPlacement() {
        if (!this.controller.getItemStorage().hasItem(Items.CHEST)) {
            terminateFailed("no_chest_item");
            return null;
        }
        Vec3 origin = this.controller.getPlayer().position();
        ChestPlacementSelector.SelectResult result = ChestPlacementSelector.select(
                this.controller, origin, params.placementRadius());
        if (result.best().isEmpty()) {
            terminateFailed("no_valid_site");
            return null;
        }
        placementCandidate = result.best().get();
        targetPos = placementCandidate.pos();
        phase = Phase.PLACING_CHEST;
        updateProgress("placing chest at " + formatPos(targetPos));
        report("placing chest at " + formatPos(targetPos), false);
        child = new PlaceItemBlockAtPosTask(targetPos, Blocks.CHEST);
        return child;
    }

    private Task tickPlaceChest() {
        if (targetPos == null) {
            terminateFailed("placement_failed");
            return null;
        }
        if (!WorldHelper.isBlock(this.controller, targetPos, Blocks.CHEST)) {
            if (placementAttempts++ < 1) {
                phase = Phase.SELECTING_PLACEMENT;
                updateProgress("placement retry; reselecting site");
                return null;
            }
            terminateFailed("placement_unverified");
            return null;
        }
        targetSource = StorageTargetSource.PLACED_BY_BOT;
        phase = Phase.VERIFYING_TARGET;
        updateProgress("verifying chest at " + formatPos(targetPos));
        report("verifying chest placement", false);
        return null;
    }

    private Task tickVerify() {
        if (targetPos == null || !StorageChestValidation.isVanillaStorageChestBlock(
                this.controller.getWorld().getBlockState(targetPos).getBlock())) {
            terminateFailed("placement_unverified");
            return null;
        }
        if (!WorldHelper.canReach(this.controller, targetPos)) {
            terminateFailed("target_unreachable");
            return null;
        }
        recordTarget();
        succeed("placed chest at " + formatPos(targetPos) + " (PLACED_BY_BOT)");
        return null;
    }

    private void recordTarget() {
        if (targetPos == null || context == null) {
            return;
        }
        ServerLevel level = this.controller.getWorld();
        String blockId = ItemHelper.stripItemName(
                level.getBlockState(targetPos).getBlock().asItem());
        long gameTime = level.getGameTime();
        boolean botPlaced = targetSource == StorageTargetSource.PLACED_BY_BOT;
        AgenticStorageTarget target = new AgenticStorageTarget(
                targetPos,
                level.dimension().location().toString(),
                blockId,
                targetSource,
                botPlaced,
                gameTime);
        context.memory().setStorageTarget(target);
        if (context.runState() != null) {
            context.runState().setStorageTargetSummary(
                    formatPos(targetPos) + " " + targetSource.name().toLowerCase(Locale.ROOT));
        }

        // C5 WS3: capture placement-origin evidence BEFORE any deposit opens the container.
        // This preserves the Tier 2 worldgen loot-table marker (Decision 4 / scan-order invariant).
        // The evidence is read by WaypointAutoRegistrar.afterDeposit (WS6) to decide whether to
        // auto-register this chest as an EllieGPS waypoint.
        WaypointOriginEvidence evidence;
        if (botPlaced) {
            // Tier 1 (certain positive): the bot just placed this chest; no worldgen question.
            evidence = WaypointOriginEvidence.botPlaced(gameTime);
        } else {
            // Tier 2 + Tier 3: resolve canonical/secondary positions for double-chest awareness,
            // then run the classifier. ContainerResolver.resolve() does not call getItem() and
            // therefore does not trigger unpackLootTable() — the Tier 2 marker is preserved.
            //
            // IMPORTANT (Decision 4): the classifier must receive the RESOLUTION's canonical
            // position, not the raw targetPos. When targetPos is the non-canonical half of a
            // double chest, canonicalPos is the OTHER half — passing targetPos as "canonical"
            // would check the same half twice and leave the real canonical half's loot-table
            // field un-checked, letting a worldgen double loot chest slip through.
            BlockPos canonical = null;
            BlockPos secondary = null;
            try {
                ContainerResolver.Resolution res = ContainerResolver.resolve(level, targetPos);
                if (res.ok() && res.resolved() != null) {
                    canonical = res.resolved().canonicalPos();
                    secondary = res.resolved().secondaryPos();
                }
            } catch (Exception e) {
                // Resolve failed; canonical stays null and we fall through to the
                // conservative UNKNOWN evidence below (Decision 4 conservative default).
            }
            if (canonical == null) {
                // Could not resolve the container: Tier 2 cannot be evaluated reliably, so
                // capture UNKNOWN evidence directly (do-not-register conservative default)
                // rather than classifying the unresolved raw position.
                evidence = new WaypointOriginEvidence(
                        false, WaypointOriginEvidence.Verdict.UNKNOWN, "origin_unverified", gameTime);
            } else {
                evidence = WaypointOriginClassifier.buildEvidence(level, canonical, secondary, gameTime);
            }
        }
        context.memory().setWaypointOriginEvidence(evidence);
    }

    private void succeed(String message) {
        phase = Phase.DONE;
        finished = true;
        updateProgress(message);
        report("chest ready at " + formatPos(targetPos), true);
        this.controller.log("[Agentic] resolve_storage_chest: " + message);
    }

    private void terminateFailed(String reason) {
        if (phase == Phase.FAILED) {
            return;
        }
        phase = Phase.FAILED;
        finished = false;
        if (child != null && !child.stopped()) {
            child.stop(this);
        }
        // ISSUE 2: catch a macro that just stopped without a chest but whose reason was not yet captured
        // (e.g. terminateFailed invoked directly off the MAX_OBTAIN_ATTEMPTS / selecting paths).
        captureObtainFailureReason();
        child = null;
        // ISSUE 2 (DESIGN.md §3): when the obtain failed and we captured the CraftMacro's real reason,
        // surface THAT to both audiences instead of the generic code. The player line is humanized; the
        // model feedback is the SAME string the executor reads back via runState.progressForKind (set by
        // updateProgress below), so both player and model receive the accurate cause. Fall back to the
        // generic vocabulary when no specific macro reason was captured (no regression to empty messages).
        boolean obtainFailure = "no_chest_item".equals(reason)
                || "could_not_obtain_chest_materials".equals(reason);
        String humanReason = (obtainFailure && lastObtainFailureReason != null
                && !lastObtainFailureReason.isBlank())
                ? humanizeObtainReason(lastObtainFailureReason)
                : describeResolveFailure(reason);
        updateProgress(humanReason);
        // Mid-step actionable note (milestone -> bypasses throttle). Emitted here, before the executor's
        // terminal("failed", ...) runs, so the run-state terminal guard does not suppress it.
        report("could not resolve chest: " + humanReason, true);
        this.controller.log("[Agentic] resolve_storage_chest failed: " + reason
                + (lastObtainFailureReason != null ? " (obtain: " + lastObtainFailureReason + ")" : ""));
        // Self-stop so SingleTaskChain takes the forced-stop path (stopped()==true while
        // isFinished()==false) and reaches onTaskFinish; the adapter then records FAILED and
        // AgenticPlanExecutor surfaces terminal("failed", ...). phase is FAILED above so the
        // onStop override no-ops (no rollback; policy stays NONE). Without this the chain never
        // reaches onTaskFinish -> silent infinite idle + leaked run-registry entry.
        if (!this.stopped()) {
            this.stop(this);
        }
    }

    /**
     * ISSUE 2: turn the CraftMacro's raw failure reason into a chest-obtain-specific, player-readable line
     * that is also informative to the model. The macro's canonical "materials unreachable" string maps to
     * the wood-specific message the user asked for; any other captured reason is passed through prefixed so
     * the true cause still reaches both audiences (DESIGN.md §3) rather than the generic "could not obtain
     * a chest". Static + side-effect-free so it is trivially port-identical to 1.21.1.
     */
    private static String humanizeObtainReason(String macroReason) {
        // Demand-driven provisioning reasons are already enumerated, human-readable, and truthful (they
        // name the output and the owed externals WITH counts). Pass them through BEFORE the legacy broad
        // contains() keys below -- those would otherwise shadow the enumerated reason back into the
        // untruthful generic "couldn't get enough wood" line (the 2026-06-09 evening playtest claimed
        // exactly that while the bot held 28 plank-equivalents). Ordering is load-bearing.
        if (macroReason.startsWith("Cannot finish crafting")
                || macroReason.startsWith("Cannot obtain materials for")) {
            return macroReason;
        }
        String r = macroReason.toLowerCase(Locale.ROOT);
        if (r.contains("materials unreachable") || r.contains("collect required materials")
                || r.contains("collect materials")) {
            return "couldn't get enough wood to craft a chest";
        }
        return "couldn't craft a chest: " + macroReason;
    }

    /** Maps a resolve failure-vocabulary code to a concise player-readable cause. Reuses, never redefines. */
    private static String describeResolveFailure(String reason) {
        return switch (reason) {
            case "no_storage_chest_found" -> "no chest found and placement not allowed";
            case "no_chest_item" -> "could not obtain a chest";
            case "could_not_obtain_chest_materials" -> "could not obtain chest materials";
            case "no_valid_site" -> "no valid chest site nearby";
            case "placement_failed", "placement_unverified" -> "chest placement failed";
            case "target_unreachable" -> "chest site unreachable";
            case "no_target" -> "lost the chest target";
            case "timeout" -> "timed out";
            default -> reason;
        };
    }

    private void updateProgress(String message) {
        if (context != null && context.runState() != null) {
            context.runState().setStorageProgress(message);
        }
        this.setDebugState(phase.name());
    }

    /**
     * Player-facing progress note via the controller seam (WS1/WS2). Guarded by the run-state
     * terminal flag so a late callback cannot overwrite a failure line. Uses {@code context.controller()}
     * (this task holds an AgenticExecutionContext).
     */
    private void report(String message, boolean milestone) {
        if (context == null) {
            return;
        }
        if (context.runState() != null && context.runState().isTerminal()) {
            return;
        }
        context.controller().reportAgenticProgress(message, milestone);
    }

    private double elapsedSec() {
        return (System.currentTimeMillis() - startMs) / 1000.0;
    }

    /**
     * ISSUE 1: refresh the progress-deadline clock whenever the obtain {@link CraftMacroResourceTask} is
     * demonstrably advancing, and report whether progress is recent enough that the timeout must NOT fire.
     *
     * <p>Two progress signals, both read without reaching into macro internals (no new public macro API):
     * <ul>
     *   <li>a CraftMacro <b>phase advance</b> ({@code COLLECT -> CRAFT -> FIND/MOVE/LOOK -> CRAFT_3X3 -> DONE});</li>
     *   <li>a rise in the bot's held count of chest-craft materials (logs + planks + crafting tables +
     *       chests) -- this covers a long single-phase {@code COLLECT} where the bot is slowly gathering
     *       logs from the surface (the live failure: held flat at 0 for ~151s while pathing up from y=-58
     *       and the absolute clock fired). As each log/plank/table/chest enters inventory the count rises
     *       and the clock resets.</li>
     * </ul>
     *
     * <p>On either signal {@code lastProgressMs} is set to now. Returns true when the most recent progress
     * was within {@code params.timeoutSeconds()} (so the macro is "making progress" and the timeout is
     * suppressed this tick). A genuinely stalled macro (no phase advance, no material gain) stops resetting
     * the clock, so the idle-deadline in {@link #onTick} fires bounded -- and in practice the macro's own
     * collectNoGain (~60s) / collect-stall (~15s) / table-approach (~15s) / craft-fail caps terminate the
     * child first, then {@link #MAX_OBTAIN_ATTEMPTS} bounds the obtain loop. No infinite hang is introduced.
     */
    private boolean detectAndRefreshMacroProgress(CraftMacroResourceTask macro) {
        long now = System.currentTimeMillis();
        boolean advanced = false;
        CraftMacroPhase macroPhase = macro.getPhase();
        if (macroPhase != lastObservedMacroPhase) {
            lastObservedMacroPhase = macroPhase;
            advanced = true;
        }
        int held = chestCraftMaterialHeld();
        if (held > lastObservedHeld) {
            advanced = true;
        }
        if (held != lastObservedHeld) {
            lastObservedHeld = held;
        }
        if (advanced) {
            this.lastProgressMs = now;
        }
        return (now - lastProgressMs) / 1000.0 < params.timeoutSeconds();
    }

    /**
     * ISSUE 1 helper: total held count of the materials a from-scratch chest craft passes through
     * (logs -> planks -> crafting table; output chest). A rise here is real craft progress regardless of
     * which CraftMacro phase reports it. Read-only; no model call, no world scan.
     */
    private int chestCraftMaterialHeld() {
        var storage = this.controller.getItemStorage();
        return storage.getItemCount(ItemHelper.LOG)
                + storage.getItemCount(ItemHelper.PLANKS)
                + storage.getItemCount(Items.CRAFTING_TABLE)
                + storage.getItemCount(Items.CHEST);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * Issue B (immersion): true when {@code target} is farther than the configured agentic travel cap
     * (default 96 blocks / 6 chunks) from the bot. An auto-discovered existing chest beyond the cap is
     * rejected so the bot does not path to a chest a player could not see/reach. The storage searchRadius
     * (default 20) already bounds the scan well under this cap; this guard makes the intent explicit and
     * holds even if searchRadius is widened. EllieGPS marked-chest recall (planned) will bypass this cap
     * for chests the player explicitly marked.
     */
    private boolean beyondTravelCap(BlockPos target) {
        double cap = this.controller.getModSettings().getAgenticMaxTravelRadius();
        double capSq = cap * cap;
        Vec3 origin = this.controller.getPlayer().position();
        boolean beyond = origin.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > capSq;
        if (beyond) {
            this.controller.log("[Agentic] resolve_storage_chest: ignoring existing chest at "
                    + formatPos(target) + " beyond travel cap " + (int) cap + " blocks");
        }
        return beyond;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (finished || phase == Phase.DONE || phase == Phase.FAILED) {
            return;
        }
        phase = Phase.FAILED;
        finished = false;
        if (child != null && !child.stopped()) {
            child.stop(interruptTask);
        }
        child = null;
        updateProgress("interrupted");
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ResolveStorageChestTask task && task.params.equals(this.params);
    }

    @Override
    protected String toDebugString() {
        return "ResolveStorageChest";
    }
}
