package com.player2.playerengine;

import static com.player2.playerengine.commands.base.PermissionClass.ADMIN;
import static com.player2.playerengine.commands.base.PermissionClass.HOSTILE;
import static com.player2.playerengine.commands.base.PermissionClass.ITEMS;
import static com.player2.playerengine.commands.base.PermissionClass.MOVE;
import static com.player2.playerengine.commands.base.PermissionClass.QUERY;
import static com.player2.playerengine.commands.base.PermissionClass.SELF;
import static com.player2.playerengine.commands.base.PermissionClass.WORLD;

import com.player2.playerengine.commands.*;
import com.player2.playerengine.commands.random.*;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.commands.base.PermissionClass;
import com.player2.playerengine.agentic.elliegps.FarmWaypointService;
import com.player2.playerengine.tasks.farming.FarmWorldObservationProvider;
import java.util.Map;
import java.util.Set;

public class PlayerEngineCommands {
   /**
    * The permission class of every command id the model can be told about: each registered command,
    * each disabled one waiting to be re-enabled, and each tool document in {@code SeedToolMetadata}.
    * A command registered without a row here fails registration. {@code PermissionClassSelfTest}
    * holds the table to the registrations and the seed documents.
    */
   public static final Map<String, PermissionClass> CLASSES = Map.ofEntries(
         Map.entry("get", WORLD),
         Map.entry("equip", SELF),
         Map.entry("build_structure", WORLD),
         Map.entry("bodylang", SELF),
         Map.entry("deposit", ITEMS),
         Map.entry("goto", MOVE),
         Map.entry("idle", SELF),
         Map.entry("hero", HOSTILE),
         Map.entry("locate_structure", MOVE),
         Map.entry("stop", SELF),
         Map.entry("food", WORLD),
         Map.entry("meat", WORLD),
         Map.entry("smelt", ITEMS),
         Map.entry("smith", ITEMS),
         Map.entry("mine", WORLD),
         Map.entry("reload_settings", ADMIN),
         Map.entry("resetmemory", ADMIN),
         Map.entry("gamer", WORLD),
         Map.entry("follow", MOVE),
         Map.entry("leaveboat", MOVE),
         Map.entry("give", ITEMS),
         Map.entry("scan", QUERY),
         Map.entry("attack", HOSTILE),
         Map.entry("chatclef", ADMIN),
         Map.entry("eat_food", SELF),
         Map.entry("pickup_drops", ITEMS),
         Map.entry("agentic", WORLD),
         Map.entry("set_attack_hostiles", HOSTILE),
         Map.entry("set_follow_mode", SELF),
         Map.entry("fish", ITEMS),
         Map.entry("read_signs", QUERY),
         Map.entry("place_sign", WORLD),
         Map.entry("scan_storage", QUERY),
         Map.entry("withdraw_from_storage", ITEMS),
         Map.entry("deposit_to_storage", ITEMS),
         Map.entry("withdraw_storage_slot", ITEMS),
         Map.entry("deposit_storage_slot", ITEMS),
         Map.entry("locate_storage", QUERY),
         Map.entry("create_waypoint", ITEMS),
         Map.entry("delete_waypoint", ITEMS),
         Map.entry("audit_waypoint", QUERY),
         Map.entry("compare_waypoint", QUERY),
         Map.entry("setup_farm", WORLD),
         Map.entry("harvest_farm", WORLD),
         Map.entry("plant_farm", WORLD),
         Map.entry("locate_waypoints", QUERY),
         Map.entry("excavate", WORLD),
         Map.entry("fill", WORLD),
         // Seed tool documents with no command of their own: an agentic plan step (mine_block) and
         // two documents no command backs (explore, stash).
         Map.entry("explore", MOVE),
         Map.entry("stash", ITEMS),
         Map.entry("mine_block", WORLD));

   /** Commands whose registration is cut until they are fixed; each keeps its row in {@link #CLASSES}. */
   public static final Set<String> DISABLED = Set.of("build_structure", "gamer");

   public static void init(PlayerEngineController controller) throws CommandException {
      FarmWaypointService.installProductionObservationProvider(FarmWorldObservationProvider.INSTANCE);
      controller.getCommandExecutor().registerNewCommand(CLASSES::get, commands());
   }

   /** The commands a companion registers, in registration order. */
   public static Command[] commands() throws CommandException {
      return new Command[] {
            new GetCommand(),
            new EquipCommand(),
            // DISABLED for release: "build_structure" schematic builder is broken. Registration
            // cut so the AI cannot call it; BuildStructureCommand/BuildStructureTask code left
            // intact. Re-enable here AND in SeedToolMetadata (doc("build_structure", ...)) once
            // fixed, and drop it from DISABLED; its class is already in CLASSES.
            // new BuildStructureCommand(),
            new BodyLanguageCommand(),
            new DepositCommand(),
            new GotoCommand(),
            new IdleCommand(),
            new HeroCommand(),
            new LocateStructureCommand(),
            new StopCommand(),
            new FoodCommand(),
            new MeatCommand(),
            new SmeltCommand(),
            new SmithCommand(),
            new MineCommand(),
            new ReloadSettingsCommand(),
            new ResetMemoryCommand(),
            // DISABLED for release: "beat the game" (gamer) is broken. Registration cut so it
            // cannot be activated; GamerCommand/BeatMinecraftTask code left intact. Re-enable
            // here AND in SeedToolMetadata (doc("gamer", ...)) once fixed, and drop it from
            // DISABLED; its class is already in CLASSES.
            // new GamerCommand(),
            new FollowCommand(),
            new LeaveBoatCommand(),
            new GiveCommand(),
            new ScanCommand(),
            new AttackPlayerOrMobCommand(),
            new SetAIBridgeEnabledCommand(),
            new EatFoodCommand(),
            new PickupDropsCommand(),
            new AgenticCommand(),
            new SetHostileAttackCommand(),
            new SetFollowModeCommand(),
            new FishCommand(),
            new ReadNearbySignsCommand(),
            new PlaceSignCommand(),
            new ScanStorageCommand(),
            new WithdrawFromStorageCommand(),
            new DepositToStorageCommand(),
            new WithdrawStorageSlotCommand(),
            new DepositStorageSlotCommand(),
            new LocateStorageCommand(),
            new CreateWaypointCommand(),
            new DeleteWaypointCommand(),
            new AuditWaypointCommand(),
            new CompareWaypointCommand(),
            new SetupFarmCommand(),
            new HarvestFarmCommand(),
            new PlantFarmCommand(),
            new LocateWaypointsCommand(),
            new ExcavateCommand(),
            new FillCommand()
      };
   }
}
