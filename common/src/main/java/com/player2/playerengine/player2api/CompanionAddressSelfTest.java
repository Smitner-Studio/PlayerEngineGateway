package com.player2.playerengine.player2api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Names and reach (operator rulings of 2026-09-28): unique names reach one companion across the
 * dimension; a bare name reaches the speaker's own companion, or the only one of that name within
 * 64 blocks, and never two; a stop line follows the same rules.
 */
public final class CompanionAddressSelfTest {
    private static int checks;

    private CompanionAddressSelfTest() {
    }

    private static final UUID ARRAN = uuid("arran");
    private static final UUID BOB = uuid("bob");
    private static final UUID CARA = uuid("cara");

    public static int runAll() {
        checks = 0;
        uniqueNameReachesAcrossTheDimension();
        ownersBareNameReachesTheirOwn();
        othersBareNameNeedsOneNearby();
        mentionsNeverFanOut();
        stopLinesParseStrictly();
        stopByABareNameStopsAtMostOne();
        return checks;
    }

    private static UUID uuid(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static CompanionAddress.Candidate<String> ada(String id, UUID owner, String ownerName, boolean sameDim,
            double distance) {
        return new CompanionAddress.Candidate<>(id, owner, ownerName, "Ada", Set.of("foreman ada", "ada"), sameDim,
                distance);
    }

    private static void uniqueNameReachesAcrossTheDimension() {
        List<CompanionAddress.Candidate<String>> all = List.of(ada("arrans", ARRAN, "Arran", true, 900),
                ada("bobs", BOB, "Bob", false, Double.MAX_VALUE));
        require(reached(CompanionAddress.unique("Arran", "Ada", all)).equals("arrans"),
                "Arran's Ada reaches from 900 blocks, from anyone");
        require(CompanionAddress.unique("bob", "ada", all) instanceof CompanionAddress.Outcome.TooFar<String>,
                "Bob's Ada in another dimension is too far");
        require(CompanionAddress.unique("Cara", "Ada", all) instanceof CompanionAddress.Outcome.Nobody<String>,
                "a unique name nobody has reaches nobody");
        require(CompanionAddress.uniqueName("Arran", "Ada").equals("Arran's Ada"), "the unique name's form");
    }

    private static void ownersBareNameReachesTheirOwn() {
        List<CompanionAddress.Candidate<String>> all = List.of(ada("arrans", ARRAN, "Arran", true, 500),
                ada("bobs", BOB, "Bob", true, 3));
        require(reached(CompanionAddress.bare("ada", ARRAN, all)).equals("arrans"),
                "the owner's bare name reaches their own Ada at 500 blocks, not Bob's beside them");
    }

    private static void othersBareNameNeedsOneNearby() {
        List<CompanionAddress.Candidate<String>> oneNear = List.of(ada("arrans", ARRAN, "Arran", true, 20),
                ada("bobs", BOB, "Bob", true, 300));
        require(reached(CompanionAddress.bare("ada", CARA, oneNear)).equals("arrans"),
                "a stranger's bare name reaches the only Ada within 64 blocks");
        List<CompanionAddress.Candidate<String>> twoNear = List.of(ada("arrans", ARRAN, "Arran", true, 20),
                ada("bobs", BOB, "Bob", true, 40));
        CompanionAddress.Outcome<String> o = CompanionAddress.bare("ada", CARA, twoNear);
        require(o instanceof CompanionAddress.Outcome.Choose<String> c
                        && c.uniqueNames().equals(List.of("Arran's Ada", "Bob's Ada")),
                "two Adas nearby: the stranger is given both unique names: " + o);
        List<CompanionAddress.Candidate<String>> noneNear = List.of(ada("arrans", ARRAN, "Arran", true, 100));
        require(CompanionAddress.bare("ada", CARA, noneNear) instanceof CompanionAddress.Outcome.Choose<String>,
                "one Ada, but beyond 64 blocks: the stranger is told its unique name");
    }

    private static void mentionsNeverFanOut() {
        List<CompanionAddress.Candidate<String>> all = List.of(ada("arrans", ARRAN, "Arran", true, 20),
                ada("bobs", BOB, "Bob", true, 40));
        CallByNameMentionRouter.Resolved<String> bare = CallByNameMentionRouter.resolveTargets("Ada, dig here", CARA, all);
        require(bare.targets().isEmpty() && bare.notices().size() == 1, "a stranger's bare Ada with two nearby reaches none: "
                + bare.targets());
        CallByNameMentionRouter.Resolved<String> unique = CallByNameMentionRouter.resolveTargets(
                "Bob's Ada, dig here", CARA, all);
        require(unique.targets().equals(Set.of("bobs")),
                "Bob's Ada reaches Bob's only, though the line also contains the bare name: " + unique.targets());
        CallByNameMentionRouter.Resolved<String> own = CallByNameMentionRouter.resolveTargets("Ada, come here", ARRAN, all);
        require(own.targets().equals(Set.of("arrans")), "the owner's bare name reaches their own only");
        CallByNameMentionRouter.Resolved<String> far = CallByNameMentionRouter.resolveTargets(
                "Arran's Ada, come", CARA, List.of(ada("arrans", ARRAN, "Arran", false, Double.MAX_VALUE)));
        require(far.targets().isEmpty() && far.notices().get(0) instanceof CompanionAddress.Outcome.TooFar<String>,
                "a unique name in another dimension reaches none and tells the speaker");
        CallByNameMentionRouter.Resolved<String> unnamed = CallByNameMentionRouter.resolveTargets("dig here", CARA, all);
        require(unnamed.targets().isEmpty() && unnamed.notices().isEmpty(), "an unnamed line reaches nobody");
    }

    private static void stopLinesParseStrictly() {
        require(StopIntent.parse("stop ellie").equals(new StopIntent.Named(null, "ellie")), "trailing name");
        require(StopIntent.parse("Ellie, stop").equals(new StopIntent.Named(null, "ellie")), "leading name");
        require(StopIntent.parse("@Ellie: stop").equals(new StopIntent.Named(null, "ellie")), "explicit address");
        require(StopIntent.parse("stop Arran's Ada").equals(new StopIntent.Named("arran", "ada")), "unique name");
        require(StopIntent.parse("Arran’s Ada, stop!").equals(new StopIntent.Named("arran", "ada")),
                "typographic apostrophe");
        require(StopIntent.parse("Ellie stop planting") == null, "extra action text is chat");
        require(StopIntent.parse("stop") == null, "an unnamed stop is chat");
    }

    private static void stopByABareNameStopsAtMostOne() {
        List<CompanionAddress.Candidate<String>> twoNear = List.of(ada("arrans", ARRAN, "Arran", true, 10),
                ada("bobs", BOB, "Bob", true, 12));
        StopIntent.Named n = StopIntent.parse("stop Ada");
        CompanionAddress.Outcome<String> o = CompanionAddress.bare(n.bot(), CARA, twoNear);
        require(o instanceof CompanionAddress.Outcome.Choose<String>,
                "a third player's bare stop with two Adas nearby stops none and asks which: " + o);
        require(reached(CompanionAddress.bare(n.bot(), BOB, twoNear)).equals("bobs"), "Bob's bare stop stops his own");
        StopIntent.Named u = StopIntent.parse("stop Arran's Ada");
        require(reached(CompanionAddress.unique(u.owner(), u.bot(), twoNear)).equals("arrans"),
                "a unique stop stops that one");
        StopIntent.Named negated = StopIntent.parse("Ada don't stop");
        require(negated == null || CompanionAddress.bare(negated.bot(), CARA, twoNear)
                instanceof CompanionAddress.Outcome.Nobody<String>, "a negated stop names nobody");
    }

    private static String reached(CompanionAddress.Outcome<String> o) {
        return o instanceof CompanionAddress.Outcome.Reach<String> r ? r.target().ref() : "(not reached: " + o + ")";
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("companion address self-test failed: " + message);
        }
    }
}
