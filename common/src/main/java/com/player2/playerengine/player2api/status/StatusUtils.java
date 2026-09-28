package com.player2.playerengine.player2api.status;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.agentic.AgenticRunSnapshot;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.Optional;
import com.player2.playerengine.automaton.api.entity.IAutomatone;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

public class StatusUtils {
   public static String getInventoryString(PlayerEngineController mod) {
      Map<String, Integer> counts = new HashMap<>();

      for (int i = 0; i < mod.getBaritone().getEntityContext().inventory().getContainerSize(); i++) {
         ItemStack stack = mod.getBaritone().getEntityContext().inventory().getItem(i);
         if (!stack.isEmpty()) {
            String name = ItemHelper.stripItemName(stack.getItem());
            counts.put(name, counts.getOrDefault(name, 0) + stack.getCount());
         }
      }

      ObjectStatus status = new ObjectStatus();

      for (Entry<String, Integer> entry : counts.entrySet()) {
         status.add(entry.getKey(), entry.getValue().toString());
      }

      return status.toString();
   }

   public static String getDimensionString(PlayerEngineController mod) {
      return mod.getWorld().dimension().location().toString().replace("minecraft:", "");
   }

   public static String getWeatherString(PlayerEngineController mod) {
      boolean isRaining = mod.getWorld().isRaining();
      boolean isThundering = mod.getWorld().isThundering();
      ObjectStatus status = new ObjectStatus().add("isRaining", String.valueOf(isRaining)).add("isThundering",
            String.valueOf(isThundering));
      return status.toString();
   }

   public static String getSpawnPosString(PlayerEngineController mod) {
      BlockPos spawnPos = mod.getWorld().getSharedSpawnPos();
      return String.format("(%d, %d, %d)", spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
   }

   public static String getTaskStatusString(PlayerEngineController mod) {
      String noTask = "No tasks currently running.";
      Optional<AgenticRunSnapshot> agentic = AgenticRunRegistry.snapshot(mod.getEntity().getUUID());
      if (agentic.isPresent()) {
         AgenticRunSnapshot run = agentic.get();
         if (!"succeeded".equals(run.state()) && !"failed".equals(run.state())) {
            String storage = run.storageTargetSummary() != null && !run.storageTargetSummary().isBlank()
                  ? " target=" + run.storageTargetSummary()
                  : "";
            return "AGENTIC[" + run.runId() + "] " + run.goalSummary()
                  + " step=" + run.activeStepKind() + " (" + run.state() + ") "
                  + run.lastMessage() + storage;
         }
      }
      Optional<StepExecution> tracked = mod.getActiveTrackedStep();
      if (tracked.isPresent() && tracked.get().getState() == StepState.RUNNING) {
         StepExecution exec = tracked.get();
         long secs = exec.getElapsedMs() / 1000L;
         StringBuilder sb = new StringBuilder();
         sb.append("<").append(exec.getStepKind()).append(" [RUNNING] ").append(secs).append("s>");
         Task root = mod.getUserTaskChain().getCurrentTask();
         if (root != null && !root.toString().contains("LookAtOwner")) {
            sb.append(" ").append(root.toString());
            if (root instanceof DescribesProgress progress) {
               sb.append(" {").append(progress.describeProgress()).append("}");
            } else if (root instanceof CraftMacroResourceTask macro) {
               sb.append(" {").append(macro.describeProgress()).append("}");
            }
         }
         return sb.toString();
      }
      List<Task> tasks = mod.getUserTaskChain().getTasks();
      return tasks.isEmpty() ? noTask
            : tasks.get(0).toString().contains("LookAtOwner") ? noTask : tasks.get(0).toString();
   }

   public static String getNearbyBlocksString(PlayerEngineController mod) {
      Map<String, Integer> blockCounts = countExposedBlocks(
            com.player2.playerengine.util.Perception.of(mod.getWorld()), mod.getPlayer().blockPosition(), 12);
      ObjectStatus status = new ObjectStatus();

      for (Entry<String, Integer> entry : blockCounts.entrySet()) {
         status.add(entry.getKey(), entry.getValue().toString());
      }

      return status.toString();
   }

   /**
    * Blocks within {@code radius} that a player there could see: an enclosed block, ore included,
    * is not counted (F1).
    */
   public static Map<String, Integer> countExposedBlocks(
         com.player2.playerengine.util.Perception.BlockLookup world, BlockPos center, int radius) {
      Map<String, Integer> blockCounts = new HashMap<>();
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               BlockPos pos = center.offset(dx, dy, dz);
               net.minecraft.world.level.block.state.BlockState state = world.at(pos);
               if (state.isAir() || !com.player2.playerengine.util.Perception.isExposed(world, pos)) {
                  continue;
               }
               // Render modded blocks as their registry id (iceandfire:silver_ore), vanilla as the
               // bare path. The old getDescriptionId() leaked the lang key (block.iceandfire.silver_ore)
               // into worldStatus, which the model then echoed back as a (rejected) @get token — the
               // same lang-key contamination that defeated the modded-craft flow.
               net.minecraft.world.level.block.Block block = state.getBlock();
               net.minecraft.resources.ResourceLocation blockKey =
                     net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
               String blockName = blockKey != null
                     ? ("minecraft".equals(blockKey.getNamespace()) ? blockKey.getPath() : blockKey.toString())
                     : block.getDescriptionId().replace("block.minecraft.", "");
               blockCounts.merge(blockName, 1, Integer::sum);
            }
         }
      }
      return blockCounts;
   }

   public static String getOxygenString(PlayerEngineController mod) {
      return String.format("%s/300", mod.getPlayer().getAirSupply());
   }

   /** Hostiles the companion can see from its eyes: kind, rough distance and direction, no coordinates (F1). */
   public static String getNearbyHostileMobs(PlayerEngineController mod) {
      int radius = com.player2.playerengine.util.Perception.HOSTILE_RADIUS;
      LivingEntity self = mod.getPlayer();
      List<Entity> monsters = new ArrayList<>();
      for (Entity entity : mod.getWorld().getAllEntities()) {
         if (entity instanceof Monster && entity.distanceTo(self) < radius) {
            monsters.add(entity);
         }
      }
      List<String> descriptions = com.player2.playerengine.util.Perception.describeHostiles(
            self.position(), monsters, e -> e.getType().getDescriptionId().replace("entity.minecraft.", ""),
            Entity::position, self::hasLineOfSight);

      return descriptions.isEmpty()
            ? String.format("no hostile mobs in sight within %d", radius)
            : "[" + String.join(",", descriptions.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
   }

   public static String getEquippedArmorStatusString(PlayerEngineController mod) {
      LivingEntity player = mod.getPlayer();
      ObjectStatus status = new ObjectStatus();
      ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
      ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
      ItemStack legs = player.getItemBySlot(EquipmentSlot.LEGS);
      ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);
      ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
      status.add("helmet",
            !head.isEmpty() && head.getItem() instanceof ArmorItem
                  ? ItemHelper.stripItemName(head.getItem())
                  : "none");
      status.add(
            "chestplate",
            !chest.isEmpty() && chest.getItem() instanceof ArmorItem
                  ? ItemHelper.stripItemName(chest.getItem())
                  : "none");
      status.add("leggings",
            !legs.isEmpty() && legs.getItem() instanceof ArmorItem
                  ? ItemHelper.stripItemName(legs.getItem())
                  : "none");
      status.add("boots",
            !feet.isEmpty() && feet.getItem() instanceof ArmorItem
                  ? ItemHelper.stripItemName(feet.getItem())
                  : "none");
      status.add(
            "offhand_shield",
            !offhand.isEmpty() && offhand.getItem() instanceof ShieldItem
                  ? ItemHelper.stripItemName(offhand.getItem())
                  : "none");
      return status.toString();
   }

   public static String getNearbyPlayers(PlayerEngineController mod) {
      List<String> descriptions = new ArrayList<>();

      for (Entity entity : mod.getEntityTracker().getCloseEntities()) {
         if (entity instanceof Player player
               && entity.distanceTo(mod.getPlayer()) < ConversationManager.messagePassingMaxDistance) {
            String username = player.getName().getString();
            String position = entity.position().align(EnumSet.allOf(Axis.class)).toString();
            descriptions.add(username + " at " + position);
         }
      }

      return descriptions.isEmpty()
            ? String.format("no nearby users within %.2f", ConversationManager.messagePassingMaxDistance)
            : "[" + String.join(",", descriptions.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
   }

   public static String getNearbyNPCs(PlayerEngineController mod) {
      List<String> descriptions = new ArrayList<>();

      for (Entity entity : mod.getEntityTracker().getCloseEntities()) {
         if (entity instanceof IAutomatone && entity.distanceTo(mod.getPlayer()) < 32.0F) {
            String username = entity.getDisplayName().getString();
            if (!Objects.equals(username, mod.getPlayer().getDisplayName().getString())) {
               String position = entity.position().align(EnumSet.allOf(Axis.class)).toString();
               descriptions.add(username + " at " + position);
            }
         }
      }

      return descriptions.isEmpty()
            ? String.format("no nearby npcs within %d", 32)
            : "[" + String.join(",", descriptions.stream().map(s -> "\"" + s + "\"").toArray(String[]::new)) + "]";
   }

   public static float getUserNameDistance(PlayerEngineController mod, String targetUsername) {
      for (Player player : mod.getWorld().players()) {
         String username = player.getName().getString();
         if (username.equals(targetUsername)) {
            return player.distanceTo(mod.getPlayer());
         }
      }

      return Float.MAX_VALUE;
   }

   public static String getDifficulty(PlayerEngineController mod) {
      return mod.getWorld().getDifficulty().toString();
   }

   public static String getTimeString(PlayerEngineController mod) {
      ObjectStatus status = new ObjectStatus();
      status.add("isDay", Boolean.toString(mod.getWorld().isDay()));
      status.add("timeOfDay", String.format("%d/24,000", mod.getWorld().getDayTime() % 24000L));
      return status.toString();
   }

   public static String getGamemodeString(PlayerEngineController mod) {
      return mod.getInteractionManager().getGameType().isCreative() ? "creative" : "survival";
   }

   public static String getCurrentPosition(PlayerEngineController mod) {
      return mod.getEntity().getEyePosition().toString();
   }

   public static String getTaskTree(PlayerEngineController mod) {
      Task task = mod.getUserTaskChain().getCurrentTask();
      return task == null ? "Task tree is empty" : task.getTaskTree();
   }

   public static float getDistanceToUUID(PlayerEngineController mod, UUID target) {
      // for (Player player : mod.getWorld().players()) {
      // if (player.getUUID().equals(target)) {
      // return player.distanceTo(mod.getPlayer());
      // }
      // }
      for (Entity entity : mod.getWorld().getAllEntities()) {
         if (entity.getUUID().equals(target)) {
            return entity.distanceTo(mod.getPlayer());
         }
      }

      return Float.MAX_VALUE;
   }

   public static float getDistanceToUsername(PlayerEngineController mod, String username) {
      return mod.getWorld().players().stream()
            .filter(p -> p.getName().getString().equals(username))
            .findFirst()
            .map(p -> p.distanceTo(mod.getPlayer()))
            .orElse(Float.MAX_VALUE);
   }
}
