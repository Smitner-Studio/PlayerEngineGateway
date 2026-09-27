package com.player2.playerengine.tasks.container;

import com.player2.playerengine.containeraccess.ContainerAnimationHelper;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ContainerScanService;
import com.player2.playerengine.containeraccess.ContainerSnapshot;
import com.player2.playerengine.containeraccess.ResolvedContainer;
import com.player2.playerengine.containeraccess.ScanMode;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.tasks.base.Task;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

/**
 * The read-only container scan session (Part C4.5, WS3): navigate to the container, open it
 * visibly, read a fresh {@link ContainerSnapshot} on the post-open tick, hold the lid for the
 * minimum visible time, then close and refresh the legacy cache.
 *
 * <p>Phases: NAVIGATE ({@link ContainerApproach} until within {@value #PROXIMITY_BLOCKS} blocks;
 * bounded by {@link ContainerResolver#NAVIGATE_TIMEOUT_MS}) -&gt; OPEN (resolve via
 * {@link ContainerResolver}, lid via {@link ContainerAnimationHelper}) -&gt; HOLD_AND_READ
 * (snapshot on the first held tick; wait out
 * {@link ContainerAnimationHelper#ANIMATION_MIN_HOLD_TICKS}) -&gt; CLOSE (guarded close +
 * Decision-12 cache refresh). The travel-cap PRECHECK runs in the command BEFORE this task
 * starts (a too-far target never navigates).
 *
 * <p><b>Chunk semantics:</b> there is no pre-navigation chunk check — the bot's approach loads
 * chunks naturally; the resolver's chunk guard runs at arrival and is defensive only.
 *
 * <p><b>Liveness, stated honestly:</b> container breakage is detected at arrival (resolve) and
 * per tick during OPEN/HOLD_AND_READ/CLOSE — not mid-walk. Mid-session removal skips the
 * animation close (the block is gone) but still runs the Decision-12 cache refresh — which
 * evicts the destroyed container from the legacy cache — and fails {@code container_missing}.
 *
 * <p>{@code onStop} always routes through the guarded {@link ContainerAnimationHelper#close},
 * so an interrupted session never leaves a phantom-open lid; close + cache refresh fire exactly
 * once across every stop path.
 */
public final class ScanContainerTask extends Task {

    /** Arrival proximity gate (blocks) — the existing container-task convention. */
    private static final double PROXIMITY_BLOCKS = 4.5;

    /** Typed failure: the {@link StorageAccessCode} machine token plus human detail. */
    public record Failure(StorageAccessCode code, String detail) {
    }

    private enum Phase {
        NAVIGATE,
        OPEN,
        HOLD_AND_READ,
        CLOSE
    }

    private final BlockPos containerPos;
    private final ScanMode mode;
    private final List<StorageItemArgs.ItemQuery> targets;

    private Phase phase = Phase.NAVIGATE;
    private long startMs;
    private Task navChild;
    private ResolvedContainer resolved;
    private ContainerSnapshot snapshot;
    private Failure failure;
    private boolean finished;
    private boolean opened;
    private boolean sessionClosed;
    private boolean cacheRefreshed;
    private int holdTicks;

    /**
     * @param targets used by {@link ScanMode#TARGETED} only (the merged queries from
     *                {@link StorageItemArgs#parse}); pass an empty list for light/deep
     */
    public ScanContainerTask(BlockPos containerPos, ScanMode mode, List<StorageItemArgs.ItemQuery> targets) {
        this.containerPos = containerPos;
        this.mode = mode;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /** The fresh snapshot, present only after a fully successful session. */
    public Optional<ContainerSnapshot> result() {
        return finished && failure == null ? Optional.ofNullable(snapshot) : Optional.empty();
    }

    /** The typed failure, present when the session ended without a result. */
    public Optional<Failure> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        switch (phase) {
            case NAVIGATE:
                return tickNavigate();
            case OPEN:
                tickOpen();
                return null;
            case HOLD_AND_READ:
                tickHoldAndRead();
                return null;
            case CLOSE:
                tickClose();
                return null;
        }
        return null;
    }

    private Task tickNavigate() {
        if (withinReach()) {
            // Arrival: resolve the live container (the resolver's chunk guard is defensive —
            // the bot's presence has normally already loaded the chunk).
            ContainerResolver.Resolution resolution =
                    ContainerResolver.resolve(this.controller.getWorld(), containerPos);
            if (!resolution.ok()) {
                terminate(new Failure(resolution.code(), resolution.detail()));
                return null;
            }
            this.resolved = resolution.resolved();
            this.navChild = null;
            this.phase = Phase.OPEN;
            this.setDebugState("opening container at " + ContainerResolver.formatPos(containerPos));
            return null;
        }
        if (System.currentTimeMillis() - startMs >= ContainerResolver.NAVIGATE_TIMEOUT_MS) {
            terminate(new Failure(
                    StorageAccessCode.CONTAINER_UNREACHABLE,
                    "could not reach " + ContainerResolver.formatPos(containerPos)
                            + " within " + (ContainerResolver.NAVIGATE_TIMEOUT_MS / 1000L) + "s"));
            return null;
        }
        if (navChild == null) {
            // ONE navigation child, held across ticks (the agentic step-task template).
            navChild = ContainerApproach.task(containerPos);
        }
        this.setDebugState("navigating to " + ContainerResolver.formatPos(containerPos));
        return navChild;
    }

    private void tickOpen() {
        if (failIfContainerGone()) {
            return;
        }
        ContainerAnimationHelper.open(this.controller.getWorld(), resolved);
        this.opened = true;
        this.holdTicks = 0;
        this.phase = Phase.HOLD_AND_READ;
        this.setDebugState("reading container (" + mode.token() + ")");
    }

    private void tickHoldAndRead() {
        if (failIfContainerGone()) {
            return;
        }
        if (snapshot == null) {
            // Fresh read on the tick AFTER open (Decision 1: never answered from the cache).
            // Pure read — the cache refresh belongs to the session close path (Decision 12).
            snapshot = ContainerScanService.snapshot(this.controller.getWorld(), resolved, mode, targets);
        }
        holdTicks++;
        if (holdTicks >= ContainerAnimationHelper.ANIMATION_MIN_HOLD_TICKS) {
            this.phase = Phase.CLOSE;
        }
    }

    private void tickClose() {
        if (failIfContainerGone()) {
            return;
        }
        closeSession();
        this.finished = true;
        this.setDebugState("scan complete");
    }

    /**
     * Per-tick liveness during the open phases (both halves of a double chest). Mid-session
     * removal -> {@code container_missing}; the animation close is skipped (the lid is gone
     * with the block) but the cache refresh still runs and evicts the destroyed container
     * (Decision 12 — see {@link #closeSession()}).
     */
    private boolean failIfContainerGone() {
        if (containerStillLive()) {
            return false;
        }
        terminate(new Failure(
                StorageAccessCode.CONTAINER_MISSING,
                "container at " + ContainerResolver.formatPos(containerPos) + " was removed mid-scan"));
        return true;
    }

    private boolean containerStillLive() {
        ServerLevel level = this.controller.getWorld();
        if (!(level.getBlockEntity(resolved.canonicalPos()) instanceof Container)) {
            return false;
        }
        return resolved.secondaryPos() == null
                || level.getBlockEntity(resolved.secondaryPos()) instanceof Container;
    }

    /** Marks the task failed; an already-open lid still closes through the guarded path, and
     * the legacy cache refresh fires whenever a container was resolved. */
    private void terminate(Failure reason) {
        this.failure = reason;
        closeSession();
        this.navChild = null;
        this.finished = true;
        this.setDebugState(reason.code().token() + ": " + reason.detail());
    }

    /**
     * Guarded close + legacy cache refresh (Decision 12), exactly once per session — shared by
     * the natural CLOSE phase, the failure path, and {@code onStop}. The animation close is
     * skipped when the lid never opened or the container block is gone (nothing left to
     * animate), but the cache refresh runs regardless — {@code WritableCache} against a removed
     * block entity evicts the stale entry, so a destroyed container never lingers as a phantom
     * in the legacy cache. Structure mirrors the WS4 transaction tasks' {@code closeAndRefresh}
     * so all C4.5 sessions (and the 1.21.1 port) share one behavior.
     */
    private void closeSession() {
        if (resolved == null) {
            return;
        }
        if (!sessionClosed) {
            this.sessionClosed = true;
            if (opened && containerStillLive()) {
                ContainerAnimationHelper.close(this.controller.getWorld(), resolved);
            }
        }
        if (!cacheRefreshed) {
            this.cacheRefreshed = true;
            ContainerScanService.refreshLegacyCache(this.controller, resolved.canonicalPos(), resolved.secondaryPos());
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Guaranteed close (Decision 7): an interrupted session never leaves a phantom-open
        // lid. closeSession() is idempotent, so a natural finish that already closed is a no-op.
        if (opened) {
            closeSession();
        }
        this.navChild = null;
    }

    private boolean withinReach() {
        return containerPos.closerThan(
                new Vec3i(
                        (int) this.controller.getEntity().position().x,
                        (int) this.controller.getEntity().position().y,
                        (int) this.controller.getEntity().position().z),
                PROXIMITY_BLOCKS);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof ScanContainerTask task)) {
            return false;
        }
        return Objects.equals(task.containerPos, this.containerPos)
                && task.mode == this.mode
                && Objects.equals(task.targets, this.targets);
    }

    @Override
    protected String toDebugString() {
        return "ScanContainer[" + mode.token() + " @ " + containerPos.toShortString() + "]";
    }
}
