/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.player2.playerengine.automaton.api.entity;

import com.player2.playerengine.companion.CompanionRules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

public class LivingEntityHungerManager {
   private int foodLevel = 20;
   // Vanilla FoodData initialises saturation to 5.0F (not 20.0F); match that so a fresh/respawned bot
   // experiences saturation drain like a real player rather than starting over-saturated.
   private float foodSaturationLevel = 5.0F;
   private float exhaustion;
   private int foodTickTimer;
   private int prevFoodLevel = 20;

   // WS1 gate fields — pushed from AutomatoneEntity.tick() via setters before each update().
   // Defaulting to false keeps standalone/initialization ticks safe; the controller pushes the
   // configured value before normal companion ticks.
   private boolean hungerEnabled = false;
   private boolean deathByHungerMatchesDifficulty = true;

   public void setHungerEnabled(boolean hungerEnabled) {
      this.hungerEnabled = hungerEnabled;
   }

   public void setDeathByHungerMatchesDifficulty(boolean deathByHungerMatchesDifficulty) {
      this.deathByHungerMatchesDifficulty = deathByHungerMatchesDifficulty;
   }

   public void add(int food, float saturationModifier) {
      // 1.21.1: FoodProperties.saturation() is the pre-computed absolute saturation value
      // (already nutrition * modifier * 2.0, computed by FoodProperties.Builder.build() via
      // FoodConstants.saturationByModifier). Vanilla 1.21.1 FoodData.add(int,float) adds it
      // DIRECTLY without any extra multiply (decompiled FoodData.java:20-23). The saturationModifier
      // parameter here IS already the absolute saturation, so we add it as-is.
      // DO NOT add the '* food * 2.0F' multiply — that is ONLY correct on 1.20.1, where
      // getSaturationModifier() returns a raw modifier (e.g. 0.6) that still needs the multiply.
      // See 1.20.1 LivingEntityHungerManager.add() for the version-split counterpart.
      this.foodLevel = Math.min(food + this.foodLevel, 20);
      this.foodSaturationLevel = Math.min(this.foodSaturationLevel + saturationModifier, (float)this.foodLevel);
   }

   public void eat(Item item) {
      if (item.components().has(DataComponents.FOOD)) {
         FoodProperties foodComponent = item.components().get(DataComponents.FOOD);
         this.add(foodComponent.nutrition(), foodComponent.saturation());
      }
   }

   public void update(LivingEntity player) {
      // WS2 master gate: when hunger simulation is disabled, freeze the bar entirely (no drain, regen, or starve).
      if (!this.hungerEnabled) {
         // Survival parity replaces the freeze with a player whose food bar never drops, so health
         // still regenerates.
         if (CompanionRules.survivalParityEnabled()
               && this.tickWellFed(player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION), this.canFoodHeal(player))) {
            player.heal(1.0F);
         }
         return;
      }

      Difficulty difficulty = player.level().getDifficulty();
      this.prevFoodLevel = this.foodLevel;
      if (this.exhaustion > 4.0F) {
         this.exhaustion -= 4.0F;
         if (this.foodSaturationLevel > 0.0F) {
            this.foodSaturationLevel = Math.max(this.foodSaturationLevel - 1.0F, 0.0F);
         } else if (difficulty != Difficulty.PEACEFUL) {
            this.foodLevel = Math.max(this.foodLevel - 1, 0);
         }
      }

      boolean bl = player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
      if (bl && this.foodSaturationLevel > 0.0F && this.canFoodHeal(player) && this.foodLevel >= 20) {
         this.foodTickTimer++;
         if (this.foodTickTimer >= 10) {
            float f = Math.min(this.foodSaturationLevel, 6.0F);
            player.heal(f / 6.0F);
            this.addExhaustion(f);
            this.foodTickTimer = 0;
         }
      } else if (bl && this.foodLevel >= 18 && this.canFoodHeal(player)) {
         this.foodTickTimer++;
         if (this.foodTickTimer >= 80) {
            player.heal(1.0F);
            this.addExhaustion(6.0F);
            this.foodTickTimer = 0;
         }
      } else if (this.foodLevel <= 0) {
         this.foodTickTimer++;
         if (this.foodTickTimer >= 80) {
            // WS2 death gate: vanilla difficulty starve conditions kept intact; outer toggle allows
            // the bar to reach 0 without dealing damage (for servers that want no starvation deaths).
            if (this.deathByHungerMatchesDifficulty
                  && (player.getHealth() > 10.0F || difficulty == Difficulty.HARD
                      || (player.getHealth() > 1.0F && difficulty == Difficulty.NORMAL))) {
               player.hurt(player.damageSources().starve(), 1.0F);
            }

            this.foodTickTimer = 0;
         }
      } else {
         this.foodTickTimer = 0;
      }
   }

   /**
    * One tick of a food bar pinned full with no saturation: vanilla {@code FoodData}'s food >= 18
    * branch heals 1 every 80 ticks while natural regeneration is on and health is not full. The
    * exhaustion that heal would cost is dropped, so the bar never moves.
    *
    * @return whether to heal 1 this tick
    */
   public boolean tickWellFed(boolean naturalRegeneration, boolean canHeal) {
      this.foodLevel = 20;
      this.prevFoodLevel = 20;
      this.foodSaturationLevel = 0.0F;
      this.exhaustion = 0.0F;
      if (!naturalRegeneration || !canHeal) {
         this.foodTickTimer = 0;
         return false;
      }

      if (++this.foodTickTimer >= 80) {
         this.foodTickTimer = 0;
         return true;
      }

      return false;
   }

   public void readNbt(CompoundTag nbt) {
      if (nbt.contains("foodLevel", 99)) {
         this.foodLevel = nbt.getInt("foodLevel");
         this.foodTickTimer = nbt.getInt("foodTickTimer");
         this.foodSaturationLevel = nbt.getFloat("foodSaturationLevel");
         this.exhaustion = nbt.getFloat("foodExhaustionLevel");
      }
   }

   public void writeNbt(CompoundTag nbt) {
      nbt.putInt("foodLevel", this.foodLevel);
      nbt.putInt("foodTickTimer", this.foodTickTimer);
      nbt.putFloat("foodSaturationLevel", this.foodSaturationLevel);
      nbt.putFloat("foodExhaustionLevel", this.exhaustion);
   }

   public int getFoodLevel() {
      return this.foodLevel;
   }

   public int getPrevFoodLevel() {
      return this.prevFoodLevel;
   }

   public boolean isNotFull() {
      return this.foodLevel < 20;
   }

   public void addExhaustion(float exhaustion) {
      // WS3 gate: when hunger is disabled, exhaustion must not accumulate. The external hook sites
      // (PathExecutor sprint, BlockBreakHelper mine, InputOverrideHandler jump/swim) only hold an
      // EntityContext and cannot reach isHungerEnabled() cleanly, so the gate lives here — the single
      // choke point all hooks pass through. Without it, exhaustion would pile up while disabled and
      // cause a one-shot hunger hit on re-enable.
      if (!this.hungerEnabled) {
         return;
      }
      this.exhaustion = Math.min(this.exhaustion + exhaustion, 40.0F);
   }

   public float getExhaustion() {
      return this.exhaustion;
   }

   public float getSaturationLevel() {
      return this.foodSaturationLevel;
   }

   public void setFoodLevel(int foodLevel) {
      this.foodLevel = foodLevel;
   }

   public void setSaturationLevel(float saturationLevel) {
      this.foodSaturationLevel = saturationLevel;
   }

   public void setExhaustion(float exhaustion) {
      this.exhaustion = exhaustion;
   }

   public boolean canFoodHeal(LivingEntity entity) {
      return entity.getHealth() > 0.0F && entity.getHealth() < entity.getMaxHealth();
   }
}
