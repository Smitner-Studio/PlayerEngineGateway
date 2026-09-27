package com.player2.playerengine;

import com.player2.playerengine.commands.*;
import com.player2.playerengine.commands.random.*;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.agentic.elliegps.FarmWaypointService;
import com.player2.playerengine.tasks.farming.FarmWorldObservationProvider;

public class PlayerEngineCommands {
   public static void init(PlayerEngineController controller) throws CommandException {
      FarmWaypointService.installProductionObservationProvider(FarmWorldObservationProvider.INSTANCE);
      controller.getCommandExecutor()
            .registerNewCommand(
                  new GetCommand(),
                  new EquipCommand(),
                  // DISABLED for release: "build_structure" schematic builder is broken. Registration
                  // cut so the AI cannot call it; BuildStructureCommand/BuildStructureTask code left
                  // intact. Re-enable here AND in SeedToolMetadata (doc("build_structure", ...)) once fixed.
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
                  // here AND in SeedToolMetadata (doc("gamer", ...)) once fixed.
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
                  new FillCommand());
   }
}
