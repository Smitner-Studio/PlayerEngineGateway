package com.player2.playerengine.player2api;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for the model-bypassing stop lane: exactly {@code stop <name>} or
 * {@code <name> stop}, where the name is a bare companion name ({@code Ada}) or a unique one
 * ({@code Arran's Ada}). Anything more ({@code Ada stop planting}) is ordinary chat. Which companion
 * the name reaches is {@link CompanionAddress}'s decision.
 */
public final class StopIntent {
    private static final Pattern UNIQUE = Pattern.compile("^(?<owner>[\\p{L}\\p{N}_-]{1,32})'s (?<bot>.+)$");

    private StopIntent() {
    }

    /** The name a stop line names. {@code owner} is null for a bare name. */
    public record Named(String owner, String bot) {
    }

    /** The stop line's name, or null when the line is not a stop line. */
    public static Named parse(String rawMessage) {
        String message = normalize(rawMessage);
        String name = null;
        if (message.startsWith("stop ")) {
            name = message.substring("stop ".length()).trim();
        } else if (message.endsWith(" stop")) {
            name = message.substring(0, message.length() - " stop".length()).trim();
        }
        if (name == null || name.isEmpty() || name.equals("stop")) {
            return null;
        }
        Matcher m = UNIQUE.matcher(name);
        if (m.matches()) {
            return new Named(m.group("owner"), m.group("bot").trim());
        }
        return new Named(null, name);
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[^\\p{L}\\p{N}_'-]+", " ")
                .replaceAll("(^| )'+|'+( |$)", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
