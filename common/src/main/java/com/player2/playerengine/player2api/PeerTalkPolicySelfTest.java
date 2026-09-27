package com.player2.playerengine.player2api;

import java.util.List;

/** Companions answer each other only when named, and only up to the limit between human lines. */
public final class PeerTalkPolicySelfTest {
    private static int checks;

    private PeerTalkPolicySelfTest() {
    }

    public static int runAll() {
        checks = 0;
        addressedLinesWakeUnaddressedDoNot();
        theLimitResetsOnlyOnAHumanLine();
        theLoggedLoopStopsAfterOneExchange();
        return checks;
    }

    private static void addressedLinesWakeUnaddressedDoNot() {
        require(PeerTalkPolicy.wakes("Rivet, keep clearing.", "Rivet", "Rivet", 0, 1), "a line naming Rivet wakes Rivet");
        require(PeerTalkPolicy.wakes("foreman ada, the chest is full", "Foreman Ada", "Ada", 0, 1), "the full name, any case");
        require(PeerTalkPolicy.wakes("Need a hand, Ada?", "Foreman Ada", "Ada", 0, 1), "the short name");
        require(!PeerTalkPolicy.wakes("Bulk unload timed out; placing the sticks.", "Foreman Ada", "Ada", 0, 1),
                "a line naming nobody does not wake");
        require(!PeerTalkPolicy.wakes("Adamant about that seam.", "Foreman Ada", "Ada", 0, 1), "a name inside a word is not a mention");
        require(!PeerTalkPolicy.wakes("Rivet, hold.", "Rivet", "Rivet", 0, 0), "peerReplies=0 never wakes");
        require(!PeerTalkPolicy.wakes("", "Rivet", "Rivet", 0, 1), "a blank line never wakes");
    }

    private static void theLimitResetsOnlyOnAHumanLine() {
        require(PeerTalkPolicy.wakes("Rivet, go.", "Rivet", "Rivet", 0, 1), "first addressed line wakes");
        require(!PeerTalkPolicy.wakes("Rivet, go.", "Rivet", "Rivet", 1, 1), "second one before a human line does not");
        require(PeerTalkPolicy.wakes("Rivet, go.", "Rivet", "Rivet", 1, 2), "peerReplies=2 allows a second");
    }

    /**
     * The playtest loop, replayed: each companion's line names the other. Without the gate every
     * line woke the other side (red witness: 8 wakes); with peerReplies=1 each answers once.
     */
    private static void theLoggedLoopStopsAfterOneExchange() {
        List<String[]> lines = List.of(
                new String[]{"Rivet", "Bulk unload timed out; I'm placing the two sticks in the chest directly. Ada, clear yours."},
                new String[]{"Ada", "Deposit timed out. Checking chest proximity. Rivet, keep clearing."},
                new String[]{"Rivet", "Ada, the chest is right here. Placing the sticks."},
                new String[]{"Ada", "Chest's at -182 68 -10. Trying the deposit again. Rivet, hold."},
                new String[]{"Rivet", "Holding, Ada."},
                new String[]{"Ada", "Rivet, hold position."},
                new String[]{"Rivet", "Ada, holding."},
                new String[]{"Ada", "Rivet, good."});
        int adaAnswered = 0;
        int rivetAnswered = 0;
        int wakes = 0;
        for (String[] line : lines) {
            if (line[0].equals("Rivet") && PeerTalkPolicy.wakes(line[1], "Foreman Ada", "Ada", adaAnswered, 1)) {
                adaAnswered++;
                wakes++;
            } else if (line[0].equals("Ada") && PeerTalkPolicy.wakes(line[1], "Rivet", "Rivet", rivetAnswered, 1)) {
                rivetAnswered++;
                wakes++;
            }
        }
        require(wakes == 2, "one answer each, then silence until a human speaks; got " + wakes);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("peer talk self-test failed: " + message);
        }
    }
}
