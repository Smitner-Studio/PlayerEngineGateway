package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.multiversion.FoodComponentWrapper;
import com.player2.playerengine.multiversion.item.ItemVer;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.misc.EatFoodTask;
import com.player2.playerengine.tasks.resources.CollectFoodTask;
import com.player2.playerengine.tasks.speedrun.DragonBreathTracker;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.util.helpers.ConfigHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FoodChain extends SingleTaskChain {
   private static FoodChain.FoodChainConfig config;
   private static boolean hasFood;
   private final DragonBreathTracker dragonBreathTracker = new DragonBreathTracker();
   private boolean isTryingToEat = false;
   private boolean requestFillup = false;
   private boolean needsToCollectFood = false;
   private Optional<Item> cachedPerfectFood = Optional.empty();
   private boolean shouldStop = false;

   // WS8: starving-with-no-food notice fields.
   // Distinct from the auto-eat threshold — this threshold means the bot is genuinely starving.
   private static final int STARVING_FOOD_LEVEL = FoodRecoveryInfoPolicy.STARVING_FOOD_LEVEL;
   // The episode stays active while supplied food is being eaten. Recovery is reported only after
   // hunger rises above the starvation threshold and automatic eating has quiesced.
   private boolean starvationEpisodeActive = false;
   private boolean recoveryInfoPending = false;

   public FoodChain(TaskRunner runner) {
      super(runner);
   }

   @Override
   protected void onTaskFinish(PlayerEngineController controller) {
      // When an EatFoodTask finishes, clear the eating flag and restore shield if needed.
      if (this.isTryingToEat) {
         this.isTryingToEat = false;
         if (controller.getItemStorage().hasItem(Items.SHIELD) && !controller.getItemStorage().hasItemInOffhand(controller, Items.SHIELD)) {
            controller.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
         }
      }
   }

   private void startEat(PlayerEngineController controller, Item food) {
      // WS6: dispatch the animation-aware EatFoodTask instead of holding CLICK_RIGHT.
      // setTask() is idempotent for the same food (EatFoodTask.isEqual compares foodItem by identity),
      // so calling this every tick while needsToEat()/requestFillup is true will not restart a
      // running eat — it only starts a fresh task when the previous one finishes.
      this.setTask(new EatFoodTask(food));
      this.isTryingToEat = true;
      this.requestFillup = true;
   }

   private void stopEat(PlayerEngineController controller) {
      if (this.isTryingToEat) {
         // WS6: no CLICK_RIGHT to release — the animation task manages its own vanilla use-item state.
         // Stop any in-progress EatFoodTask so it can call stopUsingItem() if mid-animation.
         this.setTask(null);
         this.isTryingToEat = false;
         this.requestFillup = false;
         if (controller.getItemStorage().hasItem(Items.SHIELD) && !controller.getItemStorage().hasItemInOffhand(controller, Items.SHIELD)) {
            controller.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
         }
      }
   }

   public boolean isTryingToEat() {
      return this.isTryingToEat;
   }

   @Override
   public float getPriority() {
      if (this.controller == null) {
         return Float.NEGATIVE_INFINITY;
      } else if (WorldHelper.isInNetherPortal(this.controller)) {
         this.stopEat(this.controller);
         return Float.NEGATIVE_INFINITY;
      } else if (this.controller.getMobDefenseChain().isShielding()) {
         this.stopEat(this.controller);
         return Float.NEGATIVE_INFINITY;
      } else {
         this.dragonBreathTracker.updateBreath(this.controller);

         for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer(this.controller.getEntity())) {
            if (this.dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
               this.stopEat(this.controller);
               return Float.NEGATIVE_INFINITY;
            }
         }

         // WS6: gate on both isAutoEat() and isHungerEnabled() — when hunger is disabled,
         // auto-eat is suppressed (the whole hunger simulation is frozen, so eating is pointless).
         if (this.controller.getModSettings().isAutoEat()
               && this.controller.getModSettings().isHungerEnabled()
               && !this.controller.getEntity().isInLava() && !this.shouldStop) {
            if (this.controller.getMLGBucketChain().doneMLG() && !this.controller.getMLGBucketChain().isFalling(this.controller)) {
               Tuple<Integer, Optional<Item>> calculation = this.calculateFood(this.controller);
               int foodScore = (Integer)calculation.getA();
               this.cachedPerfectFood = (Optional<Item>)calculation.getB();
               hasFood = foodScore > 0;
               if (this.requestFillup && this.controller.getBaritone().getEntityContext().hungerManager().getFoodLevel() >= 20) {
                  this.requestFillup = false;
               }

               if (!hasFood) {
                  this.requestFillup = false;
               }

               if (hasFood && (this.needsToEat() || this.requestFillup) && this.cachedPerfectFood.isPresent()) {
                  // WS6: route eating through the animation-aware EatFoodTask.
                  // One item at a time — the task eats one item, then requestFillup keeps
                  // re-triggering until getFoodLevel() >= 20.
                  this.startEat(this.controller, this.cachedPerfectFood.get());
               } else {
                  this.stopEat(this.controller);
               }

               // WS8 (revised): starving-with-no-food is now an EDGE-TRIGGERED episode, never a
               // present-tense notice appended to permanent conversation history. The model reads the
               // current state from the live, per-companion AgentStatus.isStarving() flag (rebuilt fresh
               // every turn, never persisted, self-correcting the instant food recovers), so nothing
               // present-tense can go stale or spam. Here we only detect episode edges. `hasFood` was
               // just set for THIS companion at L112 in the same single-threaded pass — correct to read.
               boolean isStarving = this.controller.getModSettings().isHungerEnabled() && !hasFood
                     && this.controller.getBaritone().getEntityContext().hungerManager().getFoodLevel() <= STARVING_FOOD_LEVEL;
               FoodRecoveryInfoPolicy.State recovery = FoodRecoveryInfoPolicy.advance(
                     this.starvationEpisodeActive,
                     this.recoveryInfoPending,
                     isStarving,
                     this.controller.getBaritone().getEntityContext().hungerManager().getFoodLevel(),
                     this.isTryingToEat,
                     this.requestFillup);
               this.starvationEpisodeActive = recovery.episodeActive();
               this.recoveryInfoPending = recovery.recoveryInfoPending();
               if (recovery.notifyPlayer()) {
                  // RISING edge: notify the player ONCE per episode (player-facing, keyed). No enqueueInfo
                  // — the model learns the current state from the live status flag, not history.
                  this.controller.reportAgenticProgress(Component.translatable("message.playerengine.food.starving"), true);
               }
               if (recovery.deferModelInfo()) {
                  // One past-tense episodic note for the MODEL only (raw English, model-facing).
                  // Passive delivery cannot trigger a reaction turn or interrupt the resumed task.
                  AiConversationFeedback.deferInfo(this.controller,
                      "Earlier you were starving with no food, but you have since recovered.");
               }

               // When the EatFoodTask is running, return a positive priority to stay active.
               // Eating is prioritized over collecting food — eat what we have first, then collect.
               if (this.isTryingToEat && this.mainTask != null && !this.mainTask.isFinished()) {
                  return 55.0F;
               }

               PlayerEngineSettings settings = this.controller.getModSettings();
               if (this.needsToCollectFood || foodScore < settings.getMinimumFoodAllowed()) {
                  this.needsToCollectFood = foodScore < settings.getFoodUnitsToCollect();
                  if (this.needsToCollectFood) {
                     this.setTask(new CollectFoodTask(settings.getFoodUnitsToCollect()));
                     return 55.0F;
                  }
               }

               this.setTask(null);
               return Float.NEGATIVE_INFINITY;
            } else {
               this.stopEat(this.controller);
               return Float.NEGATIVE_INFINITY;
            }
         } else {
            this.stopEat(this.controller);
            return Float.NEGATIVE_INFINITY;
         }
      }
   }

   @Override
   public boolean isActive() {
      // FoodChain must always evaluate its priority so that auto-eat can trigger even when no
      // CollectFoodTask is running (i.e. when mainTask is null). This mirrors the pattern used by
      // MobDefenseChain, which also needs to fire without a pre-existing task.
      return true;
   }

   @Override
   public String getName() {
      return "Food chain";
   }

   @Override
   protected void onStop() {
      super.onStop();
      if (this.controller != null) {
         this.stopEat(this.controller);
      }
   }

   public boolean needsToEat() {
      if (hasFood && !this.shouldStop) {
         LivingEntity player = this.controller.getEntity();
         int foodLevel = this.controller.getBaritone().getEntityContext().hungerManager().getFoodLevel();
         float health = player.getHealth();
         if (foodLevel >= 20) {
            return false;
         } else if (health <= 10.0F) {
            return true;
         } else if (player.isOnFire() || player.hasEffect(MobEffects.WITHER) || health < config.alwaysEatWhenWitherOrFireAndHealthBelow) {
            return true;
         } else if (foodLevel <= config.alwaysEatWhenBelowHunger) {
            return true;
         } else if (health < config.alwaysEatWhenBelowHealth) {
            return true;
         } else if (foodLevel < config.alwaysEatWhenBelowHungerAndPerfectFit && this.cachedPerfectFood.isPresent()) {
            int need = 20 - foodLevel;
            Item best = this.cachedPerfectFood.get();
            int fills = Optional.ofNullable(ItemVer.getFoodComponent(best)).map(FoodComponentWrapper::getHunger).orElse(-1);
            return fills > 0 && fills <= need;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private Tuple<Integer, Optional<Item>> calculateFood(PlayerEngineController controller) {
      Item bestFood = null;
      double bestFoodScore = Double.NEGATIVE_INFINITY;
      int foodTotal = 0;
      LivingEntity player = controller.getEntity();
      float health = player.getHealth();
      float hunger = controller.getBaritone().getEntityContext().hungerManager().getFoodLevel();
      float saturation = controller.getBaritone().getEntityContext().hungerManager().getSaturationLevel();

      for (ItemStack stack : controller.getItemStorage().getItemStacksPlayerInventory(true)) {
         if (ItemVer.isFood(stack) && !stack.is(Items.SPIDER_EYE)) {
            FoodComponentWrapper food = ItemVer.getFoodComponent(stack.getItem());
            if (food != null) {
               float hungerIfEaten = Math.min(hunger + food.getHunger(), 20.0F);
               float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.getSaturationModifier());
               float gainedSaturation = saturationIfEaten - saturation;
               float gainedHunger = hungerIfEaten - hunger;
               float hungerWasted = food.getHunger() - gainedHunger;
               float score = gainedSaturation * 2.0F - hungerWasted;
               if (stack.is(Items.ROTTEN_FLESH)) {
                  score -= 100.0F;
               }

               if (score > bestFoodScore) {
                  bestFoodScore = score;
                  bestFood = stack.getItem();
               }

               foodTotal += food.getHunger() * stack.getCount();
            }
         }
      }

      return new Tuple(foodTotal, Optional.ofNullable(bestFood));
   }

   public boolean hasFood() {
      return hasFood;
   }

   /**
    * Live, self-contained, PER-COMPANION starving check for the transient AgentStatus flag. Recomputes
    * THIS companion's own inventory food via {@link #calculateFood} rather than reading the shared
    * {@code private static hasFood} (which is last-writer-wins across all companions and only valid for
    * the current companion during its own getPriority pass) — so a companion that has food can never
    * report starving just because a sibling has none. Rebuilt every model turn into the throwaway status
    * wrapper, so it self-corrects the instant food recovers and can never go stale.
    */
   public boolean isStarving() {
      if (this.controller == null || !this.controller.getModSettings().isHungerEnabled()) {
         return false;
      }
      int ownFoodScore = this.calculateFood(this.controller).getA();
      int food = this.controller.getBaritone().getEntityContext().hungerManager().getFoodLevel();
      return ownFoodScore <= 0 && food <= STARVING_FOOD_LEVEL;
   }

   public void shouldStop(boolean shouldStopInput) {
      this.shouldStop = shouldStopInput;
   }

   public boolean isShouldStop() {
      return this.shouldStop;
   }

   static {
      ConfigHelper.loadConfig(
         "configs/food_chain_settings.json", FoodChain.FoodChainConfig::new, FoodChain.FoodChainConfig.class, newConfig -> config = newConfig
      );
   }

   static class FoodChainConfig {
      public int alwaysEatWhenWitherOrFireAndHealthBelow = 6;
      public int alwaysEatWhenBelowHunger = 10;
      public int alwaysEatWhenBelowHealth = 14;
      public int alwaysEatWhenBelowHungerAndPerfectFit = 15;
   }
}
