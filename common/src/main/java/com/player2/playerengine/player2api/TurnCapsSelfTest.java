package com.player2.playerengine.player2api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 60 turns an hour per player across companions, 120 per companion, over a rolling hour. */
public final class TurnCapsSelfTest {
    private static int checks;

    private TurnCapsSelfTest() {
    }

    private static UUID uuid(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public static int runAll() {
        checks = 0;
        long[] now = {1_000_000L};
        TurnCaps caps = new TurnCaps(TurnCaps.PER_PLAYER_PER_HOUR, TurnCaps.PER_COMPANION_PER_HOUR, () -> now[0]);
        UUID bob = uuid("bob");
        UUID ada = uuid("ada");
        UUID rivet = uuid("rivet");
        for (int i = 0; i < 60; i++) {
            require(caps.tryCharge(bob, i % 2 == 0 ? ada : rivet), "turn " + (i + 1) + " from one player is allowed");
        }
        require(!caps.tryCharge(bob, ada), "turn 61 from one player makes no call, on either companion");
        require(!caps.tryCharge(bob, uuid("third")), "the player cap spans every companion");
        require(caps.tryCharge(uuid("cara"), ada), "another player is not held by Bob's cap");

        TurnCaps companion = new TurnCaps(TurnCaps.PER_PLAYER_PER_HOUR, TurnCaps.PER_COMPANION_PER_HOUR, () -> now[0]);
        for (int i = 0; i < 120; i++) {
            require(companion.tryCharge(uuid("player-" + (i % 3)), ada), "companion turn " + (i + 1));
        }
        require(!companion.tryCharge(uuid("fresh-player"), ada), "turn 121 on one companion makes no call");
        require(!companion.tryCharge(null, ada), "a peer turn counts against the companion too");
        require(companion.tryCharge(uuid("fresh-player"), rivet), "a refused turn charged the player nothing");

        now[0] += TurnCaps.HOUR_MS;
        require(caps.tryCharge(bob, ada), "an hour later the player may ask again");
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("turn caps self-test failed: " + message);
        }
    }
}
