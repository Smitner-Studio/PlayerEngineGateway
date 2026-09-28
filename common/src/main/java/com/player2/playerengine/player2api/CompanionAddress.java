package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which companion a name reaches (operator rulings of 2026-09-28: R8 reconciled with R1). Every
 * companion has a unique name, its owner's name and its own: {@code Arran's Ada}.
 * <ul>
 *   <li>A unique name reaches that companion from anyone, at any distance in the speaker's
 *       dimension. In another dimension the speaker is told it is too far.</li>
 *   <li>A bare name from the companion's owner reaches the owner's own companion the same way.</li>
 *   <li>A bare name from anyone else reaches a companion only when exactly one of that name is
 *       within {@link #NEAR} blocks in the speaker's dimension; otherwise the speaker is given the
 *       unique names to choose from.</li>
 * </ul>
 * A name never reaches two companions. Pure: the caller describes each companion as a
 * {@link Candidate}.
 */
public final class CompanionAddress {
    /** Unnamed chat and another player's bare name reach this far. */
    public static final double NEAR = 64.0;

    private CompanionAddress() {
    }

    /**
     * One companion as the speaker sees it.
     *
     * @param names         normalized keys it answers to (its full and its short name)
     * @param sameDimension whether it is in the speaker's dimension
     * @param distance      blocks from the speaker; meaningless in another dimension
     */
    public record Candidate<T>(T ref, UUID ownerUuid, String ownerName, String displayName, Set<String> names,
            boolean sameDimension, double distance) {
        public String uniqueName() {
            return CompanionAddress.uniqueName(ownerName, displayName);
        }
    }

    /**
     * {@code <owner>'s <name>}. Unique because Player2NPC keeps one companion per character per
     * owner, and it parses as a qualified mention ({@link CallByNameMentionParser}).
     */
    public static String uniqueName(String ownerName, String displayName) {
        return (ownerName == null || ownerName.isBlank() ? "nobody" : ownerName) + "'s " + displayName;
    }

    /** What a name resolves to. */
    public sealed interface Outcome<T> {
        record Reach<T>(Candidate<T> target) implements Outcome<T> {
        }

        /** The name is right, but the companion is in another dimension. */
        record TooFar<T>(Candidate<T> target) implements Outcome<T> {
        }

        /** Not unambiguous: the unique names that would each reach one. */
        record Choose<T>(List<String> uniqueNames) implements Outcome<T> {
        }

        record Nobody<T>() implements Outcome<T> {
        }
    }

    /** {@code <owner>'s <bot>}: that companion only. */
    public static <T> Outcome<T> unique(String ownerKey, String botKey, List<Candidate<T>> all) {
        String owner = key(ownerKey);
        String bot = key(botKey);
        List<Candidate<T>> matches = new ArrayList<>();
        for (Candidate<T> c : all) {
            if (key(c.ownerName()).equals(owner) && c.names().contains(bot)) {
                matches.add(c);
            }
        }
        return exactlyOne(matches);
    }

    /** A bare name from {@code speaker}. */
    public static <T> Outcome<T> bare(String botKey, UUID speaker, List<Candidate<T>> all) {
        String bot = key(botKey);
        List<Candidate<T>> named = new ArrayList<>();
        List<Candidate<T>> own = new ArrayList<>();
        List<Candidate<T>> near = new ArrayList<>();
        for (Candidate<T> c : all) {
            if (!c.names().contains(bot)) {
                continue;
            }
            named.add(c);
            if (speaker != null && speaker.equals(c.ownerUuid())) {
                own.add(c);
            }
            if (c.sameDimension() && c.distance() <= NEAR) {
                near.add(c);
            }
        }
        if (named.isEmpty()) {
            return new Outcome.Nobody<>();
        }
        if (!own.isEmpty()) {
            return exactlyOne(own);
        }
        if (near.size() == 1) {
            return new Outcome.Reach<>(near.get(0));
        }
        return choose(named);
    }

    private static <T> Outcome<T> exactlyOne(List<Candidate<T>> matches) {
        if (matches.isEmpty()) {
            return new Outcome.Nobody<>();
        }
        if (matches.size() > 1) {
            return choose(matches);
        }
        Candidate<T> c = matches.get(0);
        return c.sameDimension() ? new Outcome.Reach<>(c) : new Outcome.TooFar<>(c);
    }

    private static <T> Outcome<T> choose(List<Candidate<T>> matches) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Candidate<T> c : matches) {
            names.add(c.uniqueName());
        }
        return new Outcome.Choose<>(List.copyOf(names));
    }

    /** The normalized key a name is matched by; {@code ""} for none. */
    public static String key(String s) {
        String k = CallByNameMentionParser.normalizeKey(s);
        return k == null ? "" : k;
    }
}
