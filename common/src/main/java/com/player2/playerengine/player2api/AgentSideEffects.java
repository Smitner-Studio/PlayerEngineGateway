
package com.player2.playerengine.player2api;

import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.manager.TTSManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class AgentSideEffects {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * A companion's line: chat to every player, speech, and the relay to other companions. What it
     * does is not here: a reply's program runs as a job (ProgramJobs), never from this message.
     */
    public static void onEntityMessage(MinecraftServer server, Event.CharacterMessage characterMessage) {
        AgentConversationData sendingCharacterData = characterMessage.sendingCharacterData();
        boolean hasText = characterMessage.message() != null && !characterMessage.message().isBlank();
        // A marker-only message ("[bl:greeting]") strips to empty text (decision 2) but still carries
        // pending gesture boundaries that MUST dispatch via the TTS/segment path — otherwise the gesture
        // never fires, no stream_tts is sent, segment_done can never arrive, and the cooldown is never
        // armed/cleared (a silent non-fire after the model expected the gesture; DESIGN.md §3). So the
        // TTS dispatch / markSpeakingFor / invalid-marker report / onAICharacterMessage all run when there
        // is text OR a pending gesture boundary. Only the player CHAT line is gated on actual text (no one
        // wants a "<bot> " empty chat line for a marker-only turn).
        java.util.List<MarkerParser.SegmentBoundary> pendingBoundaries = sendingCharacterData.getPendingSegmentActions();
        boolean hasPendingGesture = pendingBoundaries != null && !pendingBoundaries.isEmpty();
        if (hasText) {
            Component chatLine = Component.translatable("message.playerengine.chat.character_message",
                    sendingCharacterData.getName(), characterMessage.message());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                broadcastChatToPlayer(server, chatLine, player);
            }
        }
        if (hasText || hasPendingGesture) {
            // Bodylang TTS-timed gestures: read the chunk list + VALID-only boundaries parsed at
            // handleLlmResponse off the sending bot's data and forward them so the stream_tts payload
            // carries the gesture chunks/boundaries (Workstream 1/2). The CharacterMessage.message()
            // field is already STRIPPED (decision 2), so chat, TTS, and markSpeakingFor all use it. For a
            // marker-only message the message is empty; the client chunk loop skips synthesis on the empty
            // chunk and immediately emits segment_done for the boundary.
            TTSManager.TTS(characterMessage.message(),
                    sendingCharacterData.getPendingChunks(),
                    sendingCharacterData.getPendingSegmentActions(),
                    sendingCharacterData.getCharacter(),
                    sendingCharacterData.getPlayer2apiService(), sendingCharacterData.getUUID());
            // Per-bot speaking cooldown replaces the old server-wide TTS lock: this bot is gated
            // until its message is plausibly done playing client-side, but other bots can keep
            // dispatching LLM calls in their own billing buckets. markSpeakingFor STAYS HERE
            // (decision 4) operating on the now-stripped message — do not move or duplicate it. On a
            // marker-only (empty) message this arms a near-minimal cooldown, i.e. an immediate gesture.
            sendingCharacterData.markSpeakingFor(characterMessage.message());
            // Workstream 6 — player-facing report for any INVALID markers the model emitted this turn.
            // The model already got a truthful InfoMessage at parse time (handleLlmResponse); here the
            // PLAYER gets a concise, human chat line so neither audience is told a gesture happened that
            // did not (DESIGN.md §3). Audience-tailored: short line for the player, full note for the model.
            java.util.List<String> invalidMarkers = sendingCharacterData.getPendingInvalidMarkers();
            if (invalidMarkers != null && !invalidMarkers.isEmpty()) {
                broadcastChatToAllPlayers(server, Component.translatable("message.playerengine.agent.invalid_gesture",
                        sendingCharacterData.getName(), String.join("', '", invalidMarkers)));
            }
            ConversationManager.onAICharacterMessage(characterMessage,
                    characterMessage.sendingCharacterData().getUUID());
        }
    }

    public static void onError(MinecraftServer server, String errMsg, ServerPlayer player) {
        LOGGER.error(errMsg);
        broadcastErrorMsgToPlayer(server, errMsg, player);
    }

    public static void broadcastChatToPlayer(MinecraftServer server, String message, ServerPlayer player) {
        broadcastChatToPlayer(server, Component.literal(message), player);
    }

    public static void broadcastChatToPlayer(MinecraftServer server, Component message, ServerPlayer player) {
        sendTo(player, message);
    }

    private static void broadcastErrorMsgToPlayer(MinecraftServer server, String message, ServerPlayer player) {
        MutableComponent output = Component.literal(message);
        output.setStyle(output.getStyle().applyFormat(ChatFormatting.RED));
        sendTo(player, output);
    }

    // The player is null when a turn ends while its owner is offline (billing resolves no online
    // owner); throwing here would crash the server tick.
    private static void sendTo(ServerPlayer player, Component message) {
        if (player == null) {
            LOGGER.warn("No online player to show a chat line; skipped: {}", message.getString());
            return;
        }
        player.displayClientMessage(message, false);
    }

    public static void broadcastChatToAllPlayers(MinecraftServer server, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            broadcastChatToPlayer(server, message, player);
        }
    }

    public static void broadcastChatToAllPlayers(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            broadcastChatToPlayer(server, message, player);
        }
    }

    public static void teleportOwnerTo(AgentConversationData data){
        Player owner = data.getMod().getOwner();
        LivingEntity entity = data.getEntity();

        double x = entity.getX() + 0.5;
        double y = entity.getY() + 0.5;
        double z = entity.getZ() + 0.5;

        owner.teleportTo(x, y, z);
    }

}
