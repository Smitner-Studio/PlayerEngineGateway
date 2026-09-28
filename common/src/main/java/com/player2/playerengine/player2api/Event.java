package com.player2.playerengine.player2api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public sealed interface Event // tagged union basically of the below events
        permits Event.UserMessage, Event.CharacterMessage, Event.InfoMessage {
    String message();

    public String getConversationHistoryString();

    public record UserMessage(
            String message,
            String userName,
            boolean fromVoice,
            @Nullable UUID authenticatedUserUuid) implements Event {
        public UserMessage(String message, String userName) {
            this(message, userName, false, null);
        }

        public UserMessage(String message, String userName, boolean fromVoice) {
            this(message, userName, fromVoice, null);
        }

        public UserMessage withMessage(String replacement) {
            return new UserMessage(replacement, userName, fromVoice, authenticatedUserUuid);
        }

        public String getConversationHistoryString() {
            return String.format("User Message: [%s]: %s", userName, message);
        }

        public String toString() {
            if (fromVoice) {
                return String.format("UserMessage(userName='%s', message='%s', fromVoice=true)", userName, message);
            }
            return String.format("UserMessage(userName='%s', message='%s')", userName, message);
        }
    }

    public record InfoMessage(String message) implements Event {
        public String getConversationHistoryString() {
            return String.format("Info: %s", message);
        }

        public String toString() {
            return getConversationHistoryString();
        }
    }

    public record CharacterMessage(String message, AgentConversationData sendingCharacterData,
            @Nullable String originatingUserName)
            implements Event {
        public CharacterMessage(String message, AgentConversationData sendingCharacterData) {
            this(message, sendingCharacterData, null);
        }

        public String getConversationHistoryString() {
            return String.format("Other AI Message: [%s]: %s", sendingCharacterData.getName(), message);
        }

        public String toString() {
            return String.format("CharacterMessage(name='%s', message='%s', originatingUser=%s)",
                    sendingCharacterData.getName(), message, originatingUserName);
        }

    }
}
