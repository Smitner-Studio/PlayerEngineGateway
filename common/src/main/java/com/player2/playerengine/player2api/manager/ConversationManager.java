package com.player2.playerengine.player2api.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.Collections;
import java.util.stream.Collectors;

import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.player2api.Event;
import com.player2.playerengine.player2api.LLMCompleter;
import com.player2.playerengine.player2api.CompanionAddress;
import com.player2.playerengine.player2api.StopIntent;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.AgentConversationData;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.ChatEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.BotBlacklistPolicy;
import com.player2.playerengine.player2api.CallByNameMentionRouter;
import com.player2.playerengine.player2api.UserBlacklistPolicy;
import com.player2.playerengine.player2api.utils.SttLogging;
import com.player2.playerengine.player2api.Event.UserMessage;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.status.StatusUtils;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public class ConversationManager {
    public static final Logger LOGGER = LogManager.getLogger();

    public static ConcurrentHashMap<UUID, AgentConversationData> queueData = new ConcurrentHashMap<>();
    public static final float messagePassingMaxDistance = 64; // let messages between entities pass iff <= this maximum
    private static boolean hasInit = false;

    public static void init() {
        if (!hasInit) {
            hasInit = true;
            // unused but need to keep this so subscribes to events
            // TODO: figure out what to do w. fabric here:
            ChatEvent.RECEIVED.register((player, component) -> {
                String message = component.plainCopy().getString();
                String sender = player.getName().getString();
                ConversationManager.onUserChatMessage(new UserMessage(
                        message, sender, false, player.getUUID()));
                return EventResult.pass();
            });
        }
    }

    /**
     * LLM dispatch lanes keyed by (billing key, endpoint profile). The billing key is the online payer
     * UUID for PROMPTER_PAYS / OWNER_PAYS_ALL online, or "token:&lt;username&gt;" for stored-token mode.
     * A slow lane only blocks itself: two companions of one player on different profiles think at once.
     */
    private static final LlmLanes llmLanes = new LlmLanes();

    /**
     * Extra completers (e.g. build-structure) that own their own lifecycle. Tracked separately so
     * shutdown can drain them without touching the dispatch lanes.
     */
    private static final CopyOnWriteArrayList<LLMCompleter> extraLLMCompleters = new CopyOnWriteArrayList<>();

    /** Extra completers (e.g. build-structure) register here; included in server shutdown. */
    public static void registerLLMCompleter(LLMCompleter completer) {
        if (completer != null && !extraLLMCompleters.contains(completer)) {
            extraLLMCompleters.add(completer);
        }
    }

    public static void unregisterLLMCompleter(LLMCompleter completer) {
        if (completer != null) {
            extraLLMCompleters.remove(completer);
        }
    }

    /**
     * Shuts down every registered completer (dispatch lanes + extras) and clears the maps so
     * the next session (e.g. integrated server restart in the same JVM) starts with fresh executors.
     * Lanes are lazily recreated on first dispatch.
     */
    public static void shutdownAndResetLLMCompleters() {
        llmLanes.shutdownAll();
        for (LLMCompleter c : new ArrayList<>(extraLLMCompleters)) {
            c.shutdown();
        }
        extraLLMCompleters.clear();
    }

    /**
     * Drop every lane of a billing key (e.g. on player disconnect under PROMPTER_PAYS). In-flight
     * worker threads are shut down; new dispatch for that key lazily builds fresh lanes if/when the
     * player rejoins.
     */
    public static void shutdownCompleterForBillingKey(String billingKey) {
        llmLanes.shutdownBilling(billingKey);
    }

    // ## Utils
    public static AgentConversationData getOrCreateEventQueueData(PlayerEngineController mod) {
        return queueData.computeIfAbsent(mod.getPlayer().getUUID(), k -> {
            LOGGER.info(
                    "EventQueueManager/getOrCreateEventQueueData: creating new queue data for entId={}",
                    mod.getPlayer().getStringUUID());
            return new AgentConversationData(mod);
        });
    }

    private static Stream<AgentConversationData> filterQueueData(Predicate<AgentConversationData> pred) {
        return queueData.values().stream().filter(pred);
    }

    private static Stream<AgentConversationData> getCloseDataByUUID(UUID sender) {
        return filterQueueData(data -> data.getDistance(sender) < messagePassingMaxDistance);
    }

    // ## Callbacks (need to register these externally)

    private static void maybeNotifyUserBlacklistCallByName(MinecraftServer server, String speakerName,
            HashSet<UUID> notifiedBotOwnerUuids, AgentConversationData blockedTarget) {
        if (server == null || speakerName == null || speakerName.isBlank()) {
            return;
        }
        Player owner = blockedTarget.getMod().getOwner();
        if (owner == null) {
            return;
        }
        UUID ownerUuid = owner.getUUID();
        if (!notifiedBotOwnerUuids.add(ownerUuid)) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.getGameProfile().getName().equalsIgnoreCase(speakerName.trim())) {
                sp.sendSystemMessage(Component.translatable("message.playerengine.blacklist.user_blocked"));
                return;
            }
        }
    }

    /**
     * Test hook for the smoke harness: sees every notice a speaker is sent (too far, which one,
     * stop acknowledgements), since a fake player has no client to show them.
     */
    public static volatile BiConsumer<String, Component> noticeTap;

    // register when a user sends a chat message
    public static void onUserChatMessage(UserMessage msg) {
        LOGGER.info("User message event={}", msg);
        List<CompanionAddress.Candidate<AgentConversationData>> companions = candidatesFor(msg.userName());
        MinecraftServer server = companions.stream()
                .map(c -> c.ref().getMod().getPlayer().getServer())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (handleStop(msg, companions, server)) {
            return;
        }
        if (handleConfirm(msg, companions)) {
            return;
        }
        boolean callByName = Player2ServerConfigHolder.get().isCallByNameChat();
        List<AgentConversationData> nearby = companions.stream()
                .filter(c -> c.sameDimension() && c.distance() < CompanionAddress.NEAR)
                .map(CompanionAddress.Candidate::ref)
                .collect(Collectors.toList());
        if (!callByName) {
            if (nearby.isEmpty()) {
                logMessageNotDelivered(msg, false, "no_nearby_companion", nearby, Set.of());
                return;
            }
            int queued = 0;
            for (AgentConversationData data : nearby) {
                if (server != null && BotBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                    logMessageBlocked(msg, data.getName(), "bot_blacklist");
                    continue;
                }
                if (server != null && UserBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                    logMessageBlocked(msg, data.getName(), "user_blacklist");
                    continue;
                }
                data.onEvent(msg);
                queued++;
            }
            if (queued == 0) {
                logMessageNotDelivered(msg, false, "all_nearby_blocked", nearby, Set.of());
            }
            return;
        }

        CallByNameMentionRouter.Resolved<AgentConversationData> resolved = CallByNameMentionRouter.resolveTargets(
                msg.message(), msg.authenticatedUserUuid(), companions);
        for (CompanionAddress.Outcome<AgentConversationData> notice : resolved.notices()) {
            notifyPlayer(msg.userName(), server, noticeText(notice));
        }
        if (resolved.targets().isEmpty()) {
            logMessageNotDelivered(msg, true, resolved.notices().isEmpty() ? "call_by_name_no_mention"
                    : "call_by_name_not_reached", nearby, resolved.targets());
            return;
        }
        if (resolved.cleaned() == null) {
            logMessageNotDelivered(msg, true, "call_by_name_cleaned_message_null", nearby, resolved.targets());
            return;
        }
        UserMessage cleanedMessage = resolved.cleaned().equals(msg.message()) ? msg : msg.withMessage(resolved.cleaned());
        HashSet<UUID> userBlacklistNotifiedOwners = new HashSet<>();
        int queued = 0;
        for (AgentConversationData data : resolved.targets()) {
            if (server != null && BotBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                logMessageBlocked(msg, data.getName(), "bot_blacklist");
                continue;
            }
            if (server != null && UserBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                maybeNotifyUserBlacklistCallByName(server, msg.userName(), userBlacklistNotifiedOwners, data);
                logMessageBlocked(msg, data.getName(), "user_blacklist");
                continue;
            }
            data.onEvent(cleanedMessage);
            queued++;
        }
        if (queued == 0) {
            logMessageNotDelivered(msg, true, "call_by_name_all_targets_blocked", nearby, resolved.targets());
        }
    }

    /**
     * Every companion as {@code speakerName} sees it: owner, names, and whether and how far it is in
     * the speaker's dimension. A companion's world has the speaker among its players exactly when
     * both are in the same dimension.
     */
    private static List<CompanionAddress.Candidate<AgentConversationData>> candidatesFor(String speakerName) {
        List<CompanionAddress.Candidate<AgentConversationData>> out = new ArrayList<>();
        for (AgentConversationData data : queueData.values()) {
            try {
                Character ch = data.getCharacter();
                if (ch == null || data.getMod() == null || data.getMod().getPlayer() == null) {
                    continue;
                }
                Set<String> names = new HashSet<>();
                for (String n : new String[] {ch.name(), ch.shortName()}) {
                    String k = CompanionAddress.key(n);
                    if (!k.isEmpty()) {
                        names.add(k);
                    }
                }
                Player owner = data.getMod().getOwner();
                float distance = StatusUtils.getDistanceToUsername(data.getMod(), speakerName);
                boolean sameDimension = distance < Float.MAX_VALUE;
                out.add(new CompanionAddress.Candidate<>(data, owner == null ? null : owner.getUUID(),
                        data.getMod().getOwnerUsername(), displayName(ch), names, sameDimension, distance));
            } catch (RuntimeException stale) {
                LOGGER.warn("Skipping stale companion while routing chat: type={}", stale.getClass().getSimpleName());
            }
        }
        return out;
    }

    private static String displayName(Character ch) {
        return ch.shortName() != null && !ch.shortName().isBlank() ? ch.shortName() : ch.name();
    }

    /** The unique name players use to reach this companion, as {@code Arran's Ada}. */
    public static String uniqueName(AgentConversationData data) {
        return CompanionAddress.uniqueName(data.getMod().getOwnerUsername(), displayName(data.getCharacter()));
    }

    private static Component noticeText(CompanionAddress.Outcome<AgentConversationData> notice) {
        if (notice instanceof CompanionAddress.Outcome.TooFar<AgentConversationData> far) {
            return Component.translatable("message.playerengine.call.too_far", far.target().uniqueName());
        }
        if (notice instanceof CompanionAddress.Outcome.Choose<AgentConversationData> choose) {
            return Component.translatable("message.playerengine.call.which", String.join(", ", choose.uniqueNames()));
        }
        return Component.empty();
    }

    /**
     * The stop lane: anyone may stop a companion, with no proximity check, and the model is bypassed.
     * A unique name stops that companion; a bare name follows the mention rules, so it stops the
     * speaker's own companion or the only one of that name within 64 blocks, and otherwise stops
     * none and asks which. It never stops two. The sender must be authenticated: the UUID comes from
     * the server's chat or speech ingress, never from message text.
     */
    private static boolean handleStop(UserMessage msg, List<CompanionAddress.Candidate<AgentConversationData>> companions,
            MinecraftServer server) {
        UUID speaker = msg == null ? null : msg.authenticatedUserUuid();
        if (speaker == null || msg.message() == null) {
            return false;
        }
        StopIntent.Named named = StopIntent.parse(msg.message());
        if (named == null) {
            return false;
        }
        CompanionAddress.Outcome<AgentConversationData> outcome = named.owner() != null
                ? CompanionAddress.unique(named.owner(), named.bot(), companions)
                : CompanionAddress.bare(named.bot(), speaker, companions);
        AgentConversationData target;
        if (outcome instanceof CompanionAddress.Outcome.Reach<AgentConversationData> r) {
            target = r.target().ref();
        } else if (outcome instanceof CompanionAddress.Outcome.TooFar<AgentConversationData> far) {
            target = far.target().ref();
        } else if (outcome instanceof CompanionAddress.Outcome.Choose<AgentConversationData> choose) {
            notifyPlayer(msg.userName(), server, noticeText(choose));
            LOGGER.warn("Stop from {} named {} companions; stopped none", msg.userName(), choose.uniqueNames().size());
            return true;
        } else {
            return false;
        }
        boolean requestStillDraining = target.cancelPendingModelActionsForOperatorStop();
        target.getMod().isStopping = true;
        target.getMod().stop();
        String name = uniqueName(target);
        notifyPlayer(msg.userName(), server, requestStillDraining
                ? Component.translatable("message.playerengine.agent.owner_stop_ack_delayed", name)
                : Component.translatable("message.playerengine.agent.owner_stop_ack", name));
        LOGGER.info("Stop from {} applied to {}", msg.userName(), name);
        return true;
    }

    /**
     * An owner's yes or no to a companion's open {@code confirm} (design §5.3) answers it here,
     * deterministically, and goes no further. Any other reply closes the question unanswered and
     * carries on to the model as a normal message.
     *
     * @return true when the reply was consumed as an answer
     */
    private static boolean handleConfirm(UserMessage msg,
            List<CompanionAddress.Candidate<AgentConversationData>> companions) {
        UUID speaker = msg == null ? null : msg.authenticatedUserUuid();
        if (speaker == null || msg.message() == null) {
            return false;
        }
        boolean consumed = false;
        for (CompanionAddress.Candidate<AgentConversationData> c : companions) {
            com.player2.playerengine.PlayerEngineController mod = c.ref().getMod();
            if (!mod.isOwner(speaker)) {
                continue;
            }
            com.player2.playerengine.seam.Seam seam = mod.getCommandExecutor().seam();
            if (seam.awaitingReply() && seam.reply(msg.message())) {
                LOGGER.info("Confirm answered by {} for {}: {}", msg.userName(), c.ref().getName(), msg.message());
                consumed = true;
            }
        }
        return consumed;
    }

    private static void notifyPlayer(String userName, MinecraftServer server, Component message) {
        BiConsumer<String, Component> tap = noticeTap;
        if (tap != null) {
            tap.accept(userName, message);
        }
        if (userName == null || server == null || message == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (userName.equals(player.getGameProfile().getName())) {
                AgentSideEffects.broadcastChatToPlayer(server, message, player);
                return;
            }
        }
    }

    private static void logMessageNotDelivered(UserMessage msg, boolean callByName, String reason,
            List<AgentConversationData> nearby, Set<AgentConversationData> targets) {
        String nearbyNames = nearby.stream().map(AgentConversationData::getName).collect(Collectors.joining(", "));
        String targetNames = targets == null ? ""
                : targets.stream().map(AgentConversationData::getName).collect(Collectors.joining(", "));
        if (msg.fromVoice()) {
            LOGGER.warn(
                    "STT/voice: message not delivered to companion (reason={}, callByName={}, user={}, nearby=[{}], targets=[{}], preview=\"{}\"). "
                            + "If callByName is enabled, include the companion name (e.g. \"Lina, ...\") in speech.",
                    reason, callByName, msg.userName(), nearbyNames, targetNames, SttLogging.messagePreview(msg.message()));
        } else {
            LOGGER.warn(
                    "Chat: message not delivered to companion (reason={}, callByName={}, user={}, nearby=[{}], targets=[{}], preview=\"{}\")",
                    reason, callByName, msg.userName(), nearbyNames, targetNames, SttLogging.messagePreview(msg.message()));
        }
    }

    private static void logMessageBlocked(UserMessage msg, String companionName, String blockReason) {
        if (msg.fromVoice()) {
            LOGGER.warn("STT/voice: message blocked for companion={} (reason={}, user={}, preview=\"{}\")",
                    companionName, blockReason, msg.userName(), SttLogging.messagePreview(msg.message()));
        } else {
            LOGGER.debug("Chat: message blocked for companion={} (reason={}, user={})", companionName, blockReason,
                    msg.userName());
        }
    }

    // register when an AI character messages
    public static void onAICharacterMessage(Event.CharacterMessage msg, UUID senderId) {
        UUID sendingUUID = msg.sendingCharacterData().getUUID();
        MinecraftServer server = msg.sendingCharacterData().getMod().getPlayer().getServer();
        String initiator = msg.originatingUserName();
        getCloseDataByUUID(sendingUUID).filter(data -> !(data.getUUID().equals(senderId)))
                .filter(data -> initiator == null || initiator.isBlank() || server == null
                        || (!BotBlacklistPolicy.isBlocked(server, initiator, data)
                                && !UserBlacklistPolicy.isBlocked(server, initiator, data)))
                .forEach(data -> {
                    LOGGER.info("onCharMsg/ msg={}, sender={}, running onCharMsg for ={}", msg.message(), senderId,
                            data.getName());
                    data.onAICharacterMessage(msg);
                });
    }

    private static void process(Consumer<Event.CharacterMessage> onCharacterEvent,
            BiConsumer<String, ServerPlayer> onErrEvent) {
        // Dispatch at most one ready candidate per (billing key, endpoint profile) lane so a slow lane
        // doesn't starve the others. Within a lane the highest priority goes first. A companion is
        // never dispatched twice at once: getPriority() is 0 while it is processing, and its lane
        // holds one request at a time.
        List<AgentConversationData> ready = new ArrayList<>();
        Map<AgentConversationData, LlmLanes.LaneKey> keys = new HashMap<>();
        for (AgentConversationData data : queueData.values()) {
            if (data.getPriority() == 0
                    || data.getEntity() == null
                    || !data.getEntity().isAlive()
                    || data.getEntity().isRemoved()
                    || data.getMod().getOwner() == null) {
                continue;
            }
            Player2PayerResolution.ApiBillingContext billing = data.previewBilling();
            String billingKey = billing != null ? billing.billingKey() : null;
            if (billingKey == null) {
                // No usable billing — let AgentConversationData.process emit the standard "no billing" error.
                billingKey = "__no_billing__:" + data.getUUID();
            }
            Character character = data.getMod().getAIPersistantData() != null
                    ? data.getMod().getAIPersistantData().getCharacter() : null;
            keys.put(data, LlmLanes.LaneKey.of(billingKey, character != null ? character.id() : null));
            ready.add(data);
        }
        llmLanes.dispatch(ready, keys::get, AgentConversationData::getPriority, (data, completer) -> {
            Player owner = data.getMod().getOwner();
            MinecraftServer srv = owner != null ? owner.getServer() : null;
            ServerPlayer ownerServerPlayer = (srv != null) ? srv.getPlayerList().getPlayer(owner.getUUID()) : null;
            data.process(onCharacterEvent, errMsg -> onErrEvent.accept(errMsg, ownerServerPlayer), completer);
        });
    }

    // side effects are here:
    public static void injectOnTick(MinecraftServer server) {
        queueData.forEach((k, v) -> {
            if(v.getMod().getPlayer().getServer() != server){
                despwnCompanion(k);
            }
        });
        queueData.forEach((k, v) -> {
            if (v.getMod().getPlayer().getServer() == server) {
                try {
                    v.tickPlan();
                } catch (RuntimeException e) {
                    LOGGER.error("[Plan] tick failed for bot={}", v.getName(), e);
                }
            }
        });

        Consumer<Event.CharacterMessage> onCharacterEvent = (data) -> {
            AgentSideEffects.onEntityMessage(server, data);
        };
        BiConsumer<String, ServerPlayer> onErrEvent = (errMsg, player) -> {
            AgentSideEffects.onError(server, errMsg, player);
        };

        // No global gate: each dispatch lane gates only its own in-flight call, and per-bot
        // TTS pacing lives in AgentConversationData. Other bots continue to make progress while one
        // lane waits on a slow client.
        process(onCharacterEvent, onErrEvent);
    }

    public static void sendGreeting(PlayerEngineController mod, Character character) {
        LOGGER.info("Sending greeting character={}", character);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onGreeting();
    }

    public static void sendReturnMessage(PlayerEngineController mod, Character character, String ownerName) {
        LOGGER.info("Sending return message character={} owner={}", character, ownerName);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onReturn(ownerName);
    }

    public static void sendDeathRevival(PlayerEngineController mod, Character character, String deathCause) {
        LOGGER.info("Sending death revival character={} cause={}", character, deathCause);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onDeathRevival(deathCause);
    }

    public static void resetMemory(PlayerEngineController mod) {
        mod.getAIPersistantData().clearHistory();
    }


    // recall (map : Map<T, UUID>).values() : Collection<UUID>
    public static void syncQueueData(Collection<UUID> validUuids) {
        queueData.keySet().retainAll(validUuids);
    } 

    public static void despwnCompanion(UUID id) {
        queueData.remove(id);
    }

    public static Collection<AgentConversationData> getDataByOwner(UUID ownerId) {
        if (ownerId == null) return Collections.emptyList();

        return queueData.values().stream()
                .filter(data -> {
                    if (data == null || data.getMod() == null) return false;
                    Player owner = data.getMod().getOwner();
                    if(owner == null) return false;
                    LOGGER.info("getDataByOwner: ownerId={}, test={}", ownerId, owner.getUUID());
                    return owner != null && ownerId.equals(owner.getUUID());
                })
                .collect(Collectors.toList());
    }

    /** Summary of what {@link #clearPendingWork} drained, for operator feedback. */
    public record QueueClearSummary(int queuesCleared, int bucketsShutdown) {
    }

    /**
     * Flush every {@link AgentConversationData} event queue, reset per-bot greeting / in-flight
     * flags, and shut down all LLM dispatch lanes. Persisted conversation history
     * is preserved (this drains pending work, not memory). Lazy lane reconstruction takes care
     * of the next dispatch.
     */
    public static QueueClearSummary clearPendingWork() {
        return clearPendingWork(true);
    }

    /** As {@link #clearPendingWork()}; with {@code dropPlans} false each companion keeps its saved plan. */
    public static QueueClearSummary clearPendingWork(boolean dropPlans) {
        int queuesCleared = 0;
        for (AgentConversationData data : queueData.values()) {
            data.resetForClear(dropPlans);
            queuesCleared++;
        }
        int bucketsShutdown = llmLanes.shutdownAll();
        LOGGER.info("ConversationManager.clearPendingWork: queuesCleared={} bucketsShutdown={}",
                queuesCleared, bucketsShutdown);
        return new QueueClearSummary(queuesCleared, bucketsShutdown);
    }

    /**
     * Scoped variant of {@link #clearPendingWork}: only touches conversations whose owner UUID
     * matches. Lane shutdown is best-effort here — if the owner is also the billing key (e.g.
     * OWNER_PAYS_ALL online) we shut that key's lanes, otherwise we leave shared lanes alone.
     */
    public static QueueClearSummary clearPendingWorkFor(UUID ownerUuid) {
        if (ownerUuid == null) {
            return new QueueClearSummary(0, 0);
        }
        int queuesCleared = 0;
        HashSet<String> seenBillingKeys = new HashSet<>();
        for (AgentConversationData data : queueData.values()) {
            Player owner = data.getMod() != null ? data.getMod().getOwner() : null;
            if (owner == null || !ownerUuid.equals(owner.getUUID())) {
                continue;
            }
            try {
                Player2PayerResolution.ApiBillingContext billing = data.previewBilling();
                String billingKey = billing != null ? billing.billingKey() : null;
                if (billingKey != null) {
                    seenBillingKeys.add(billingKey);
                }
            } catch (Exception e) {
                LOGGER.warn("clearPendingWorkFor: previewBilling threw for owner={}, msg={}", ownerUuid, e.getMessage());
            }
            data.resetForClear();
            queuesCleared++;
        }
        int bucketsShutdown = 0;
        for (String billingKey : seenBillingKeys) {
            bucketsShutdown += llmLanes.shutdownBilling(billingKey);
        }
        LOGGER.info("ConversationManager.clearPendingWorkFor owner={}: queuesCleared={} bucketsShutdown={}",
                ownerUuid, queuesCleared, bucketsShutdown);
        return new QueueClearSummary(queuesCleared, bucketsShutdown);
    }

}
