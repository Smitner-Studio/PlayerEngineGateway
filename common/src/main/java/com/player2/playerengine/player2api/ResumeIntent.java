package com.player2.playerengine.player2api;

import java.util.Set;

/**
 * The model-free job lane (§6.6): a bare "continue" resumes the paused job, and "resume X" the
 * newest shelved job whose goal has X's words. Anything longer is chat for the model.
 */
final class ResumeIntent {
    private static final Set<String> CONTINUE = Set.of("continue", "keep going", "carry on", "go on", "resume",
            "continue please", "please continue", "go ahead and continue");
    private static final int MAX_WORDS = 8;

    private ResumeIntent() {
    }

    /** What a line asks. {@code what} is null for a bare continue. */
    record Asked(String what) {
    }

    /** The request, or null when the line is not one. */
    static Asked parse(String rawMessage) {
        String m = StopIntent.normalize(rawMessage);
        if (m.isEmpty() || m.split(" ").length > MAX_WORDS) {
            return null;
        }
        if (CONTINUE.contains(m)) {
            return new Asked(null);
        }
        for (String verb : new String[] {"resume ", "continue with ", "continue ", "go back to ", "back to "}) {
            if (m.startsWith(verb) && m.length() > verb.length()) {
                return new Asked(m.substring(verb.length()).trim());
            }
        }
        return null;
    }
}
