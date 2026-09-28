package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Parses a chat line for companion mentions and resolves which companions it reaches, by the
 * rules of {@link CompanionAddress}: a unique name ({@code Arran's Ada}) or its owner's bare name
 * reaches that companion anywhere in the speaker's dimension; another player's bare name reaches a
 * companion only when it is the only one of that name within 64 blocks. A line with no resolvable
 * mention reaches nobody.
 *
 * Mention forms supported:
 * - Unqualified: Ellie   (anywhere; boundary-aware)
 * - Qualified:  Rick's Ellie   or  Rick’s Ellie
 * - Explicit:   @Ellie or @"Chat GPT"
 * - A leading spoken address that speech-to-text garbled, when it matches one companion within 64
 *   blocks
 */
public final class CallByNameMentionRouter {
    private CallByNameMentionRouter() {
    }

    /**
     * @param targets the companions the line reaches
     * @param cleaned the line with a leading address stripped, or null when it reaches nobody
     * @param notices the names that did not reach: too far, or not unambiguous
     */
    public record Resolved<T>(Set<T> targets, @Nullable String cleaned, List<CompanionAddress.Outcome<T>> notices) {
        static <T> Resolved<T> none() {
            return new Resolved<>(Set.of(), null, List.of());
        }
    }

    public static <T> Resolved<T> resolveTargets(String raw, UUID speaker, List<CompanionAddress.Candidate<T>> candidates) {
        if (raw == null || raw.isBlank() || candidates == null || candidates.isEmpty()) {
            return Resolved.none();
        }
        Map<String, List<CompanionAddress.Candidate<T>>> byBotKey = new HashMap<>();
        Set<String> ownerKeys = new LinkedHashSet<>();
        for (CompanionAddress.Candidate<T> c : candidates) {
            for (String k : c.names()) {
                byBotKey.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
            }
            String ownerKey = CompanionAddress.key(c.ownerName());
            if (!ownerKey.isEmpty()) {
                ownerKeys.add(ownerKey);
            }
        }

        CallByNameMentionParser.MentionParseResult parsed = CallByNameMentionParser.parse(raw, byBotKey.keySet(),
                ownerKeys);
        if (parsed.intents().isEmpty()) {
            Map<String, List<CompanionAddress.Candidate<T>>> near = new HashMap<>();
            byBotKey.forEach((k, list) -> {
                for (CompanionAddress.Candidate<T> c : list) {
                    if (c.sameDimension() && c.distance() <= CompanionAddress.NEAR) {
                        near.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
                    }
                }
            });
            Optional<FuzzyAddressMatch<T>> fuzzy = resolveUniqueFuzzyLeadingAddress(raw, near);
            if (fuzzy.isPresent()) {
                FuzzyAddressMatch<T> match = fuzzy.get();
                String cleaned = stripLeadingAddressing(raw, match.prefixLen()).orElse(match.canonicalKey());
                return new Resolved<>(Set.of(match.target().ref()), cleaned, List.of());
            }
            return Resolved.none();
        }

        Set<T> reached = new LinkedHashSet<>();
        List<CompanionAddress.Outcome<T>> notices = new ArrayList<>();
        Set<String> namedUniquely = new LinkedHashSet<>();
        for (CallByNameMentionParser.MentionIntent intent : parsed.intents()) {
            if (intent instanceof CallByNameMentionParser.MentionIntent.Qualified q) {
                CompanionAddress.Outcome<T> o = CompanionAddress.unique(q.owner(), q.bot(), candidates);
                if (!(o instanceof CompanionAddress.Outcome.Nobody<T>)) {
                    namedUniquely.add(CompanionAddress.key(q.bot()));
                    collect(o, reached, notices);
                }
            }
        }
        for (CallByNameMentionParser.MentionIntent intent : parsed.intents()) {
            if (intent instanceof CallByNameMentionParser.MentionIntent.Unqualified u
                    && !namedUniquely.contains(CompanionAddress.key(u.bot()))) {
                collect(CompanionAddress.bare(u.bot(), speaker, candidates), reached, notices);
            }
        }
        if (reached.isEmpty()) {
            return new Resolved<>(Set.of(), null, notices);
        }
        String cleaned = parsed.stripLeadingAddressing()
                .flatMap(prefixLen -> stripLeadingAddressing(raw, prefixLen))
                .orElse(raw);
        if (cleaned == null || cleaned.isBlank()) {
            return new Resolved<>(Set.of(), null, notices);
        }
        return new Resolved<>(reached, cleaned, notices);
    }

    private static <T> void collect(CompanionAddress.Outcome<T> o, Set<T> reached, List<CompanionAddress.Outcome<T>> notices) {
        if (o instanceof CompanionAddress.Outcome.Reach<T> r) {
            reached.add(r.target().ref());
        } else if (o instanceof CompanionAddress.Outcome.TooFar<T> || o instanceof CompanionAddress.Outcome.Choose<T>) {
            if (!notices.contains(o)) {
                notices.add(o);
            }
        }
    }

    private static Optional<String> stripLeadingAddressing(String raw, int prefixLen) {
        if (raw == null) {
            return Optional.empty();
        }
        if (prefixLen <= 0 || prefixLen > raw.length()) {
            return Optional.empty();
        }
        String rest = raw.substring(prefixLen).trim();
        while (!rest.isEmpty() && isHailSeparator(rest.charAt(0))) {
            rest = rest.substring(1).trim();
        }
        if (rest.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(rest);
    }

    private static boolean isHailSeparator(char c) {
        return c == ':' || c == ',' || c == ';' || java.lang.Character.isWhitespace(c);
    }

    private record FuzzyAddressMatch<T>(CompanionAddress.Candidate<T> target, String canonicalKey, int prefixLen) {
    }

    private record LeadingAddress(String text, int prefixLen) {
    }

    private static <T> Optional<FuzzyAddressMatch<T>> resolveUniqueFuzzyLeadingAddress(String raw,
            Map<String, List<CompanionAddress.Candidate<T>>> byBotKey) {
        Optional<LeadingAddress> leading = leadingAddress(raw);
        if (leading.isEmpty() || byBotKey == null || byBotKey.isEmpty()) {
            return Optional.empty();
        }
        Map<CompanionAddress.Candidate<T>, FuzzyAddressMatch<T>> matchesByBot = new IdentityHashMap<>();
        for (Map.Entry<String, List<CompanionAddress.Candidate<T>>> entry : byBotKey.entrySet()) {
            String key = entry.getKey();
            if (!looksLikeLeadingSttAddress(leading.get().text(), key)) {
                continue;
            }
            for (CompanionAddress.Candidate<T> c : entry.getValue()) {
                FuzzyAddressMatch<T> existing = matchesByBot.get(c);
                String canonical = existing == null
                        ? key
                        : shortestKey(existing.canonicalKey(), key);
                matchesByBot.put(c, new FuzzyAddressMatch<>(c, canonical, leading.get().prefixLen()));
            }
        }
        if (matchesByBot.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(matchesByBot.values().iterator().next());
    }

    private static String shortestKey(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return compactAlnum(b).length() < compactAlnum(a).length() ? b : a;
    }

    private static Optional<LeadingAddress> leadingAddress(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int leadingOffset = 0;
        while (leadingOffset < raw.length() && java.lang.Character.isWhitespace(raw.charAt(leadingOffset))) {
            leadingOffset++;
        }
        String trimmed = java.text.Normalizer.normalize(raw.substring(leadingOffset).trim(),
                java.text.Normalizer.Form.NFKC);
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        int punct = indexOfFirst(trimmed, '.', ':', ',', ';', '!', '?');
        String head = punct >= 0 ? trimmed.substring(0, punct).trim() : trimmed;
        int prefixLen = leadingOffset + (punct >= 0 ? punct + 1 : firstTokenLength(trimmed));
        if (head.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = head.split("\\s+");
        if (parts.length == 0) {
            return Optional.empty();
        }
        if (compactAlnum(parts[0]).length() != 1) {
            return Optional.of(new LeadingAddress(parts[0], prefixLen));
        }
        StringBuilder spelled = new StringBuilder();
        int count = 0;
        int spelledPrefixLen = 0;
        int cursor = 0;
        for (String part : parts) {
            String compact = compactAlnum(part);
            if (compact.length() != 1) {
                break;
            }
            int partStart = head.indexOf(part, cursor);
            if (partStart < 0) {
                break;
            }
            spelledPrefixLen = partStart + part.length();
            cursor = spelledPrefixLen;
            if (spelled.length() > 0) {
                spelled.append(' ');
            }
            spelled.append(part);
            count++;
            if (count >= 8) {
                break;
            }
        }
        int consumedPrefixLen = punct >= 0 ? prefixLen : leadingOffset + spelledPrefixLen;
        return count >= 4 ? Optional.of(new LeadingAddress(spelled.toString(), consumedPrefixLen)) : Optional.empty();
    }

    private static int firstTokenLength(String text) {
        int i = 0;
        while (i < text.length() && !java.lang.Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int indexOfFirst(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static boolean looksLikeLeadingSttAddress(String leading, String botKey) {
        String spoken = compactAlnum(leading);
        String target = compactAlnum(botKey);
        if (spoken.length() < 4 || target.length() < 4) {
            return false;
        }
        if (spoken.equals(target) || target.startsWith(spoken)) {
            return true;
        }
        if (spoken.length() >= 5 && target.endsWith(spoken) && target.length() - spoken.length() == 1) {
            return true;
        }
        return isSpelledLetterRun(leading) && isSubsequence(spoken, target);
    }

    private static boolean isSpelledLetterRun(String text) {
        if (text == null) {
            return false;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 4) {
            return false;
        }
        for (String part : parts) {
            if (compactAlnum(part).length() != 1) {
                return false;
            }
        }
        return true;
    }

    private static String compactAlnum(String text) {
        if (text == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (java.lang.Character.getType(c) == java.lang.Character.NON_SPACING_MARK) {
                continue;
            }
            if (java.lang.Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isSubsequence(String needle, String haystack) {
        int pos = 0;
        for (int i = 0; i < haystack.length() && pos < needle.length(); i++) {
            if (needle.charAt(pos) == haystack.charAt(i)) {
                pos++;
            }
        }
        return pos == needle.length();
    }
}
