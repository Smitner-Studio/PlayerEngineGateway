package com.player2.playerengine.tasks.container;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.tasks.misc.EquipArmorTask;
import com.player2.playerengine.tasks.squashed.CataloguedResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

public class UpgradeInSmithingTableTask extends ResourceTask {
   private final ItemTarget tool;
   private final ItemTarget template;
   private final ItemTarget material;
   private final ItemTarget output;
   private BlockPos tablePos = null;

   /**
    * Chain-scoped material reservation ledger (WS3), or {@code null} for the standalone
    * catalogue path (3-arg constructor) and any non-agentic caller. When non-null:
    * <ul>
    *   <li>the gather-decision read subtracts each role's ledger reservation so a
    *       sibling-reserved item does not look "present" (triggers gather-more instead
    *       of stealing reserved stock);</li>
    *   <li>the three roles' concretely-resolved species are reserved just before the
    *       physical removals (belt-and-suspenders; the real protection is WS5's pre-seed).</li>
    * </ul>
    * Item-vs-ItemTarget impedance: each role is an {@link ItemTarget} that can match
    * multiple {@link Item}s, but the ledger is {@code Map<Item,Integer>}. Each role is
    * reduced to the single concrete species the inventory will actually yield (the
    * highest-stock matching held species — mirroring the first-match removal predicate)
    * before consulting/reserving. A pure tag-target with no held stock reserves nothing
    * (documented residual bound, OQ#10).
    */
   @Nullable
   private final MaterialReservationService ledger;
   /** True once the three roles have been reserved for this attempt (reserve-once guard). */
   private boolean reservedRoles = false;
   /**
    * Species → amount this task actually granted into the ledger, so the owning
    * {@code SmithDeferredTask} can release exactly what was reserved on its single
    * {@code terminate()} exit. Item-keyed (same species may appear once); summed defensively.
    */
   private final java.util.Map<Item, Integer> grantedReservations = new java.util.HashMap<>();

   /**
    * Typed terminal-result channel added by WS2.
    *
    * <p>Set at every exit point of {@link #onResourceTick} so that
    * {@code SmithDeferredTask} can read the current state and classify
    * role-specific {@code MISSING_*} / {@code NO_TABLE} outcomes.
    *
    * <ul>
    *   <li>{@link #IN_PROGRESS} — the task is still running (initial value).</li>
    *   <li>{@link #NEEDS_GATHER} — inputs are short; the task returned a
    *       {@code CataloguedResourceTask} gather sub-task.</li>
    *   <li>{@link #NO_TABLE} — no smithing table is reachable and one cannot
    *       be placed (no table in inventory, catalogue acquire path failed).</li>
    *   <li>{@link #UPGRADED} — the upgrade was performed and output count is met.</li>
    * </ul>
    */
   public enum SmithStepResult {
      IN_PROGRESS,
      NEEDS_GATHER,
      NO_TABLE,
      UPGRADED
   }

   /** Current terminal result, read by {@code SmithDeferredTask}. */
   private SmithStepResult stepResult = SmithStepResult.IN_PROGRESS;

   /**
    * Existing 3-arg constructor — keeps {@code TaskCatalogue.smith(...)} working
    * unchanged. Template defaults to {@code NETHERITE_UPGRADE_SMITHING_TEMPLATE}.
    */
   public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget output) {
      super(output);
      this.tool = new ItemTarget(tool, output.getTargetCount());
      this.material = new ItemTarget(material, output.getTargetCount());
      this.template = new ItemTarget(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, output.getTargetCount());
      this.output = output;
      this.ledger = null;
   }

   /**
    * New 4-arg constructor for the generic smithing path (WS2). Accepts a resolved
    * template {@link ItemTarget} instead of defaulting to the netherite template.
    * The {@code TaskCatalogue.smith(...)} path continues to use the 3-arg constructor.
    *
    * @param tool     the base item to upgrade (e.g. diamond pickaxe).
    * @param material the addition item (e.g. netherite ingot).
    * @param templateTarget the resolved template (e.g. netherite upgrade smithing template).
    * @param output   the desired output item (e.g. netherite pickaxe).
    */
   public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget templateTarget, ItemTarget output) {
      this(tool, material, templateTarget, output, null);
   }

   /**
    * Reservation-aware 5-arg constructor (WS3). Identical to the 4-arg generic path but
    * threads the chain-scoped {@link MaterialReservationService} so the gather-decision
    * read is reservation-aware and the three roles are reserved before the physical
    * removals. {@code ledger == null} is byte-for-byte identical to the 4-arg path.
    *
    * @param tool     the base item to upgrade (e.g. diamond pickaxe).
    * @param material the addition item (e.g. netherite ingot).
    * @param templateTarget the resolved template (e.g. netherite upgrade smithing template).
    * @param output   the desired output item (e.g. netherite pickaxe).
    * @param ledger   the run's reservation ledger, or {@code null} outside a run (no-op).
    */
   public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget templateTarget, ItemTarget output,
                                     @Nullable MaterialReservationService ledger) {
      super(output);
      this.tool = new ItemTarget(tool, output.getTargetCount());
      this.material = new ItemTarget(material, output.getTargetCount());
      this.template = new ItemTarget(templateTarget, output.getTargetCount());
      this.output = output;
      this.ledger = ledger;
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController controller) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController controller) {
      controller.getBehaviour().addProtectedItems(this.tool.getMatches());
      controller.getBehaviour().addProtectedItems(this.material.getMatches());
      controller.getBehaviour().addProtectedItems(this.template.getMatches());
      controller.getBehaviour().addProtectedItems(Items.SMITHING_TABLE);
   }

   /**
    * Returns the current typed terminal result (WS2 addition).
    *
    * <p>This accessor is set at every exit point of {@link #onResourceTick}, so
    * {@code SmithDeferredTask} can read the latest state each tick without subclassing or
    * threading additional state through the task hierarchy.
    *
    * <p>The catalogue path (3-arg constructor) ignores this value — it is purely additive.
    */
   public SmithStepResult getStepResult() {
      return stepResult;
   }

   @Override
   protected Task onResourceTick(PlayerEngineController controller) {
      int desiredOutputCount = this.output.getTargetCount();
      int currentOutputCount = controller.getItemStorage().getItemCount(this.output);
      if (currentOutputCount >= desiredOutputCount) {
         this.stepResult = SmithStepResult.UPGRADED;
         return null;
      } else {
         int needed = desiredOutputCount - currentOutputCount;
         // Reservation-aware gather decision (WS3): subtract each role's ledger reservation for the
         // concretely-resolved species so a sibling-reserved item does not look "present" — the bot
         // gathers more instead of stealing reserved stock. ledger == null falls back to the raw
         // ItemTarget-overload count (byte-identical to the pre-WS3 behaviour).
         if (effectiveFree(controller, this.tool) < needed
            || effectiveFree(controller, this.material) < needed
            || effectiveFree(controller, this.template) < needed) {
            this.setDebugState("Getting materials for upgrade");
            this.stepResult = SmithStepResult.NEEDS_GATHER;
            return new CataloguedResourceTask(new ItemTarget(this.tool, needed), new ItemTarget(this.material, needed), new ItemTarget(this.template, needed));
         } else if (StorageHelper.isArmorEquipped(controller, this.tool.getMatches())) {
            this.setDebugState("Unequipping armor before upgrading.");
            // Still in progress — unequipping armor is part of the upgrade preparation.
            this.stepResult = SmithStepResult.IN_PROGRESS;
            return new EquipArmorTask(new ItemTarget[0]);
         } else {
            if (this.tablePos == null || !controller.getWorld().getBlockState(this.tablePos).is(Blocks.SMITHING_TABLE)) {
               Optional<BlockPos> nearestTable = controller.getBlockScanner().getNearestBlock(Blocks.SMITHING_TABLE);
               if (!nearestTable.isPresent()) {
                  if (controller.getItemStorage().hasItem(Items.SMITHING_TABLE)) {
                     this.setDebugState("Placing smithing table.");
                     this.stepResult = SmithStepResult.IN_PROGRESS;
                     return new PlaceBlockNearbyTask(Blocks.SMITHING_TABLE);
                  }

                  // No table in range, no table in inventory, catalogue acquire returned — try once.
                  // SmithDeferredTask treats persistent NO_TABLE from catalogue as terminal.
                  this.setDebugState("Obtaining smithing table.");
                  this.stepResult = SmithStepResult.NO_TABLE;
                  return TaskCatalogue.getItemTask(Items.SMITHING_TABLE, 1);
               }

               this.tablePos = nearestTable.get();
            }

            if (!this.tablePos
               .closerThan(
                  new Vec3i((int)controller.getEntity().position().x, (int)controller.getEntity().position().y, (int)controller.getEntity().position().z), 4.5
               )) {
               this.setDebugState("Going to smithing table.");
               this.stepResult = SmithStepResult.IN_PROGRESS;
               return ContainerApproach.task(this.tablePos);
            } else {
               this.setDebugState("Upgrading item...");
               // Reserve the three roles' concretely-resolved species just before the physical
               // removals (WS3 belt-and-suspenders; the real protection is WS5's plan-time pre-seed).
               // Reserve-once per attempt so we never double-count across ticks.
               if (this.ledger != null && !this.reservedRoles) {
                  reserveRole(controller, this.template);
                  reserveRole(controller, this.tool);
                  reserveRole(controller, this.material);
                  this.reservedRoles = true;
               }
               LivingEntityInventory inventory = ((IInventoryProvider)controller.getEntity()).getLivingInventory();
               inventory.remove(stack -> this.template.matches(stack.getItem()), 1, inventory);
               inventory.remove(stack -> this.tool.matches(stack.getItem()), 1, inventory);
               inventory.remove(stack -> this.material.matches(stack.getItem()), 1, inventory);
               inventory.insertStack(new ItemStack(this.output.getMatches()[0], 1));
               controller.getItemStorage().registerSlotAction();
               this.stepResult = SmithStepResult.UPGRADED;
               return null;
            }
         }
      }
   }

   @Override
   protected void onResourceStop(PlayerEngineController controller, Task interruptTask) {
      controller.getBehaviour().pop();
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return !(other instanceof UpgradeInSmithingTableTask task)
         ? false
         : task.tool.equals(this.tool)
               && task.output.equals(this.output)
               && task.material.equals(this.material)
               && task.template.equals(this.template);
   }

   @Override
   protected String toDebugStringName() {
      return "Upgrading in Smithing Table";
   }

   public ItemTarget getMaterials() {
      return this.material;
   }

   public ItemTarget getTools() {
      return this.tool;
   }

   public ItemTarget getTemplate() {
      return this.template;
   }

   // -------------------------------------------------------------------------
   // WS3 reservation helpers
   // -------------------------------------------------------------------------

   /**
    * Reduces a multi-species {@link ItemTarget} role to the single concrete {@link Item} the
    * inventory will actually yield, so the {@code Item}-keyed ledger reserves the exact species
    * the step draws. Mirrors the first-match removal predicate ({@code role.matches(...)}) by
    * picking the <em>highest-stock matching held species</em> among {@code role.getMatches()}:
    *
    * <ul>
    *   <li>single-species role → that item;</li>
    *   <li>multi-species role with held stock → the matching species the bot holds the most of
    *       (the one a removal/gather scan reports stock for);</li>
    *   <li>pure tag-target with no held stock → {@code null} (reserve nothing — documented
    *       residual bound, OQ#10). Never reserves a blind {@code getMatches()[0]} that the bot
    *       may not hold while leaving the drawn species unprotected.</li>
    * </ul>
    */
   @Nullable
   private Item resolveSpecies(PlayerEngineController controller, ItemTarget role) {
      Item[] matches = role.getMatches();
      if (matches == null || matches.length == 0) {
         return null;
      }
      if (matches.length == 1) {
         return matches[0];
      }
      Item best = null;
      int bestCount = 0;
      for (Item candidate : matches) {
         if (candidate == null || candidate == Items.AIR) {
            continue;
         }
         int count = controller.getItemStorage().getItemCount(candidate);
         if (count > bestCount) {
            bestCount = count;
            best = candidate;
         }
      }
      // No matching species held: reserve nothing for this role (residual bound), rather than
      // reserve a wrong/blind species.
      return best;
   }

   /**
    * Reservation-aware free count for a role used by the gather decision. Reduces the role to its
    * concrete species and returns {@code ledger.free(species)} (held minus this run's reservation);
    * when the ledger is null or the species cannot be pinned, falls back to the raw
    * {@code ItemTarget}-overload count (byte-identical to the pre-WS3 read).
    */
   private int effectiveFree(PlayerEngineController controller, ItemTarget role) {
      int raw = controller.getItemStorage().getItemCount(role);
      if (this.ledger == null) {
         return raw;
      }
      Item species = resolveSpecies(controller, role);
      if (species == null) {
         // Pure tag-target with no held stock — nothing reserved for it; use the raw broad count.
         return raw;
      }
      return this.ledger.free(controller, species);
   }

   /**
    * Reserves one unit of a role's concrete species into the ledger (belt-and-suspenders before the
    * physical removal). Tolerates an unpinnable species (reserves nothing). Records the granted
    * amount so {@code SmithDeferredTask.terminate()} can release exactly what was reserved.
    */
   private void reserveRole(PlayerEngineController controller, ItemTarget role) {
      if (this.ledger == null) {
         return;
      }
      Item species = resolveSpecies(controller, role);
      if (species == null) {
         return;
      }
      int granted = this.ledger.reserve(controller, species, 1);
      if (granted > 0) {
         this.grantedReservations.merge(species, granted, Integer::sum);
      }
   }

   /**
    * Releases every reservation this task granted (called from {@code SmithDeferredTask.terminate()},
    * the single terminal exit). No-op outside a run. Idempotent: clears the granted map after release.
    */
   public void releaseReservations() {
      if (this.ledger == null || this.grantedReservations.isEmpty()) {
         return;
      }
      for (java.util.Map.Entry<Item, Integer> e : this.grantedReservations.entrySet()) {
         this.ledger.release(e.getKey(), e.getValue());
      }
      this.grantedReservations.clear();
   }
}
