package com.player2.playerengine;

import com.player2.playerengine.automaton.AdditionalBaritoneSettings;
import com.player2.playerengine.chains.FoodChain;
import com.player2.playerengine.chains.MLGBucketFallChain;
import com.player2.playerengine.chains.ForceEquipGearChain;
import com.player2.playerengine.chains.MobDefenseChain;
import com.player2.playerengine.chains.PlayerDefenseChain;
import com.player2.playerengine.chains.PlayerInteractionFixChain;
import com.player2.playerengine.chains.PreEquipItemChain;
import com.player2.playerengine.chains.UnstuckChain;
import com.player2.playerengine.chains.UserTaskChain;
import com.player2.playerengine.chains.WorldSurvivalChain;
import com.player2.playerengine.commands.BlockScanner;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.control.InputControls;
import com.player2.playerengine.control.PlayerExtraController;
import com.player2.playerengine.control.SlotHandler;
import com.player2.playerengine.equip.ExplicitEquipPolicy;
import com.player2.playerengine.equip.PickupArmorEvalQueue;
import com.player2.playerengine.equip.PickupWeaponEvalQueue;

import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.player2api.AIPersistantData;
import com.player2.playerengine.player2api.Player2APIService;

import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.tasks.movement.BodyLanguageTask;
import com.player2.playerengine.trackers.*;
import com.player2.playerengine.trackers.storage.ContainerSubTracker;
import com.player2.playerengine.trackers.storage.ItemStorageTracker;
import com.player2.playerengine.trackers.storage.SurvivalConsumptionLedger;
import com.player2.playerengine.automaton.Baritone;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.automaton.api.utils.IEntityContext;
import com.player2.playerengine.automaton.api.utils.IInteractionController;
import com.player2.playerengine.trackers.CacheTracker;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.player2.playerengine.executor.IStepExecutorAdapter;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.Playground;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

public class PlayerEngineController {
   private final IBaritone baritone;
   private AIPersistantData aiPersistantData;
   private Player2APIService player2apiService;
   private final IEntityContext ctx;
   private CommandExecutor commandExecutor;
   private TaskRunner taskRunner;
   private TrackerManager trackerManager;
   private BotBehaviour botBehaviour;
   private UserTaskChain userTaskChain;
   private TaskStepExecutorAdapter stepExecutorAdapter;
   private FoodChain foodChain;
   private MobDefenseChain mobDefenseChain;
   private ForceEquipGearChain forceEquipGearChain;
   private MLGBucketFallChain mlgBucketChain;
   private ItemStorageTracker storageTracker;
   private ContainerSubTracker containerSubTracker;
   private EntityTracker entityTracker;
   private BlockScanner blockScanner;
   private SimpleChunkTracker chunkTracker;
   private CacheTracker cacheTracker;
   private MiscBlockTracker miscBlockTracker;
   private CraftingRecipeTracker craftingRecipeTracker;
   private EntityStuckTracker entityStuckTracker;
   private UserBlockRangeTracker userBlockRangeTracker;
   private InputControls inputControls;
   private SlotHandler slotHandler;
   private final ExplicitEquipPolicy explicitEquipPolicy = new ExplicitEquipPolicy();
   private final SurvivalConsumptionLedger survivalConsumptionLedger = new SurvivalConsumptionLedger();
   private final PickupArmorEvalQueue pickupArmorEvalQueue;
   private final PickupWeaponEvalQueue pickupWeaponEvalQueue;
   private PlayerExtraController extraController;
   private PlayerEngineSettings settings;
   private PlayerEngineSettings stagedSettings;
   private Task observedUserTask;
   private int stagedAdoptionFailures;
   private volatile long settingsPublicationGeneration;
   private volatile SettingsLiveState settingsLiveState = SettingsLiveState.APPLIED;
   private ChunkLoadingTracker chunkLoader;
   private boolean paused = false;
   private Task storedTask;
   public boolean isStopping = false;
   private Player owner;
   public static HashMap<UUID, Player2APIService> staticAPIServices = new HashMap<>();
   /** Registry of all active PlayerEngineController instances by bot entity UUID. Used by server admin commands. */
   public static final ConcurrentHashMap<UUID, PlayerEngineController> staticControllers = new ConcurrentHashMap<>();
   /** PlayerEngine-owned additions, keyed by the identity of Baritone's shared mutable setting list. */
   private static final IdentityHashMap<List<Item>, LinkedHashSet<Item>> THROWAWAY_INJECTIONS =
         new IdentityHashMap<>();
   private static final AtomicLong SETTINGS_PUBLICATION_GENERATION = new AtomicLong();
   private boolean shouldDefendFromHostiles = false;
   /** FollowDiag beta logging (state-change-only); mirrors the LOGGER field on the other FollowDiag classes. */
   private static final Logger LOGGER = LogManager.getLogger();
   /** Per-companion follow behavior. Default NORMAL; setter drives shouldDefendFromHostiles as a derived side-effect. */
   private FollowMode followMode = FollowMode.NORMAL;

   /**
    * Throttle floor (ms) for non-milestone agentic progress lines. Identical across branches so a
    * given run produces the same broadcast cadence everywhere. Milestone/failure lines bypass this
    * interval (see {@link #reportAgenticProgress(String, boolean)}). Constant-only for C4; a runtime
    * config knob (agenticProgressMinIntervalMs) is deferred to a follow-up.
    */
   private static final long MIN_REPORT_INTERVAL_MS = 1500L;
   /** Last wall-clock time an agentic progress line was broadcast to a player for this bot. */
   private long lastAgenticReportMs = 0L;
   /** Last agentic progress message broadcast for this bot, for consecutive-duplicate suppression. */
   private String lastAgenticReportMessage = "";

   public PlayerEngineController(IBaritone baritone, Character character, String player2GameId) {
      this(baritone, character, player2GameId, null);
   }

   public PlayerEngineController(IBaritone baritone, Character character, String player2GameId, Player owner) {
      this.owner = owner;
      this.baritone = baritone;
      this.ctx = baritone.getEntityContext();
      this.commandExecutor = new CommandExecutor(this);
      this.taskRunner = new TaskRunner(this);
      this.trackerManager = new TrackerManager(this);
      this.userTaskChain = new UserTaskChain(this.taskRunner);
      this.stepExecutorAdapter = new TaskStepExecutorAdapter(this);
      this.mobDefenseChain = new MobDefenseChain(this.taskRunner);
      new PlayerInteractionFixChain(this.taskRunner);
      this.mlgBucketChain = new MLGBucketFallChain(this.taskRunner);
      new UnstuckChain(this.taskRunner);
      new PreEquipItemChain(this.taskRunner);
      new WorldSurvivalChain(this.taskRunner);
      this.foodChain = new FoodChain(this.taskRunner);
      this.forceEquipGearChain = new ForceEquipGearChain(this.taskRunner);
      new PlayerDefenseChain(this.taskRunner);
      this.storageTracker = new ItemStorageTracker(this, this.trackerManager,
            container -> this.containerSubTracker = container);
      this.entityTracker = new EntityTracker(this.trackerManager);
      this.blockScanner = new BlockScanner(this);
      this.chunkTracker = new SimpleChunkTracker(this);
      this.miscBlockTracker = new MiscBlockTracker(this);
      this.craftingRecipeTracker = new CraftingRecipeTracker(this.trackerManager);
      this.entityStuckTracker = new EntityStuckTracker(this.trackerManager);
      this.userBlockRangeTracker = new UserBlockRangeTracker(this.trackerManager);
      this.inputControls = new InputControls(this);
      this.slotHandler = new SlotHandler(this);
      this.pickupArmorEvalQueue = new PickupArmorEvalQueue(this);
      this.pickupWeaponEvalQueue = new PickupWeaponEvalQueue(this);
      this.extraController = new PlayerExtraController(this);
      this.initializeBaritoneSettings();
      this.botBehaviour = new BotBehaviour(this);
      this.cacheTracker = new CacheTracker(this);
      this.initializeCommands();
      PlayerEngineSettings.load(
            newSettings -> {
               this.applyInitiallyLoadedSettings(newSettings);
               if ((!this.getUserTaskChain().isActive() || this.getUserTaskChain().isRunningIdleTask())
                      && this.getModSettings().shouldRunIdleCommandWhenNotActive()) {
                  this.getUserTaskChain().runIdleCommand(() -> this.getCommandExecutor()
                        .executeWithPrefix(this.getModSettings().getIdleCommand()));
               }

               this.getExtraBaritoneSettings().avoidBlockBreak(this.userBlockRangeTracker::isNearUserTrackedBlock);
               this.getExtraBaritoneSettings().avoidBlockPlace(this.entityStuckTracker::isBlockedByEntity);
            });
      Playground.IDLE_TEST_INIT_FUNCTION(this);

      // AI setup: (should be at end to ensure as many things are not null as
      // possible)
      ConversationManager.getOrCreateEventQueueData(this);
      this.aiPersistantData = new AIPersistantData(this, character);
      this.player2apiService = new Player2APIService(this, player2GameId);
      staticAPIServices.put(this.getEntity().getUUID(), this.player2apiService);
      staticControllers.put(this.getEntity().getUUID(), this);
      this.chunkLoader = new ChunkLoadingTracker(this);
   }

   public void serverTick() {
      this.observeSettingsTaskBoundary();
      this.inputControls.onTickPre();
      this.storageTracker.setDirty();
      this.miscBlockTracker.tick();
      this.trackerManager.tick();
      this.blockScanner.tick();
      this.taskRunner.tick();
      this.cacheTracker.tick();
      this.inputControls.onTickPost();
      this.baritone.serverTick();
      this.player2apiService.trySendHeartbeat();
      this.chunkLoader.tick();
   }

   public static void staticServerTick(MinecraftServer server) {
      com.player2.playerengine.util.time.TimerGame.incrementServerTick();
      ConversationManager.injectOnTick(server);
      if (server != null && server.getTickCount() % 200 == 0) {
         pruneStaleControllers(server);
      }
   }

   /**
    * Removes this controller from global registries (admin commands, API routing, agentic snapshots).
    * Call when the companion entity is removed or discarded — not on {@link #stop()} alone.
    */
   public void unregisterFromGlobalRegistry() {
      unregisterFromGlobalRegistry(this.getEntity().getUUID());
   }

   public static void unregisterFromGlobalRegistry(UUID entityUuid) {
      if (entityUuid == null) {
         return;
      }
      staticControllers.remove(entityUuid);
      staticAPIServices.remove(entityUuid);
      AgenticRunRegistry.clear(entityUuid);
   }

   /**
    * Drops registry entries whose entity no longer exists on the server (despawn, world unload, death).
    *
    * @return number of stale entries removed
    */
   public static int pruneStaleControllers(MinecraftServer server) {
      if (server == null || staticControllers.isEmpty()) {
         return 0;
      }
      int removed = 0;
      for (Iterator<Map.Entry<UUID, PlayerEngineController>> it = staticControllers.entrySet().iterator();
           it.hasNext(); ) {
         Map.Entry<UUID, PlayerEngineController> entry = it.next();
         UUID uuid = entry.getKey();
         PlayerEngineController controller = entry.getValue();
         if (!isControllerEntityActive(server, controller, uuid)) {
            it.remove();
            staticAPIServices.remove(uuid);
            AgenticRunRegistry.clear(uuid);
            removed++;
         }
      }
      return removed;
   }

   private static boolean isControllerEntityActive(MinecraftServer server,
                                                   PlayerEngineController controller,
                                                   UUID entityUuid) {
      if (controller == null) {
         return false;
      }
      try {
         LivingEntity entity = controller.getEntity();
         if (entity == null || entity.isRemoved()) {
            return false;
         }
         if (!entity.isAlive()) {
            return false;
         }
         if (entity.level() instanceof ServerLevel serverLevel && serverLevel.getEntity(entityUuid) == entity) {
            return true;
         }
         for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(entityUuid) == entity) {
               return true;
            }
         }
         // Chunk unloaded: keep registry entry until the entity is actually removed.
         return true;
      } catch (RuntimeException ignored) {
         return false;
      }
   }

   /** Label for {@code chain status}: character display name + short entity id. */
   public String getChainStatusDisplayName() {
      Character character = this.getAIPersistantData() != null ? this.getAIPersistantData().getCharacter() : null;
      String name = character != null ? character.shortName() : this.getEntity().getName().getString();
      String shortId = this.getEntity().getUUID().toString().substring(0, 8);
      return name + " (" + shortId + ")";
   }

   /**
    * Stops Baritone/input and cancels the user task chain. When a tracked step is running,
    * {@link StopReason#CANCELLED_OPERATOR} is recorded on the step execution log.
    */
   public void stop() {
      stop(StopReason.CANCELLED_OPERATOR);
   }

   /**
    * Same as {@link #stop()} but records {@code trackedStepCancelReason} on the active
    * {@link TaskStepExecutorAdapter} when applicable (e.g. disconnect policy).
    */
   public void stop(StopReason trackedStepCancelReason) {
      this.stepExecutorAdapter.armPendingChainCancel(trackedStepCancelReason);
      this.getUserTaskChain().cancel(this);
      if (this.taskRunner.getCurrentTaskChain() != null) {
         this.taskRunner.getCurrentTaskChain().stop();
      }

      this.getTaskRunner().disable();
      this.getBaritone().getPathingBehavior().forceCancel();
      this.getBaritone().getInputOverrideHandler().clearAllKeys();
   }

   /**
    * Despawn/dismiss/removal hook (cross-mod contract with Player2NPC). When the companion is torn
    * down while a task or agentic run is in flight, the run silently vanished and the model's next
    * turn began with a fresh greeting and no knowledge of what was running — so the model could (and
    * did) falsely claim the task succeeded. This emits the interruption to BOTH audiences (DESIGN.md
    * §3) before {@code stop} cancels the chains:
    * <ul>
    *   <li>the player gets a concise chat line (only when an owner ServerPlayer is online);</li>
    *   <li>the model gets an InfoMessage on THIS companion's conversation queue, enqueued BEFORE the
    *       call site's {@code ConversationManager.despwnCompanion} wipes that queue.</li>
    * </ul>
    * MUST be called by Player2NPC despawn sites in place of {@code stop()}, ahead of
    * {@code unregisterFromGlobalRegistry()} and {@code despwnCompanion(uuid)}. {@code ownerPlayer} may
    * be {@code null} (e.g. the entity {@code remove} path with the owner offline): the player line is
    * then skipped but the model InfoMessage is still enqueued, preserving truthfulness.
    */
   public void stopWithRespawnNotification(ServerPlayer ownerPlayer) {
      boolean active = this.hasActiveNonIdleUserTask()
            || AgenticRunRegistry.get(this.getEntity().getUUID()).isPresent();
      if (active) {
         // Model-facing (truthfulness-directed): enqueue onto THIS companion's queue before it is wiped.
         AiConversationFeedback.enqueueInfo(this,
               "You were despawned while a task was running. The task has been cancelled and will not"
                     + " resume. Do not claim the task completed or succeeded.");
         // Player-facing: concise human chat line, only when the owner is an online ServerPlayer.
         if (ownerPlayer != null && ownerPlayer.getServer() != null) {
            Character character = this.getAIPersistantData() != null
                  ? this.getAIPersistantData().getCharacter() : null;
            Component nameComponent = character != null && character.shortName() != null
                  ? Component.literal(character.shortName())
                  : Component.translatable("message.playerengine.agent.companion_default_name");
            AgentSideEffects.broadcastChatToPlayer(ownerPlayer.getServer(),
                  Component.translatable("message.playerengine.agent.despawned_with_task", nameComponent),
                  ownerPlayer);
         }
      }
      this.stop(StopReason.CANCELLED_RESPAWN);
   }

   private void initializeBaritoneSettings() {
      this.getExtraBaritoneSettings().canWalkOnEndPortal(false);
      this.getExtraBaritoneSettings().avoidBlockPlace(this.entityStuckTracker::isBlockedByEntity);
      this.getExtraBaritoneSettings().avoidBlockBreak(this.userBlockRangeTracker::isNearUserTrackedBlock);
      this.getBaritoneSettings().freeLook.set(false);
      this.getBaritoneSettings().overshootTraverse.set(true);
      this.getBaritoneSettings().allowOvershootDiagonalDescend.set(true);
      this.getBaritoneSettings().allowInventory.set(true);
      this.getBaritoneSettings().allowParkour.set(false);
      this.getBaritoneSettings().allowParkourAscend.set(false);
      this.getBaritoneSettings().allowParkourPlace.set(false);
      this.getBaritoneSettings().allowDiagonalDescend.set(false);
      this.getBaritoneSettings().allowDiagonalAscend.set(false);
      this.getBaritoneSettings().fadePath.set(true);
      this.getBaritoneSettings().mineScanDroppedItems.set(false);
      this.getBaritoneSettings().mineDropLoiterDurationMSThanksLouca.set(0L);
      this.getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);
      this.getBaritoneSettings().randomLooking.set(0.0);
      this.getBaritoneSettings().randomLooking113.set(0.0);
      this.getBaritoneSettings().failureTimeoutMS.reset();
      this.getBaritoneSettings().planAheadFailureTimeoutMS.reset();
      this.getBaritoneSettings().movementTimeoutTicks.reset();
   }

   private void initializeCommands() {
      try {
         PlayerEngineCommands.init(this);
      } catch (Exception var2) {
         var2.printStackTrace();
      }
   }

   public void runUserTask(Task task, Runnable onFinish) {
      this.userTaskChain.runTask(this, task, onFinish);
   }

   public void runUserTask(Task task) {
      this.runUserTask(task, () -> {
      });
   }

   /** Installs a scheduler-visible idle policy task without transient next-task state. */
   public void runIdleUserTask(Task task, Runnable onFinish) {
      this.userTaskChain.runIdleTask(this, task, onFinish);
   }

   public void runIdleUserTask(Task task) {
      this.runIdleUserTask(task, () -> {
      });
   }

   /** Clears a policy-idle task without cancelling or replacing real user work. */
   public boolean clearPolicyIdleUserTask() {
      return this.userTaskChain.clearPolicyIdleTask();
   }

   /** Pre-mutation gate for tracked work emitted by the configured idle command. */
   public boolean rejectsConfiguredIdleTrackedSubmission() {
      return this.userTaskChain.rejectsConfiguredIdleTrackedSubmission();
   }

   /**
    * Submit a task for tracked execution through the Phase A2 executor adapter.
    *
    * The adapter validates preconditions, records PENDING -> RUNNING ->
    * SUCCEEDED/FAILED state transitions, and always invokes {@code onComplete}
    * at the terminal state so CommandExecutor's sequential chain is preserved.
    *
    * @param stepId         Stable log identifier for this step.
    * @param stepKind       Action family descriptor (e.g. {@code follow_player}).
    * @param task           Pre-built Task to execute on UserTaskChain.
    * @param rollbackPolicy Compensating action policy on failure.
    * @param onComplete     Callback invoked at any terminal state.
    * @return The StepExecution tracker for observing state and log.
    */
   public StepExecution runUserTaskTracked(String stepId, String stepKind,
                                           Task task, RollbackPolicy rollbackPolicy,
                                           Runnable onComplete) {
      return this.stepExecutorAdapter.submit(stepId, stepKind, task, rollbackPolicy, onComplete);
   }

   public IStepExecutorAdapter getStepExecutorAdapter() {
      return this.stepExecutorAdapter;
   }

   public void cancelUserTask() {
      this.userTaskChain.cancel(this);
   }

   // Incremented by every command line CommandExecutor runs, whatever sent it. A plan step that
   // finishes after a later dispatch was superseded, even though it reports Finished.
   private final java.util.concurrent.atomic.AtomicLong commandDispatchSeq = new java.util.concurrent.atomic.AtomicLong();
   private volatile com.player2.playerengine.tasks.construction.area.AreaSpec.Box lastArea;
   private volatile String planStatusLine = "";
   private volatile long lastOwnerMessageMillis;

   public long bumpCommandDispatchSeq() {
      return this.commandDispatchSeq.incrementAndGet();
   }

   public long getCommandDispatchSeq() {
      return this.commandDispatchSeq.get();
   }

   /** The last box an area command finished, for {@code anchor=last} and the model's status. */
   public com.player2.playerengine.tasks.construction.area.AreaSpec.Box getLastArea() {
      return this.lastArea;
   }

   public void setLastArea(com.player2.playerengine.tasks.construction.area.AreaSpec.Box box) {
      this.lastArea = box;
   }

   public String getPlanStatusLine() {
      return this.planStatusLine;
   }

   public void setPlanStatusLine(String line) {
      this.planStatusLine = line == null ? "" : line;
   }

   /** Wall-clock time of the last chat line from the authenticated owner. */
   public long getLastOwnerMessageMillis() {
      return this.lastOwnerMessageMillis;
   }

   public void markOwnerMessage(long millis) {
      this.lastOwnerMessageMillis = millis;
   }

   public CommandExecutor getCommandExecutor() {
      return this.commandExecutor;
   }

   public LivingEntity getEntity() {
      return this.ctx.entity();
   }

   public ServerLevel getWorld() {
      return this.ctx.world();
   }

   public IInteractionController getInteractionManager() {
      return this.ctx.playerController();
   }

   public IBaritone getBaritone() {
      return this.baritone;
   }

   public com.player2.playerengine.automaton.api.Settings getBaritoneSettings() {
      return this.baritone.settings();
   }

   public AdditionalBaritoneSettings getExtraBaritoneSettings() {
      return ((Baritone) this.baritone).getExtraBaritoneSettings();
   }

   public TaskRunner getTaskRunner() {
      return this.taskRunner;
   }

   public UserTaskChain getUserTaskChain() {
      return this.userTaskChain;
   }

   public boolean hasActiveNonIdleUserTask() {
      return this.userTaskChain.hasActiveNonIdleUserTask();
   }

   public java.util.Optional<com.player2.playerengine.executor.StepExecution> getActiveTrackedStep() {
      return this.stepExecutorAdapter.getRunningExecution();
   }

   public BotBehaviour getBehaviour() {
      return this.botBehaviour;
   }

   public boolean isPaused() {
      return this.paused;
   }

   public void setPaused(boolean pausing) {
      this.paused = pausing;
   }

   public Task getStoredTask() {
      return this.storedTask;
   }

   public void setStoredTask(Task currentTask) {
      this.storedTask = currentTask;
   }

   public ItemStorageTracker getItemStorage() {
      return this.storageTracker;
   }

   public SurvivalConsumptionLedger getSurvivalConsumptionLedger() {
      return this.survivalConsumptionLedger;
   }

   public EntityTracker getEntityTracker() {
      return this.entityTracker;
   }

   public CraftingRecipeTracker getCraftingRecipeTracker() {
      return this.craftingRecipeTracker;
   }

   public BlockScanner getBlockScanner() {
      return this.blockScanner;
   }

   public SimpleChunkTracker getChunkTracker() {
      return this.chunkTracker;
   }

   public MiscBlockTracker getMiscBlockTracker() {
      return this.miscBlockTracker;
   }

   public PlayerEngineSettings getModSettings() {
      if (this.settings == null) {
         this.settings = new PlayerEngineSettings();
      }
      return this.settings;
   }

   public record SettingsPublicationOutcome(int controllerCount, int appliedCount,
                                            int pendingCount, int failedCount) {
      public boolean fullyApplied() {
         return this.pendingCount == 0 && this.failedCount == 0;
      }

      public boolean hasFailures() {
         return this.failedCount > 0;
      }
   }

   public enum SettingsLiveState {
      APPLIED,
      PENDING,
      FAILED
   }

   private enum ControllerPublicationState {
      APPLIED,
      PENDING,
      FAILED
   }

   public static SettingsPublicationOutcome publishSettingsFromAdmin(PlayerEngineSettings settings) {
      if (settings == null) {
         return new SettingsPublicationOutcome(1, 0, 0, 1);
      }
      List<PlayerEngineController> controllers;
      try {
         controllers = List.copyOf(staticControllers.values());
      } catch (RuntimeException e) {
         LOGGER.warn("Unable to snapshot PlayerEngine controllers for an admin settings publication.", e);
         return new SettingsPublicationOutcome(1, 0, 0, 1);
      }
      long generation = SETTINGS_PUBLICATION_GENERATION.incrementAndGet();
      int applied = 0;
      int pending = 0;
      int failed = 0;
      for (PlayerEngineController controller : controllers) {
         ControllerPublicationState state = ControllerPublicationState.FAILED;
         try {
            LivingEntity player = controller.getPlayer();
            MinecraftServer server = player == null ? null : player.getServer();
            if (server == null) {
               controller.markSettingsPublicationState(generation, SettingsLiveState.FAILED);
               state = ControllerPublicationState.FAILED;
            } else {
               PlayerEngineSettings controllerCopy = settings.defensiveCopy();
               if (server.isSameThread()) {
                  state = applyAdminSettingsSafely(controller, controllerCopy, server, generation, true);
               } else {
                  controller.markSettingsPublicationState(generation, SettingsLiveState.PENDING);
                  server.execute(() -> applyAdminSettingsSafely(
                        controller, controllerCopy, server, generation, true));
                  state = ControllerPublicationState.PENDING;
               }
            }
         } catch (RuntimeException e) {
            controller.markSettingsPublicationState(generation, SettingsLiveState.FAILED);
            LOGGER.warn("Unable to publish admin settings to one PlayerEngine controller; continuing.", e);
         }
         switch (state) {
            case APPLIED -> applied++;
            case PENDING -> pending++;
            case FAILED -> failed++;
         }
      }
      return new SettingsPublicationOutcome(controllers.size(), applied, pending, failed);
   }

   public static SettingsLiveState currentSettingsLiveState() {
      SettingsLiveState aggregate = SettingsLiveState.APPLIED;
      try {
         for (PlayerEngineController controller : staticControllers.values()) {
            if (controller == null) {
               continue;
            }
            SettingsLiveState state = controller.settingsLiveState;
            if (state == SettingsLiveState.FAILED) {
               return SettingsLiveState.FAILED;
            }
            if (state == SettingsLiveState.PENDING) {
               aggregate = SettingsLiveState.PENDING;
            }
         }
      } catch (RuntimeException e) {
         LOGGER.warn("Unable to inspect PlayerEngine live settings state.", e);
         return SettingsLiveState.FAILED;
      }
      return aggregate;
   }

   private static ControllerPublicationState applyAdminSettingsSafely(
         PlayerEngineController controller,
         PlayerEngineSettings settings,
         MinecraftServer server,
         long generation,
      boolean retryAllowed) {
      try {
         return controller.applyAdminSettings(settings, generation);
      } catch (RuntimeException e) {
         LOGGER.warn("Unable to apply admin settings to one PlayerEngine controller.", e);
         if (retryAllowed && server != null) {
            try {
               server.tell(new TickTask(server.getTickCount() + 1,
                     () -> applyAdminSettingsSafely(controller, settings, server, generation, false)));
               controller.markSettingsPublicationState(generation, SettingsLiveState.PENDING);
               return ControllerPublicationState.PENDING;
            } catch (RuntimeException retryScheduleFailure) {
               LOGGER.warn("Unable to schedule one PlayerEngine admin settings retry.", retryScheduleFailure);
            }
         }
         controller.markSettingsPublicationState(generation, SettingsLiveState.FAILED);
         return ControllerPublicationState.FAILED;
      }
   }

   private void applyInitiallyLoadedSettings(PlayerEngineSettings newSettings) {
      PlayerEngineSettings initial = newSettings != null
            ? newSettings.defensiveCopy() : new PlayerEngineSettings();
      this.reconcileAcceptableThrowawayItems(initial);
      this.settings = initial;
      this.stagedSettings = null;
      this.stagedAdoptionFailures = 0;
      this.settingsLiveState = SettingsLiveState.APPLIED;
   }

   private synchronized ControllerPublicationState applyAdminSettings(
         PlayerEngineSettings newSettings, long generation) {
      if (newSettings == null) {
         throw new IllegalArgumentException("Admin settings publication cannot be null");
      }
      if (generation < this.settingsPublicationGeneration) {
         return switch (this.settingsLiveState) {
            case APPLIED -> ControllerPublicationState.APPLIED;
            case PENDING -> ControllerPublicationState.PENDING;
            case FAILED -> ControllerPublicationState.FAILED;
         };
      }
      PlayerEngineSettings desired = newSettings.defensiveCopy();
      Task currentTask = this.userTaskChain == null ? null : this.userTaskChain.getCurrentTask();
      boolean activeRealTask = this.userTaskChain != null
            && this.userTaskChain.hasActiveNonIdleUserTask();
      if (activeRealTask) {
         PlayerEngineSettings live = desired.defensiveCopy();
         live.copyNextTaskValuesFrom(this.getModSettings());
         this.settings = live;
         this.stagedSettings = desired;
         this.stagedAdoptionFailures = 0;
         if (!(currentTask instanceof BodyLanguageTask)) {
            this.observedUserTask = currentTask;
         }
         this.settingsPublicationGeneration = generation;
         this.settingsLiveState = SettingsLiveState.PENDING;
         return ControllerPublicationState.PENDING;
      }

      this.reconcileAcceptableThrowawayItems(desired);
      this.settings = desired;
      this.stagedSettings = null;
      this.observedUserTask = null;
      this.stagedAdoptionFailures = 0;
      this.settingsPublicationGeneration = generation;
      this.settingsLiveState = SettingsLiveState.APPLIED;
      return ControllerPublicationState.APPLIED;
   }

   private synchronized void markSettingsPublicationState(long generation, SettingsLiveState state) {
      if (state != null && generation >= this.settingsPublicationGeneration) {
         this.settingsPublicationGeneration = generation;
         this.settingsLiveState = state;
      }
   }

   private void observeSettingsTaskBoundary() {
      if (this.userTaskChain == null) {
         return;
      }
      Task currentTask = this.userTaskChain.getCurrentTask();
      if (currentTask instanceof BodyLanguageTask) {
         // A transient gesture is an overlay, not a next-task boundary. Preserve the underlying
         // task identity so resuming it cannot activate staged settings mid-task.
         return;
      }
      if (!this.userTaskChain.hasActiveNonIdleUserTask() || currentTask == null) {
         this.observedUserTask = null;
         this.adoptStagedSettings();
      } else if (this.observedUserTask == null) {
         this.observedUserTask = currentTask;
      } else if (this.observedUserTask != currentTask) {
         this.observedUserTask = currentTask;
         this.adoptStagedSettings();
      }
   }

   private void adoptStagedSettings() {
      if (this.stagedSettings == null || this.stagedAdoptionFailures >= 2) {
         return;
      }
      PlayerEngineSettings desired = this.stagedSettings;
      try {
         this.reconcileAcceptableThrowawayItems(desired);
         this.settings = desired;
         this.stagedSettings = null;
         this.stagedAdoptionFailures = 0;
         this.settingsLiveState = SettingsLiveState.APPLIED;
      } catch (RuntimeException e) {
         this.stagedAdoptionFailures++;
         this.settingsLiveState = this.stagedAdoptionFailures >= 2
               ? SettingsLiveState.FAILED : SettingsLiveState.PENDING;
         LOGGER.warn("Unable to adopt staged PlayerEngine settings at a task boundary (attempt {}/2).",
               this.stagedAdoptionFailures, e);
      }
   }

   private void reconcileAcceptableThrowawayItems(PlayerEngineSettings newSettings) {
      List<Item> acceptable = this.getBaritoneSettings().acceptableThrowawayItems.get();
      LinkedHashSet<Item> configured = new LinkedHashSet<>();
      for (Item item : newSettings.getThrowawayItems(this, true)) {
         if (item != null) {
            configured.add(item);
         }
      }
      synchronized (THROWAWAY_INJECTIONS) {
         List<Item> underlying = new ArrayList<>(acceptable);
         LinkedHashSet<Item> previouslyInjected = THROWAWAY_INJECTIONS.get(acceptable);
         if (previouslyInjected != null) {
            for (Item item : previouslyInjected) {
               underlying.remove(item);
            }
         }

         LinkedHashSet<Item> underlyingItems = new LinkedHashSet<>(underlying);
         LinkedHashSet<Item> newlyInjected = new LinkedHashSet<>();
         for (Item item : configured) {
            if (underlyingItems.add(item)) {
               underlying.add(item);
               newlyInjected.add(item);
            }
         }

         acceptable.clear();
         acceptable.addAll(underlying);
         if (newlyInjected.isEmpty()) {
            THROWAWAY_INJECTIONS.remove(acceptable);
         } else {
            THROWAWAY_INJECTIONS.put(acceptable, newlyInjected);
         }
      }
   }

   public FoodChain getFoodChain() {
      return this.foodChain;
   }

   public MobDefenseChain getMobDefenseChain() {
      return this.mobDefenseChain;
   }

   public MLGBucketFallChain getMLGBucketChain() {
      return this.mlgBucketChain;
   }

   public void log(String message) {
      Debug.logMessage(message);
   }

   public void logWarning(String message) {
      Debug.logWarning(message);
   }

   public static boolean inGame() {
      return true;
   }

   public LivingEntity getPlayer() {
      return this.ctx.entity();
   }

   public InputControls getInputControls() {
      return this.inputControls;
   }

   public SlotHandler getSlotHandler() {
      return this.slotHandler;
   }

   public PickupArmorEvalQueue getPickupArmorEvalQueue() {
      return this.pickupArmorEvalQueue;
   }

   public PickupWeaponEvalQueue getPickupWeaponEvalQueue() {
      return this.pickupWeaponEvalQueue;
   }

   public ExplicitEquipPolicy getExplicitEquipPolicy() {
      return this.explicitEquipPolicy;
   }

   public LivingEntityInventory getInventory() {
      return this.getBaritone().getEntityContext().inventory();
   }

   public PlayerExtraController getControllerExtras() {
      return this.extraController;
   }

   public void setChatClefEnabled(boolean enabled) {
      ConversationManager.getOrCreateEventQueueData(this).setEnabled(enabled);

      if (!enabled) {
         this.getUserTaskChain().cancel(this);
         this.getTaskRunner().disable();
      }
   }

   public void logCharacterMessage(String message, Character character, boolean isPublic) {
      int maxLength = 256;
      int start = 0;

      while (start < message.length()) {
         int end = Math.min(start + maxLength, message.length());
         String chunk = message.substring(start, end);
         if (chunk.length() > 0 && !chunk.isBlank()) {
            Debug.logCharacterMessage(chunk, character, isPublic);
         }

         start = end;
      }
   }

   public Player getOwner() {
      return this.owner;
   }

   public void setOwner(Player owner) {
      this.owner = owner;
      if (aiPersistantData != null) {
         aiPersistantData.rebindOwnerPersistenceIfChanged();
         aiPersistantData.updateSystemPrompt();
      }
   }

   public boolean isOwner(UUID playerToCheck) {
      return playerToCheck.equals(owner.getUUID());
   }

   public AIPersistantData getAIPersistantData() {
      return this.aiPersistantData;
   }

   public Player2APIService getPlayer2APIService() {
      return this.player2apiService;
   }

   public String getOwnerUsername() {
      if (getOwner() == null) {
         return "UNKNOWN OWNER";
      }
      return getOwner().getName().getString();
   }

   public Optional<ServerPlayer> getClosestPlayer() {
      return this.getWorld().players().stream().sorted((a, b) -> {
         float adist = a.distanceTo(this.getEntity());
         float bdist = b.distanceTo(this.getEntity());
         return Float.compare(adist, bdist);
      }).findFirst();
   }

   /**
    * Player-facing agentic progress broadcast (non-milestone). Convenience overload that applies the
    * {@link #MIN_REPORT_INTERVAL_MS} throttle. See {@link #reportAgenticProgress(String, boolean)}.
    */
   public void reportAgenticProgress(String message) {
      reportAgenticProgress(message, false);
   }

   /**
    * Deterministic, throttled/deduplicated agentic progress reporting to the OWNING player. Reuses
    * the existing chat channel ({@link AgentSideEffects#broadcastChatToPlayer}); it adds NO Player2
    * API call and consumes no model budget — the {@code message} is a pre-built deterministic string
    * (a step's {@code describeProgress()} output or a fixed milestone/failure note).
    *
    * <p>Audience is OWNER-SCOPED: it targets {@link #getOwner()} when that owner is an online
    * {@link ServerPlayer}, else the nearest player, else no-ops. This INTENTIONALLY differs from
    * {@link AgentSideEffects#onEntityMessage} which broadcasts AI dialogue to ALL players (its
    * owner-filter is commented out). Progress is a private status feed for the owner, not dialogue —
    * do not "fix" this to all-players.
    *
    * <p>Throttle/dedup: non-milestone lines are skipped when fired within {@link #MIN_REPORT_INTERVAL_MS}
    * of the last broadcast. Milestone/failure lines ({@code milestone == true}) BYPASS the interval so a
    * failure is never swallowed by the rate limit, but ALL lines still dedup against the last broadcast
    * message for this bot. No-ops on null/blank message, missing server, or no resolvable player.
    *
    * @param message   pre-built deterministic progress/failure text (no model call)
    * @param milestone when true, bypass the interval throttle (still deduplicated)
    */
   public void reportAgenticProgress(String message, boolean milestone) {
      // 1. blank guard.
      if (message == null || message.isBlank()) {
         return;
      }
      // 1c. operator filter (config/playerengine-companion.properties progressChat).
      if (!com.player2.playerengine.companion.CompanionRules.get().progressChat().shows(milestone)) {
         return;
      }
      // 2. resolve server — there is no getServer() on the controller; use getPlayer().getServer().
      MinecraftServer server = (getPlayer() != null) ? getPlayer().getServer() : null;
      if (server == null) {
         return;
      }
      // 3. resolve target ServerPlayer. getOwner() returns Player (NOT ServerPlayer), so the
      //    instanceof-cast is required; fall back to the nearest player; else no-op.
      ServerPlayer target;
      if (getOwner() instanceof ServerPlayer sp) {
         target = sp;
      } else {
         target = getClosestPlayer().orElse(null);
      }
      if (target == null) {
         return;
      }
      long now = System.currentTimeMillis();
      // 4. throttle (non-milestone only): skip if too soon after the last broadcast.
      if (!milestone && (now - lastAgenticReportMs) < MIN_REPORT_INTERVAL_MS) {
         return;
      }
      // 5. dedup: skip identical consecutive messages (applies to milestones too).
      if (message.equals(lastAgenticReportMessage)) {
         return;
      }
      // 6. broadcast via the single existing player-chat path, prefixed with the bot name.
      AgentSideEffects.broadcastChatToPlayer(server,
            Component.translatable("message.playerengine.agent.progress_prefix",
                  agenticReportBotName(), message),
            target);
      // 7. update throttle/dedup state.
      lastAgenticReportMs = now;
      lastAgenticReportMessage = message;
   }

   /**
    * Client-localized agentic progress broadcast (non-milestone). Convenience overload that applies
    * the {@link #MIN_REPORT_INTERVAL_MS} throttle. See {@link #reportAgenticProgress(Component, boolean)}.
    */
   public void reportAgenticProgress(Component message) {
      reportAgenticProgress(message, false);
   }

   /**
    * Component variant of {@link #reportAgenticProgress(String, boolean)}: the player-facing
    * {@code message} is passed UNRESOLVED into the prefix template's {@code %2$s} arg so the CLIENT
    * resolves the translation (the {@code String} overload resolves on the server, always en_us).
    * Throttle/dedup behavior is preserved — dedup keys on {@code message.getString()}, the same
    * English text the {@code String} path used.
    *
    * @param message   pre-built deterministic progress/failure {@link Component} (no model call)
    * @param milestone when true, bypass the interval throttle (still deduplicated)
    */
   public void reportAgenticProgress(Component message, boolean milestone) {
      // 1. null guard.
      if (message == null) {
         return;
      }
      // 1b. blank guard / dedup key (resolved server-side only for throttle/dedup, NOT broadcast).
      String dedupKey = message.getString();
      if (dedupKey.isBlank()) {
         return;
      }
      // 1c. operator filter (config/playerengine-companion.properties progressChat).
      if (!com.player2.playerengine.companion.CompanionRules.get().progressChat().shows(milestone)) {
         return;
      }
      // 2. resolve server — there is no getServer() on the controller; use getPlayer().getServer().
      MinecraftServer server = (getPlayer() != null) ? getPlayer().getServer() : null;
      if (server == null) {
         return;
      }
      // 3. resolve target ServerPlayer. getOwner() returns Player (NOT ServerPlayer), so the
      //    instanceof-cast is required; fall back to the nearest player; else no-op.
      ServerPlayer target;
      if (getOwner() instanceof ServerPlayer sp) {
         target = sp;
      } else {
         target = getClosestPlayer().orElse(null);
      }
      if (target == null) {
         return;
      }
      long now = System.currentTimeMillis();
      // 4. throttle (non-milestone only): skip if too soon after the last broadcast.
      if (!milestone && (now - lastAgenticReportMs) < MIN_REPORT_INTERVAL_MS) {
         return;
      }
      // 5. dedup: skip identical consecutive messages (applies to milestones too).
      if (dedupKey.equals(lastAgenticReportMessage)) {
         return;
      }
      // 6. broadcast via the single existing player-chat path; the UNRESOLVED message is nested as the
      //    prefix template's %2$s arg so the client resolves the whole thing in its own locale.
      AgentSideEffects.broadcastChatToPlayer(server,
            Component.translatable("message.playerengine.agent.progress_prefix",
                  agenticReportBotName(), message),
            target);
      // 7. update throttle/dedup state.
      lastAgenticReportMs = now;
      lastAgenticReportMessage = dedupKey;
   }

   /** Short bot name used as the {@code [<botName>] } prefix on agentic progress/failure lines. */
   private String agenticReportBotName() {
      Character character = this.getAIPersistantData() != null ? this.getAIPersistantData().getCharacter() : null;
      if (character != null && character.shortName() != null && !character.shortName().isBlank()) {
         return character.shortName();
      }
      return this.getEntity().getName().getString();
   }

   public boolean getShouldDefendFromHostiles(){
      return this.shouldDefendFromHostiles;
   }
   public void setShouldDefendFromHostiles(boolean toSet){
      this.shouldDefendFromHostiles = toSet;
   }

   public FollowMode getFollowMode() { return this.followMode; }

   public void setFollowMode(FollowMode mode) {
      FollowMode resolved = (mode == null) ? FollowMode.NORMAL : mode;
      if (resolved != this.followMode) {
         LOGGER.info("[FollowDiag] MODE-CHANGE: {} -> {}", this.followMode, resolved);
      }
      this.followMode = resolved;
      // Derived side-effect: COWARD suppresses MobDefenseChain; NORMAL/DEFENDER keep it active.
      // Keep shouldDefendFromHostiles independently settable; this is the driver, not the store.
      this.setShouldDefendFromHostiles(this.followMode == FollowMode.COWARD);
   }
}
