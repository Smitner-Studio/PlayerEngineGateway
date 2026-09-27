package com.player2.playerengine.player2api;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.executor.BudgetFallbackBehavior;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.player2api.auth.TokenStorage;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.gateway.GatewayCallContext;
import com.player2.playerengine.player2api.manager.HeartbeatManager;
import com.player2.playerengine.player2api.network.TtsClientPreferenceStore;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import com.player2.playerengine.player2api.utils.Utils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class Player2APIService {
   private static final Logger LOGGER = LogManager.getLogger();

   private String clientId;
   private PlayerEngineController controller;

   /** Effective billing for the current AI/conversation turn (set by {@link AgentConversationData#process}). */
   private volatile Player2PayerResolution.ApiBillingContext activeBillingContext;

   public Player2APIService(PlayerEngineController controller, String clientId) {
      this.clientId = clientId;
      this.controller = controller;
   }

   public String getClientId() {
      return clientId;
   }

   public PlayerEngineController getController() {
      return controller;
   }

   public void setActiveBillingContext(Player2PayerResolution.ApiBillingContext ctx) {
      this.activeBillingContext = ctx;
   }

   public Player2PayerResolution.ApiBillingContext billingOrFallback() {
      Player2PayerResolution.ApiBillingContext ctx = activeBillingContext;
      if (ctx != null) {
         return ctx;
      }
      return Player2PayerResolution.resolve(controller, null, clientId);
   }

   private Map<String, JsonElement> api(String method, String endpoint, JsonObject body) throws Exception {
      Player2PayerResolution.ApiBillingContext billing = billingOrFallback();
      // The gateway routes a companion's calls to its character's endpoint profile.
      AIPersistantData data = controller.getAIPersistantData();
      Character character = data != null ? data.getCharacter() : null;
      return GatewayCallContext.call(character != null ? character.id() : null,
            billing != null ? billing.billingKey() : null,
            () -> Player2ApiDispatcher.route(controller, clientId, method, endpoint, body, billing));
   }

   /**
    * NPC chat / command pick — uses {@link AiTaskClass#DECISION} (default tier).
    *
    * @deprecated Call {@link #completeConversation(ConversationHistory, AiTaskClass)} with an
    *             explicit task class. Kept for legacy/test call sites.
    */
   @Deprecated
   public JsonObject completeConversation(ConversationHistory conversationHistory) throws Exception {
      return completeConversation(conversationHistory, AiTaskClass.DECISION);
   }

   /**
    * Complete a conversation and parse the response JSON, routing to the appropriate Player2
    * profile based on {@code taskClass} (B3) and the A4 budget guard.
    */
   public JsonObject completeConversation(ConversationHistory conversationHistory, AiTaskClass taskClass) throws Exception {
      JsonObject requestBody = new JsonObject();
      JsonArray messagesArray = LogEgressGuard.cappedMessages(
            conversationHistory.getListJSON(), "Player2APIService.completeConversation");
      String lastMessageForDebug = LogEgressGuard.capForModel(
            conversationHistory.getListJSON().get(conversationHistory.getListJSON().size() - 1).toString(), "debug");

      requestBody.add("messages", messagesArray);
      LogEgressGuard.applyChatCompletionRequestCaps(requestBody, "Player2APIService.completeConversation");
      // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 2a: log outgoing request message count and body char length (NOT tokens)
      if (LLMCompleter.DEBUG_LLM_PROBE) {
         int dbgMsgCount = messagesArray.size();
         int dbgBodyChars = requestBody.toString().length();
         LOGGER.info("[DBG llm-latency-2026-06-13] request messages={} bodyChars={} taskClass={}", dbgMsgCount, dbgBodyChars, taskClass);
      } // [/DEBUG-INSTR:llm-latency-2026-06-13]
      LOGGER.info("Called complete conversation (string) HTTP request, last msg={}", lastMessageForDebug);
      Map<String, JsonElement> responseMap = sendChatCompletionRequest(requestBody, taskClass);
      // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 2b: log usage tokens from response if backend provides them
      if (LLMCompleter.DEBUG_LLM_PROBE) {
         JsonElement dbgUsageEl = responseMap.get("usage");
         if (dbgUsageEl != null && !dbgUsageEl.isJsonNull() && dbgUsageEl.isJsonObject()) {
            com.google.gson.JsonObject dbgUsage = dbgUsageEl.getAsJsonObject();
            int dbgPrompt = dbgUsage.has("prompt_tokens") ? dbgUsage.get("prompt_tokens").getAsInt() : -1;
            int dbgCompletion = dbgUsage.has("completion_tokens") ? dbgUsage.get("completion_tokens").getAsInt() : -1;
            int dbgTotal = dbgUsage.has("total_tokens") ? dbgUsage.get("total_tokens").getAsInt() : -1;
            LOGGER.info("[DBG llm-latency-2026-06-13] usage prompt_tokens={} completion_tokens={} total_tokens={}", dbgPrompt, dbgCompletion, dbgTotal);
         } else {
            LOGGER.info("[DBG llm-latency-2026-06-13] usage not provided by backend (no estimate)");
         }
      } // [/DEBUG-INSTR:llm-latency-2026-06-13]
      if (responseMap.containsKey("choices")) {
         JsonArray choices = responseMap.get("choices").getAsJsonArray();
         if (choices.size() != 0) {
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObject != null && messageObject.has("content")) {
               String content = messageObject.get("content").getAsString();
               LOGGER.info("Finished complete conversation HTTP request last msg={}", lastMessageForDebug);
               return Utils.parseCleanedJson(content);
            }
         }
      }

      throw new Exception("Invalid response format: " + responseMap.toString());
   }

   /**
    * Complete a conversation and return the raw text content, routing to the appropriate
    * Player2 profile based on {@code taskClass} (B3) and the A4 budget guard.
    */
   public String completeConversationToString(ConversationHistory conversationHistory, AiTaskClass taskClass) throws Exception {
      JsonObject requestBody = new JsonObject();
      JsonArray messagesArray = LogEgressGuard.cappedMessages(
            conversationHistory.getListJSON(), "Player2APIService.completeConversationToString");

      requestBody.add("messages", messagesArray);
      LogEgressGuard.applyChatCompletionRequestCaps(requestBody, "Player2APIService.completeConversationToString");
      // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 2a (toString variant): log outgoing request message count and body char length (NOT tokens)
      if (LLMCompleter.DEBUG_LLM_PROBE) {
         int dbgMsgCount = messagesArray.size();
         int dbgBodyChars = requestBody.toString().length();
         LOGGER.info("[DBG llm-latency-2026-06-13] request messages={} bodyChars={} taskClass={}", dbgMsgCount, dbgBodyChars, taskClass);
      } // [/DEBUG-INSTR:llm-latency-2026-06-13]
      String lastMessageForDebug = LogEgressGuard.capForModel(
            conversationHistory.getListJSON().get(conversationHistory.getListJSON().size() - 1).toString(), "debug");
      LOGGER.info("Called complete conversation (string) HTTP request, last msg={}", lastMessageForDebug);
      Map<String, JsonElement> responseMap = sendChatCompletionRequest(requestBody, taskClass);
      // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 2b (toString variant): log usage tokens from response if backend provides them
      if (LLMCompleter.DEBUG_LLM_PROBE) {
         JsonElement dbgUsageEl = responseMap.get("usage");
         if (dbgUsageEl != null && !dbgUsageEl.isJsonNull() && dbgUsageEl.isJsonObject()) {
            com.google.gson.JsonObject dbgUsage = dbgUsageEl.getAsJsonObject();
            int dbgPrompt = dbgUsage.has("prompt_tokens") ? dbgUsage.get("prompt_tokens").getAsInt() : -1;
            int dbgCompletion = dbgUsage.has("completion_tokens") ? dbgUsage.get("completion_tokens").getAsInt() : -1;
            int dbgTotal = dbgUsage.has("total_tokens") ? dbgUsage.get("total_tokens").getAsInt() : -1;
            LOGGER.info("[DBG llm-latency-2026-06-13] usage prompt_tokens={} completion_tokens={} total_tokens={}", dbgPrompt, dbgCompletion, dbgTotal);
         } else {
            LOGGER.info("[DBG llm-latency-2026-06-13] usage not provided by backend (no estimate)");
         }
      } // [/DEBUG-INSTR:llm-latency-2026-06-13]
      if (responseMap.containsKey("choices")) {
         JsonArray choices = responseMap.get("choices").getAsJsonArray();
         if (choices.size() != 0) {
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObject != null && messageObject.has("content")) {
               LOGGER.info("Finished complete conversation HTTP request last msg={}", lastMessageForDebug);
               return messageObject.get("content").getAsString();
            }
         }
      }

      throw new Exception("Invalid response format: " + responseMap.toString());
   }

   /**
    * History summarization — uses {@link AiTaskClass#SUMMARIZATION} (Default profile).
    *
    * @deprecated Call {@link #completeConversationToString(ConversationHistory, AiTaskClass)}
    *             with an explicit task class. Kept for legacy/test call sites.
    */
   @Deprecated
   public String completeConversationToString(ConversationHistory conversationHistory) throws Exception {
      return completeConversationToString(conversationHistory, AiTaskClass.SUMMARIZATION);
   }

   static boolean canUseNamedFallback(JoulesCache.JoulesSnapshot snapshot, BudgetThresholds thresholds) {
      return JoulesCache.isFreshPatronSnapshot(snapshot, thresholds);
   }

   private Map<String, JsonElement> sendChatCompletionRequest(JsonObject requestBody, AiTaskClass taskClass) throws Exception {
      Player2PayerResolution.ApiBillingContext billing = billingOrFallback();
      String billingKey = billing != null ? billing.billingKey() : null;
      Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();

      ServerPlayer payerToNotify = billing != null ? billing.onlinePayer() : null;

      // --- Phase A4: budget guard ---
      // Per-player thresholds (call-count + Joules limits) come from the player's own config in
      // PROMPTER_PAYS mode. Profile routing decisions always come from the server config.
      MinecraftServer budgetServer = payerToNotify != null ? payerToNotify.getServer() : null;
      BudgetThresholds thresholds = BudgetThresholdsResolver.resolve(budgetServer, billing);

      BudgetTracker.BudgetCheckResult callResult = BudgetTracker.checkAndRecord(billingKey, thresholds);
      JoulesCache.maybeRefresh(this, billingKey, thresholds);
      JoulesCache.JoulesSnapshot joulesSnap = JoulesCache.get(billingKey).orElse(null);
      BudgetTracker.BudgetCheckResult joulesResult = JoulesCache.checkJoulesThreshold(joulesSnap, thresholds);
      BudgetTracker.BudgetCheckResult result = BudgetTracker.stricter(callResult, joulesResult);

      if (result == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
         boolean callWasFirst = callResult == BudgetTracker.BudgetCheckResult.HARD_LIMIT
                 && BudgetTracker.shouldSendHardMessage(billingKey);
         boolean joulesWasFirst = joulesResult == BudgetTracker.BudgetCheckResult.HARD_LIMIT
                 && JoulesCache.shouldSendHardMessage(billingKey);
         if ((callWasFirst || joulesWasFirst) && payerToNotify != null) {
            if (callResult == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
               payerToNotify.sendSystemMessage(Component.translatable(
                       "message.playerengine.budget.hard_limit_calls", thresholds.getBudgetWindowMinutes()
               ).withStyle(ChatFormatting.RED));
            } else {
               payerToNotify.sendSystemMessage(Component.translatable(
                       "message.playerengine.budget.hard_limit_joules"
               ).withStyle(ChatFormatting.RED));
            }
         }
         throw new Exception(StopReason.BUDGET_HARD_LIMIT.name() + ":limit_reached");
      }

      if (result == BudgetTracker.BudgetCheckResult.SOFT_LIMIT) {
         // Profile routing is always a server-level decision regardless of payer mode
         String fallbackProfile = config.getFallbackProfile();
         boolean implicitDefaultFallback = fallbackProfile == null || fallbackProfile.isBlank();
         boolean isDedicatedProxy = config.isDedicatedClientProxy();

         if (config.getBudgetFallbackBehavior() == BudgetFallbackBehavior.HARD_STOP) {
            throw softLimitHardStop(billingKey, payerToNotify);
         }

         // SWITCH_PROFILE + no named target means an explicit step-down to the implicit Default
         // profile. Return before B3 so task-class routing cannot promote this call back to named.
         if (implicitDefaultFallback) {
            boolean shouldMsg = BudgetTracker.shouldSendSoftMessage(billingKey)
                    || JoulesCache.shouldSendSoftMessage(billingKey);
            if (shouldMsg && payerToNotify != null) {
               payerToNotify.sendSystemMessage(Component.translatable(
                       "message.playerengine.budget.soft_limit_switch",
                       Component.translatable("message.playerengine.profile.default")
               ).withStyle(ChatFormatting.YELLOW));
            }
            return api("POST", "/v1/chat/completions", requestBody);
         }

         // A named fallback is allowed only for a fresh patron snapshot, using the same gate as B3.
         // Named URL rewriting is unavailable in dedicated client-proxy mode.
         if (isDedicatedProxy || !canUseNamedFallback(joulesSnap, thresholds)) {
            throw softLimitHardStop(billingKey, payerToNotify);
         }

         java.util.Optional<String> profileUrl = ProfileUrlResolver.resolve(this, fallbackProfile);
         if (profileUrl.isEmpty()) {
            LOGGER.warn("sendChatCompletionRequest: configured named fallback could not be resolved; stopping at soft limit");
            throw softLimitHardStop(billingKey, payerToNotify);
         }
         boolean shouldMsg = BudgetTracker.shouldSendSoftMessage(billingKey)
                 || JoulesCache.shouldSendSoftMessage(billingKey);
         if (shouldMsg && payerToNotify != null) {
            payerToNotify.sendSystemMessage(Component.translatable(
                    "message.playerengine.budget.soft_limit_switch",
                    fallbackProfile
            ).withStyle(ChatFormatting.YELLOW));
         }
         Player2HTTPUtils.setProfileBaseUrlOverride(profileUrl.get());
         try {
            return api("POST", "/v1/chat/completions", requestBody);
         } finally {
            Player2HTTPUtils.clearProfileBaseUrlOverride();
         }
      }
      // --- end budget guard ---

      // --- Phase B3: task-class routing (only runs when A4 did not already set a profile override) ---
      RoutingDecision routing = ModelTierRouter.resolve(taskClass, this, joulesSnap, thresholds, config);
      if (routing.isOnDevice()) {
         LOGGER.error("ModelTierRouter: RETRIEVAL task class reached sendChatCompletionRequest — caller bug; using Default");
         return api("POST", "/v1/chat/completions", requestBody);
      }
      if (routing.profileBaseUrlOverride().isPresent()) {
         Player2HTTPUtils.setProfileBaseUrlOverride(routing.profileBaseUrlOverride().get());
         try {
            return api("POST", "/v1/chat/completions", requestBody);
         } finally {
            Player2HTTPUtils.clearProfileBaseUrlOverride();
         }
      }
      // --- end B3 routing ---

      return api("POST", "/v1/chat/completions", requestBody);
   }

   private static Exception softLimitHardStop(String billingKey, ServerPlayer payerToNotify) {
      boolean shouldMsg = BudgetTracker.shouldSendSoftMessage(billingKey)
              || JoulesCache.shouldSendSoftMessage(billingKey);
      if (shouldMsg && payerToNotify != null) {
         payerToNotify.sendSystemMessage(Component.translatable(
                 "message.playerengine.budget.soft_limit_paused"
         ).withStyle(ChatFormatting.YELLOW));
      }
      return new Exception(StopReason.BUDGET_HARD_LIMIT.name() + ":soft_limit_hard_stop");
   }

   /**
    * Single-string TTS path (no inline markers): one chunk, no boundaries. Backward-compatible
    * entry point used by callers that have no parsed marker data.
    */
   public void textToSpeech(String message, Character character, UUID botUuid,
         Consumer<Map<String, JsonElement>> onFinish) {
      textToSpeech(message, java.util.List.of(message), java.util.List.of(), character, botUuid, onFinish);
   }

   /**
    * TTS dispatch with TTS-timed gesture chunks/boundaries (Workstream 2 + 4).
    *
    * <p><strong>FROZEN stream_tts S2C wire field order (the client mirrors this exact order):</strong>
    * <pre>
    *   writeUtf(clientId)
    *   writeUtf("")                       // reserved (unused web-stream field)
    *   writeUtf(message)                  // STRIPPED text (no [bl:...] markers) — full message
    *   writeDouble(1)                     // speed
    *   writeVarInt(voiceIds.length); per id: writeUtf(id)
    *   writeUtf(botUuid)                  // ---- end of the LEGACY trailing field ----
    *   // ==== appended bodylang-gesture fields (FROZEN — append-only, never reorder) ====
    *   writeVarInt(chunkCount); per chunk: writeUtf(chunkText)        // ordered, stripped chunks
    *   writeVarInt(boundaryCount);                                    // VALID boundaries only
    *   per boundary: writeVarInt(chunkIndexAfter); writeUtf(actionName)  // actionName = lowercase enum
    * </pre>
    * Invalid markers are omitted from the wire entirely (reported to both audiences at parse time), so
    * {@code segIndex} in the {@code segment_done} C2S packet indexes into this VALID-ONLY boundary list.
    *
    * @param message        full stripped text (chat + log + markSpeakingFor length all use this)
    * @param chunks         ordered stripped chunks (split at marker positions; never null/empty)
    * @param validBoundaries ordered VALID-only gesture boundaries (invalid ones already filtered out)
    */
   public void textToSpeech(String message, java.util.List<String> chunks,
         java.util.List<MarkerParser.SegmentBoundary> validBoundaries, Character character, UUID botUuid,
         Consumer<Map<String, JsonElement>> onFinish) {
      try {
         // WS4 re-center: the audible radius AND the same-dimension check anchor on the BOT ENTITY
         // (controller.getPlayer(), a LivingEntity — NOT a ServerPlayer) so the bot's voice follows
         // the bot, symmetric with bot-centered AI hearing. NEVER cast controller.getPlayer() to
         // ServerPlayer (a bot is not a player — it would ClassCastException). The old
         // "ServerPlayer owner = (ServerPlayer) controller.getOwner();" anchor was removed.
         Entity botEntity = controller.getPlayer();
         if (botEntity == null) return;
         MinecraftServer server = botEntity.getServer();
         if (server == null) return;

         double TTS_RANGE = 64.0;

         if (chunks == null || chunks.isEmpty()) {
            chunks = java.util.List.of(message);
         }
         if (validBoundaries == null) {
            validBoundaries = java.util.List.of();
         }

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!TtsClientPreferenceStore.isTtsEnabled(player.getUUID())) {
               continue;
            }
            if (player.level() == botEntity.level()
                  && player.distanceTo((Entity) botEntity) <= TTS_RANGE) {
               RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                     player.registryAccess());
               // ---- legacy stream_tts fields (unchanged order) ----
               buf.writeUtf(clientId);
               buf.writeUtf("");
               buf.writeUtf(message);
               buf.writeDouble(1);
               buf.writeVarInt(character.voiceIds().length);
               for (String id : character.voiceIds()) {
                  buf.writeUtf(id);
               }
               buf.writeUtf(botUuid.toString());
               // ---- appended bodylang-gesture fields (REUSE this per-loader buf — do NOT make a
               //      new buffer; the RegistryFriendlyByteBuf class is the 1.21.1 loader delta) ----
               buf.writeVarInt(chunks.size());
               for (String chunkText : chunks) {
                  buf.writeUtf(chunkText);
               }
               buf.writeVarInt(validBoundaries.size());
               for (MarkerParser.SegmentBoundary b : validBoundaries) {
                  buf.writeVarInt(b.chunkIndexAfter());
                  buf.writeUtf(b.action().name().toLowerCase(java.util.Locale.ROOT));
               }

               player.connection.send(NetworkManager.toPacket(NetworkManager.Side.S2C,
                     ResourceLocation.fromNamespaceAndPath("playerengine", "stream_tts"), buf));
            }
         }

         // WS4 server-side fallback timer (liveness backstop): keyed by botUuid, generous deadline =
         // markSpeakingFor estimate + slack. Fires unfired VALID boundaries in order then clears the
         // cooldown if no prompter message_done arrives (client crash / bot-to-bot / ambient / prompter
         // moved away). Idempotent vs. late ACKs; dispatch runs on the server tick thread.
         com.player2.playerengine.PlayerEngine.scheduleTtsFallbackTimer(server, botUuid,
               message == null ? 0 : message.length());

         onFinish.accept(null);
      } catch (Exception var9) {
         LOGGER.error("Error broadcasting TTS", var9);
      }
   }

   // public void textToSpeech(String message, Character character,
   // Consumer<Map<String, JsonElement>> onFinish) {
   // try {
   // JsonObject requestBody = new JsonObject();
   // requestBody.addProperty("speed", 1);
   // requestBody.addProperty("text", message);
   // requestBody.addProperty("audio_format", "mp3");
   // JsonArray voiceIdsArray = new JsonArray();
   //
   // for (String voiceId : character.voiceIds()) {
   // voiceIdsArray.add(voiceId);
   // }
   //
   // requestBody.add("voice_ids", voiceIdsArray);
   // LOGGER.info("TTS request w/ msg={}", message);
   // Map<String, JsonElement> responseMap =
   // Player2HTTPUtils.sendRequest(controller.getOwner(), clientId,"/v1/tts/speak",
   // true, requestBody);
   // onFinish.accept(responseMap);
   // } catch (Exception var9) {
   // }
   // }

   public void startSTT() {
      JsonObject requestBody = new JsonObject();
      // Capped at the /v1/stt/start schema maximum (StartSpeechToTextRequest.timeout max 60s).
      requestBody.addProperty("timeout", 60);

      try {
         api("POST", "/v1/stt/start", requestBody);
      } catch (Exception var3) {
         System.err.println("[Player2APIService/startSTT]: Error" + var3.getMessage());
      }
   }

   public String stopSTT() {
      try {
         Map<String, JsonElement> responseMap = api("POST", "/v1/stt/stop", null);
         if (!responseMap.containsKey("text")) {
            throw new Exception("Could not find key 'text' in response");
         } else {
            return responseMap.get("text").getAsString();
         }
      } catch (Exception var2) {
         return var2.getMessage();
      }
   }

   public void trySendHeartbeat() {
      var cfg = Player2ServerConfigHolder.get();
      String ownerName = controller.getOwnerUsername();
      boolean tokenPresent = !TokenStorage.getToken(ownerName, clientId).isEmpty();
      boolean ownerOnline = controller.getOwner() instanceof ServerPlayer sp
            && controller.getPlayer().level().getServer().getPlayerList().getPlayer(sp.getUUID()) != null;
      if (!Player2ServerConfigHolder.shouldSendServerControllerHeartbeat(cfg, tokenPresent, ownerOnline)) {
         return;
      }
      if (HeartbeatManager.shouldHeartbeat(ownerName, clientId)) {
         if (tokenPresent) {
            sendHeartbeat();
         }
         HeartbeatManager.storeHeartbeatTime(ownerName, clientId);
      }
   }

   public void sendHeartbeat() {
      com.player2.playerengine.player2api.auth.AuthenticationManager.getExecutor().submit(() -> {
         try {
            System.out.println("Sending Heartbeat " + clientId);
            Player2PayerResolution.ApiBillingContext bill = Player2PayerResolution.resolve(controller, null, clientId);
            Player2ApiDispatcher.route(controller, clientId, "GET", "/v1/health", null, bill);
            System.out.println("Heartbeat Successful");
         } catch (Exception var2) {
            System.err.printf("Heartbeat Fail: %s\n", var2.getMessage());
         }
      });
   }

   /**
    * Search for schematics given a query
    * 
    * @return List of schematics matching the query
    */
   public List<JsonObject> searchSchematics(String query) {
      try {
         JsonObject requestBody = new JsonObject();
         requestBody.addProperty("query", query);
         requestBody.addProperty("max_results", 10);

         Map<String, JsonElement> responseMap = api("POST", "/v1/minecraft/schematics/search", requestBody);

         JsonElement resultsJsonElement = responseMap.get("results");
         if (resultsJsonElement != null && resultsJsonElement.isJsonArray()) {
            JsonArray resultsJsonArray = resultsJsonElement.getAsJsonArray();

            List<JsonObject> schematics = new ArrayList<>();
            for (JsonElement voiceElement : resultsJsonArray) {
               JsonObject voiceObject = voiceElement.getAsJsonObject();
               schematics.add(voiceObject);
            }
            return schematics;
         } else {
            System.err.println(
                  "No results field array in response with keys: [" + String.join(",", responseMap.keySet()) + "]");
         }
      } catch (Exception e) {
         System.err.println("Search schematics request failed: " + e.getMessage());
      }
      return Collections.emptyList();
   }

   /**
    * Get schematic binary given a schematic ID
    * 
    * @return Schematic binary data
    */
   public String getSchematicBinary(String schematicId) {
      try {
         Map<String, JsonElement> responseMap = api("GET", "/v1/minecraft/schematics/" + schematicId, null);
         JsonElement dataJsonElement = responseMap.get("data");
         if (dataJsonElement != null && dataJsonElement.isJsonPrimitive()) {
            return dataJsonElement.getAsString();
         } else {
            System.err.println(
                  "No data field primitive in response with keys: [" + String.join(",", responseMap.keySet()) + "]");
         }
      } catch (Exception e) {
         System.err.println("Get schematic binary request failed: " + e.getMessage());
      }
      return "";
   }

   public String getGameData(String key) {
      try {
         Map<String, JsonElement> responseMap = api("GET",
               "/v1/games/" + clientId + "/data/user?key=" + key, null);
         JsonElement valueElement = responseMap.get("value");
         if (valueElement != null && valueElement.isJsonPrimitive()) {
            return valueElement.getAsString();
         }
      } catch (Exception e) {
         // Usually 404 when key is not found
         System.err.println("Get game data request failed: " + e.getMessage());
      }
      return null;
   }

   public boolean setGameData(String key, String value) {
      try {
         JsonObject requestBody = new JsonObject();
         requestBody.addProperty("key", key);
         requestBody.addProperty("value", value);
         Map<String, JsonElement> responseMap = api("PUT", "/v1/games/" + clientId + "/data/user", requestBody);
         JsonElement successElement = responseMap.get("success");
         if (successElement != null && successElement.isJsonPrimitive()) {
            return successElement.getAsBoolean();
         }
      } catch (Exception e) {
         System.err.println("Set game data request failed: " + e.getMessage());
      }
      return false;
   }
}
