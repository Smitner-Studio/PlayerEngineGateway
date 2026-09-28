package com.player2.playerengine.player2api;

import net.minecraft.network.chat.Component;

/**
 * A model turn can end while its owner is offline: billing then reports an error with no player to
 * tell, because ConversationManager resolves the owner to null. The chat helpers must log and skip,
 * never throw on the server tick.
 */
public final class OfflineOwnerChatSelfTest {
    private static int checks;

    private OfflineOwnerChatSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        errorWithNoOnlineOwnerDoesNotThrow();
        chatToAnAbsentPlayerDoesNotThrow();
        return checks;
    }

    private static void errorWithNoOnlineOwnerDoesNotThrow() {
        runsWithoutThrowing(() -> AgentSideEffects.onError(null,
                        "Player2: no billing player/token available for this API request.", null),
                "an error with no online owner is logged, not thrown");
    }

    private static void chatToAnAbsentPlayerDoesNotThrow() {
        runsWithoutThrowing(() -> AgentSideEffects.broadcastChatToPlayer(null, "hello", null),
                "a text line to an absent player is skipped");
        runsWithoutThrowing(() -> AgentSideEffects.broadcastChatToPlayer(null, Component.literal("hello"), null),
                "a component line to an absent player is skipped");
    }

    private static void runsWithoutThrowing(Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (RuntimeException e) {
            throw new AssertionError(message + ": threw " + e, e);
        }
    }
}
