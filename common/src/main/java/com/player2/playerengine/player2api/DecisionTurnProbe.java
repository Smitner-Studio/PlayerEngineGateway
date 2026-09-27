package com.player2.playerengine.player2api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.player2api.gateway.GatewayCallContext;
import com.player2.playerengine.player2api.utils.HTTPUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Self-test access to the real decision turn, {@link Player2APIService#completeConversation}, with
 * only the budget guard and billing hop replaced: the request goes through the gateway as the given
 * character, exactly as a companion's call does.
 */
public final class DecisionTurnProbe {
    private static final String CHAT = "/v1/chat/completions";
    private static final long TIMEOUT_SECONDS = 20;

    private DecisionTurnProbe() {
    }

    private static final class RoutedService extends Player2APIService {
        private final String character;

        RoutedService(String character) {
            super(null, "self-test");
            this.character = character;
        }

        @Override
        String characterId() {
            return character;
        }

        @Override
        Map<String, JsonElement> sendChatCompletionRequest(JsonObject requestBody, AiTaskClass taskClass) throws Exception {
            return GatewayCallContext.call(character, "player-1",
                    () -> HTTPUtils.sendRequest("https://api.player2.game", CHAT, "POST", requestBody, new HashMap<>()));
        }
    }

    /** One decision turn, on the calling thread. {@code messages} starts with the system prompt. */
    public static JsonObject complete(String characterId, List<JsonObject> messages) throws Exception {
        return new RoutedService(characterId).completeConversation(history(messages), AiTaskClass.DECISION);
    }

    /**
     * One decision turn through {@link LLMCompleter}, as {@code AgentConversationData} submits it.
     *
     * @return the error string the conversation layer receives, or {@code null} when the reply parsed
     */
    public static String completerError(String characterId, List<JsonObject> messages) throws Exception {
        LLMCompleter completer = new LLMCompleter();
        try {
            CompletableFuture<String> outcome = new CompletableFuture<>();
            LLMCompleter.Submission submission = completer.processToJson(new RoutedService(characterId),
                    history(messages), reply -> outcome.complete(null), outcome::complete, true, AiTaskClass.DECISION);
            if (!submission.accepted()) {
                throw new IllegalStateException("an idle completer must accept the decision turn");
            }
            return outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            completer.shutdown();
        }
    }

    /** Whether the conversation layer keeps {@code errMsg} from the player (and retries instead). */
    public static boolean isSilentToPlayer(String errMsg) {
        return AgentConversationData.isModelParseFailure(errMsg);
    }

    private static ConversationHistory history(List<JsonObject> messages) {
        ConversationHistory history = new ConversationHistory(messages.get(0).get("content").getAsString());
        for (JsonObject message : messages.subList(1, messages.size())) {
            history.addHistory(message.deepCopy(), false, null);
        }
        return history;
    }
}
