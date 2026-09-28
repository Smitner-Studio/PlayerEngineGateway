package com.player2.playerengine.tasks.construction;

import com.player2.playerengine.tasks.base.ITaskRequiresGrounded;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.automaton.api.process.IBuilderProcess;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {
   private final BlockPos pos;
   private final boolean sparePlayerPlaced;
   private boolean isClear;
   private boolean refused;

   public DestroyBlockTask(BlockPos pos) {
      this(pos, false);
   }

   private DestroyBlockTask(BlockPos pos, boolean sparePlayerPlaced) {
      this.pos = pos;
      this.sparePlayerPlaced = sparePlayerPlaced;
   }

   /**
    * A break that refuses a player-placed block: it finishes without breaking and reports
    * {@link #refused()}. clearArea marks the position schematic-air, which exempts it from the
    * builder's protection, so target selection alone would leave the break itself unguarded.
    */
   public static DestroyBlockTask sparingPlayerPlaced(BlockPos pos) {
      return new DestroyBlockTask(pos, true);
   }

   /** True when this break was refused because a player placed the block. */
   public boolean refused() {
      return this.refused;
   }

   @Override
   protected void onStart() {
      this.isClear = false;
      this.refused = false;
      if (this.guardTripped()) {
         return;
      }
      IBuilderProcess builder = this.controller.getBaritone().getBuilderProcess();
      builder.clearArea(this.pos, this.pos);
   }

   @Override
   protected Task onTick() {
      IBuilderProcess builder = this.controller.getBaritone().getBuilderProcess();
      if (this.refused || this.guardTripped()) {
         if (builder.isActive()) {
            builder.onLostControl();
         }
         return null;
      }
      if (!builder.isActive()) {
         this.isClear = true;
         return null;
      } else {
         this.setDebugState("Automatone is breaking the block.");
         return null;
      }
   }

   private boolean guardTripped() {
      if (this.sparePlayerPlaced && WorldHelper.isPlayerPlaced(this.controller, this.pos)) {
         this.refused = true;
         this.setDebugState("Refusing to break a player-placed block.");
      }
      return this.refused;
   }

   @Override
   protected void onStop(Task interruptTask) {
      IBuilderProcess builder = this.controller.getBaritone().getBuilderProcess();
      if (builder.isActive()) {
         builder.onLostControl();
      }
   }

   @Override
   public boolean isFinished() {
      return this.refused || this.isClear || this.controller.getWorld().isEmptyBlock(this.pos);
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof DestroyBlockTask task
         ? Objects.equals(task.pos, this.pos) && task.sparePlayerPlaced == this.sparePlayerPlaced
         : false;
   }

   @Override
   protected String toDebugString() {
      return "Destroying block at " + this.pos.toShortString();
   }
}
