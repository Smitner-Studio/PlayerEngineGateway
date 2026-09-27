package com.player2.playerengine.tasks.container;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.trackers.storage.ContainerCache;
import com.player2.playerengine.util.ItemTarget;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Shared, bounded deposit engine: wraps exactly ONE {@link StoreInContainerTask} against a KNOWN
 * {@link BlockPos} and guarantees finite termination with a visible, typed outcome.
 *
 * <p>This is the single bounded deposit core for the whole engine. Both the direct {@code deposit}
 * path ({@code StoreInAnyContainerTask}, after it resolves a container) and the agentic
 * {@code deposit_items} step ({@code DepositItemsTask}, after it reads/revalidates its C2 target)
 * delegate their actual insert through this task, so the robustness (absolute timeout + no-progress
 * stall guard + container-full classification) lives in exactly one place.
 *
 * <p>The lifecycle is lifted verbatim from {@code DepositItemsTask}'s former DEPOSITING phase:
 * <ul>
 *   <li>Absolute timeout enforced FIRST every tick, even while a child is active — {@link StoreInContainerTask}
 *       has no overall timeout, so without this a deposit against a full container could run forever.</li>
 *   <li>ONE {@link StoreInContainerTask} created lazily and held across ticks.</li>
 *   <li>Held-count no-progress tracking; on no decrease for {@link #STALL_TICKS_LIMIT} ticks while the
 *       child is active AND {@code getContainerAtPosition(pos).isFull()} -> partial (container full).</li>
 *   <li>Natural-finish classification: fully deposited / partial container-full / partial N remaining.</li>
 *   <li>Empty selection or zero held -> {@link Outcome#NOTHING}.</li>
 * </ul>
 *
 * <p>Intentional non-responsibilities: it does NOT read {@code AgenticExecutionContext}, does NOT
 * resolve/scan/place a container, and does NOT pick items. The caller supplies the container
 * {@link BlockPos} and the {@link ItemTarget}[] selection. The item move itself, including the
 * version-divergent {@code isSameItemSameComponents}/{@code isSameItemSameTags} line, lives in
 * {@link ContainerDeposit}.
 */
public final class BoundedContainerDepositTask extends Task implements DescribesProgress {

    /** Consecutive no-progress ticks (with the container full) before declaring a partial deposit. */
    private static final int STALL_TICKS_LIMIT = 40;

    /** Typed terminal classification of a bounded deposit. */
    public enum Outcome {
        DEPOSITED,
        PARTIAL_CONTAINER_FULL,
        PARTIAL_REMAINING,
        NOTHING,
        TIMEOUT
    }

    private final BlockPos containerPos;
    private final double timeoutSeconds;
    private final ItemTarget[] targets;
    private final Item[] targetItems;

    private boolean finished;
    private Outcome outcome;
    private String message = "";
    private long startMs;
    private Task child;

    private int initialHeldCount;
    private int lastHeldCount = Integer.MAX_VALUE;
    private int noProgressTicks;
    private int depositedCount;

    public BoundedContainerDepositTask(BlockPos containerPos, double timeoutSeconds, ItemTarget... targets) {
        this.containerPos = containerPos;
        this.timeoutSeconds = timeoutSeconds;
        this.targets = targets != null ? targets : new ItemTarget[0];
        this.targetItems = ItemTarget.getMatches(this.targets);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** The typed outcome, or {@code null} until the task finishes. */
    public Outcome outcome() {
        return outcome;
    }

    /** Count of items that left the inventory into the container. */
    public int depositedCount() {
        return depositedCount;
    }

    /** Count of selected items still held in the inventory at termination (0 unless partial). */
    public int remainingCount() {
        return heldCount();
    }

    /** Human-readable outcome detail, matching the agentic success-string vocabulary. */
    public String message() {
        return message;
    }

    @Override
    public String describeProgress() {
        return String.format(
                Locale.ROOT,
                "bounded-deposit target=%d,%d,%d targets=%d deposited=%d%s",
                containerPos.getX(),
                containerPos.getY(),
                containerPos.getZ(),
                targets.length,
                depositedCount,
                message.isEmpty() ? "" : " " + message);
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
        this.initialHeldCount = heldCount();
        this.lastHeldCount = initialHeldCount;
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        // Absolute deadline first, even while a child is active: StoreInContainerTask has no overall
        // timeout, so without this the deposit could run forever against a full container.
        if (elapsedSec() >= timeoutSeconds) {
            depositedCount = Math.max(0, initialHeldCount - heldCount());
            terminate(Outcome.TIMEOUT, "timeout");
            return null;
        }

        if (targets.length == 0 || targetItems.length == 0 || initialHeldCount <= 0) {
            terminate(Outcome.NOTHING, "nothing_to_deposit");
            return null;
        }

        if (child == null) {
            // Lazily create exactly ONE StoreInContainerTask and hold it across ticks.
            child = new StoreInContainerTask(containerPos, false, targets);
        }
        boolean childActive = child.isActive() && !child.stopped();

        // Natural finish: every target's count has left the inventory, or nothing left to move.
        // Guard on childActive: the child's `controller` is injected by the chain on its FIRST tick
        // (Task.tick -> this.controller = parentChain.controller), which only happens AFTER we return
        // the child below. StoreInContainerTask.isFinished() dereferences this.controller,
        // so inspecting a freshly-created (not-yet-ticked) child here would NPE on the very first
        // DEPOSITING tick. On that first tick childActive is false, so we skip straight to returning
        // the child (the chain starts it); the next tick inspects it safely.
        if (childActive && child.isFinished()) {
            classifyFinalOutcome();
            return null;
        }

        // Full/stall guard: StoreInContainerTask.isFinished() with count-based targets never returns
        // true while items remain in inventory and the container is full. Detect no progress on the
        // held count and, when the container is full, declare a partial deposit instead of hanging.
        int held = heldCount();
        if (held < lastHeldCount) {
            depositedCount += (lastHeldCount - held);
            lastHeldCount = held;
            noProgressTicks = 0;
        } else if (childActive) {
            noProgressTicks++;
        }

        if (held <= 0) {
            // Everything we targeted left the inventory; treat as a complete deposit.
            depositedCount = initialHeldCount;
            terminate(Outcome.DEPOSITED, "deposited " + depositedCount + " item(s)");
            return null;
        }

        if (noProgressTicks >= STALL_TICKS_LIMIT && containerIsFull()) {
            depositedCount = initialHeldCount - held;
            terminate(Outcome.PARTIAL_CONTAINER_FULL, "partial: container_full");
            return null;
        }

        this.setDebugState("depositing (" + (initialHeldCount - held) + "/" + initialHeldCount + ")");
        return child;
    }

    private void classifyFinalOutcome() {
        int remaining = heldCount();
        depositedCount = Math.max(0, initialHeldCount - remaining);
        if (remaining <= 0) {
            terminate(Outcome.DEPOSITED, "deposited " + depositedCount + " item(s)");
        } else if (containerIsFull()) {
            terminate(Outcome.PARTIAL_CONTAINER_FULL, "partial: container_full");
        } else {
            terminate(Outcome.PARTIAL_REMAINING, "partial: " + remaining + " remaining");
        }
    }

    private void terminate(Outcome result, String detail) {
        this.outcome = result;
        this.message = detail;
        this.finished = true;
        if (child != null && !child.stopped()) {
            child.stop(this);
        }
        child = null;
        this.setDebugState(detail);
    }

    /** Current held count, in the bot inventory, of all selected target items. */
    private int heldCount() {
        if (targetItems.length == 0) {
            return 0;
        }
        return this.controller.getItemStorage().getItemCount(targetItems);
    }

    private boolean containerIsFull() {
        Optional<ContainerCache> cache = this.controller.getItemStorage().getContainerAtPosition(containerPos);
        return cache.isPresent() && cache.get().isFull();
    }

    private double elapsedSec() {
        return (System.currentTimeMillis() - startMs) / 1000.0;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (finished) {
            return;
        }
        if (child != null && !child.stopped()) {
            child.stop(interruptTask);
        }
        child = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof BoundedContainerDepositTask task)) {
            return false;
        }
        return java.util.Objects.equals(task.containerPos, this.containerPos)
                && java.util.Arrays.equals((Object[]) task.targets, (Object[]) this.targets);
    }

    @Override
    protected String toDebugString() {
        return "BoundedContainerDeposit[" + containerPos.toShortString() + "]";
    }
}
