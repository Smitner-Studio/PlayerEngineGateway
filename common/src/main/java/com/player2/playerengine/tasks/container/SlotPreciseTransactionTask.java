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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The single owning session task for BOTH slot-precise commands
 * ({@code withdraw_storage_slot} / {@code deposit_storage_slot}), parameterized by direction
 * (Part C4.5, WS4).
 *
 * <p>Same phase chain and constants as {@link BoundedContainerTransferTask}: PRECHECK -&gt;
 * NAVIGATE (bounded) -&gt; OPEN -&gt; VALIDATE -&gt; TRANSFER (validate and transfer run in the
 * SAME server tick — a slot op is atomic by construction, so there is no
 * {@code container_changed} mode here) -&gt; HOLD -&gt; CLOSE (guarded animation close + the
 * Decision-12 legacy cache refresh). The slot transfer never runs in {@code Command.call()};
 * the command only parses args and starts this task.
 *
 * <p>Container slots use the deep-scan indexing of the resolved view ({@code 0..totalSlots-1};
 * double chests are one 54-slot space). Bot slots address the main inventory ({@code 0..35}).
 *
 * <p><b>Destination merge rule:</b> an occupied destination slot only merges when it holds the
 * EXACT same stack (item + data). The exactness check is count-normalized
 * {@code ItemStack.matches} — the method name and signature are identical on both maintained
 * branches, so this keeps common byte-identical without new
 * {@code isSameItemSameTags/Components} calls (Decision 8; the pre-existing quarantined helpers
 * cannot test one specific slot). A mismatch or no-room destination is {@code slot_occupied};
 * nothing moves.
 */
public final class SlotPreciseTransactionTask extends Task implements DescribesProgress {

    /** Navigation proximity gate (blocks) — the existing container-task convention. */
    private static final double ARRIVAL_DISTANCE_BLOCKS = 4.5;

    /** Sentinel for an omitted optional slot argument (auto-place). */
    public static final int SLOT_AUTO = -1;

    /** Bot main inventory slot range is {@code 0..MAX_BOT_SLOT}. */
    public static final int MAX_BOT_SLOT = LivingEntityInventory.MAIN_SIZE - 1;

    /** Typed terminal classification of a slot-precise transaction. */
    public enum Outcome {
        COMPLETED,
        FAILED
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
    /** Explicit container slot, or {@link #SLOT_AUTO} (deposit auto-place). */
    private final int requestedContainerSlot;
    /** Explicit bot main slot, or {@link #SLOT_AUTO} (withdraw auto-place). */
    private final int requestedBotSlot;
    private final int count;

    private Phase phase = Phase.PRECHECK;
    private boolean finished;
    private Outcome outcome;
    private StorageAccessCode code = StorageAccessCode.OK;
    private final List<ScanReportFormatter.EntryFailure> failures = new ArrayList<>();

    private ResolvedContainer resolved;
    private Task navigateChild;
    private long navigateStartMs;
    private int holdTicks;
    private boolean openIssued;
    private boolean closed;
    private boolean cacheRefreshed;

    // Transfer results for the command's receipt rendering.
    private int movedCount;
    private String displayId = "";
    private int containerSlotUsed = SLOT_AUTO;
    private Integer botSlotUsed; // null = auto-placed (receipt clause omitted)

    public SlotPreciseTransactionTask(
            ScanReportFormatter.TransferDirection direction,
            BlockPos targetPos,
            int containerSlot,
            int botSlot,
            int count) {
        this.direction = direction;
        this.targetPos = targetPos;
        this.requestedContainerSlot = containerSlot;
        this.requestedBotSlot = botSlot;
        this.count = count;
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

    /** OK on success, otherwise the failure's code. */
    public StorageAccessCode code() {
        return code;
    }

    /** The failure entries (a slot op fails on its first violated precondition). */
    public List<ScanReportFormatter.EntryFailure> failures() {
        return List.copyOf(failures);
    }

    /** The resolved container view; {@code null} until OPEN succeeded. */
    public ResolvedContainer resolvedContainer() {
        return resolved;
    }

    /** Items actually moved (equals the requested count on a clean success). */
    public int movedCount() {
        return movedCount;
    }

    /** Canonical display id of the transacted item (set once validation located it). */
    public String displayId() {
        return displayId;
    }

    /** The container slot the transfer used (the requested one, or the auto-chosen one). */
    public int containerSlotUsed() {
        return containerSlotUsed;
    }

    /** The bot main slot used, or {@code null} when auto-placed (receipt clause omitted). */
    public Integer botSlotUsed() {
        return botSlotUsed;
    }

    @Override
    public String describeProgress() {
        return String.format(
                Locale.ROOT,
                "slot-precise dir=%s target=%d,%d,%d containerSlot=%d botSlot=%d count=%d phase=%s",
                direction.name().toLowerCase(Locale.ROOT),
                targetPos.getX(),
                targetPos.getY(),
                targetPos.getZ(),
                requestedContainerSlot,
                requestedBotSlot,
                count,
                phase.name().toLowerCase(Locale.ROOT));
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
            // Validate and transfer in the SAME server tick (atomic slot op; Decision 4).
            if (direction == ScanReportFormatter.TransferDirection.WITHDRAW) {
                validateAndTransferWithdraw();
            } else {
                validateAndTransferDeposit();
            }
            phase = Phase.HOLD;
            this.setDebugState(outcome == Outcome.COMPLETED
                    ? "slot transfer done"
                    : "validation failed: " + code.token());
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
        if (openIssued) {
            closeAndRefresh();
        }
    }

    // ------------------------------------------------------------------ session helpers

    /** Single-failure termination: slot ops fail on their first violated precondition. The lid
     * (if open) still waits out the hold and closes through the normal guarded path. */
    private void failSession(StorageAccessCode failCode, String detail) {
        this.code = failCode;
        failures.add(new ScanReportFormatter.EntryFailure(failCode, displayId, detail));
        this.outcome = Outcome.FAILED;
        this.setDebugState("failed: " + failCode.token());
        if (openIssued && !closed) {
            phase = Phase.HOLD;
        } else {
            finished = true;
        }
    }

    private void handleMidSessionRemoval() {
        if (outcome == null) {
            code = StorageAccessCode.CONTAINER_MISSING;
            failures.add(new ScanReportFormatter.EntryFailure(
                    StorageAccessCode.CONTAINER_MISSING, "",
                    "container at " + ContainerResolver.formatPos(targetPos)
                            + " was removed mid-transaction"));
            outcome = Outcome.FAILED;
        }
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

    // ------------------------------------------------------------------ withdraw (container -> bot)

    private void validateAndTransferWithdraw() {
        Container container = resolved.container();
        LivingEntityInventory inv = this.controller.getInventory();
        int totalSlots = resolved.totalSlots();

        if (requestedContainerSlot < 0 || requestedContainerSlot >= totalSlots) {
            failSession(StorageAccessCode.INVALID_SLOT,
                    resolved.kind().token() + " has slots 0-" + (totalSlots - 1));
            return;
        }
        ItemStack src = container.getItem(requestedContainerSlot);
        if (src.isEmpty()) {
            failSession(StorageAccessCode.SLOT_EMPTY,
                    "slot " + requestedContainerSlot + " is empty" + nonEmptyHint(container));
            return;
        }
        displayId = StorageItemArgs.displayIdFor(src.getItem());
        if (count > src.getCount()) {
            failSession(StorageAccessCode.INSUFFICIENT_ITEMS,
                    "slot " + requestedContainerSlot + " has " + src.getCount() + "x "
                            + displayId + ", requested " + count);
            return;
        }

        if (requestedBotSlot != SLOT_AUTO) {
            if (requestedBotSlot < 0 || requestedBotSlot > MAX_BOT_SLOT) {
                failSession(StorageAccessCode.INVALID_SLOT,
                        "bot inventory has slots 0-" + MAX_BOT_SLOT);
                return;
            }
            ItemStack dest = inv.main.get(requestedBotSlot);
            if (!dest.isEmpty()) {
                if (!exactSameStack(dest, src)) {
                    failSession(StorageAccessCode.SLOT_OCCUPIED,
                            "bot slot " + requestedBotSlot + " holds " + dest.getCount() + "x "
                                    + StorageItemArgs.displayIdFor(dest.getItem()));
                    return;
                }
                int room = Math.min(inv.getMaxStackSize(), dest.getMaxStackSize()) - dest.getCount();
                if (count > room) {
                    failSession(StorageAccessCode.SLOT_OCCUPIED,
                            "bot slot " + requestedBotSlot + " holds " + dest.getCount() + "x "
                                    + displayId + ", room for " + Math.max(0, room));
                    return;
                }
            } else {
                int capacity = Math.min(inv.getMaxStackSize(), src.getMaxStackSize());
                if (count > capacity) {
                    failSession(StorageAccessCode.INSUFFICIENT_SPACE,
                            "bot slot " + requestedBotSlot + " can hold at most " + capacity
                                    + "x " + displayId);
                    return;
                }
            }
        } else {
            int capacity = botMainCapacityFor(inv, src);
            if (count > capacity) {
                failSession(StorageAccessCode.INSUFFICIENT_SPACE,
                        "bot inventory can hold only " + capacity + " of " + count + "x " + displayId);
                return;
            }
        }

        // Transfer (same tick as validation; atomic).
        ItemStack taken = container.removeItem(requestedContainerSlot, count);
        int takenCount = taken.getCount();
        if (requestedBotSlot != SLOT_AUTO) {
            ItemStack dest = inv.main.get(requestedBotSlot);
            if (dest.isEmpty()) {
                inv.main.set(requestedBotSlot, taken);
            } else {
                dest.grow(takenCount); // exact-same stack verified above
            }
            movedCount = takenCount;
            botSlotUsed = requestedBotSlot;
        } else {
            inv.insertStack(taken);
            int leftover = taken.getCount(); // insertStack mutates the stack down to leftover
            if (leftover > 0) {
                // Rare exact-stack packing edge: put the remainder back into the slot it came
                // from (same source stack — always exact-safe); never silent loss.
                ItemStack back = container.getItem(requestedContainerSlot);
                if (back.isEmpty()) {
                    container.setItem(requestedContainerSlot, taken);
                } else {
                    back.grow(leftover);
                }
            }
            movedCount = takenCount - leftover;
            botSlotUsed = null;
        }
        container.setChanged();
        inv.setChanged();
        containerSlotUsed = requestedContainerSlot;

        if (movedCount == 0) {
            failSession(StorageAccessCode.INSUFFICIENT_SPACE,
                    "bot inventory could not hold any of the " + count + "x " + displayId);
            return;
        }
        outcome = Outcome.COMPLETED;
        code = StorageAccessCode.OK;
    }

    // ------------------------------------------------------------------ deposit (bot -> container)

    private void validateAndTransferDeposit() {
        Container container = resolved.container();
        LivingEntityInventory inv = this.controller.getInventory();
        int totalSlots = resolved.totalSlots();

        if (requestedBotSlot < 0 || requestedBotSlot > MAX_BOT_SLOT) {
            failSession(StorageAccessCode.INVALID_SLOT,
                    "bot inventory has slots 0-" + MAX_BOT_SLOT);
            return;
        }
        ItemStack src = inv.main.get(requestedBotSlot);
        if (src.isEmpty()) {
            failSession(StorageAccessCode.SLOT_EMPTY,
                    "bot slot " + requestedBotSlot + " is empty");
            return;
        }
        displayId = StorageItemArgs.displayIdFor(src.getItem());
        if (count > src.getCount()) {
            failSession(StorageAccessCode.INSUFFICIENT_ITEMS,
                    "bot slot " + requestedBotSlot + " has " + src.getCount() + "x "
                            + displayId + ", requested " + count);
            return;
        }
        ItemStack probe = src.copyWithCount(1);
        if (container instanceof WorldlyContainer worldly && !worldly.canPlaceItemThroughFace(0, probe, null)) {
            failSession(StorageAccessCode.ITEM_NOT_ALLOWED,
                    "\"" + displayId + "\" is not allowed in this " + resolved.kind().token());
            return;
        }

        int chosenSlot;
        if (requestedContainerSlot != SLOT_AUTO) {
            if (requestedContainerSlot < 0 || requestedContainerSlot >= totalSlots) {
                failSession(StorageAccessCode.INVALID_SLOT,
                        resolved.kind().token() + " has slots 0-" + (totalSlots - 1));
                return;
            }
            if (!container.canPlaceItem(requestedContainerSlot, probe)) {
                failSession(StorageAccessCode.ITEM_NOT_ALLOWED,
                        "slot " + requestedContainerSlot + " does not accept \"" + displayId + "\"");
                return;
            }
            ItemStack dest = container.getItem(requestedContainerSlot);
            if (!dest.isEmpty()) {
                if (!exactSameStack(dest, src)) {
                    failSession(StorageAccessCode.SLOT_OCCUPIED,
                            "slot " + requestedContainerSlot + " holds " + dest.getCount() + "x "
                                    + StorageItemArgs.displayIdFor(dest.getItem()));
                    return;
                }
                int clamped = Math.min(container.getMaxStackSize(), dest.getMaxStackSize());
                int room = clamped - dest.getCount();
                if (count > room) {
                    failSession(StorageAccessCode.SLOT_OCCUPIED,
                            "slot " + requestedContainerSlot + " holds " + dest.getCount() + "x "
                                    + displayId + ", room for " + Math.max(0, room));
                    return;
                }
            } else {
                int capacity = Math.min(container.getMaxStackSize(), src.getMaxStackSize());
                if (count > capacity) {
                    failSession(StorageAccessCode.INSUFFICIENT_SPACE,
                            "slot " + requestedContainerSlot + " can hold at most " + capacity
                                    + "x " + displayId);
                    return;
                }
            }
            chosenSlot = requestedContainerSlot;
        } else {
            // Auto-place: first exact-same-stack slot with room for the full count, else the
            // first empty slot that accepts it (single-slot semantics — slot-precise ops never
            // span slots).
            chosenSlot = SLOT_AUTO;
            for (int i = 0; i < totalSlots; i++) {
                ItemStack dest = container.getItem(i);
                if (dest.isEmpty() || !container.canPlaceItem(i, probe) || !exactSameStack(dest, src)) {
                    continue;
                }
                int clamped = Math.min(container.getMaxStackSize(), dest.getMaxStackSize());
                if (clamped - dest.getCount() >= count) {
                    chosenSlot = i;
                    break;
                }
            }
            if (chosenSlot == SLOT_AUTO) {
                int clampedNew = Math.min(container.getMaxStackSize(), src.getMaxStackSize());
                for (int i = 0; i < totalSlots; i++) {
                    if (container.getItem(i).isEmpty() && container.canPlaceItem(i, probe)
                            && clampedNew >= count) {
                        chosenSlot = i;
                        break;
                    }
                }
            }
            if (chosenSlot == SLOT_AUTO) {
                failSession(StorageAccessCode.INSUFFICIENT_SPACE,
                        "no single container slot can hold " + count + "x " + displayId);
                return;
            }
        }

        // Transfer (same tick as validation; atomic). split preserves the source stack's data.
        ItemStack chunk = src.split(count);
        if (src.isEmpty()) {
            inv.main.set(requestedBotSlot, ItemStack.EMPTY);
        }
        ItemStack dest = container.getItem(chosenSlot);
        if (dest.isEmpty()) {
            container.setItem(chosenSlot, chunk);
        } else {
            dest.grow(count); // exact-same stack verified above
        }
        container.setChanged();
        inv.setChanged();

        movedCount = count;
        containerSlotUsed = chosenSlot;
        botSlotUsed = requestedBotSlot;
        outcome = Outcome.COMPLETED;
        code = StorageAccessCode.OK;
    }

    // ------------------------------------------------------------------ shared arithmetic

    /**
     * Exact-stack equality (item + data, count-normalized) for destination merges.
     * {@code ItemStack.matches} carries the same name/signature on both maintained branches, so
     * common stays byte-identical without new {@code isSameItemSameTags/Components} calls
     * (Decision 8); the existing quarantined helpers cannot test one specific slot.
     */
    private static boolean exactSameStack(ItemStack a, ItemStack b) {
        return ItemStack.matches(a.copyWithCount(1), b.copyWithCount(1));
    }

    /** Bot main capacity for {@code src}'s item: Item-level per-stack headroom + empty slots. */
    private static int botMainCapacityFor(LivingEntityInventory inv, ItemStack src) {
        int capacity = 0;
        int maxStack = Math.min(inv.getMaxStackSize(), src.getMaxStackSize());
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack s = inv.main.get(i);
            if (s.isEmpty()) {
                capacity += maxStack;
            } else if (s.is(src.getItem())) {
                capacity += Math.max(0, Math.min(inv.getMaxStackSize(), s.getMaxStackSize()) - s.getCount());
            }
        }
        return capacity;
    }

    /** Names up to three nearest non-empty slots for the {@code slot_empty} detail. */
    private String nonEmptyHint(Container container) {
        List<Integer> nonEmpty = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty()) {
                nonEmpty.add(i);
            }
        }
        if (nonEmpty.isEmpty()) {
            return "; container is empty";
        }
        nonEmpty.sort((a, b) -> Integer.compare(
                Math.abs(a - requestedContainerSlot), Math.abs(b - requestedContainerSlot)));
        StringBuilder sb = new StringBuilder("; nearest non-empty slots: ");
        for (int i = 0; i < nonEmpty.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(nonEmpty.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ task identity

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof SlotPreciseTransactionTask task)) {
            return false;
        }
        return task.direction == this.direction
                && Objects.equals(task.targetPos, this.targetPos)
                && task.requestedContainerSlot == this.requestedContainerSlot
                && task.requestedBotSlot == this.requestedBotSlot
                && task.count == this.count;
    }

    @Override
    protected String toDebugString() {
        return "SlotPreciseTransaction[" + direction.name().toLowerCase(Locale.ROOT)
                + " " + targetPos.toShortString()
                + " cSlot=" + requestedContainerSlot
                + " bSlot=" + requestedBotSlot
                + " x" + count + "]";
    }
}
