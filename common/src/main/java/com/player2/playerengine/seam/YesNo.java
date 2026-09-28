package com.player2.playerengine.seam;

import java.util.Locale;
import java.util.Set;

/**
 * The deterministic answer to a {@code confirm} (§5.3): a whole reply that is a yes or a no phrase.
 * Anything longer or different is not guessed at; it goes to the model.
 */
public final class YesNo {
    private static final Set<String> YES = Set.of("yes", "y", "yeah", "yea", "yep", "yup", "sure", "ok", "okay",
            "go", "go ahead", "do it", "please do", "yes please", "confirm", "confirmed", "affirmative", "sounds good");
    private static final Set<String> NO = Set.of("no", "n", "nope", "nah", "no thanks", "dont", "don't", "do not",
            "cancel", "negative", "never mind", "nevermind", "not now");

    private YesNo() {
    }

    /** True for a yes phrase, false for a no phrase, null for anything else. */
    public static Boolean parse(String reply) {
        if (reply == null) {
            return null;
        }
        String s = reply.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?,]+$", "").replaceAll("\\s+", " ");
        if (YES.contains(s)) {
            return Boolean.TRUE;
        }
        return NO.contains(s) ? Boolean.FALSE : null;
    }
}
