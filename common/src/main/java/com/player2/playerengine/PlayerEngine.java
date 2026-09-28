package com.player2.playerengine;

import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.structureprotection.StructureProtectionEvents;
import com.google.common.base.Suppliers;
import com.player2.playerengine.automaton.KeepName;
import com.player2.playerengine.automaton.command.defaults.DefaultCommands;
import com.player2.playerengine.automaton.entity.CustomFishingBobberEntity;
import com.player2.playerengine.player2api.Player2ClientApiBridge;
import com.player2.playerengine.modintelligence.ModIntelligenceService;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.network.Player2DisconnectHandler;
import com.player2.playerengine.player2api.network.Player2ServerNetworking;
import com.player2.playerengine.player2api.network.TtsClientPreferenceStore;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.player2.playerengine.player2api.auth.AuthenticationManager;
import com.player2.playerengine.player2api.auth.TokenStorage;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.manager.TTSManager;
import com.player2.playerengine.util.ExecutorShutdown;
import com.player2.playerengine.player2api.Event;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;

@KeepName
public final class PlayerEngine {
   public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);

   public static final String MOD_ID = "playerengine";
   public static final String MOD_NAME = "PlayerEngine";
   public static final ResourceLocation CLIENT_PLAYER2_PROXY_REQUEST_PACKET_ID = ResourceLocation
         .fromNamespaceAndPath(MOD_ID, "client_player2_proxy_request");
   public static final ResourceLocation CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID = ResourceLocation
         .fromNamespaceAndPath(MOD_ID, "client_player2_proxy_response");
   public static final ResourceLocation TTS_PREFERENCE_PACKET_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID,
         "tts_preference");
   /**
    * C2S: per-boundary gesture signal. Payload (FROZEN — the client mirrors this): {@code writeUtf(botUuid)}
    * then {@code writeVarInt(segIndex)}, where {@code segIndex} indexes the VALID-ONLY wire boundary list
    * (the {@code stream_tts} boundary list, invalid markers omitted). Honored only from the PROMPTER's
    * client; fires the boundary's BodyLanguageTask once. NEVER clears the cooldown. Declared via id(...)
    * so the per-version ResourceLocation factory is encapsulated (parity-safe).
    */
   public static final ResourceLocation TTS_SEGMENT_DONE_PACKET_ID = id("segment_done");
   /**
    * C2S: end-of-message signal (replaces the retired {@code tts_playback_done}). Payload (FROZEN):
    * {@code writeUtf(botUuid)} then ONE trailing {@code writeBoolean(degraded)} (partial-speech flag).
    * Honored only from the PROMPTER's client (was owner); gated by {@code isBotTtsPlaybackAckEnabled()};
    * clears the turn-taking cooldown (idempotent) and cancels the fallback timer.
    */
   public static final ResourceLocation TTS_MESSAGE_DONE_PACKET_ID = id("message_done");
   public static final TagKey<Item> EMPTY_BUCKETS = TagKey.create(Registries.ITEM, id("empty_buckets"));
   public static final TagKey<Item> WATER_BUCKETS = TagKey.create(Registries.ITEM, id("water_buckets"));
   private static ThreadPoolExecutor threadPool;
   private static final AtomicBoolean backgroundExecutorsShutDown = new AtomicBoolean(false);

   public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(MOD_ID,
         Registries.ENTITY_TYPE);
   public static RegistrySupplier<EntityType<CustomFishingBobberEntity>> FISHING_BOBBER = ENTITY_TYPES.register(
         "custom_fishing_bobber",
         () -> EntityType.Builder
               .of((EntityType.EntityFactory<CustomFishingBobberEntity>) CustomFishingBobberEntity::new,
                     MobCategory.CREATURE)
               .sized(EntityType.FISHING_BOBBER.getWidth(), EntityType.FISHING_BOBBER.getHeight())
               .clientTrackingRange(64)
               .updateInterval(1)
               .build("custom_fishing_bobber")

   );

   public static ResourceLocation id(String path) {
      return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
   }

   public static ThreadPoolExecutor getExecutor() {
      return threadPool;
   }

   /**
    * Allow the next logical server session to register shutdown again (integrated / same JVM).
    * Also recreates the worker pool if it was terminated by the previous session's shutdown,
    * so pathfinding threads can be submitted without a RejectedExecutionException.
    */
   public static void resetBackgroundExecutorsShutdownGate() {
      backgroundExecutorsShutDown.set(false);
      if (threadPool.isShutdown()) {
         threadPool = newWorkerPool();
      }
   }

   private static ThreadPoolExecutor newWorkerPool() {
      AtomicInteger threadCounter = new AtomicInteger(0);
      return new ThreadPoolExecutor(
            4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> new Thread(r, MOD_NAME + " Worker " + threadCounter.incrementAndGet()));
   }

   public static void shutdownBackgroundExecutors() {
      if (!backgroundExecutorsShutDown.compareAndSet(false, true)) {
         return;
      }
      ConversationManager.shutdownAndResetLLMCompleters();
      // Use the manager's reset so integrated-server restart in the same JVM gets a fresh
      // executor instead of being left with a terminated thread.
      TTSManager.shutdownAndReset();
      ExecutorShutdown.shutdownNowAwait("PlayerEngine.workerPool", threadPool);
      AuthenticationManager.shutdownAndReset();
   }

   public static void onInitialize() {
      Player2ServerConfigHolder.load();
      PlayerEvent.PLAYER_QUIT.register(Player2DisconnectHandler::onPlayerQuit);
      PlayerEvent.PLAYER_JOIN.register(player -> {
         if (player instanceof ServerPlayer sp) {
            Player2ServerNetworking.sendConfigSync(sp);
         }
      });
      // Respect-player-structures (WS2): record real-player block placements and prune them on
      // break, common-side via Architectury BlockEvent.PLACE/BREAK. No-op when the
      // respectStructuresEnabled toggle is off; never cancels placement/break.
      StructureProtectionEvents.register();
      DefaultCommands.registerAll();
      ENTITY_TYPES.register();
      PlayerEngineStorageMigration.runOnce();
      copyToolOverridesReadmeIfAbsent();
      RagIndex.initialize();
      MCCommands.onInit();
      TickEvent.SERVER_POST.register(PlayerEngineController::staticServerTick);
      // Bodylang TTS-timed gestures: drive the per-bot fallback timers on the server tick thread so a
      // missing prompter ACK (bot-to-bot / ambient / prompter-moved-away / client-crash) still fires
      // unfired gestures and clears the cooldown (Workstream 4).
      TickEvent.SERVER_POST.register(PlayerEngine::tickTtsFallbackTimers);
      ConversationManager.init();
      ModIntelligenceService.registerEventHandlers();
      if (dev.architectury.platform.Platform.getEnvironment() == dev.architectury.utils.Env.SERVER) {
         NetworkManager.registerS2CPayloadType(ResourceLocation.fromNamespaceAndPath("playerengine", "stream_tts"));
         NetworkManager.registerS2CPayloadType(ResourceLocation.fromNamespaceAndPath("playerengine", "response_stt"));
         NetworkManager.registerS2CPayloadType(CLIENT_PLAYER2_PROXY_REQUEST_PACKET_ID);
         NetworkManager.registerS2CPayloadType(Player2ServerNetworking.SYNC_SERVER_PLAYER2);
      }
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID,
            (buf, context) -> {
               Player2ClientApiBridge.handleClientProxyResponse(buf, (ServerPlayer) context.getPlayer());
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            ResourceLocation.fromNamespaceAndPath("playerengine", "user_message"),
            (buf, context) -> {
               String username = context.getPlayer().getName().getString();
               String message = buf.readUtf();
               LOGGER.info("Server: received user_message packet (voice/STT) from {} len={} preview=\"{}\"",
                     username, message.length(), com.player2.playerengine.player2api.utils.SttLogging.messagePreview(message));
               ConversationManager.onUserChatMessage(new Event.UserMessage(
                     message, username, true, context.getPlayer().getUUID()));
               AgentSideEffects.broadcastChatToAllPlayers(context.getPlayer().getServer(),
                     Component.translatable("message.playerengine.chat.player_message",
                           context.getPlayer().getName().getString(), message));
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            ResourceLocation.fromNamespaceAndPath("playerengine", "request_stt"),
            (buf, context) -> {
               String clientId = buf.readUtf();
               String username = context.getPlayer().getName().getString();
               LOGGER.info("Server: received request_stt packet from {} clientId={}", username, clientId);
               String storedToken = TokenStorage.getToken(username, clientId);
               if (storedToken == null || storedToken.isBlank()) {
                  LOGGER.warn("Server: no stored STT token for user={} clientId={} (Player2 login may be required)",
                        username, clientId);
               }
               RegistryFriendlyByteBuf buf2 = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                     context.getPlayer().registryAccess());
               buf2.writeUtf(storedToken == null ? "" : storedToken);
               LOGGER.info("Server: sending response_stt packet to {} (tokenPresent={})",
                     username, storedToken != null && !storedToken.isBlank());
               ((ServerPlayer) context.getPlayer()).connection.send(NetworkManager.toPacket(
                     NetworkManager.Side.S2C,
                     ResourceLocation.fromNamespaceAndPath("playerengine", "response_stt"), buf2));
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            TTS_PREFERENCE_PACKET_ID,
            (buf, context) -> {
               boolean enabled = buf.readBoolean();
               TtsClientPreferenceStore.setTtsEnabled(context.getPlayer().getUUID(), enabled);
            });
      // segment_done (C2S): per-boundary gesture trigger. Honored ONLY from the bot's PROMPTER client.
      // Payload: readUtf(botUuid), readVarInt(segIndex). segIndex indexes the VALID-ONLY boundary list.
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            TTS_SEGMENT_DONE_PACKET_ID,
            (buf, context) -> {
               ServerPlayer sender = (ServerPlayer) context.getPlayer();
               String botUuidStr = buf.readUtf();
               int segIndex = buf.readVarInt();
               UUID botUuid;
               try {
                  botUuid = UUID.fromString(botUuidStr);
               } catch (IllegalArgumentException e) {
                  LOGGER.warn("PlayerEngine: segment_done invalid bot UUID: {}", botUuidStr);
                  return;
               }
               AgentConversationData botData = ConversationManager.queueData.get(botUuid);
               if (botData == null) {
                  return;
               }
               // Prompter identity guard (replaces the old owner check): only the player the bot is
               // talking to this turn may drive its gestures (decision 10). Payer/owner signals ignored.
               ServerPlayer prompter = resolvePrompter(botData);
               if (prompter == null || !prompter.getUUID().equals(sender.getUUID())) {
                  return;
               }
               fireSegment(botUuid, botData, segIndex, false);
               // NEVER clearTtsCooldown here (decision 3/4 — cooldown is cleared only by message_done
               // or the fallback timer).
            });

      // message_done (C2S): end-of-message. Honored ONLY from the PROMPTER client; gated by ack-enabled.
      // Payload: readUtf(botUuid), then ONE trailing readBoolean(degraded). Replaces tts_playback_done.
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            TTS_MESSAGE_DONE_PACKET_ID,
            (buf, context) -> {
               if (!Player2ServerConfigHolder.get().isBotTtsPlaybackAckEnabled()) {
                  return;
               }
               ServerPlayer sender = (ServerPlayer) context.getPlayer();
               String botUuidStr = buf.readUtf();
               boolean degraded = buf.readBoolean();
               UUID botUuid;
               try {
                  botUuid = UUID.fromString(botUuidStr);
               } catch (IllegalArgumentException e) {
                  LOGGER.warn("PlayerEngine: message_done invalid bot UUID: {}", botUuidStr);
                  return;
               }
               AgentConversationData botData = ConversationManager.queueData.get(botUuid);
               if (botData == null) {
                  return;
               }
               ServerPlayer prompter = resolvePrompter(botData);
               if (prompter == null || !prompter.getUUID().equals(sender.getUUID())) {
                  return;
               }
               LOGGER.info("PlayerEngine: message_done ACK for bot={} from prompter={} degraded={}",
                     botData.getName(), sender.getName().getString(), degraded);
               botData.clearTtsCooldown(); // idempotent — no-op if the fallback timer already cleared it
               botData.cancelFallbackTimer(); // runs the registered hook -> clearFallbackTimer(botUuid)
               if (degraded) {
                  // Workstream 6: partial speech. Tell BOTH audiences truthfully (DESIGN.md §3).
                  MinecraftServer server = sender.getServer();
                  if (server != null) {
                     AgentSideEffects.broadcastChatToAllPlayers(server,
                           Component.translatable("message.playerengine.agent.partial_speech", botData.getName()));
                  }
                  botData.reportPartialSpeechToModel();
               }
            });
   }

   // --- Bodylang TTS-timed gestures: prompter resolution + fallback timer (Workstream 4) ---

   /**
    * Resolve a bot's PROMPTER (chain initiator) to a present {@link ServerPlayer}. Reuses the
    * package-private {@code Player2PayerResolution.findByName} scan (do NOT duplicate it). Returns
    * {@code null} when the prompter is null (ambient/proactive), a bot, or not online — in which case
    * there is no authoritative client and the fallback timer is the sole driver (decision 10).
    * NEVER routes through {@code Player2PayerResolution.resolve}/{@code ApiBillingContext} (payer is
    * billing only — decision 9).
    */
   private static ServerPlayer resolvePrompter(AgentConversationData botData) {
      String prompterName = botData.getChainInitiatorUsername();
      if (prompterName == null || prompterName.isBlank()) {
         return null;
      }
      net.minecraft.world.entity.LivingEntity bot = botData.getMod().getPlayer();
      MinecraftServer server = bot != null ? bot.getServer() : null;
      if (server == null) {
         return null;
      }
      return com.player2.playerengine.player2api.Player2PayerResolution.findByName(server, prompterName);
   }

   /**
    * Per-bot fallback-timer + fired-segment state. {@code deadlineNanos} is a GENEROUS liveness deadline
    * (NOT the exact speech estimate) so a slow-but-present prompter ACK is not pre-empted.
    * {@code firedSegments} tracks which valid boundary indices already fired (segment_done OR timer),
    * giving idempotency BOTH ways — a duplicate/late {@code segment_done} after the timer is a no-op,
    * and the timer never re-fires what a prompter ACK already fired. {@code cleared} guards a single
    * cooldown clear. {@code active} is set false by {@code message_done} so the deadline never fires the
    * liveness path, but the entry (and its dedup set) is KEPT until the NEXT dispatch overwrites it — so
    * a late duplicate {@code segment_done} arriving AFTER {@code message_done} is still de-duplicated.
    */
   private static final class TtsFallbackState {
      final long deadlineNanos;
      final java.util.Set<Integer> firedSegments = java.util.concurrent.ConcurrentHashMap.newKeySet();
      volatile boolean cleared = false;
      volatile boolean active = true;

      TtsFallbackState(long deadlineNanos) {
         this.deadlineNanos = deadlineNanos;
      }
   }

   private static final java.util.Map<UUID, TtsFallbackState> TTS_FALLBACK_TIMERS =
         new java.util.concurrent.ConcurrentHashMap<>();

   /**
    * Schedule (or replace) the server-side fallback timer for a bot at TTS dispatch. Deadline =
    * markSpeakingFor-style estimate ({@code ceil(len/25)+1}s) PLUS a fixed slack so it is purely a
    * liveness backstop, never precise timing. A new dispatch REPLACES the entry, resetting the
    * fired-segment set for the new message.
    */
   public static void scheduleTtsFallbackTimer(MinecraftServer server, UUID botUuid, int messageLength) {
      int estimateSec = (int) Math.ceil(messageLength / 25.0) + 1;
      int slackSec = 5; // generous; must not pre-empt a slow-but-present prompter ACK
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos((long) estimateSec + slackSec);
      TTS_FALLBACK_TIMERS.put(botUuid, new TtsFallbackState(deadline));
      // Register a cancel hook so message_done can pre-empt the timer's liveness firing (idempotent).
      AgentConversationData botData = ConversationManager.queueData.get(botUuid);
      if (botData != null) {
         botData.setFallbackTimerCancel(() -> clearFallbackTimer(botUuid));
      }
   }

   /**
    * Deactivate a bot's fallback timer's liveness firing (idempotent). Called from {@code message_done}.
    * The entry is intentionally KEPT (not removed) so the fired-segment dedup set still rejects a late
    * duplicate {@code segment_done}; the next dispatch overwrites it. The tick driver garbage-collects
    * inactive entries once past their deadline.
    */
   public static void clearFallbackTimer(UUID botUuid) {
      TtsFallbackState state = TTS_FALLBACK_TIMERS.get(botUuid);
      if (state != null) {
         state.active = false;
         state.cleared = true; // message_done already cleared the cooldown
      }
   }

   /**
    * Drive the fallback timers. Called every server tick (SERVER_POST). When an ACTIVE bot's deadline
    * passes before {@code message_done}, fire any UNFIRED valid boundaries in order, then clear the
    * cooldown — the liveness backstop for bot-to-bot / ambient / prompter-moved-away / client-crash
    * cases. Runs on the server tick thread, so the gesture dispatch is on the correct thread. Inactive
    * (message_done'd) entries are garbage-collected once their deadline passes.
    */
   public static void tickTtsFallbackTimers(MinecraftServer server) {
      if (TTS_FALLBACK_TIMERS.isEmpty()) {
         return;
      }
      long now = System.nanoTime();
      for (java.util.Map.Entry<UUID, TtsFallbackState> e : TTS_FALLBACK_TIMERS.entrySet()) {
         TtsFallbackState state = e.getValue();
         if (now < state.deadlineNanos) {
            continue;
         }
         UUID botUuid = e.getKey();
         if (state.active) {
            AgentConversationData botData = ConversationManager.queueData.get(botUuid);
            if (botData != null) {
               java.util.List<com.player2.playerengine.player2api.MarkerParser.SegmentBoundary> boundaries =
                     botData.getPendingSegmentActions();
               for (int i = 0; i < boundaries.size(); i++) {
                  fireSegment(botUuid, botData, i, true);
               }
               if (!state.cleared) {
                  state.cleared = true;
                  botData.clearTtsCooldown();
               }
               LOGGER.info("PlayerEngine: TTS fallback timer fired for bot={} (no prompter ACK)", botData.getName());
            }
         }
         // Past deadline (whether it just fired or was already message_done'd): drop the entry.
         TTS_FALLBACK_TIMERS.remove(botUuid);
      }
   }

   /**
    * Fire the valid boundary at {@code segIndex} exactly once for {@code botData}. Idempotent across
    * duplicate {@code segment_done} packets AND across the timer/ACK race (whichever marks the index
    * first wins; the other is a no-op). The gesture runs the registered {@code bodylang} task body on
    * the server thread; a gesture suspends the running task rather than replacing it, so it is not an
    * order and moves no dispatch seq.
    */
   private static void fireSegment(UUID botUuid, AgentConversationData botData, int segIndex, boolean fromTimer) {
      java.util.List<com.player2.playerengine.player2api.MarkerParser.SegmentBoundary> boundaries =
            botData.getPendingSegmentActions();
      if (segIndex < 0 || segIndex >= boundaries.size()) {
         return;
      }
      // Get-or-create the dedup state so idempotency holds even if the timer entry was already GC'd
      // (e.g. a very late duplicate segment_done). A bare new state has an immediate-past deadline but
      // is inactive-by-omission: the tick driver only fires ACTIVE entries, so a dedup-only state never
      // triggers the liveness path; it is dropped on the next tick after its (already-past) deadline.
      TtsFallbackState state = TTS_FALLBACK_TIMERS.computeIfAbsent(botUuid, k -> {
         TtsFallbackState s = new TtsFallbackState(System.nanoTime());
         s.active = false;
         s.cleared = true;
         return s;
      });
      if (!state.firedSegments.add(segIndex)) {
         return; // already fired
      }
      com.player2.playerengine.player2api.MarkerParser.SegmentBoundary b = boundaries.get(segIndex);
      String action = b.action().name().toLowerCase(java.util.Locale.ROOT);
      LOGGER.info("PlayerEngine: firing bodylang segment bot={} seg={} action={} fromTimer={}",
            botData.getName(), segIndex, action, fromTimer);
      PlayerEngineController mod = botData.getMod();
      com.player2.playerengine.commands.base.Command gesture = mod.getCommandExecutor().get("bodylang");
      net.minecraft.server.MinecraftServer server = mod.getPlayer() == null ? null : mod.getPlayer().getServer();
      if (gesture == null || server == null) {
         return;
      }
      server.execute(() -> {
         try {
            gesture.run(mod, "bodylang " + action, () -> { },
                  e -> LOGGER.warn("PlayerEngine: gesture {} failed for bot={}: {}", action, botData.getName(),
                        e.getMessage()));
         } catch (com.player2.playerengine.commands.base.CommandException e) {
            LOGGER.warn("PlayerEngine: gesture {} refused for bot={}: {}", action, botData.getName(), e.getMessage());
         }
      });
   }

   private static void copyToolOverridesReadmeIfAbsent() {
      try {
         java.nio.file.Path dest = PlayerEnginePaths.userFile("tool_overrides.README.md");
         if (!java.nio.file.Files.exists(dest)) {
            java.nio.file.Files.createDirectories(dest.getParent());
            try (java.io.InputStream in = PlayerEngine.class.getClassLoader()
                  .getResourceAsStream("tool_overrides.README.md")) {
               if (in != null) {
                  java.nio.file.Files.copy(in, dest);
                  LOGGER.info("PlayerEngine: copied tool_overrides.README.md to {}", dest);
               }
            }
         }
      } catch (java.io.IOException e) {
         LOGGER.warn("PlayerEngine: could not copy tool_overrides.README.md: {}", e.getMessage());
      }
   }

   static {
      threadPool = newWorkerPool();
   }
}
