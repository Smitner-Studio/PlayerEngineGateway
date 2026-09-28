
package com.player2.playerengine.player2api;

import java.util.function.Consumer;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.CommandCaller;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.commands.base.UnknownCommandException;
import com.player2.playerengine.retrieval.RagDeepSearchCommands;
import com.player2.playerengine.retrieval.learning.AliasLearningService;
import com.player2.playerengine.tasks.LookAtOwnerTask;
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

import java.util.Locale;
import java.util.UUID;

public class AgentSideEffects {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String MIXED_IDLE_MODEL_ERROR =
            "idle must be sent by itself and cannot be combined with another command";
    private static final String IGNORED_IDLE_MODEL_NOTE =
            "idle was ignored because a real user task is still active; use stop to cancel that task";

    public sealed interface CommandExecutionStopReason
            permits CommandExecutionStopReason.Cancelled,
            CommandExecutionStopReason.Finished,
            CommandExecutionStopReason.Error {
        String commandName();

        record Cancelled(String commandName) implements CommandExecutionStopReason {
        }

        record Finished(String commandName, String note) implements CommandExecutionStopReason {
        }

        record Error(String commandName, String errMsg) implements CommandExecutionStopReason {
        }
    }

    enum IdleHandling {
        NOT_IDLE,
        IGNORE_ACTIVE_TASK,
        INSTALL_LOOK_AT_OWNER,
        LEAVE_WITHOUT_USER_TASK
    }

    enum IdleCommandShape {
        NO_IDLE,
        SOLE_IDLE,
        MIXED_IDLE
    }

    public static void onEntityMessage(MinecraftServer server, Event.CharacterMessage characterMessage) {
        // message part:
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
                // if you are an owner, or close, send to player.
                // if(sendingCharacterData.isOwner(player.getUUID()) ||
                // isClose(sendingCharacterData, player) ){
                broadcastChatToPlayer(server, chatLine, player);
                // }
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

        // command part:
        if (characterMessage.command() != null && !characterMessage.command().isBlank()) {
            onCommandListGenerated(characterMessage.sendingCharacterData().getMod(), characterMessage.command(),
                    characterMessage.sendingCharacterData()::onCommandFinish, null,
                    characterMessage.sendingCharacterData().commandCaller());
        }
    }

    public static void onError(MinecraftServer server, String errMsg, ServerPlayer player) {
        LOGGER.error(errMsg);
        broadcastErrorMsgToPlayer(server, errMsg, player);
    }

    public static void onCommandListGenerated(PlayerEngineController mod, String command,
                                              Consumer<CommandExecutionStopReason> onStop) {
        onCommandListGenerated(mod, command, onStop, null, CommandCaller.UNPRIVILEGED);
    }

    /**
     * @param onAcceptedSeq receives the controller's command dispatch seq once the line has parsed and
     *                      is about to run; null when the caller does not track supersession
     * @param caller the player the line runs for, whose permissions the dispatcher checks
     */
    public static void onCommandListGenerated(PlayerEngineController mod, String command,
                                              Consumer<CommandExecutionStopReason> onStop,
                                              java.util.function.LongConsumer onAcceptedSeq,
                                              CommandCaller caller) {
        CommandExecutor cmdExecutor = mod.getCommandExecutor();
        String commandWithPrefix = cmdExecutor.isClientCommand(command) ? command
                : (cmdExecutor.getCommandPrefix() + command);
        if (commandWithPrefix.equals("@stop")) {
            mod.isStopping = true;
        } else {
            mod.isStopping = false;
        }
        IdleCommandShape idleShape = classifyIdleCommandLine(
                commandWithPrefix, cmdExecutor.getCommandPrefix());
        if (idleShape == IdleCommandShape.MIXED_IDLE) {
            rejectMixedIdleCommand(mod, onStop);
            return;
        }
        String commandId = idleShape == IdleCommandShape.SOLE_IDLE
                ? "idle" : firstCommandId(commandWithPrefix, cmdExecutor);
        IdleHandling idleHandling = classifyIdleHandling(
                commandId,
                mod.hasActiveNonIdleUserTask(),
                mod.getModSettings().isEnableLookAtOwnerIdle());
        if (idleHandling != IdleHandling.NOT_IDLE) {
            if (idleHandling == IdleHandling.IGNORE_ACTIVE_TASK) {
                LOGGER.info("Ignoring idle while a non-idle user task is active (step={})",
                        mod.getActiveTrackedStep().map(e -> e.getStepKind()).orElse("unknown"));
                reportIgnoredIdleToPlayer(mod);
            } else if (idleHandling == IdleHandling.INSTALL_LOOK_AT_OWNER) {
                mod.runIdleUserTask(new LookAtOwnerTask());
            } else if (idleHandling == IdleHandling.LEAVE_WITHOUT_USER_TASK) {
                mod.clearPolicyIdleUserTask();
            }
            completeHandledIdle(idleHandling, commandWithPrefix, onStop);
            return;
        }

        // add quotes to build_structure so it gets proccessed as one arg:
        String processedCommandWithPrefix = commandWithPrefix;
        // String processedCommandWithPrefix = commandWithPrefix.replaceFirst(
        //         "^(@build_structure)\\s+(?![\"'])(.+)$",
        //         "$1 \"$2\"");

        MinecraftServer server = mod.getWorld().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        UUID botUuid = mod.getPlayer().getUUID();
        String acceptedCommandId = firstCommandId(processedCommandWithPrefix, cmdExecutor);
        if (RagDeepSearchCommands.isMetaCommandId(acceptedCommandId)) {
            LOGGER.debug("[B5] ignoring virtual command rag_deepsearch in AgentSideEffects");
            return;
        }

        // Shared finish handler for both the clean path (note == null) and the success-with-note path
        // (note != null). Behavior is identical apart from the note carried on the Finished reason, so
        // the clean/cancelled paths are byte-for-byte unchanged.
        Consumer<String> onFinishWithNote =
                (note) -> {
                    if (mod.isStopping) {
                        LOGGER.info(
                                "[AgentSideEffects/AgentSideEffects]: (%s) was cancelled. Not adding finish event to queue.",
                                processedCommandWithPrefix);
                        onStop.accept(new CommandExecutionStopReason.Cancelled(commandWithPrefix));
                        LOGGER.info("after cancel, not running look at owner");
                    } else {
                        if (!commandWithPrefix.equals("@bodylang greeting")) {
                            LOGGER.info("Running on stop after finish cmd={}", commandWithPrefix);
                            onStop.accept(new CommandExecutionStopReason.Finished(commandWithPrefix, note));
                        } else {
                            LOGGER.info("Ignore onStop for bodylang greeting");
                        }
                        // ISSUE 1: gate LookAtOwner scheduling. onStop.accept(Finished) above already ran;
                        // skipping this leaves the bot idle (no user task), the desired behavior. The
                        // runUserTask call is the LAST statement in this block, so the finish flow
                        // (queue events, AliasLearning) is unaffected.
                        if (mod.getModSettings().isEnableLookAtOwnerIdle()) {
                            LOGGER.info("Running look at owner task after finish cmd={}", commandWithPrefix);
                            mod.runIdleUserTask(new LookAtOwnerTask());
                        }
                    }
                };

        Runnable runExecute =
                () ->
                        cmdExecutor.execute(
                                processedCommandWithPrefix,
                                caller,
                                () -> {
                                    if (onAcceptedSeq != null) {
                                        onAcceptedSeq.accept(mod.getCommandDispatchSeq());
                                    }
                                    if (server != null && ownerUuid != null && acceptedCommandId != null) {
                                        AliasLearningService.onCommandAccepted(
                                                server, ownerUuid, botUuid, acceptedCommandId, cmdExecutor);
                                    }
                                },
                                () -> onFinishWithNote.accept(null),
                                onFinishWithNote,
                                (err) -> {
                                    boolean unknownCommand = err instanceof UnknownCommandException;
                                    // Keep the RAG-learning audit honest: an UnknownCommandException means the
                                    // emitted name resolved to a command that is NOT registered (not a real
                                    // alias hit), so feeding it to onCommandRejected would record a phantom
                                    // SKIPPED_EXECUTION_ERROR against an unregistered tool id. For aliased
                                    // commands (drop -> give) firstCommandId already returns the resolved name
                                    // and this branch is never taken, so genuine rejections are still audited.
                                    if (!unknownCommand
                                            && server != null && ownerUuid != null && acceptedCommandId != null) {
                                        AliasLearningService.onCommandRejected(
                                                server, ownerUuid, botUuid, acceptedCommandId, err.getMessage());
                                    }
                                    // Honest player-facing correction ONLY for an unknown command name.
                                    // The pre-committed chat line was broadcast before the command ran and
                                    // asserted an action the bot could not perform; add a concise retraction
                                    // so the player is not left believing it happened (DESIGN.md §3). Gated on
                                    // UnknownCommandException so item-arg rejections (already broadcast by
                                    // GetCommand) and runtime errors are NOT double-broadcast / mis-corrected.
                                    // The model already gets the enriched failure verbatim via the executor's
                                    // error route -> onCommandFinish InfoMessage, so both audiences are served
                                    // with audience-tailored wording (DESIGN.md §3): the model sees the raw
                                    // enriched "... Did you mean ...?" string; the player gets a short, plain line.
                                    if (unknownCommand && server != null && ownerUuid != null) {
                                        ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(ownerUuid);
                                        if (ownerPlayer != null) {
                                            broadcastChatToPlayer(server,
                                                    playerCorrectionFor(processedCommandWithPrefix, cmdExecutor,
                                                            err.getMessage()),
                                                    ownerPlayer);
                                        }
                                    }
                                    onStop.accept(
                                            new CommandExecutionStopReason.Error(commandWithPrefix, err.getMessage()));
                                    // ISSUE 1: gate LookAtOwner scheduling. onStop.accept(Error) above
                                    // already ran; skipping this leaves the bot idle (no user task). Last
                                    // statement in the error callback, so the error flow is unaffected.
                                    if (mod.getModSettings().isEnableLookAtOwnerIdle()) {
                                        LOGGER.info("Running look at owner aftr error in cmd={}", commandWithPrefix);
                                        mod.runIdleUserTask(new LookAtOwnerTask());
                                    }
                                });

        if (server != null && !server.isSameThread()) {
            server.execute(runExecute);
        } else {
            runExecute.run();
        }
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

    /** First semicolon-separated command name without prefix or arguments. */
    public static String firstCommandId(String commandWithPrefix, CommandExecutor cmdExecutor) {
        if (commandWithPrefix == null || commandWithPrefix.isBlank()) {
            return null;
        }
        try {
            String line = commandWithPrefix;
            if (cmdExecutor.isClientCommand(line)) {
                line = line.substring(cmdExecutor.getCommandPrefix().length());
            }
            String first = line.split(";")[0].trim();
            if (first.isEmpty()) {
                return null;
            }
            int sp = first.indexOf(' ');
            String name = sp == -1 ? first : first.substring(0, sp);
            // Lower-case first (prior behavior), then resolve a silent synonym (e.g. drop -> give) so the
            // RAG-learning layer (AliasLearningService) audits the RESOLVED command, not the raw synonym —
            // otherwise an aliased emission records a phantom rejection (drop is not in commandSheet).
            return CommandExecutor.resolveName(name.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    /** Classifies idle across the full semicolon command line using CommandExecutor's split rules. */
    static IdleCommandShape classifyIdleCommandLine(String commandWithPrefix, String commandPrefix) {
        if (commandWithPrefix == null || commandWithPrefix.isBlank()) {
            return IdleCommandShape.NO_IDLE;
        }
        String prefix = commandPrefix == null ? "" : commandPrefix;
        String line = commandWithPrefix;
        if (!prefix.isEmpty() && line.startsWith(prefix)) {
            line = line.substring(prefix.length());
        }
        int commandCount = 0;
        boolean containsIdle = false;
        for (String rawPart : line.split(";", -1)) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (!prefix.isEmpty() && part.startsWith(prefix)) {
                part = part.substring(prefix.length()).trim();
            }
            if (part.isEmpty()) {
                continue;
            }
            int space = part.indexOf(' ');
            String rawName = space < 0 ? part : part.substring(0, space);
            String commandId = CommandExecutor.resolveName(rawName.toLowerCase(Locale.ROOT));
            commandCount++;
            containsIdle |= "idle".equals(commandId);
        }
        if (!containsIdle) {
            return IdleCommandShape.NO_IDLE;
        }
        return commandCount == 1
                ? IdleCommandShape.SOLE_IDLE : IdleCommandShape.MIXED_IDLE;
    }

    /** Package-visible pure branch policy exercised by {@link AgentSideEffectsSelfTest}. */
    static IdleHandling classifyIdleHandling(
            String commandId,
            boolean hasActiveNonIdleTask,
            boolean enableLookAtOwnerIdle) {
        if (!"idle".equals(commandId)) {
            return IdleHandling.NOT_IDLE;
        }
        if (hasActiveNonIdleTask) {
            return IdleHandling.IGNORE_ACTIVE_TASK;
        }
        return enableLookAtOwnerIdle
                ? IdleHandling.INSTALL_LOOK_AT_OWNER
                : IdleHandling.LEAVE_WITHOUT_USER_TASK;
    }

    /** Resolves one locally-handled idle command exactly once, including ignored-idle degradation. */
    static void completeHandledIdle(
            IdleHandling handling,
            String commandWithPrefix,
            Consumer<CommandExecutionStopReason> onStop) {
        if (handling == null || handling == IdleHandling.NOT_IDLE) {
            throw new IllegalArgumentException("idle completion requires a handled idle branch");
        }
        String note = handling == IdleHandling.IGNORE_ACTIVE_TASK
                ? IGNORED_IDLE_MODEL_NOTE : null;
        java.util.Objects.requireNonNull(onStop, "onStop")
                .accept(new CommandExecutionStopReason.Finished(commandWithPrefix, note));
    }

    private static void reportIgnoredIdleToPlayer(PlayerEngineController mod) {
        MinecraftServer server = mod.getWorld() == null ? null : mod.getWorld().getServer();
        if (server != null && mod.getOwner() instanceof ServerPlayer owner) {
            broadcastChatToPlayer(server,
                    Component.translatable("message.playerengine.agent.idle_ignored_active_task"), owner);
        }
    }

    private static void rejectMixedIdleCommand(
            PlayerEngineController mod,
            Consumer<CommandExecutionStopReason> onStop) {
        MinecraftServer server = mod.getWorld() == null ? null : mod.getWorld().getServer();
        if (server != null && mod.getOwner() instanceof ServerPlayer owner) {
            broadcastChatToPlayer(server,
                    Component.translatable("message.playerengine.agent.mixed_idle_commands"), owner);
        }
        completeRejectedMixedIdle(onStop);
    }

    /** Package-visible exact terminal exercised without a live server by the self-test. */
    static void completeRejectedMixedIdle(Consumer<CommandExecutionStopReason> onStop) {
        java.util.Objects.requireNonNull(onStop, "onStop").accept(
                new CommandExecutionStopReason.Error("idle", MIXED_IDLE_MODEL_ERROR));
    }

    /**
     * Builds the short, plain, player-facing retraction for an unknown emitted command name. The
     * model already receives the full enriched executor message ("Command drop does not exist. Did
     * you mean \"give\"?") via the onCommandFinish InfoMessage path; the player gets an
     * audience-tailored line (DESIGN.md §3) rather than that model-oriented string. When the enriched
     * message carries a "Did you mean \"x\"?" suggestion, the player line names it ("trying 'x'");
     * otherwise it degrades to a generic honest retraction. Best-effort: any parse failure falls back
     * to the generic line, never throws.
     */
    private static Component playerCorrectionFor(String commandWithPrefix, CommandExecutor cmdExecutor,
                                              String enrichedMessage) {
        String rawName = rawCommandName(commandWithPrefix, cmdExecutor);
        String suggestion = extractSuggestion(enrichedMessage);
        if (rawName != null && suggestion != null) {
            return Component.translatable("message.playerengine.agent.correction_with_suggestion", rawName, suggestion);
        }
        if (rawName != null) {
            return Component.translatable("message.playerengine.agent.correction_no_suggestion", rawName);
        }
        return Component.translatable("message.playerengine.agent.correction_fallback");
    }

    /** Raw first command name as the model emitted it (no alias resolution — the player hears the
     * word they actually triggered). Null on any parse failure. */
    private static String rawCommandName(String commandWithPrefix, CommandExecutor cmdExecutor) {
        if (commandWithPrefix == null || commandWithPrefix.isBlank()) {
            return null;
        }
        try {
            String line = commandWithPrefix;
            if (cmdExecutor.isClientCommand(line)) {
                line = line.substring(cmdExecutor.getCommandPrefix().length());
            }
            String first = line.split(";")[0].trim();
            if (first.isEmpty()) {
                return null;
            }
            int sp = first.indexOf(' ');
            return (sp == -1 ? first : first.substring(0, sp)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts the suggested command from the enriched executor message of the form
     * {@code ... Did you mean "give"?}. Returns null when no suggestion is present. */
    private static String extractSuggestion(String enrichedMessage) {
        if (enrichedMessage == null) {
            return null;
        }
        int idx = enrichedMessage.indexOf("Did you mean \"");
        if (idx < 0) {
            return null;
        }
        int start = idx + "Did you mean \"".length();
        int end = enrichedMessage.indexOf('"', start);
        if (end <= start) {
            return null;
        }
        return enrichedMessage.substring(start, end);
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
