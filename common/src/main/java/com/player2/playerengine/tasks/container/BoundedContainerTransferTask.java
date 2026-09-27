package com.player2.playerengine.tasks.container;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.containeraccess.ContainerAnimationHelper;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ContainerScanService;
import com.player2.playerengine.containeraccess.ResolvedContainer;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The ONE direction-parameterized item+count transfer engine for {@code withdraw_from_storage}
 * AND {@code deposit_to_storage} (Part C4.5, WS4).
 *
 * <p>It borrows the legacy bounded-deposit engine's <em>shape</em> (phases, bounded lifetime,
 * typed outcome) but never invokes that engine: the legacy path writes and counts only the
 * single block entity at the named pos, which is incompatible with the 54-slot resolved
 * double-chest view. <b>Validation view == transfer view (Decision 4):</b> every precondition is
 * checked against, and every item moves through, the same {@link ResolvedContainer#container()}
 * object — for double chests the 54-slot {@code CompoundContainer} whose slot ops delegate to
 * the correct half and fire {@code setChanged}.
 *
 * <p>Phases: PRECHECK (travel cap, no navigation beyond it) -&gt; NAVIGATE (bounded by
 * {@link ContainerResolver#NAVIGATE_TIMEOUT_MS}) -&gt; OPEN (resolve + lid animation, strictly
 * after arrival) -&gt; VALIDATE -&gt; TRANSFER (validation and transfer execute in the SAME
 * server tick, command order, so there is no partially-applied failure state) -&gt; HOLD
 * ({@link ContainerAnimationHelper#ANIMATION_MIN_HOLD_TICKS}, even after a post-open validation
 * failure) -&gt; CLOSE (guarded animation close + the Decision-12 legacy cache refresh).
 *
 * <p><b>Multi-entry semantics (normative, plan WS4):</b> all EXPLICIT-count entries validate
 * together against the live container + bot inventory (capacity cumulative, command order).
 * ALL (omitted-count) entries never fail validation, but they DO reserve the capacity they
 * will consume, in the same command order — the transfer also runs in command order, so a
 * later explicit entry must validate against what will actually remain after an earlier
 * best-effort entry (otherwise it would pass validation, move partially, and be mislabeled
 * {@code container_changed}). If ANY explicit entry fails, NOTHING in the command moves —
 * including its ALL entries — and {@link #failures()} enumerates every failing explicit entry
 * with its live number. ALL entries are best-effort <em>per entry</em>: an ALL entry that
 * moves less than present — or zero — never fails the command (it surfaces through the
 * receipt's short-reason clause). A genuine post-validation shortfall (hopper/player race
 * inside the session) is a degraded success carrying the {@code container_changed} token —
 * never a silent loss and never an unearned clean success.
 *
 * <p>Item matching is Item-level for selection, counting and capacity
 * ({@code stack.is(item)} — Decision 8), but stack MERGES are exact-stack: a deposit only
 * grows a destination stack from bot stacks that are exact-same (count-normalized
 * {@code ItemStack.matches}, the {@link SlotPreciseTransactionTask} convention), so
 * differently-tagged variants (tipped arrows, fireworks, modded NBT stackables) are never
 * silently transmuted into another stack's data. Deposits
 * pre-clamp the per-slot stack size to
 * {@code min(container.getMaxStackSize(), stack.getMaxStackSize())} so both branches behave
 * identically. The owning command maps {@link #outcome()} / {@link #failures()} /
 * {@link #entryOutcomes()} to the finish paths with the frozen {@link ScanReportFormatter}
 * strings; this task never talks to the player or the model itself.
 */
public final class BoundedContainerTransferTask extends Task implements DescribesProgress {

    /** Navigation proximity gate (blocks) — the existing container-task convention. */
    private static final double ARRIVAL_DISTANCE_BLOCKS = 4.5;

    /** Typed terminal classification of a bounded transfer. */
    public enum Outcome {
        /** Every entry moved fully (explicit counts met; ALL entries left nothing behind). */
        MOVED_ALL,
        /** Something moved but at least one ALL entry came up short, or a
         * {@code container_changed} race shortened an explicit entry — note path. */
        MOVED_PARTIAL,
        /** Validation passed trivially but zero items moved (e.g. ALL entries with nothing
         * available/fitting/allowed) — error path with per-entry reasons. */
        NOTHING,
        /** Session or validation failure; nothing moved. */
        FAILED
    }

    /** Internal callers may request a bounded partial withdrawal without changing command EXACT/ALL semantics. */
    public enum WithdrawQuantity {
        EXACT,
        UP_TO
    }

    private enum Phase {
        PRECHECK,
        NAVIGATE,
        OPEN,
        VALIDATE,
        TRANSFER,
        HOLD,
        CLOSE
    }

    private final ScanReportFormatter.TransferDirection direction;
    private final BlockPos targetPos;
    private final List<StorageItemArgs.ItemQuery> entries;
    private final WithdrawQuantity withdrawQuantity;

    private Phase phase = Phase.PRECHECK;
    private boolean finished;
    private Outcome outcome;
    private StorageAccessCode code = StorageAccessCode.OK;
    private final List<ScanReportFormatter.EntryFailure> failures = new ArrayList<>();
    private final List<ScanReportFormatter.EntryOutcome> entryOutcomes = new ArrayList<>();
    /** Per-entry reasons collected for zero-moved entries; promoted to {@link #failures} when
     * the whole command classifies as {@link Outcome#NOTHING}. */
    private final List<ScanReportFormatter.EntryFailure> zeroMoveFailures = new ArrayList<>();

    private ResolvedContainer resolved;
    private Task navigateChild;
    private long navigateStartMs;
    private int holdTicks;
    private boolean openIssued;
    private boolean closed;
    private boolean cacheRefreshed;
    private boolean containerChanged;
    private boolean transferDone;

    public BoundedContainerTransferTask(
            ScanReportFormatter.TransferDirection direction,
            BlockPos targetPos,
            List<StorageItemArgs.ItemQuery> entries) {
        this(direction, targetPos, entries, WithdrawQuantity.EXACT);
    }

    private BoundedContainerTransferTask(
            ScanReportFormatter.TransferDirection direction,
            BlockPos targetPos,
            List<StorageItemArgs.ItemQuery> entries,
            WithdrawQuantity withdrawQuantity) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.targetPos = Objects.requireNonNull(targetPos, "targetPos").immutable();
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.withdrawQuantity = Objects.requireNonNull(withdrawQuantity, "withdrawQuantity");
        if (withdrawQuantity == WithdrawQuantity.UP_TO
                && (direction != ScanReportFormatter.TransferDirection.WITHDRAW
                || this.entries.size() != 1
                || this.entries.get(0).isAll())) {
            throw new IllegalArgumentException("UP_TO requires one explicit withdrawal entry");
        }
    }

    /**
     * Creates an acquisition-only withdrawal that moves {@code 0..maxCount} exact item units.
     * Unlike the command constructor, a live source shortfall is a typed partial receipt rather
     * than an all-or-nothing validation failure; the upper bound is never exceeded.
     */
    public static BoundedContainerTransferTask withdrawUpTo(
            BlockPos targetPos,
            Item item,
            int maxCount) {
        Objects.requireNonNull(item, "item");
        if (maxCount < 1 || maxCount > StorageItemArgs.MAX_COUNT) {
            throw new IllegalArgumentException(
                    "maxCount must be within 1.." + StorageItemArgs.MAX_COUNT);
        }
        return new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW,
                targetPos,
                List.of(new StorageItemArgs.ItemQuery(
                        item, StorageItemArgs.displayIdFor(item), maxCount)),
                WithdrawQuantity.UP_TO);
    }

    // ------------------------------------------------------------------ typed result surface

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** The typed outcome, or {@code null} until the task finishes. */
    public Outcome outcome() {
        return outcome;
    }

    /** Primary code: OK on a clean success, {@code CONTAINER_CHANGED} on the race-degraded
     * success, otherwise the first failure's code. */
    public StorageAccessCode code() {
        return code;
    }

    /** Every failing entry (command order) for {@code ScanReportFormatter.transferFailure}. */
    public List<ScanReportFormatter.EntryFailure> failures() {
        return List.copyOf(failures);
    }

    /** Per-entry outcomes (command order) for {@code ScanReportFormatter.transferReceipt}. */
    public List<ScanReportFormatter.EntryOutcome> entryOutcomes() {
        return List.copyOf(entryOutcomes);
    }

    /** The resolved container view; {@code null} until OPEN succeeded. */
    public ResolvedContainer resolvedContainer() {
        return resolved;
    }

    @Override
    public String describeProgress() {
        return String.format(
                Locale.ROOT,
                "bounded-transfer dir=%s target=%d,%d,%d entries=%d phase=%s",
                direction.name().toLowerCase(Locale.ROOT),
                targetPos.getX(),
                targetPos.getY(),
                targetPos.getZ(),
                entries.size(),
                phase.name().toLowerCase(Locale.ROOT))
                + (withdrawQuantity == WithdrawQuantity.UP_TO ? " quantity=up_to" : "");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        this.navigateStartMs = System.currentTimeMillis();
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        if (openIssued && !closed) {
            holdTicks++;
        }

        if (phase == Phase.PRECHECK) {
            // Travel cap (fixed, same value/rationale as the legacy deposit precedent): beyond
            // it the bot never moves. The command normally pre-gates this too; this is the
            // defensive in-task copy.
            Vec3 origin = this.controller.getEntity().position();
            double capSq = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS * ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
            double distSq = origin.distanceToSqr(
                    targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
            if (distSq > capSq) {
                failSession(StorageAccessCode.CONTAINER_TOO_FAR,
                        "target " + ContainerResolver.formatPos(targetPos) + " is "
                                + (int) Math.sqrt(distSq) + " blocks away, beyond the "
                                + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS
                                + "-block storage travel cap");
                return null;
            }
            navigateStartMs = System.currentTimeMillis();
            phase = Phase.NAVIGATE;
        }

        if (phase == Phase.NAVIGATE) {
            if (!targetPos.closerThan(this.controller.getEntity().blockPosition(), ARRIVAL_DISTANCE_BLOCKS)) {
                if (System.currentTimeMillis() - navigateStartMs >= ContainerResolver.NAVIGATE_TIMEOUT_MS) {
                    failSession(StorageAccessCode.CONTAINER_UNREACHABLE,
                            "could not reach " + ContainerResolver.formatPos(targetPos) + " within "
                                    + (ContainerResolver.NAVIGATE_TIMEOUT_MS / 1000L) + "s");
                    return null;
                }
                this.setDebugState("navigating to " + targetPos.toShortString());
                if (navigateChild == null) {
                    navigateChild = ContainerApproach.task(targetPos);
                }
                return navigateChild;
            }
            navigateChild = null;
            phase = Phase.OPEN;
        }

        if (phase == Phase.OPEN) {
            // Lid never opens before arrival: OPEN runs strictly after NAVIGATE completed.
            ContainerResolver.Resolution resolution =
                    ContainerResolver.resolve(this.controller.getWorld(), targetPos);
            if (!resolution.ok()) {
                failSession(resolution.code(), resolution.detail());
                return null;
            }
            resolved = resolution.resolved();
            ContainerAnimationHelper.open(this.controller.getWorld(), resolved);
            openIssued = true;
            holdTicks = 0;
            phase = Phase.VALIDATE;
            // Validate on the tick AFTER open so the open event renders before any item moves.
            return null;
        }

        if (phase == Phase.VALIDATE) {
            if (!containerStillPresent()) {
                handleMidSessionRemoval();
                return null;
            }
            Container container = resolved.container();
            LivingEntityInventory inv = this.controller.getInventory();
            List<ScanReportFormatter.EntryFailure> validationFailures =
                    direction == ScanReportFormatter.TransferDirection.WITHDRAW
                            ? validateWithdraw(container, inv)
                            : validateDeposit(container, inv);
            if (!validationFailures.isEmpty()) {
                // Explicit-set atomicity: ANY explicit failure blocks the whole command,
                // including its ALL entries. The lid still waits out the hold and closes
                // through the normal guarded path (WS2 rule).
                failures.addAll(validationFailures);
                code = validationFailures.get(0).code();
                outcome = Outcome.FAILED;
                phase = Phase.HOLD;
                this.setDebugState("validation failed: " + code.token());
                return null;
            }
            phase = Phase.TRANSFER;
            // Deliberate fall-through: validation and transfer execute in the SAME server tick
            // (Decision 4 — no partially-applied failure state, no race window between them).
        }

        if (phase == Phase.TRANSFER) {
            Container container = resolved.container();
            LivingEntityInventory inv = this.controller.getInventory();
            if (direction == ScanReportFormatter.TransferDirection.WITHDRAW) {
                transferWithdraw(container, inv);
            } else {
                transferDeposit(container, inv);
            }
            transferDone = true;
            classifyOutcome();
            phase = Phase.HOLD;
            this.setDebugState("transfer done: " + (outcome == null ? "?" : outcome.name().toLowerCase(Locale.ROOT)));
            return null;
        }

        if (phase == Phase.HOLD) {
            if (!containerStillPresent()) {
                handleMidSessionRemoval();
                return null;
            }
            if (holdTicks < ContainerAnimationHelper.ANIMATION_MIN_HOLD_TICKS) {
                this.setDebugState("holding container open (" + holdTicks + "/"
                        + ContainerAnimationHelper.ANIMATION_MIN_HOLD_TICKS + ")");
                return null;
            }
            phase = Phase.CLOSE;
        }

        if (phase == Phase.CLOSE) {
            closeAndRefresh();
            finished = true;
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (finished) {
            return;
        }
        // Guaranteed close on every stop path (Decision 7); the navigate child is stopped by
        // the base class. Cache refresh runs here too — items may already have moved.
        if (openIssued) {
            closeAndRefresh();
        }
    }

    // ------------------------------------------------------------------ session helpers

    /** Session-level failure (precheck/navigate/resolve/mid-session). Nothing has moved. */
    private void failSession(StorageAccessCode failCode, String detail) {
        this.code = failCode;
        failures.add(new ScanReportFormatter.EntryFailure(failCode, "", detail));
        this.outcome = Outcome.FAILED;
        this.setDebugState("failed: " + failCode.token());
        if (openIssued && !closed) {
            phase = Phase.HOLD; // lid is open: hold, then guarded close
        } else {
            finished = true;
        }
    }

    /** Container block entity vanished while the session was open. */
    private void handleMidSessionRemoval() {
        if (outcome == null) {
            // Nothing transferred yet -> the transaction failed outright.
            code = StorageAccessCode.CONTAINER_MISSING;
            failures.add(new ScanReportFormatter.EntryFailure(
                    StorageAccessCode.CONTAINER_MISSING, "",
                    "container at " + ContainerResolver.formatPos(targetPos)
                            + " was removed mid-transaction"));
            outcome = Outcome.FAILED;
        }
        // Close is skipped inside closeAndRefresh (block gone); the cache refresh still runs
        // and evicts the destroyed container from the legacy cache.
        closeAndRefresh();
        finished = true;
    }

    private boolean containerStillPresent() {
        if (resolved == null) {
            return false;
        }
        ServerLevel level = this.controller.getWorld();
        if (!(level.getBlockEntity(resolved.canonicalPos()) instanceof Container)) {
            return false;
        }
        return resolved.secondaryPos() == null
                || level.getBlockEntity(resolved.secondaryPos()) instanceof Container;
    }

    /** Guarded animation close + the Decision-12 legacy cache refresh; idempotent. */
    private void closeAndRefresh() {
        if (resolved == null) {
            return;
        }
        if (!closed) {
            closed = true;
            if (containerStillPresent()) {
                ContainerAnimationHelper.close(this.controller.getWorld(), resolved);
            }
        }
        if (!cacheRefreshed) {
            cacheRefreshed = true;
            ContainerScanService.refreshLegacyCache(
                    this.controller, resolved.canonicalPos(), resolved.secondaryPos());
        }
    }

    // ------------------------------------------------------------------ validation

    /**
     * Validates every EXPLICIT withdraw entry against the live container (availability) and the
     * bot main inventory (capacity, cumulative empty-slot budget in command order). ALL entries
     * never validate-fail (best-effort per entry at transfer time), but they DO reserve bot
     * capacity in command order: the transfer runs in command order too, so an earlier
     * best-effort entry consumes empty slots a later explicit entry can no longer count on —
     * skipping them here would let that explicit entry pass validation, move partially, and be
     * mislabeled {@code container_changed} (Decision 4's shortfall==race rule).
     */
    private List<ScanReportFormatter.EntryFailure> validateWithdraw(Container container, LivingEntityInventory inv) {
        List<ScanReportFormatter.EntryFailure> fails = new ArrayList<>();
        int emptyBudget = countEmptyMainSlots(inv);
        for (StorageItemArgs.ItemQuery entry : entries) {
            int present = container.countItem(entry.item());
            int headroom = botMainHeadroom(inv, entry.item());
            int maxStack = maxStackFor(entry.item());
            if (withdrawQuantity == WithdrawQuantity.UP_TO) {
                int bounded = Math.min(present, entry.countOrAll());
                int fromEmpty = Math.max(0, bounded - headroom);
                int slotsNeeded = (fromEmpty + maxStack - 1) / maxStack;
                emptyBudget -= Math.min(slotsNeeded, emptyBudget);
                continue;
            }
            if (entry.isAll()) {
                // Reservation = min(what the entry would consume, what is left), in
                // empty-slot units; entry items never repeat (the parser merges duplicates),
                // so per-item headroom is independent and only the empty budget is shared.
                int fromEmpty = Math.max(0, present - headroom);
                int slotsNeeded = (fromEmpty + maxStack - 1) / maxStack;
                emptyBudget -= Math.min(slotsNeeded, emptyBudget);
                continue;
            }
            int requested = entry.countOrAll();
            if (present < requested) {
                fails.add(new ScanReportFormatter.EntryFailure(
                        StorageAccessCode.INSUFFICIENT_ITEMS, entry.displayId(),
                        "requested " + requested + "x " + entry.displayId()
                                + ", container has " + present));
                continue;
            }
            int fromEmpty = Math.max(0, requested - headroom);
            int slotsNeeded = (fromEmpty + maxStack - 1) / maxStack;
            if (slotsNeeded > emptyBudget) {
                int capacity = headroom + emptyBudget * maxStack;
                fails.add(new ScanReportFormatter.EntryFailure(
                        StorageAccessCode.INSUFFICIENT_SPACE, entry.displayId(),
                        "bot inventory can hold only " + capacity + " of " + requested
                                + "x " + entry.displayId()));
            } else {
                emptyBudget -= slotsNeeded;
            }
        }
        return fails;
    }

    /**
     * Validates every EXPLICIT deposit entry: item allowed in this container, bot availability,
     * and container capacity computed from the SAME resolved view the transfer writes through
     * (empty slots x pre-clamped stack size + same-item headroom, cumulative, command order).
     * ALL entries never validate-fail, but they DO reserve container capacity in command order
     * (same rationale as the withdraw side — the transfer interleaves them in command order).
     * Headroom is Item-level while the transfer's merges are exact-stack; for tagged stacks
     * this can overestimate slightly and the residual surfaces as the documented
     * {@code container_changed} packing edge — never as silent loss.
     */
    private List<ScanReportFormatter.EntryFailure> validateDeposit(Container container, LivingEntityInventory inv) {
        List<ScanReportFormatter.EntryFailure> fails = new ArrayList<>();
        int emptiesConsumed = 0;
        for (StorageItemArgs.ItemQuery entry : entries) {
            if (entry.isAll()) {
                ItemStack allProbe = entry.item().getDefaultInstance();
                if (!itemAllowedInContainer(container, allProbe)) {
                    // A disallowed ALL entry moves nothing at transfer time -> reserves nothing.
                    continue;
                }
                int botHas = countBotMain(inv, entry.item());
                int clampedAll = Math.min(container.getMaxStackSize(), maxStackFor(entry.item()));
                int headroom = containerHeadroom(container, entry.item(), allProbe);
                int emptiesAvailable =
                        Math.max(0, countEmptyContainerSlots(container, allProbe) - emptiesConsumed);
                int fromEmpty = Math.max(0, botHas - headroom);
                int slotsNeeded = (fromEmpty + clampedAll - 1) / clampedAll;
                emptiesConsumed += Math.min(slotsNeeded, emptiesAvailable);
                continue;
            }
            int requested = entry.countOrAll();
            ItemStack probe = entry.item().getDefaultInstance();
            if (!itemAllowedInContainer(container, probe)) {
                fails.add(new ScanReportFormatter.EntryFailure(
                        StorageAccessCode.ITEM_NOT_ALLOWED, entry.displayId(),
                        "\"" + entry.displayId() + "\" is not allowed in this "
                                + resolved.kind().token()));
                continue;
            }
            int botHas = countBotMain(inv, entry.item());
            if (botHas < requested) {
                fails.add(new ScanReportFormatter.EntryFailure(
                        StorageAccessCode.INSUFFICIENT_ITEMS, entry.displayId(),
                        "requested " + requested + "x " + entry.displayId()
                                + ", bot inventory has " + botHas));
                continue;
            }
            int clampedNew = Math.min(container.getMaxStackSize(), maxStackFor(entry.item()));
            int headroom = containerHeadroom(container, entry.item(), probe);
            int emptiesAvailable = countEmptyContainerSlots(container, probe) - emptiesConsumed;
            int fromEmpty = Math.max(0, requested - headroom);
            int slotsNeeded = (fromEmpty + clampedNew - 1) / clampedNew;
            if (slotsNeeded > emptiesAvailable) {
                int capacity = headroom + Math.max(0, emptiesAvailable) * clampedNew;
                fails.add(new ScanReportFormatter.EntryFailure(
                        StorageAccessCode.INSUFFICIENT_SPACE, entry.displayId(),
                        "container has room for only " + capacity + " of " + requested
                                + "x " + entry.displayId()));
            } else {
                emptiesConsumed += slotsNeeded;
            }
        }
        return fails;
    }

    // ------------------------------------------------------------------ transfer (single tick)

    /** Withdraw transfer: {@code container.removeItem} + {@code LivingEntityInventory.insertStack},
     * command order, on the SAME resolved view validation used. */
    private void transferWithdraw(Container container, LivingEntityInventory inv) {
        for (StorageItemArgs.ItemQuery entry : entries) {
            Item item = entry.item();
            int present = container.countItem(item);
            boolean all = entry.isAll();
            boolean upTo = withdrawQuantity == WithdrawQuantity.UP_TO;
            int requested = all ? StorageItemArgs.COUNT_ALL : entry.countOrAll();
            int want = all ? present : upTo ? Math.min(present, requested) : requested;
            int moved = 0;
            boolean botFull = false;

            for (int i = 0; i < container.getContainerSize() && moved < want; i++) {
                ItemStack slotStack = container.getItem(i);
                if (slotStack.isEmpty() || !slotStack.is(item)) {
                    continue;
                }
                int take = Math.min(slotStack.getCount(), want - moved);
                ItemStack taken = container.removeItem(i, take);
                int takenCount = taken.getCount();
                inv.insertStack(taken);
                int leftover = taken.getCount(); // insertStack mutates the stack down to leftover
                moved += takenCount - leftover;
                if (leftover > 0) {
                    // Bot could not hold the rest. Put the leftover back into the slot it came
                    // from (same source stack, so the merge is always exact-safe) — never
                    // silent loss.
                    ItemStack back = container.getItem(i);
                    if (back.isEmpty()) {
                        container.setItem(i, taken);
                    } else {
                        back.grow(leftover);
                    }
                    botFull = true;
                    break;
                }
            }
            container.setChanged();
            inv.setChanged();

            String shortReason = "";
            if (upTo) {
                if (present == 0) {
                    shortReason = "none in container";
                    zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                            StorageAccessCode.INSUFFICIENT_ITEMS, entry.displayId(),
                            "container has no " + entry.displayId()));
                } else if (moved < requested) {
                    shortReason = moved < want
                            ? "bot inventory full"
                            : "container held only " + present;
                    if (moved == 0) {
                        zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                                StorageAccessCode.INSUFFICIENT_SPACE, entry.displayId(),
                                "bot inventory cannot hold any " + entry.displayId()
                                        + " (container has " + present + ")"));
                    }
                }
            } else if (all) {
                if (present == 0) {
                    shortReason = "none in container";
                    zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                            StorageAccessCode.INSUFFICIENT_ITEMS, entry.displayId(),
                            "container has no " + entry.displayId()));
                } else if (moved < present) {
                    shortReason = "bot inventory full";
                    if (moved == 0) {
                        zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                                StorageAccessCode.INSUFFICIENT_SPACE, entry.displayId(),
                                "bot inventory cannot hold any " + entry.displayId()
                                        + " (container has " + present + ")"));
                    }
                }
            } else if (moved < want) {
                // Validation passed for this explicit entry, so a shortfall here is a genuine
                // concurrent mutation (or the rare exact-stack packing edge) — degraded
                // success via the container_changed note, never silent loss (Decision 4).
                containerChanged = true;
                shortReason = botFull
                        ? "bot inventory filled mid-transaction"
                        : "container contents changed mid-transaction";
            }
            entryOutcomes.add(new ScanReportFormatter.EntryOutcome(
                    entry.displayId(), moved, requested, present, shortReason));
        }
    }

    /** Deposit transfer: same-item headroom first, then empty slots, pre-clamped stack size
     * (Decision 8), command order, writing through the SAME resolved view validation used. */
    private void transferDeposit(Container container, LivingEntityInventory inv) {
        for (StorageItemArgs.ItemQuery entry : entries) {
            Item item = entry.item();
            ItemStack probe = item.getDefaultInstance();
            int botHas = countBotMain(inv, item);
            boolean all = entry.isAll();

            if (!itemAllowedInContainer(container, probe)) {
                // Explicit disallowed entries already failed validation; only ALL entries
                // reach here — best-effort zero with the reason on the receipt.
                entryOutcomes.add(new ScanReportFormatter.EntryOutcome(
                        entry.displayId(), 0, StorageItemArgs.COUNT_ALL, botHas,
                        "item not allowed in " + resolved.kind().token()));
                zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                        StorageAccessCode.ITEM_NOT_ALLOWED, entry.displayId(),
                        "\"" + entry.displayId() + "\" is not allowed in this "
                                + resolved.kind().token()));
                continue;
            }

            int want = all ? botHas : entry.countOrAll();
            int clampedNew = Math.min(container.getMaxStackSize(), maxStackFor(item));
            int moved = 0;

            // Phase A: top up existing same-item stacks. A destination stack may only grow
            // from bot stacks that are EXACT-same (count-normalized ItemStack.matches), never
            // from a merely Item-matching one — growing e.g. a tipped-arrow stack from a
            // differently-tagged bot stack would silently transmute that data (never silent
            // loss/corruption; Decision 4). Untagged stacks behave exactly as before.
            for (int i = 0; i < container.getContainerSize() && moved < want; i++) {
                ItemStack dst = container.getItem(i);
                if (dst.isEmpty() || !dst.is(item) || !container.canPlaceItem(i, probe)) {
                    continue;
                }
                int clamped = Math.min(container.getMaxStackSize(), dst.getMaxStackSize());
                int room = clamped - dst.getCount();
                if (room <= 0) {
                    continue;
                }
                int got = takeFromBotMainExact(inv, dst, Math.min(room, want - moved));
                if (got <= 0) {
                    // No exact-matching bot stack for THIS destination; a later destination
                    // stack may still match a different bot variant — keep scanning.
                    continue;
                }
                dst.grow(got);
                moved += got;
            }

            // Phase B: empty slots, packed to the pre-clamped stack size (matches the
            // ceil(fromEmpty/clampedNew) slot budget validation used). The first chunk seeds
            // the slot with its full stack data; top-ups may only come from bot stacks
            // EXACT-same as the seed — merging a differently-tagged variant into it would
            // overwrite that variant's data (same rule as Phase A). When the seed's variant
            // runs out, the next variant simply seeds the next empty slot.
            for (int i = 0; i < container.getContainerSize() && moved < want; i++) {
                if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, probe)) {
                    continue;
                }
                ItemStack seed = extractSeedFromBot(inv, item, Math.min(clampedNew, want - moved));
                if (seed.isEmpty()) {
                    break;
                }
                int placed = seed.getCount();
                while (placed < clampedNew && moved + placed < want) {
                    int got = takeFromBotMainExact(inv, seed,
                            Math.min(clampedNew - placed, want - moved - placed));
                    if (got <= 0) {
                        break;
                    }
                    seed.grow(got);
                    placed += got;
                }
                container.setItem(i, seed);
                moved += placed;
            }
            container.setChanged();
            inv.setChanged();

            String shortReason = "";
            if (all) {
                if (botHas == 0) {
                    shortReason = "none carried";
                    zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                            StorageAccessCode.INSUFFICIENT_ITEMS, entry.displayId(),
                            "bot inventory has no " + entry.displayId()));
                } else if (moved < botHas) {
                    shortReason = "container full";
                    if (moved == 0) {
                        zeroMoveFailures.add(new ScanReportFormatter.EntryFailure(
                                StorageAccessCode.INSUFFICIENT_SPACE, entry.displayId(),
                                "container cannot fit any " + entry.displayId()
                                        + " (bot has " + botHas + ")"));
                    }
                }
            } else if (moved < want) {
                // Validation (incl. the ALL-entry capacity reservations) passed for this
                // explicit entry, so a shortfall here is a genuine concurrent mutation or the
                // documented exact-stack packing edge (Item-level capacity vs exact-stack
                // merges) — degraded success via the container_changed note, never silent
                // loss (Decision 4).
                containerChanged = true;
                shortReason = "container contents changed mid-transaction";
            }
            entryOutcomes.add(new ScanReportFormatter.EntryOutcome(
                    entry.displayId(), moved, all ? StorageItemArgs.COUNT_ALL : want, botHas, shortReason));
        }
    }

    /** Terminal classification once the transfer tick completed. */
    private void classifyOutcome() {
        int totalMoved = 0;
        boolean anyShort = false;
        for (ScanReportFormatter.EntryOutcome eo : entryOutcomes) {
            totalMoved += eo.moved();
            if (!eo.shortReason().isEmpty()) {
                anyShort = true;
            }
        }
        if (containerChanged) {
            // container_changed is a finishWithNote degradation, never an error — even at zero
            // moved the receipt (note path) carries the per-entry truth.
            outcome = Outcome.MOVED_PARTIAL;
            code = StorageAccessCode.CONTAINER_CHANGED;
        } else if (totalMoved == 0) {
            outcome = Outcome.NOTHING;
            failures.addAll(zeroMoveFailures);
            code = failures.isEmpty() ? StorageAccessCode.INSUFFICIENT_ITEMS : failures.get(0).code();
        } else if (anyShort) {
            outcome = Outcome.MOVED_PARTIAL;
            code = StorageAccessCode.OK;
        } else {
            outcome = Outcome.MOVED_ALL;
            code = StorageAccessCode.OK;
        }
    }

    // ------------------------------------------------------------------ inventory arithmetic

    /** {@code WorldlyContainer} insertion gate (e.g. shulker-in-shulker rejection). The plain
     * {@code canPlaceItem} default is true and shulkers only override the through-face check,
     * so both are consulted. */
    private static boolean itemAllowedInContainer(Container container, ItemStack probe) {
        if (container instanceof WorldlyContainer worldly && !worldly.canPlaceItemThroughFace(0, probe, null)) {
            return false;
        }
        return true;
    }

    private static int maxStackFor(Item item) {
        // Item-level max stack via the default instance — identical lookup on both branches.
        return item.getDefaultInstance().getMaxStackSize();
    }

    private static int countEmptyMainSlots(LivingEntityInventory inv) {
        int empty = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            if (inv.main.get(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static int countBotMain(LivingEntityInventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack s = inv.main.get(i);
            if (!s.isEmpty() && s.is(item)) {
                count += s.getCount();
            }
        }
        return count;
    }

    /** Pure arithmetic seam for deterministic UP_TO receipt tests. */
    static int upToWithdrawAmount(int present, int requested, int capacity) {
        if (present < 0 || requested < 0 || capacity < 0) {
            throw new IllegalArgumentException("withdraw quantities cannot be negative");
        }
        return Math.min(present, Math.min(requested, capacity));
    }

    /** Headroom in occupied bot main slots already holding {@code item} (Item-level match). */
    private static int botMainHeadroom(LivingEntityInventory inv, Item item) {
        int headroom = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack s = inv.main.get(i);
            if (s.isEmpty() || !s.is(item)) {
                continue;
            }
            int cap = Math.min(inv.getMaxStackSize(), s.getMaxStackSize());
            headroom += Math.max(0, cap - s.getCount());
        }
        return headroom;
    }

    /** Headroom in occupied container slots already holding {@code item}, pre-clamped. */
    private static int containerHeadroom(Container container, Item item, ItemStack probe) {
        int headroom = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty() || !s.is(item) || !container.canPlaceItem(i, probe)) {
                continue;
            }
            int clamped = Math.min(container.getMaxStackSize(), s.getMaxStackSize());
            headroom += Math.max(0, clamped - s.getCount());
        }
        return headroom;
    }

    private static int countEmptyContainerSlots(Container container, ItemStack probe) {
        int empty = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty() && container.canPlaceItem(i, probe)) {
                empty++;
            }
        }
        return empty;
    }

    /** Shrinks up to {@code n} items EXACT-same as {@code template} (item + data,
     * count-normalized) out of the bot main inventory; returns the count actually taken.
     * Deposit merges must never draw from a merely Item-matching stack — growing the
     * destination by that amount would silently transmute the bot stack's tag data. */
    private static int takeFromBotMainExact(LivingEntityInventory inv, ItemStack template, int n) {
        int taken = 0;
        for (int j = 0; j < inv.main.size() && taken < n; j++) {
            ItemStack s = inv.main.get(j);
            if (s.isEmpty() || !exactSameStack(s, template)) {
                continue;
            }
            int t = Math.min(s.getCount(), n - taken);
            s.shrink(t);
            if (s.isEmpty()) {
                inv.main.set(j, ItemStack.EMPTY);
            }
            taken += t;
        }
        return taken;
    }

    /**
     * Exact-stack equality (item + data, count-normalized) — the same convention as
     * {@link SlotPreciseTransactionTask}: {@code ItemStack.matches} carries the same
     * name/signature on both maintained branches, so common stays byte-identical without new
     * {@code isSameItemSameTags/Components} calls (Decision 8).
     */
    private static boolean exactSameStack(ItemStack a, ItemStack b) {
        return ItemStack.matches(a.copyWithCount(1), b.copyWithCount(1));
    }

    /** Splits the first matching bot main stack (up to {@code cap}), preserving its full stack
     * data for the empty-slot seed; {@code ItemStack.EMPTY} when the bot has none. */
    private static ItemStack extractSeedFromBot(LivingEntityInventory inv, Item item, int cap) {
        for (int j = 0; j < inv.main.size(); j++) {
            ItemStack s = inv.main.get(j);
            if (s.isEmpty() || !s.is(item)) {
                continue;
            }
            ItemStack seed = s.split(Math.min(s.getCount(), cap));
            if (s.isEmpty()) {
                inv.main.set(j, ItemStack.EMPTY);
            }
            return seed;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ task identity

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof BoundedContainerTransferTask task)) {
            return false;
        }
        return task.direction == this.direction
                && Objects.equals(task.targetPos, this.targetPos)
                && Objects.equals(task.entries, this.entries)
                && task.withdrawQuantity == this.withdrawQuantity;
    }

    @Override
    protected String toDebugString() {
        return "BoundedContainerTransfer[" + direction.name().toLowerCase(Locale.ROOT)
                + " " + targetPos.toShortString()
                + (withdrawQuantity == WithdrawQuantity.UP_TO ? " up-to" : "") + "]";
    }
}
