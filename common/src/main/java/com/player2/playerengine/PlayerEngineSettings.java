package com.player2.playerengine;

import com.player2.playerengine.control.KillAura;
import com.player2.playerengine.tasks.movement.DefaultGoToDimensionTask;
import com.player2.playerengine.util.BlockRange;
import com.player2.playerengine.util.helpers.ConfigHelper;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.serialization.IFailableConfigFile;
import com.google.common.collect.Streams;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class PlayerEngineSettings implements IFailableConfigFile {

   private static final String CONFIG_FILE = PlayerEngine.MOD_ID + "_settings.json";
   static final int ADMIN_ITEM_LIST_MAX_SERIALIZED_LENGTH = 3500;

   private transient boolean failedToLoad = false;
   private boolean showDebugTickMs = false;
   private boolean showTaskChains = true;
   private boolean hideAllWarningLogs = false;
   private String logLevel = "NORMAL";
   private String chatLogPrefix = "[Alto Clef] ";
   private boolean showTimer = true;
   private float containerItemMoveDelay = 0.2F;
   private boolean useCraftingBookToCraft = true;
   private float resourcePickupDropRange = 16.0F;
   private int minimumFoodAllowed = 0;
   private int foodUnitsToCollect = 0;
   private float resourceChestLocateRange = 500.0F;
   private float resourceMineRange = 100.0F;
   private boolean avoidSearchingDungeonChests = true;
   private boolean avoidOceanBlocks = false;
   private float entityReachRange = 4.0F;
   private boolean collectPickaxeFirst = true;
   private boolean replantCrops = true;
   private boolean mobDefense = true;
   private KillAura.Strategy forceFieldStrategy = KillAura.Strategy.SMART;
   private boolean dodgeProjectiles = true;
   private boolean killOrAvoidAnnoyingHostiles = true;
   private boolean avoidDrowning = true;
   private boolean autoCloseScreenWhenLookingOrMining = true;
   private boolean extinguishSelfWithWater = true;
   private boolean autoEat = true;
   private boolean hungerEnabled = false;                    // master gate for the hunger sim + auto-eat
   private boolean deathByHungerMatchesDifficulty = true;    // true = vanilla difficulty starve; false = no starvation damage
   private boolean autoMLGBucket = true;
   private boolean autoReconnect = true;
   private boolean autoRespawn = true;
   private DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR overworldToNetherBehaviour = DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR.BUILD_PORTAL_VANILLA;
   private int netherFastTravelWalkingRange = 600;
   private String idleCommand = "idle";
   private String deathCommand = "";
   private boolean fastCraftMacrosEnabled = true;
   private float craftDelaySeconds = 0.5F;
   private float craftTableLookHoldSeconds = 0.25F;
   private boolean preferLocalCraftingTable = true;
   // Table-REUSE search range (blocks) for CraftMacroResourceTask's FIND_OR_PLACE_TABLE phase. Before
   // placing/crafting a NEW crafting table the macro adopts a pre-existing, pathable crafting_table within
   // this radius and WALKS to it (MOVE_TO_TABLE) rather than provisioning its own. This is the REUSE
   // decision and is intentionally far wider than CraftingTableLocator.REACH (3.5), which stays the
   // arm's-reach "can I craft right NOW" gate. 48 blocks = 3 chunks (16 x 3) measured from the bot.
   private double craftingTableReuseRadius = 48.0;
   private boolean enableAgenticPlanner = false;
   // ISSUE 1 (temporary, user request): gate LookAtOwnerTask scheduling. When false the bot does NOT set
   // LookAtOwner after a command finishes/errors or on @idle — it simply holds with no user task (idles).
   // Default FALSE per request; a fuller LookAtOwner rework is planned separately. Real toggle so the
   // rework can re-enable without code changes. Do NOT delete LookAtOwnerTask; only its scheduling is gated.
   private boolean enableLookAtOwnerIdle = false;
   private int agenticPlannerRagTopK = 8;
   private int agenticPlannerMaxSteps = 4;
   private boolean agenticPlannerFallbackGather = true;
   private float gatherLooseItemsRadius = 16.0F;
   private int gatherLooseItemsMaxItems = 64;
   private float gatherLooseItemsTimeoutSeconds = 60.0F;
   private float gatherLooseItemsSettleSeconds = 3.0F;
   private float agenticStorageSearchRadius = 20.0F;
   private float agenticStoragePlacementRadius = 4.0F;
   // Issue B (immersion) cap: the maximum distance, in blocks, an agentic run may TRAVEL to reach a
   // gather source or a pre-existing storage chest. Default 96 = 6 chunks, ~3/5 of the 1.20.1 default
   // server view-distance (10 chunks / 160 blocks): a player would not see or reach a chest this far,
   // so neither should the bot. This bounds the mine/gather far-source wander and rejects far existing
   // chests. NOTE: when EllieGPS marked-chest memory exists, recall of a player-marked chest BEYOND
   // this radius will be allowed explicitly (this cap governs only un-marked, auto-discovered targets).
   private float agenticMaxTravelRadius = 96.0F;
   private boolean agenticStorageAllowPlacement = true;
   private boolean agenticStoragePreferExisting = true;
   private boolean agenticStorageAvoidLootChests = true;
   private float agenticStorageResolveTimeoutSeconds = 180.0F;
   private float agenticDepositTimeoutSeconds = 120.0F;
   private boolean agenticDepositKeepTools = true;
   private boolean agenticEnableLabelChest = true;
   private float agenticLabelTimeoutSeconds = 60.0F;
   private boolean agenticLabelUseModelText = false;
   private boolean enableDeferredSmelt = true;
   private int deferredSmeltMaxBatch = 64;
   private boolean enableSmithing = true;
   private int smithMaxBatch = 64;
   private int deferredSmeltStallPolls = 200;
   private int deferredSmeltTimeoutSeconds = 600;
   private float aggregateCountDropRadius = 16.0F;
   private float aggregateLocalSourceBlockRadius = 32.0F;
   private float mineCollectSettleSeconds = 1.0F;
   // Generic agentic block-mining (mine_block step) settings. Defaults/clamps from the
   // block-mining plan config table. Getters clamp on read (mirrors the gather/storage settings).
   private double mineBlockRadius = 32.0;
   private int mineBlockMaxBlocks = 16;
   private double mineBlockTimeoutSeconds = 120.0;
   private int toolAcquireMaxContainers = 8;
   private int toolAcquireClimbBudget = 6;
   private int wanderBoundDefaultSeconds = 90;
   private int wanderNoImprovementSeconds = 30;
   // EllieGPS (Part C5) settings — pinned field names match the config key table exactly.
   // Getters are pinned in the Parallelization plan; do NOT rename without updating every consumer.
   private boolean ellieGpsEnabled = true;
   private int ellieGpsSnapshotSlotThreshold = 45;
   private boolean ellieGpsUseModelDescription = true;

   private List<Item> throwawayItems = Arrays.asList(
      Items.DRIPSTONE_BLOCK,
      Items.ROOTED_DIRT,
      Items.GRAVEL,
      Items.SAND,
      Items.DIORITE,
      Items.ANDESITE,
      Items.GRANITE,
      Items.TUFF,
      Items.COBBLESTONE,
      Items.DIRT,
      Items.COBBLED_DEEPSLATE,
      Items.ACACIA_LEAVES,
      Items.BIRCH_LEAVES,
      Items.DARK_OAK_LEAVES,
      Items.OAK_LEAVES,
      Items.JUNGLE_LEAVES,
      Items.SPRUCE_LEAVES,
      Items.NETHERRACK,
      Items.MAGMA_BLOCK,
      Items.SOUL_SOIL,
      Items.SOUL_SAND,
      Items.NETHER_BRICKS,
      Items.NETHER_BRICK,
      Items.BASALT,
      Items.BLACKSTONE,
      Items.END_STONE,
      Items.SANDSTONE,
      Items.STONE_BRICKS
   );
   private int reservedBuildingBlockCount = 64;
   private boolean dontThrowAwayCustomNameItems = true;
   private boolean dontThrowAwayEnchantedItems = true;
   private boolean throwAwayUnusedItems = true;

   private List<Item> importantItems = Streams.concat(
         new Stream[]{
            Stream.of(
               Items.TOTEM_OF_UNDYING,
               Items.ENCHANTED_GOLDEN_APPLE,
               Items.ENDER_EYE,
               Items.TRIDENT,
               Items.DIAMOND,
               Items.DIAMOND_BLOCK,
               Items.NETHERITE_SCRAP,
               Items.NETHERITE_INGOT,
               Items.NETHERITE_BLOCK
            ),
            Stream.of(ItemHelper.DIAMOND_ARMORS),
            Stream.of(ItemHelper.NETHERITE_ARMORS),
            Stream.of(ItemHelper.DIAMOND_TOOLS),
            Stream.of(ItemHelper.NETHERITE_TOOLS),
            Stream.of(ItemHelper.SHULKER_BOXES)
         }
      )
      .toList();
   private boolean limitFuelsToSupportedFuels = true;

   // NOTE: the legacy AND agentic smelt/cook fuel selection deliberately goes through FuelPlanner, which
   // reads the vanilla registry burn-map (AbstractFurnaceBlockEntity.getFuel()) and picks
   // charcoal/coal/planks/logs. It intentionally BYPASSES supportedFuels / isSupportedFuel /
   // calculateInventoryFuelCount (the retired legacy gate), so planks/logs work as fuel ("trees-as-fuel")
   // even though they are not listed here. Do NOT "reconcile" this set with FuelPlanner's preference list
   // or add planks/logs here to make them match — that would re-couple the retired gate and risk
   // re-breaking trees-as-fuel. This list now only governs any remaining isSupportedFuel callers.
   private List<Item> supportedFuels = Streams.concat(new Stream[]{Stream.of(Items.COAL, Items.CHARCOAL)}).toList();
   private BlockPos homeBasePosition = new BlockPos(0, 64, 0);
   private List<BlockRange> areasToProtect = Collections.emptyList();

   public static void load(Consumer<PlayerEngineSettings> onReload) {
      PlayerEngineSettings initial = ConfigHelper.getConfig(
            CONFIG_FILE, PlayerEngineSettings::new, PlayerEngineSettings.class);
      initial.normalizeAdminSupportedValues();
      onReload.accept(initial);
      // One canonical, controller-independent callback. Each controller still receives its own
      // initial snapshot above, while an explicit reload re-reads disk and publishes to all live
      // controllers instead of retaining the first/last controller's originally loaded object.
      ConfigHelper.registerReload(CONFIG_FILE, PlayerEngineSettingsAdminService::reloadFromDiskAndPublish);
   }

   public boolean shouldShowTaskChain() {
      return this.showTaskChains;
   }

   public boolean shouldShowDebugTickMs() {
      return this.showDebugTickMs;
   }

   public boolean shouldHideAllWarningLogs() {
      return this.hideAllWarningLogs;
   }

   public String getLogLevel() {
      return this.logLevel;
   }

   public String getChatLogPrefix() {
      return this.chatLogPrefix;
   }

   public boolean shouldShowTimer() {
      return this.showTimer;
   }

   public float getResourcePickupRange() {
      return this.resourcePickupDropRange;
   }

   public float getResourceChestLocateRange() {
      return this.resourceChestLocateRange;
   }

   public float getResourceMineRange() {
      return this.resourceMineRange;
   }

   public float getContainerItemMoveDelay() {
      return this.containerItemMoveDelay;
   }

   public boolean shouldUseCraftingBookToCraft() {
      return this.useCraftingBookToCraft;
   }

   public int getFoodUnitsToCollect() {
      return this.foodUnitsToCollect;
   }

   public int getMinimumFoodAllowed() {
      return this.minimumFoodAllowed;
   }

   public boolean isMobDefense() {
      return this.mobDefense;
   }

   public boolean isDodgeProjectiles() {
      return this.dodgeProjectiles;
   }

   public boolean isAutoEat() {
      return this.autoEat;
   }

   public boolean isHungerEnabled() {
      // config/playerengine-companion.properties `hunger`, when set, outranks this file: a
      // singleplayer instance keeps its settings file across pack updates.
      Boolean forced = com.player2.playerengine.companion.CompanionRules.get().hungerOverride();
      return forced != null ? forced : this.hungerEnabled;
   }

   public boolean isDeathByHungerMatchesDifficulty() {
      return this.deathByHungerMatchesDifficulty;
   }

   public boolean isAutoReconnect() {
      return this.autoReconnect;
   }

   public boolean isAutoRespawn() {
      return this.autoRespawn;
   }

   public boolean shouldReplantCrops() {
      return this.replantCrops;
   }

   public boolean shouldDealWithAnnoyingHostiles() {
      return this.killOrAvoidAnnoyingHostiles;
   }

   public KillAura.Strategy getForceFieldStrategy() {
      return this.forceFieldStrategy;
   }

   public String getIdleCommand() {
      return this.idleCommand == null ? "" : this.idleCommand;
   }

   public String getDeathCommand() {
      return this.deathCommand;
   }

   public boolean shouldRunIdleCommandWhenNotActive() {
      return this.idleCommand != null && !this.idleCommand.isBlank();
   }

   public boolean shouldAutoMLGBucket() {
      return this.autoMLGBucket;
   }

   public boolean shouldCollectPickaxeFirst() {
      return this.collectPickaxeFirst;
   }

   public boolean shouldAvoidDrowning() {
      return this.avoidDrowning;
   }

   public boolean shouldCloseScreenWhenLookingOrMining() {
      return this.autoCloseScreenWhenLookingOrMining;
   }

   public boolean shouldExtinguishSelfWithWater() {
      return this.extinguishSelfWithWater;
   }

   public boolean shouldAvoidSearchingForDungeonChests() {
      return this.avoidSearchingDungeonChests;
   }

   public boolean shouldAvoidOcean() {
      return this.avoidOceanBlocks;
   }

   public boolean isThrowaway(Item item) {
      return this.throwawayItems.contains(item);
   }

   public boolean isImportant(Item item) {
      return this.importantItems.contains(item);
   }

   public boolean shouldThrowawayUnusedItems() {
      return this.throwAwayUnusedItems;
   }

   public int getReservedBuildingBlockCount() {
      return this.reservedBuildingBlockCount;
   }

   public boolean getDontThrowAwayCustomNameItems() {
      return this.dontThrowAwayCustomNameItems;
   }

   public boolean getDontThrowAwayEnchantedItems() {
      return this.dontThrowAwayEnchantedItems;
   }

   public float getEntityReachRange() {
      return this.entityReachRange;
   }

   public Item[] getThrowawayItems(PlayerEngineController mod, boolean includeProtected) {
      return this.throwawayItems.stream().filter(item -> includeProtected || !mod.getBehaviour().isProtected(item)).toArray(Item[]::new);
   }

   public Item[] getThrowawayItems(PlayerEngineController mod) {
      return this.getThrowawayItems(mod, false);
   }

   public boolean shouldLimitFuelsToSupportedFuels() {
      return this.limitFuelsToSupportedFuels;
   }

   public boolean isSupportedFuel(Item item) {
      return !this.limitFuelsToSupportedFuels || this.supportedFuels.contains(item);
   }

   public Item[] getSupportedFuelItems() {
      return this.supportedFuels.toArray(Item[]::new);
   }

   public DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR getOverworldToNetherBehaviour() {
      return this.overworldToNetherBehaviour;
   }

   public int getNetherFastTravelWalkingRange() {
      return this.netherFastTravelWalkingRange;
   }

   public BlockPos getHomeBasePosition() {
      return this.homeBasePosition;
   }

   public boolean isFastCraftMacrosEnabled() {
      return this.fastCraftMacrosEnabled;
   }

   public double getCraftDelaySeconds() {
      return clamp(this.craftDelaySeconds, 0.0F, 5.0F);
   }

   public double getCraftTableLookHoldSeconds() {
      return clamp(this.craftTableLookHoldSeconds, 0.0F, 3.0F);
   }

   public boolean isPreferLocalCraftingTable() {
      return this.preferLocalCraftingTable;
   }

   /**
    * Table-REUSE search radius (blocks) used by the crafting macro to adopt and walk to a pre-existing,
    * pathable crafting table instead of placing a new one. Clamped to a sane band: never below the
    * arm's-reach REACH (so reuse is at least as wide as "use right now") and capped so the search never
    * exceeds the agentic travel radius. Default 48 (3 chunks).
    */
   public double getCraftingTableReuseRadius() {
      return clamp((float) this.craftingTableReuseRadius, 3.5F, 128.0F);
   }

   public boolean isEnableAgenticPlanner() {
      return this.enableAgenticPlanner;
   }

   /** ISSUE 1: whether LookAtOwnerTask may be scheduled (idle / post-finish / post-error). Default false. */
   public boolean isEnableLookAtOwnerIdle() {
      return this.enableLookAtOwnerIdle;
   }

   public int getAgenticPlannerRagTopK() {
      return (int) clamp(this.agenticPlannerRagTopK, 1, 20);
   }

   public int getAgenticPlannerMaxSteps() {
      return (int) clamp(this.agenticPlannerMaxSteps, 1, 4);
   }

   public boolean isAgenticPlannerFallbackGather() {
      return this.agenticPlannerFallbackGather;
   }

   public double getGatherLooseItemsRadius() {
      return clamp(this.gatherLooseItemsRadius, 2.0F, 64.0F);
   }

   public int getGatherLooseItemsMaxItems() {
      return (int) clamp(this.gatherLooseItemsMaxItems, 1, 1024);
   }

   public double getGatherLooseItemsTimeoutSeconds() {
      return clamp(this.gatherLooseItemsTimeoutSeconds, 5.0F, 300.0F);
   }

   public double getGatherLooseItemsSettleSeconds() {
      return clamp(this.gatherLooseItemsSettleSeconds, 0.5F, 20.0F);
   }

   /** Search radius (blocks) for target blocks and for chest/waypoint tool search. Default 32, clamp 4..128. */
   public double getMineBlockRadius() {
      return clamp((float) this.mineBlockRadius, 4.0F, 128.0F);
   }

   /** Max blocks one mine_block step will break. Default 16, clamp 1..256. */
   public int getMineBlockMaxBlocks() {
      return (int) clamp(this.mineBlockMaxBlocks, 1, 256);
   }

   /** Overall mine_block step timeout in seconds (partial success on expiry). Default 120, clamp 10..600. */
   public double getMineBlockTimeoutSeconds() {
      return clamp((float) this.mineBlockTimeoutSeconds, 10.0F, 600.0F);
   }

   /** Max chest withdraw attempts across the marked+unmarked tool-acquire stages. Default 8, clamp 1..32. */
   public int getToolAcquireMaxContainers() {
      return (int) clamp(this.toolAcquireMaxContainers, 1, 32);
   }

   /** Max material-climb attempts (cycle guard) before definitive tool-acquire failure. Default 6, clamp 1..16. */
   public int getToolAcquireClimbBudget() {
      return (int) clamp(this.toolAcquireClimbBudget, 1, 16);
   }

   public double getAgenticStorageSearchRadius() {
      return clamp(this.agenticStorageSearchRadius, 4.0F, 64.0F);
   }

   public double getAgenticStoragePlacementRadius() {
      // Cap reduced 16 -> 8 (and default 8 -> 4): candidate count is (2*ceil(r)+1)^2 * 5, so r=8 was
      // 1445 getBlockState-heavy candidates per scan on the server thread; r=4 is 405. Placement only
      // needs a nearby empty floor tile, so a small radius suffices and stays well under render distance.
      return clamp(this.agenticStoragePlacementRadius, 2.0F, 8.0F);
   }

   /**
    * Issue B (immersion): max distance an agentic run may travel to an un-marked, auto-discovered
    * source/chest. Default 96 blocks (6 chunks), clamped 16..160 (never above the 10-chunk default
    * render distance). EllieGPS (planned) will later allow explicit recall of player-marked chests
    * beyond this radius; until then the bot must not path to something a player could not see/reach.
    */
   public double getAgenticMaxTravelRadius() {
      return clamp(this.agenticMaxTravelRadius, 16.0F, 160.0F);
   }

   public boolean isAgenticStorageAllowPlacement() {
      return this.agenticStorageAllowPlacement;
   }

   public boolean isAgenticStoragePreferExisting() {
      return this.agenticStoragePreferExisting;
   }

   public boolean isAgenticStorageAvoidLootChests() {
      return this.agenticStorageAvoidLootChests;
   }

   public double getAgenticStorageResolveTimeoutSeconds() {
      return clamp(this.agenticStorageResolveTimeoutSeconds, 10.0F, 300.0F);
   }

   public double getAgenticDepositTimeoutSeconds() {
      return clamp(this.agenticDepositTimeoutSeconds, 10.0F, 300.0F);
   }

   public boolean isAgenticDepositKeepTools() {
      return this.agenticDepositKeepTools;
   }

   public boolean isAgenticEnableLabelChest() {
      return this.agenticEnableLabelChest;
   }

   public double getAgenticLabelTimeoutSeconds() {
      return clamp(this.agenticLabelTimeoutSeconds, 10.0F, 180.0F);
   }

   public boolean isAgenticLabelUseModelText() {
      return this.agenticLabelUseModelText;
   }

   public boolean isEnableDeferredSmelt() {
      return this.enableDeferredSmelt;
   }

   public int getDeferredSmeltMaxBatch() {
      return (int) clamp(this.deferredSmeltMaxBatch, 1, 512);
   }

   public boolean isEnableSmithing() {
      return this.enableSmithing;
   }

   public int getSmithMaxBatch() {
      return (int) clamp(this.smithMaxBatch, 1, 512);
   }

   public int getDeferredSmeltStallPolls() {
      return (int) clamp(this.deferredSmeltStallPolls, 20, 2000);
   }

   public int getDeferredSmeltTimeoutSeconds() {
      return (int) clamp(this.deferredSmeltTimeoutSeconds, 60, 3600);
   }

   public double getAggregateCountDropRadius() {
      return clamp(this.aggregateCountDropRadius, 0.0F, 64.0F);
   }

   public double getAggregateLocalSourceBlockRadius() {
      return clamp(this.aggregateLocalSourceBlockRadius, 0.0F, 128.0F);
   }

   public double getMineCollectSettleSeconds() {
      return clamp(this.mineCollectSettleSeconds, 0.0F, 5.0F);
   }

   public int getWanderBoundDefaultSeconds() {
      return (int) clamp(this.wanderBoundDefaultSeconds, 0, 600);
   }

   public int getWanderNoImprovementSeconds() {
      return (int) clamp(this.wanderNoImprovementSeconds, 0, 300);
   }

   // -------------------------------------------------------------------------
   // EllieGPS settings (Part C5) — pinned getter signatures (Parallelization plan)
   // -------------------------------------------------------------------------

   /**
    * Master EllieGPS switch. When false: commands refuse with a clear dual-audience error,
    * the auto-hook silent-skips with a degradation note, and the counting term is 0.
    * Effective on settings reload (no restart needed); the startup service swap is unconditional.
    * Default: {@code true}.
    */
   public boolean getEllieGpsEnabled() {
      return this.ellieGpsEnabled;
   }

   /**
    * Slot-count threshold above which a waypoint record omits the inline item snapshot and
    * becomes keyword-only. A count of used slots ({@code totalSlots - emptySlots}) strictly
    * greater than this value triggers keyword-only mode.
    * Clamped to {@code [1, 54]}. Default: {@code 45}.
    */
   public int getEllieGpsSnapshotSlotThreshold() {
      return (int) clamp(this.ellieGpsSnapshotSlotThreshold, 1, 54);
   }

   /**
    * Whether to dispatch an async SUMMARIZATION-routed LLM call to polish the waypoint
    * description. The deterministic description is always built first; the LLM polish is
    * best-effort and never blocks the command or the agentic run.
    * Default: {@code true}.
    */
   public boolean getEllieGpsUseModelDescription() {
      return this.ellieGpsUseModelDescription;
   }

   PlayerEngineSettings defensiveCopy() {
      PlayerEngineSettings copy = new PlayerEngineSettings();
      copy.failedToLoad = this.failedToLoad;
      copy.showDebugTickMs = this.showDebugTickMs;
      copy.showTaskChains = this.showTaskChains;
      copy.hideAllWarningLogs = this.hideAllWarningLogs;
      copy.logLevel = this.logLevel;
      copy.chatLogPrefix = this.chatLogPrefix;
      copy.showTimer = this.showTimer;
      copy.containerItemMoveDelay = this.containerItemMoveDelay;
      copy.useCraftingBookToCraft = this.useCraftingBookToCraft;
      copy.resourcePickupDropRange = this.resourcePickupDropRange;
      copy.minimumFoodAllowed = this.minimumFoodAllowed;
      copy.foodUnitsToCollect = this.foodUnitsToCollect;
      copy.resourceChestLocateRange = this.resourceChestLocateRange;
      copy.resourceMineRange = this.resourceMineRange;
      copy.avoidSearchingDungeonChests = this.avoidSearchingDungeonChests;
      copy.avoidOceanBlocks = this.avoidOceanBlocks;
      copy.entityReachRange = this.entityReachRange;
      copy.collectPickaxeFirst = this.collectPickaxeFirst;
      copy.replantCrops = this.replantCrops;
      copy.mobDefense = this.mobDefense;
      copy.forceFieldStrategy = this.forceFieldStrategy;
      copy.dodgeProjectiles = this.dodgeProjectiles;
      copy.killOrAvoidAnnoyingHostiles = this.killOrAvoidAnnoyingHostiles;
      copy.avoidDrowning = this.avoidDrowning;
      copy.autoCloseScreenWhenLookingOrMining = this.autoCloseScreenWhenLookingOrMining;
      copy.extinguishSelfWithWater = this.extinguishSelfWithWater;
      copy.autoEat = this.autoEat;
      copy.hungerEnabled = this.hungerEnabled;
      copy.deathByHungerMatchesDifficulty = this.deathByHungerMatchesDifficulty;
      copy.autoMLGBucket = this.autoMLGBucket;
      copy.autoReconnect = this.autoReconnect;
      copy.autoRespawn = this.autoRespawn;
      copy.overworldToNetherBehaviour = this.overworldToNetherBehaviour;
      copy.netherFastTravelWalkingRange = this.netherFastTravelWalkingRange;
      copy.idleCommand = this.idleCommand;
      copy.deathCommand = this.deathCommand;
      copy.fastCraftMacrosEnabled = this.fastCraftMacrosEnabled;
      copy.craftDelaySeconds = this.craftDelaySeconds;
      copy.craftTableLookHoldSeconds = this.craftTableLookHoldSeconds;
      copy.preferLocalCraftingTable = this.preferLocalCraftingTable;
      copy.craftingTableReuseRadius = this.craftingTableReuseRadius;
      copy.enableAgenticPlanner = this.enableAgenticPlanner;
      copy.enableLookAtOwnerIdle = this.enableLookAtOwnerIdle;
      copy.agenticPlannerRagTopK = this.agenticPlannerRagTopK;
      copy.agenticPlannerMaxSteps = this.agenticPlannerMaxSteps;
      copy.agenticPlannerFallbackGather = this.agenticPlannerFallbackGather;
      copy.gatherLooseItemsRadius = this.gatherLooseItemsRadius;
      copy.gatherLooseItemsMaxItems = this.gatherLooseItemsMaxItems;
      copy.gatherLooseItemsTimeoutSeconds = this.gatherLooseItemsTimeoutSeconds;
      copy.gatherLooseItemsSettleSeconds = this.gatherLooseItemsSettleSeconds;
      copy.agenticStorageSearchRadius = this.agenticStorageSearchRadius;
      copy.agenticStoragePlacementRadius = this.agenticStoragePlacementRadius;
      copy.agenticMaxTravelRadius = this.agenticMaxTravelRadius;
      copy.agenticStorageAllowPlacement = this.agenticStorageAllowPlacement;
      copy.agenticStoragePreferExisting = this.agenticStoragePreferExisting;
      copy.agenticStorageAvoidLootChests = this.agenticStorageAvoidLootChests;
      copy.agenticStorageResolveTimeoutSeconds = this.agenticStorageResolveTimeoutSeconds;
      copy.agenticDepositTimeoutSeconds = this.agenticDepositTimeoutSeconds;
      copy.agenticDepositKeepTools = this.agenticDepositKeepTools;
      copy.agenticEnableLabelChest = this.agenticEnableLabelChest;
      copy.agenticLabelTimeoutSeconds = this.agenticLabelTimeoutSeconds;
      copy.agenticLabelUseModelText = this.agenticLabelUseModelText;
      copy.enableDeferredSmelt = this.enableDeferredSmelt;
      copy.deferredSmeltMaxBatch = this.deferredSmeltMaxBatch;
      copy.enableSmithing = this.enableSmithing;
      copy.smithMaxBatch = this.smithMaxBatch;
      copy.deferredSmeltStallPolls = this.deferredSmeltStallPolls;
      copy.deferredSmeltTimeoutSeconds = this.deferredSmeltTimeoutSeconds;
      copy.aggregateCountDropRadius = this.aggregateCountDropRadius;
      copy.aggregateLocalSourceBlockRadius = this.aggregateLocalSourceBlockRadius;
      copy.mineCollectSettleSeconds = this.mineCollectSettleSeconds;
      copy.mineBlockRadius = this.mineBlockRadius;
      copy.mineBlockMaxBlocks = this.mineBlockMaxBlocks;
      copy.mineBlockTimeoutSeconds = this.mineBlockTimeoutSeconds;
      copy.toolAcquireMaxContainers = this.toolAcquireMaxContainers;
      copy.toolAcquireClimbBudget = this.toolAcquireClimbBudget;
      copy.wanderBoundDefaultSeconds = this.wanderBoundDefaultSeconds;
      copy.wanderNoImprovementSeconds = this.wanderNoImprovementSeconds;
      copy.ellieGpsEnabled = this.ellieGpsEnabled;
      copy.ellieGpsSnapshotSlotThreshold = this.ellieGpsSnapshotSlotThreshold;
      copy.ellieGpsUseModelDescription = this.ellieGpsUseModelDescription;
      copy.throwawayItems = copyItems(this.throwawayItems);
      copy.reservedBuildingBlockCount = this.reservedBuildingBlockCount;
      copy.dontThrowAwayCustomNameItems = this.dontThrowAwayCustomNameItems;
      copy.dontThrowAwayEnchantedItems = this.dontThrowAwayEnchantedItems;
      copy.throwAwayUnusedItems = this.throwAwayUnusedItems;
      copy.importantItems = copyItems(this.importantItems);
      copy.limitFuelsToSupportedFuels = this.limitFuelsToSupportedFuels;
      copy.supportedFuels = copyItems(this.supportedFuels);
      copy.homeBasePosition = this.homeBasePosition == null ? null : this.homeBasePosition.immutable();
      copy.areasToProtect = copyBlockRanges(this.areasToProtect);
      return copy;
   }

   /**
    * Restores the settings whose approved GUI contract is NEXT_TASK from {@code source}.
    * The complementary fields remain from this instance and therefore take effect live.
    * Keep this allowlist in lockstep with Player2NPC's PlayerEngineGuiSettings effect map.
    */
   void copyNextTaskValuesFrom(PlayerEngineSettings source) {
      if (source == null) {
         return;
      }
      this.collectPickaxeFirst = source.collectPickaxeFirst;
      this.replantCrops = source.replantCrops;
      this.fastCraftMacrosEnabled = source.fastCraftMacrosEnabled;
      this.craftDelaySeconds = source.craftDelaySeconds;
      this.craftTableLookHoldSeconds = source.craftTableLookHoldSeconds;
      this.preferLocalCraftingTable = source.preferLocalCraftingTable;
      this.craftingTableReuseRadius = source.craftingTableReuseRadius;
      this.enableAgenticPlanner = source.enableAgenticPlanner;
      this.agenticPlannerRagTopK = source.agenticPlannerRagTopK;
      this.agenticPlannerMaxSteps = source.agenticPlannerMaxSteps;
      this.agenticPlannerFallbackGather = source.agenticPlannerFallbackGather;
      this.gatherLooseItemsRadius = source.gatherLooseItemsRadius;
      this.gatherLooseItemsMaxItems = source.gatherLooseItemsMaxItems;
      this.gatherLooseItemsTimeoutSeconds = source.gatherLooseItemsTimeoutSeconds;
      this.gatherLooseItemsSettleSeconds = source.gatherLooseItemsSettleSeconds;
      this.agenticStorageSearchRadius = source.agenticStorageSearchRadius;
      this.agenticStoragePlacementRadius = source.agenticStoragePlacementRadius;
      this.agenticMaxTravelRadius = source.agenticMaxTravelRadius;
      this.agenticStorageAllowPlacement = source.agenticStorageAllowPlacement;
      this.agenticStoragePreferExisting = source.agenticStoragePreferExisting;
      this.agenticStorageAvoidLootChests = source.agenticStorageAvoidLootChests;
      this.agenticStorageResolveTimeoutSeconds = source.agenticStorageResolveTimeoutSeconds;
      this.agenticDepositTimeoutSeconds = source.agenticDepositTimeoutSeconds;
      this.agenticDepositKeepTools = source.agenticDepositKeepTools;
      this.agenticEnableLabelChest = source.agenticEnableLabelChest;
      this.agenticLabelTimeoutSeconds = source.agenticLabelTimeoutSeconds;
      this.agenticLabelUseModelText = source.agenticLabelUseModelText;
      this.enableDeferredSmelt = source.enableDeferredSmelt;
      this.deferredSmeltMaxBatch = source.deferredSmeltMaxBatch;
      this.enableSmithing = source.enableSmithing;
      this.smithMaxBatch = source.smithMaxBatch;
      this.deferredSmeltStallPolls = source.deferredSmeltStallPolls;
      this.deferredSmeltTimeoutSeconds = source.deferredSmeltTimeoutSeconds;
      this.aggregateCountDropRadius = source.aggregateCountDropRadius;
      this.aggregateLocalSourceBlockRadius = source.aggregateLocalSourceBlockRadius;
      this.mineCollectSettleSeconds = source.mineCollectSettleSeconds;
      this.wanderBoundDefaultSeconds = source.wanderBoundDefaultSeconds;
      this.wanderNoImprovementSeconds = source.wanderNoImprovementSeconds;
      this.throwawayItems = copyItems(source.throwawayItems);
      this.dontThrowAwayCustomNameItems = source.dontThrowAwayCustomNameItems;
      this.dontThrowAwayEnchantedItems = source.dontThrowAwayEnchantedItems;
      this.throwAwayUnusedItems = source.throwAwayUnusedItems;
      this.importantItems = copyItems(source.importantItems);
      this.limitFuelsToSupportedFuels = source.limitFuelsToSupportedFuels;
      this.supportedFuels = copyItems(source.supportedFuels);
      this.homeBasePosition = source.homeBasePosition == null ? null : source.homeBasePosition.immutable();
   }

   void normalizeAdminSupportedValues() {
      if (!isValidIdleCommand(this.idleCommand)) {
         this.idleCommand = "";
      }
      if (this.forceFieldStrategy == null) {
         this.forceFieldStrategy = KillAura.Strategy.SMART;
      }
      if (this.overworldToNetherBehaviour == null) {
         this.overworldToNetherBehaviour = DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR.BUILD_PORTAL_VANILLA;
      }

      this.resourcePickupDropRange = normalizeWhole(this.resourcePickupDropRange, 0, 128, 16.0F);
      this.minimumFoodAllowed = normalizeInt(this.minimumFoodAllowed, 0, 64);
      this.foodUnitsToCollect = normalizeInt(this.foodUnitsToCollect, 0, 64);
      if (!hasValidFoodSafety()) {
         this.foodUnitsToCollect = this.minimumFoodAllowed;
      }
      this.resourceChestLocateRange = normalizeWhole(this.resourceChestLocateRange, 0, 4096, 500.0F);
      this.resourceMineRange = normalizeWhole(this.resourceMineRange, 0, 4096, 100.0F);
      this.entityReachRange = normalizeWhole(this.entityReachRange, 1, 6, 4.0F);
      this.netherFastTravelWalkingRange = normalizeInt(this.netherFastTravelWalkingRange, 0, 100000);

      this.craftDelaySeconds = normalizeMilliseconds(this.craftDelaySeconds, 0, 5000, 0.5F);
      this.craftTableLookHoldSeconds = normalizeMilliseconds(this.craftTableLookHoldSeconds, 0, 3000, 0.25F);
      this.craftingTableReuseRadius = normalizeWhole(this.craftingTableReuseRadius, 4, 128, 48.0);
      this.agenticPlannerRagTopK = normalizeInt(this.agenticPlannerRagTopK, 1, 20);
      this.agenticPlannerMaxSteps = normalizeInt(this.agenticPlannerMaxSteps, 1, 4);
      this.gatherLooseItemsRadius = normalizeWhole(this.gatherLooseItemsRadius, 2, 64, 16.0F);
      this.gatherLooseItemsMaxItems = normalizeInt(this.gatherLooseItemsMaxItems, 1, 1024);
      this.gatherLooseItemsTimeoutSeconds = normalizeWhole(this.gatherLooseItemsTimeoutSeconds, 5, 300, 60.0F);
      this.gatherLooseItemsSettleSeconds = normalizeMilliseconds(this.gatherLooseItemsSettleSeconds, 500, 20000, 3.0F);
      this.agenticStorageSearchRadius = normalizeWhole(this.agenticStorageSearchRadius, 4, 64, 20.0F);
      this.agenticStoragePlacementRadius = normalizeWhole(this.agenticStoragePlacementRadius, 2, 8, 4.0F);
      this.agenticMaxTravelRadius = normalizeWhole(this.agenticMaxTravelRadius, 16, 160, 96.0F);
      this.agenticStorageResolveTimeoutSeconds = normalizeWhole(this.agenticStorageResolveTimeoutSeconds, 10, 300, 180.0F);
      this.agenticDepositTimeoutSeconds = normalizeWhole(this.agenticDepositTimeoutSeconds, 10, 300, 120.0F);
      this.agenticLabelTimeoutSeconds = normalizeWhole(this.agenticLabelTimeoutSeconds, 10, 180, 60.0F);
      this.deferredSmeltMaxBatch = normalizeInt(this.deferredSmeltMaxBatch, 1, 512);
      this.smithMaxBatch = normalizeInt(this.smithMaxBatch, 1, 512);
      this.deferredSmeltStallPolls = normalizeInt(this.deferredSmeltStallPolls, 20, 2000);
      this.deferredSmeltTimeoutSeconds = normalizeInt(this.deferredSmeltTimeoutSeconds, 60, 3600);
      this.aggregateCountDropRadius = normalizeWhole(this.aggregateCountDropRadius, 0, 64, 16.0F);
      this.aggregateLocalSourceBlockRadius = normalizeWhole(this.aggregateLocalSourceBlockRadius, 0, 128, 32.0F);
      this.mineCollectSettleSeconds = normalizeMilliseconds(this.mineCollectSettleSeconds, 0, 5000, 1.0F);
      this.wanderBoundDefaultSeconds = normalizeInt(this.wanderBoundDefaultSeconds, 0, 600);
      this.wanderNoImprovementSeconds = normalizeInt(this.wanderNoImprovementSeconds, 0, 300);
      this.ellieGpsSnapshotSlotThreshold = normalizeInt(this.ellieGpsSnapshotSlotThreshold, 1, 54);

      PlayerEngineSettings defaults = new PlayerEngineSettings();
      this.throwawayItems = normalizeItems(this.throwawayItems, defaults.throwawayItems);
      this.importantItems = normalizeItems(this.importantItems, defaults.importantItems);
      this.supportedFuels = normalizeItems(this.supportedFuels, defaults.supportedFuels);
      if (this.homeBasePosition == null) {
         this.homeBasePosition = new BlockPos(0, 64, 0);
      }
   }

   boolean hasValidFoodSafety() {
      return this.foodUnitsToCollect == 0 || this.foodUnitsToCollect >= this.minimumFoodAllowed;
   }

   void setAdminString(String key, String value) {
      switch (key) {
         case "idleCommand" -> this.idleCommand = value;
         default -> throw new IllegalArgumentException("Unsupported string setting: " + key);
      }
   }

   void setAdminBoolean(String key, boolean value) {
      switch (key) {
         case "avoidSearchingDungeonChests" -> this.avoidSearchingDungeonChests = value;
         case "avoidOceanBlocks" -> this.avoidOceanBlocks = value;
         case "collectPickaxeFirst" -> this.collectPickaxeFirst = value;
         case "replantCrops" -> this.replantCrops = value;
         case "mobDefense" -> this.mobDefense = value;
         case "dodgeProjectiles" -> this.dodgeProjectiles = value;
         case "killOrAvoidAnnoyingHostiles" -> this.killOrAvoidAnnoyingHostiles = value;
         case "avoidDrowning" -> this.avoidDrowning = value;
         case "extinguishSelfWithWater" -> this.extinguishSelfWithWater = value;
         case "autoEat" -> this.autoEat = value;
         case "hungerEnabled" -> this.hungerEnabled = value;
         case "autoMLGBucket" -> this.autoMLGBucket = value;
         case "fastCraftMacrosEnabled" -> this.fastCraftMacrosEnabled = value;
         case "preferLocalCraftingTable" -> this.preferLocalCraftingTable = value;
         case "enableAgenticPlanner" -> this.enableAgenticPlanner = value;
         case "enableLookAtOwnerIdle" -> this.enableLookAtOwnerIdle = value;
         case "agenticPlannerFallbackGather" -> this.agenticPlannerFallbackGather = value;
         case "agenticStorageAllowPlacement" -> this.agenticStorageAllowPlacement = value;
         case "agenticStoragePreferExisting" -> this.agenticStoragePreferExisting = value;
         case "agenticStorageAvoidLootChests" -> this.agenticStorageAvoidLootChests = value;
         case "agenticDepositKeepTools" -> this.agenticDepositKeepTools = value;
         case "agenticEnableLabelChest" -> this.agenticEnableLabelChest = value;
         case "agenticLabelUseModelText" -> this.agenticLabelUseModelText = value;
         case "enableDeferredSmelt" -> this.enableDeferredSmelt = value;
         case "enableSmithing" -> this.enableSmithing = value;
         case "ellieGpsEnabled" -> this.ellieGpsEnabled = value;
         case "ellieGpsUseModelDescription" -> this.ellieGpsUseModelDescription = value;
         case "dontThrowAwayCustomNameItems" -> this.dontThrowAwayCustomNameItems = value;
         case "dontThrowAwayEnchantedItems" -> this.dontThrowAwayEnchantedItems = value;
         case "throwAwayUnusedItems" -> this.throwAwayUnusedItems = value;
         case "limitFuelsToSupportedFuels" -> this.limitFuelsToSupportedFuels = value;
         default -> throw new IllegalArgumentException("Unsupported boolean setting: " + key);
      }
   }

   void setAdminInt(String key, int value) {
      switch (key) {
         case "minimumFoodAllowed" -> this.minimumFoodAllowed = value;
         case "foodUnitsToCollect" -> this.foodUnitsToCollect = value;
         case "netherFastTravelWalkingRange" -> this.netherFastTravelWalkingRange = value;
         case "agenticPlannerRagTopK" -> this.agenticPlannerRagTopK = value;
         case "agenticPlannerMaxSteps" -> this.agenticPlannerMaxSteps = value;
         case "gatherLooseItemsMaxItems" -> this.gatherLooseItemsMaxItems = value;
         case "deferredSmeltMaxBatch" -> this.deferredSmeltMaxBatch = value;
         case "smithMaxBatch" -> this.smithMaxBatch = value;
         case "deferredSmeltStallPolls" -> this.deferredSmeltStallPolls = value;
         case "deferredSmeltTimeoutSeconds" -> this.deferredSmeltTimeoutSeconds = value;
         case "wanderBoundDefaultSeconds" -> this.wanderBoundDefaultSeconds = value;
         case "wanderNoImprovementSeconds" -> this.wanderNoImprovementSeconds = value;
         case "ellieGpsSnapshotSlotThreshold" -> this.ellieGpsSnapshotSlotThreshold = value;
         default -> throw new IllegalArgumentException("Unsupported integer setting: " + key);
      }
   }

   void setAdminWholeNumber(String key, int value) {
      switch (key) {
         case "resourcePickupDropRange" -> this.resourcePickupDropRange = value;
         case "resourceChestLocateRange" -> this.resourceChestLocateRange = value;
         case "resourceMineRange" -> this.resourceMineRange = value;
         case "entityReachRange" -> this.entityReachRange = value;
         case "craftingTableReuseRadius" -> this.craftingTableReuseRadius = value;
         case "gatherLooseItemsRadius" -> this.gatherLooseItemsRadius = value;
         case "gatherLooseItemsTimeoutSeconds" -> this.gatherLooseItemsTimeoutSeconds = value;
         case "agenticStorageSearchRadius" -> this.agenticStorageSearchRadius = value;
         case "agenticStoragePlacementRadius" -> this.agenticStoragePlacementRadius = value;
         case "agenticMaxTravelRadius" -> this.agenticMaxTravelRadius = value;
         case "agenticStorageResolveTimeoutSeconds" -> this.agenticStorageResolveTimeoutSeconds = value;
         case "agenticDepositTimeoutSeconds" -> this.agenticDepositTimeoutSeconds = value;
         case "agenticLabelTimeoutSeconds" -> this.agenticLabelTimeoutSeconds = value;
         case "aggregateCountDropRadius" -> this.aggregateCountDropRadius = value;
         case "aggregateLocalSourceBlockRadius" -> this.aggregateLocalSourceBlockRadius = value;
         default -> throw new IllegalArgumentException("Unsupported whole-number setting: " + key);
      }
   }

   void setAdminMilliseconds(String key, int milliseconds) {
      float seconds = milliseconds / 1000.0F;
      switch (key) {
         case "craftDelaySeconds" -> this.craftDelaySeconds = seconds;
         case "craftTableLookHoldSeconds" -> this.craftTableLookHoldSeconds = seconds;
         case "gatherLooseItemsSettleSeconds" -> this.gatherLooseItemsSettleSeconds = seconds;
         case "mineCollectSettleSeconds" -> this.mineCollectSettleSeconds = seconds;
         default -> throw new IllegalArgumentException("Unsupported millisecond setting: " + key);
      }
   }

   void setAdminForceFieldStrategy(KillAura.Strategy value) {
      this.forceFieldStrategy = value;
   }

   void setAdminOverworldToNetherBehaviour(DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR value) {
      this.overworldToNetherBehaviour = value;
   }

   void setAdminItems(String key, List<Item> value) {
      List<Item> copy = List.copyOf(value);
      switch (key) {
         case "throwawayItems" -> this.throwawayItems = copy;
         case "importantItems" -> this.importantItems = copy;
         case "supportedFuels" -> this.supportedFuels = copy;
         default -> throw new IllegalArgumentException("Unsupported item-list setting: " + key);
      }
   }

   void setAdminHomeBasePosition(BlockPos value) {
      this.homeBasePosition = value.immutable();
   }

   List<Item> adminThrowawayItems() {
      return List.copyOf(this.throwawayItems);
   }

   List<Item> adminImportantItems() {
      return List.copyOf(this.importantItems);
   }

   List<Item> adminSupportedFuels() {
      return List.copyOf(this.supportedFuels);
   }

   private static boolean isValidIdleCommand(String value) {
      return value != null && value.codePointCount(0, value.length()) <= 128
            && value.codePoints().noneMatch(java.lang.Character::isISOControl);
   }

   private static int normalizeInt(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   private static float normalizeWhole(float value, int min, int max, float fallback) {
      float safe = Float.isFinite(value) ? value : fallback;
      return normalizeInt(Math.round(safe), min, max);
   }

   private static double normalizeWhole(double value, int min, int max, double fallback) {
      double safe = Double.isFinite(value) ? value : fallback;
      long rounded = Math.round(safe);
      return Math.max(min, Math.min(max, rounded));
   }

   private static float normalizeMilliseconds(float seconds, int minMs, int maxMs, float fallback) {
      float safe = Float.isFinite(seconds) ? seconds : fallback;
      int milliseconds = normalizeInt(Math.round(safe * 1000.0F), minMs, maxMs);
      return milliseconds / 1000.0F;
   }

   private static List<Item> normalizeItems(List<Item> source, List<Item> fallback) {
      List<Item> selected = source == null ? fallback : source;
      LinkedHashSet<Item> unique = new LinkedHashSet<>();
      int serializedLength = 0;
      for (Item item : selected) {
         if (item == null || item == Items.AIR || unique.contains(item)) {
            continue;
         }
         ResourceLocation id = BuiltInRegistries.ITEM.getResourceKey(item)
               .map(key -> key.location())
               .orElse(null);
         if (id == null) {
            continue;
         }
         int addedLength = id.toString().length() + (unique.isEmpty() ? 0 : 1);
         if (serializedLength + addedLength > ADMIN_ITEM_LIST_MAX_SERIALIZED_LENGTH) {
            break;
         }
         unique.add(item);
         serializedLength += addedLength;
         if (unique.size() == 64) {
            break;
         }
      }
      return List.copyOf(unique);
   }

   private static List<Item> copyItems(List<Item> source) {
      return source == null ? null : List.copyOf(source);
   }

   private static List<BlockRange> copyBlockRanges(List<BlockRange> source) {
      if (source == null) {
         return null;
      }
      List<BlockRange> copy = new java.util.ArrayList<>(source.size());
      for (BlockRange range : source) {
         copy.add(range == null ? null : new BlockRange(
               range.start == null ? null : range.start.immutable(),
               range.end == null ? null : range.end.immutable(),
               range.dimension));
      }
      return Collections.unmodifiableList(copy);
   }

   private static double clamp(float value, float min, float max) {
      return Math.max(min, Math.min(max, value));
   }

   private static double clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   @Override
   public void onFailLoad() {
      this.failedToLoad = true;
   }

   @Override
   public boolean failedToLoad() {
      return this.failedToLoad;
   }
}
