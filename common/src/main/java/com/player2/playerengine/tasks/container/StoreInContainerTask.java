package com.player2.playerengine.tasks.container;

import com.player2.playerengine.util.Debug;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

public class StoreInContainerTask extends Task {
   public static final Block[] CONTAINER_BLOCKS = Stream.concat(
         Arrays.stream(new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL}), Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))
      )
      .toArray(Block[]::new);
   private final BlockPos containerPos;
   private final boolean getIfNotPresent;
   private final ItemTarget[] toStore;
   /**
    * Per target, how many items still have to leave the inventory. Counting what this task moved,
    * not what the container holds, lets a deposit into a container that already has some of the
    * item move everything asked for.
    */
   private final int[] owed;

   public StoreInContainerTask(BlockPos targetContainer, boolean getIfNotPresent, ItemTarget... toStore) {
      this.containerPos = targetContainer;
      this.getIfNotPresent = getIfNotPresent;
      this.toStore = toStore;
      this.owed = Arrays.stream(toStore).mapToInt(ItemTarget::getTargetCount).toArray();
   }

   @Override
   protected void onStart() {
      for (ItemTarget target : this.toStore) {
         this.controller.getBehaviour().addProtectedItems(target.getMatches());
      }
   }

   @Override
   protected Task onTick() {
      if (this.isFinished()) {
         return null;
      } else {
         if (this.getIfNotPresent) {
            for (ItemTarget target : this.toStore) {
               int needed = target.getTargetCount();
               if (this.controller.getItemStorage().getItemCount(target) < needed) {
                  this.setDebugState("Collecting " + target + " first.");
                  return TaskCatalogue.getItemTask(target);
               }
            }
         }

         if (!this.containerPos
            .closerThan(
               new Vec3i(
                  (int)this.controller.getEntity().position().x, (int)this.controller.getEntity().position().y, (int)this.controller.getEntity().position().z
               ),
               4.5
            )) {
            this.setDebugState("Going to container");
            return ContainerApproach.task(this.containerPos);
         } else if (!(this.controller.getWorld().getBlockEntity(this.containerPos) instanceof RandomizableContainerBlockEntity container)) {
            Debug.logWarning("Block at " + this.containerPos + " is not a lootable container. Stopping.");
            return null;
         } else {
            LivingEntityInventory inventory = ((IInventoryProvider)this.controller.getEntity()).getLivingInventory();
            this.controller.getItemStorage().containers.WritableCache(this.controller, this.containerPos);
            this.setDebugState("Storing items");

            for (int t = 0; t < this.toStore.length; t++) {
               int moved = ContainerDeposit.moveOneStack(inventory, container, this.toStore[t], this.owed[t]);
               if (moved > 0) {
                  this.owed[t] -= moved;
                  container.setChanged();
                  this.controller.getItemStorage().registerSlotAction();
                  return null;
               }
            }

            return null;
         }
      }
   }

   @Override
   public boolean isFinished() {
      for (int t = 0; t < this.toStore.length; t++) {
         if (this.owed[t] > 0 && this.controller.getItemStorage().getItemCount(this.toStore[t]) > 0) {
            return false;
         }
      }
      return true;
   }

   @Override
   protected void onStop(Task interruptTask) {
      this.controller.getBehaviour().pop();
   }

   @Override
   protected boolean isEqual(Task other) {
      return !(other instanceof StoreInContainerTask task)
         ? false
         : Objects.equals(task.containerPos, this.containerPos)
            && task.getIfNotPresent == this.getIfNotPresent
            && Arrays.equals((Object[])task.toStore, (Object[])this.toStore);
   }

   @Override
   protected String toDebugString() {
      return "Storing in container[" + this.containerPos.toShortString() + "] " + Arrays.toString((Object[])this.toStore);
   }
}
