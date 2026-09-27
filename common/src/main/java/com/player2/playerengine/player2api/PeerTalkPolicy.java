package com.player2.playerengine.player2api;

/**
 * Whether a line another companion spoke wakes this companion's model. It wakes it only when it
 * names this companion, and only while this companion has answered fewer than {@code limit} such
 * lines since a human last spoke to it. A line that does not wake the model is still kept as
 * context for its next turn.
 *
 * <p>Without the gate two companions answered each other's every line ("Deposit timed out.
 * Checking chest proximity. Rivet, keep clearing." and back) until a rate limit stopped them.
 */
public final class PeerTalkPolicy {
    private PeerTalkPolicy() {
    }

    /**
     * @param line            what the other companion said
     * @param name            this companion's full name, e.g. "Foreman Ada"
     * @param shortName       the name it answers to, e.g. "Ada"
     * @param answeredSinceHuman peer lines this companion has answered since a human last spoke to it
     * @param limit           the operator's {@code peerReplies}; 0 means never
     */
    public static boolean wakes(String line, String name, String shortName, int answeredSinceHuman, int limit) {
        if (limit <= 0 || answeredSinceHuman >= limit || line == null || line.isBlank()) {
            return false;
        }
        return names(line, name) || names(line, shortName);
    }

    static boolean names(String line, String name) {
        String key = CallByNameMentionParser.normalizeKey(name);
        String text = CallByNameMentionParser.normalizeKey(line);
        return key != null && text != null && CallByNameMentionParser.containsNameWithBoundaries(text, key);
    }
}
