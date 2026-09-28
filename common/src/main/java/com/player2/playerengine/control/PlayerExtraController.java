package com.player2.playerengine.control;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.LivingEntityHungerManager;
import com.player2.playerengine.eventbus.EventBus;
import com.player2.playerengine.eventbus.events.BlockBreakingCancelEvent;
import com.player2.playerengine.eventbus.events.BlockBreakingEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

public class PlayerExtraController {
   private final PlayerEngineController mod;
   private BlockPos blockBreakPos;

   public PlayerExtraController(PlayerEngineController mod) {
      this.mod = mod;
      EventBus.subscribe(BlockBreakingEvent.class, evt -> this.onBlockBreak(evt.blockPos));
      EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> this.onBlockStopBreaking());
   }

   private void onBlockBreak(BlockPos pos) {
      this.blockBreakPos = pos;
   }

   private void onBlockStopBreaking() {
      this.blockBreakPos = null;
   }

   public BlockPos getBreakingBlockPos() {
      return this.blockBreakPos;
   }

   public boolean isBreakingBlock() {
      return this.blockBreakPos != null;
   }

   public boolean inRange(Entity entity) {
      return this.mod.getPlayer().closerThan(entity, this.mod.getModSettings().getEntityReachRange());
   }

   /**
    * Player2NPC's doHurtTarget always deals full damage. A player's hit is scaled by how charged
    * the attack is and resets the charge, so wrap the call in a transient damage multiplier.
    */
   private void hurtWithCooldown(Entity entity) {
      net.minecraft.world.entity.LivingEntity self = this.mod.getPlayer();
      com.player2.playerengine.mixins.LivingEntityMixin ticker = (com.player2.playerengine.mixins.LivingEntityMixin)self;
      float scale = com.player2.playerengine.companion.SurvivalCombat.strengthScale(
         ticker.getLastAttackedTicks(),
         com.player2.playerengine.companion.SurvivalCombat.attackDelayTicks(
            com.player2.playerengine.companion.SurvivalCombat.attackSpeed(self.getMainHandItem())));
      net.minecraft.world.entity.ai.attributes.AttributeInstance damage =
         self.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
      if (damage != null) {
         damage.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            com.player2.playerengine.companion.SurvivalCombat.COOLDOWN_MODIFIER,
            com.player2.playerengine.companion.SurvivalCombat.damageMultiplier(scale) - 1.0,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
      }

      try {
         self.doHurtTarget(entity);
      } finally {
         if (damage != null) {
            damage.removeModifier(com.player2.playerengine.companion.SurvivalCombat.COOLDOWN_MODIFIER);
         }

         ticker.setLastAttackedTicks(0);
      }
   }

   public void attack(Entity entity) {
      if (com.player2.playerengine.companion.NoPvp.isProtected(entity)) {
         return;
      }
      if (this.inRange(entity)) {
         if (com.player2.playerengine.companion.CompanionRules.survivalParityEnabled()) {
            this.hurtWithCooldown(entity);
         } else {
            this.mod.getPlayer().doHurtTarget(entity);
         }
         this.mod.getPlayer().swing(InteractionHand.MAIN_HAND);
         // Exhaustion: attacking costs 0.1 per hit (vanilla FoodConstants.EXHAUSTION_ATTACK)
         if (this.mod.getModSettings().isHungerEnabled()) {
            LivingEntityHungerManager hm = this.mod.getBaritone().getEntityContext().hungerManager();
            if (hm != null) {
               hm.addExhaustion(0.1F);
            }
         }
      }
   }
}
