package com.player2.playerengine.tasks.container;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.trackers.storage.ContainerCache;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Direct "store into any nearby/creatable container" task. It resolves a container (scan nearest valid
 * chest/barrel/shulker, else place a held container, else obtain a chest) and then performs the actual
 * deposit through the shared {@link BoundedContainerDepositTask} so a chosen-container deposit can never
 * hang (the legacy path had no timeout / stall guard and could leave items stuck in hand forever).
 *
 * <p>It is bounded on BOTH axes:
 * <ul>
 *   <li>the chosen-container deposit is bounded by {@link BoundedContainerDepositTask}'s timeout/stall guard;</li>
 *   <li>the resolve loop itself is bounded by an OVERALL budget ({@link #OVERALL_TIMEOUT_SECONDS}) so it
 *       cannot spin forever when no container can be found, placed, or obtained.</li>
 * </ul>
 *
 * <p>It always terminates finitely and surfaces a terminal {@link Outcome} (success vs. could-not-complete
 * WITH a {@link #reason()}) that the direct {@code deposit} command reads to decide whether to finish
 * normally or escalate to {@code agentic} via the command Error path.
 */
public class StoreInAnyContainerTask extends Task {
   /**
    * Overall wall-clock budget for the whole resolve+deposit flow before declaring a timeout. It
    * covers the largest per-container budget ({@link #depositBudgetSeconds} at the travel cap).
    */
   private static final double OVERALL_TIMEOUT_SECONDS = 150.0;
   /** Per-chosen-container deposit budget, before the walk to the container is added. */
   private static final double DEPOSIT_TIMEOUT_SECONDS = 45.0;
   /** Walking pace the budget allows for: well under a sprint, for detours, doors and climbs. */
   private static final double TRAVEL_BLOCKS_PER_SECOND = 2.0;
   /**
    * FIXED travel cap (blocks) for the direct {@code deposit} container search. Derived from the DEFAULT
    * render distance: 3/5 * 12 chunks * 16 blocks/chunk = 115.2 -> 115. It is intentionally a constant,
    * NOT the live {@code getAgenticMaxTravelRadius()} setting, so the direct-deposit path stays
    * render-distance- and settings-independent: the bot must never walk to a container a player at default
    * view distance could not see (the live bug pathed ~255 blocks to a village chest). When the nearest
    * container is beyond this cap it is treated as "no container found" and the task falls through to
    * placing/crafting a LOCAL chest instead of trekking. The agentic resolve path keeps its own tunable
    * {@code getAgenticMaxTravelRadius()} cap; this constant deliberately does not share that knob.
    */
   private static final double DEPOSIT_TRAVEL_CAP_BLOCKS = 115.0;

   /** Terminal classification the {@code deposit} command reads to choose finish vs. escalate. */
   public enum Outcome {
      SUCCESS,
      FAILED
   }

   private final ItemTarget[] toStore;
   private final boolean getIfNotPresent;

   private boolean finished;
   private Outcome outcome;
   /** Machine-readable failure reason: no_container_available / container_full / unreachable / timeout. */
   private String reason = "";
   private long startMs;

   /** The bounded deposit against the chosen container; created once a container is resolved. */
   private BoundedContainerDepositTask depositCore;
   private BlockPos chosenContainer;
   /** Whether any container was ever resolved during this run (drives the timeout reason). */
   private boolean everChoseContainer;
   /**
    * Positions found FULL during this run. The bounded deposit can report a container full before its
    * {@link ContainerCache} reflects {@code isFull()}, so the scan predicate would otherwise re-pick the
    * exact same just-full chest and immediately stall again. We exclude every position in this set from
    * the scan so each re-scan only considers OTHER containers.
    */
   private final Set<BlockPos> fullContainers = new HashSet<>();

   public StoreInAnyContainerTask(boolean getIfNotPresent, ItemTarget... toStore) {
      this.getIfNotPresent = getIfNotPresent;
      this.toStore = toStore;
   }

   /** True once the task has terminated (success, nothing-to-store, or could-not-complete). */
   public boolean succeeded() {
      return outcome == Outcome.SUCCESS;
   }

   public Outcome outcome() {
      return outcome;
   }

   /** Machine-readable failure reason; empty on success. */
   public String reason() {
      return reason;
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

      ItemTarget[] itemsToStore = this.getItemsToStore(this.controller);
      if (itemsToStore.length == 0) {
         // Nothing (left) to store is a success: the items are gone from inventory.
         terminate(Outcome.SUCCESS, "");
         return null;
      }

      // Overall resolve-loop budget: never spin forever trying to find/place/obtain a container.
      if (elapsedSec() >= OVERALL_TIMEOUT_SECONDS) {
         // If a container was full earlier, keep that more specific reason; if we never resolved any
         // container at all, it is a no_container_available situation; otherwise a plain timeout.
         String why = "container_full".equals(reason)
            ? "container_full"
            : (everChoseContainer ? "timeout" : "no_container_available");
         terminate(Outcome.FAILED, why);
         return null;
      }

      // Optional pre-collection of the items to store (legacy getIfNotPresent behavior).
      if (this.getIfNotPresent) {
         for (ItemTarget target : this.toStore) {
            if (this.controller.getItemStorage().getItemCount(target) < target.getTargetCount()) {
               this.setDebugState("Collecting " + target + " before storing.");
               return TaskCatalogue.getItemTask(target);
            }
         }
      }

      // If a container has already been chosen, drive the bounded deposit against it.
      if (chosenContainer != null && depositCore != null) {
         if (depositCore.isFinished()) {
            classifyDepositOutcome(itemsToStore);
            return null;
         }
         this.setDebugState("Depositing into chosen container " + chosenContainer.toShortString());
         return depositCore;
      }

      Optional<BlockPos> closestContainer = this.controller.getBlockScanner().getNearestBlock(buildContainerPredicate(), StoreInContainerTask.CONTAINER_BLOCKS);
      if (closestContainer.isPresent() && beyondTravelCap(closestContainer.get())) {
         // Nearest container is too far for a direct deposit. Treat it as "no container found" so we fall
         // through to placing/crafting a LOCAL chest rather than trekking to it (live bug: ~255-block walk).
         closestContainer = Optional.empty();
      }
      if (closestContainer.isPresent()) {
         // Lock onto this container and deposit through the bounded engine so it can never hang.
         this.chosenContainer = closestContainer.get();
         this.everChoseContainer = true;
         this.depositCore = new BoundedContainerDepositTask(chosenContainer, depositBudgetSeconds(distanceTo(chosenContainer)), itemsToStore);
         this.setDebugState("Found a container; storing items.");
         return depositCore;
      } else {
         for (Block containerBlock : StoreInContainerTask.CONTAINER_BLOCKS) {
            if (this.controller.getItemStorage().hasItem(containerBlock.asItem())) {
               this.setDebugState("Placing a container nearby.");
               return new PlaceBlockNearbyTask(
                  pos -> !WorldHelper.isChest(this.controller, pos) || WorldHelper.isAir(this.controller, pos.above()), containerBlock
               );
            }
         }

         this.setDebugState("Obtaining a chest to store items.");
         return TaskCatalogue.getItemTask(Items.CHEST, 1);
      }
   }

   /**
    * Builds the "valid container to deposit into" scan predicate. Excludes blocked-top chests, dungeon
    * chests (when configured), {@link ContainerCache}-full containers, and — critically — any position
    * already recorded in {@link #fullContainers} this run, so a just-full chest whose cache has not yet
    * updated can never be re-picked into a fresh deposit that immediately stalls.
    */
   private Predicate<BlockPos> buildContainerPredicate() {
      return pos -> {
         if (this.fullContainers.contains(pos)) {
            return false;
         } else if (WorldHelper.isChest(this.controller, pos)
            && WorldHelper.isSolidBlock(this.controller, pos.above())
            && !WorldHelper.canBreak(this.controller, pos.above())) {
            return false;
         } else {
            Optional<ContainerCache> cache = this.controller.getItemStorage().getContainerAtPosition(pos);
            if (cache.isPresent() && cache.get().isFull()) {
               return false;
            } else {
               return WorldHelper.isChest(this.controller, pos) && this.controller.getModSettings().shouldAvoidSearchingForDungeonChests()
                  ? !this.isDungeonChest(this.controller, pos)
                  : true;
            }
         }
      };
   }

   /**
    * Maps a finished {@link BoundedContainerDepositTask} into the task's terminal outcome. A full deposit
    * is success; a partial/full container with items still in hand is a could-not-complete with a reason.
    */
   private void classifyDepositOutcome(ItemTarget[] itemsToStore) {
      switch (depositCore.outcome()) {
         case DEPOSITED, NOTHING -> {
            // Everything we targeted left the inventory (or there was nothing to move) -> success.
            terminate(Outcome.SUCCESS, "");
         }
         case PARTIAL_CONTAINER_FULL -> {
            // Items remain and the chosen container is full. Record this position as full so the next
            // scan can never re-pick it (its ContainerCache may not show isFull() yet), then re-scan
            // immediately for ANOTHER container. If none exists, terminate PROMPTLY as container_full
            // rather than spinning out the remaining overall budget against the same dead chest.
            if (chosenContainer != null) {
               this.fullContainers.add(chosenContainer);
            }
            this.reason = "container_full";
            this.chosenContainer = null;
            this.depositCore = null;

            Optional<BlockPos> next = this.controller.getBlockScanner().getNearestBlock(buildContainerPredicate(), StoreInContainerTask.CONTAINER_BLOCKS);
            if (next.isPresent() && beyondTravelCap(next.get())) {
               // The only other container is beyond the direct-deposit cap; do not trek to it.
               next = Optional.empty();
            }
            if (next.isPresent()) {
               this.chosenContainer = next.get();
               this.everChoseContainer = true;
               this.depositCore = new BoundedContainerDepositTask(chosenContainer, depositBudgetSeconds(distanceTo(chosenContainer)), itemsToStore);
               this.setDebugState("Container full; storing into another container " + chosenContainer.toShortString());
            } else {
               // No other reachable container holds the remaining items: fail now (escalates to agentic).
               terminate(Outcome.FAILED, "container_full");
            }
         }
         case PARTIAL_REMAINING -> {
            // The container was reached and partially filled but is NOT full; some items couldn't be
            // placed. Report a truthful reason (not "unreachable" — the container was reached).
            terminate(Outcome.FAILED, "partial_remaining");
         }
         case TIMEOUT -> {
            // Could not finish depositing into the chosen container within its budget.
            terminate(Outcome.FAILED, "timeout");
         }
      }
   }

   private void terminate(Outcome result, String why) {
      this.outcome = result;
      this.reason = why;
      this.finished = true;
      if (depositCore != null && !depositCore.stopped()) {
         depositCore.stop(this);
      }
      depositCore = null;
   }

   @Override
   public boolean isFinished() {
      return finished;
   }

   private ItemTarget[] getItemsToStore(PlayerEngineController controller) {
      return Arrays.stream(this.toStore).filter(target -> controller.getItemStorage().hasItem(target.getMatches())).toArray(ItemTarget[]::new);
   }

   /**
    * True if {@code target} is farther than the FIXED {@link #DEPOSIT_TRAVEL_CAP_BLOCKS} direct-deposit
    * travel cap from the bot. When true the caller treats the scan result as "no container found" and
    * falls through to placing/crafting a LOCAL chest instead of pathing to a distant one. Logged for
    * debugging so an ignored far container is visible in the log.
    */
   private boolean beyondTravelCap(BlockPos target) {
      boolean beyond = distanceTo(target) > DEPOSIT_TRAVEL_CAP_BLOCKS;
      if (beyond) {
         this.controller.log("[deposit] ignoring container at " + target.toShortString()
            + " beyond travel cap " + (int) DEPOSIT_TRAVEL_CAP_BLOCKS + " blocks; using a local chest instead");
      }
      return beyond;
   }

   private double distanceTo(BlockPos target) {
      Vec3 origin = this.controller.getPlayer().position();
      return Math.sqrt(origin.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
   }

   /**
    * Deposit budget for a container {@code distance} blocks away. The walk counts: a flat 45 s ran
    * out on containers across a base before the first item moved.
    */
   static double depositBudgetSeconds(double distance) {
      return DEPOSIT_TIMEOUT_SECONDS + Math.max(0.0, distance) / TRAVEL_BLOCKS_PER_SECOND;
   }

   static double overallTimeoutSeconds() {
      return OVERALL_TIMEOUT_SECONDS;
   }

   static double travelCapBlocks() {
      return DEPOSIT_TRAVEL_CAP_BLOCKS;
   }

   private boolean isDungeonChest(PlayerEngineController controller, BlockPos pos) {
      int range = 6;

      for (int dx = -range; dx <= range; dx++) {
         for (int dz = -range; dz <= range; dz++) {
            if (controller.getWorld().getBlockState(pos.offset(dx, 0, dz)).is(Blocks.SPAWNER)) {
               return true;
            }
         }
      }

      return false;
   }

   private double elapsedSec() {
      return (System.currentTimeMillis() - startMs) / 1000.0;
   }

   @Override
   protected void onStop(Task interruptTask) {
      if (depositCore != null && !depositCore.stopped()) {
         depositCore.stop(interruptTask);
      }
      depositCore = null;
   }

   @Override
   protected boolean isEqual(Task other) {
      return !(other instanceof StoreInAnyContainerTask task)
         ? false
         : task.getIfNotPresent == this.getIfNotPresent && Arrays.equals((Object[])task.toStore, (Object[])this.toStore);
   }

   @Override
   protected String toDebugString() {
      return "Storing in any container: " + Arrays.toString((Object[])this.toStore);
   }
}
