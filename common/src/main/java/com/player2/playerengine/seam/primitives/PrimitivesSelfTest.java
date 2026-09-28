package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Outcome;
import com.player2.playerengine.seam.Primitive;
import com.player2.playerengine.seam.Seam;
import com.player2.playerengine.seam.SeamTestWorld;
import com.player2.playerengine.seam.Signature;
import com.player2.playerengine.seam.SignatureTable;
import com.player2.playerengine.seam.WorldReader;
import com.player2.playerengine.seam.YesNo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The stage-2B primitives (§5.3). For each one: the postcondition passes on the world the real
 * outcome leaves, and fails, with the code the model repairs against, on a world where the Task said
 * Finished and nothing happened. The red witness is the same faked Finished through the same primitive
 * with its postcondition removed: it counts as success, so the postcondition is what catches it.
 */
public final class PrimitivesSelfTest {
    private static int checks;

    private PrimitivesSelfTest() {
    }

    /**
     * One primitive's case.
     *
     * @param setup  the world before the call
     * @param real   what the Task does to the world when it works
     * @param faked  the code a Finished with nothing done must fail with
     */
    record Case(String label, String name, Map<String, Object> raw, Consumer<SeamTestWorld> setup,
            Consumer<SeamTestWorld> real, FailureCode faked) {
    }

    public static int runAll() {
        checks = 0;
        List<Case> cases = new ArrayList<>();
        cases.addAll(motion());
        cases.addAll(talk());
        for (Case c : cases) {
            postconditionCatchesAFakedFinished(c);
        }
        everyPrimitiveIsCovered(cases);
        waitUntilRefusesWhatItCannotRecheck();
        confirmAnswersOnlyYesOrNo();
        return checks;
    }

    // --- talk --------------------------------------------------------------------------------------

    private static List<Case> talk() {
        return List.of(
                new Case("say", "say", m("text", "on my way"),
                        w -> w.spoken = new Seam.Spoken(List.of("hi"), 3),
                        w -> w.spoken = new Seam.Spoken(List.of("hi", "on my way"), 4), FailureCode.SUPERSEDED),
                new Case("confirm", "confirm", m("question", "dig here?"),
                        w -> w.confirmation = new Seam.Confirmation(1, "dig here?", null, null),
                        w -> w.confirmation = new Seam.Confirmation(1, "dig here?", Boolean.FALSE, "no"),
                        FailureCode.SUPERSEDED));
    }

    private static void confirmAnswersOnlyYesOrNo() {
        require(YesNo.parse("Yes!") == Boolean.TRUE && YesNo.parse(" go ahead ") == Boolean.TRUE
                        && YesNo.parse("nope.") == Boolean.FALSE && YesNo.parse("don't") == Boolean.FALSE,
                "yes and no phrases parse");
        require(YesNo.parse("yes but only the top") == null && YesNo.parse("maybe") == null && YesNo.parse("") == null,
                "anything else is not guessed");
        Seam seam = new Seam(null);
        require(!seam.reply("yes"), "a reply with no open question is not consumed");
        seam.ask("dig here?");
        require(seam.awaitingReply() && seam.reply("Yes") && seam.confirmation().answer() == Boolean.TRUE,
                "a yes answers the open question and is consumed");
        require(!seam.reply("no"), "a second reply does not re-answer it");
        seam.ask("fill it?");
        require(!seam.reply("only half of it") && seam.confirmation().answered()
                        && seam.confirmation().answer() == null && !seam.awaitingReply(),
                "another reply closes the question unanswered and goes on to the model");
        SeamTestWorld w = new SeamTestWorld();
        w.confirmation = seam.confirmation();
        Outcome other = Seam.verify(Seam.primitive("confirm"), coerce("confirm", m("question", "fill it?")), Map.of(),
                Primitive.TaskEnd.finished(null), w, List.of());
        require(!other.ok(), "confirm does not pass on a reply that was not yes or no: " + other);
        Seam.Confirmation asked = seam.ask("stay?");
        seam.closeConfirmation(asked.id());
        require(seam.confirmation() == null && !seam.awaitingReply(), "a stopped confirm drops its question");
    }

    // --- motion ------------------------------------------------------------------------------------

    private static final WorldReader AIR = new WorldReader() {
        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    };

    private static List<Case> motion() {
        Predicate<Object> fiveTorches = v -> ((Integer) v) >= 5;
        return List.of(
                new Case("follow_owner", "follow_owner", m("until_near_blocks", 3, "timeout_s", 30),
                        w -> w.owner = new Vec3(20, 64, 0),
                        w -> w.position = new Vec3(18, 64, 0), FailureCode.TIMEOUT),
                new Case("follow_owner, owner gone", "follow_owner", m("until_near_blocks", 3, "timeout_s", 30),
                        w -> w.owner = null,
                        w -> w.owner = new Vec3(1, 0, 0), FailureCode.NOT_FOUND),
                new Case("wait", "wait", m("ticks", 40),
                        w -> w.gameTime = 1000,
                        w -> w.gameTime = 1040, FailureCode.SUPERSEDED),
                new Case("wait_until", "wait_until",
                        m("query_name", "count", "args", m("item", "torch"), "predicate", fiveTorches, "timeout_s", 10),
                        w -> w.reader = AIR,
                        w -> w.carry("torch", 5), FailureCode.TIMEOUT),
                new Case("wait_until, positional args", "wait_until",
                        m("query_name", "count", "args", List.of("Torches"), "predicate", fiveTorches, "timeout_s", 10),
                        w -> w.reader = AIR,
                        w -> w.carry("torch", 7), FailureCode.TIMEOUT));
    }

    private static void waitUntilRefusesWhatItCannotRecheck() {
        Primitive p = Seam.primitive("wait_until");
        Map<String, Object> big = coerce("wait_until", m("query_name", "find_blocks",
                "args", m("block", "oak_log", "radius", 16, "max", 4), "predicate", (Predicate<Object>) v -> true,
                "timeout_s", 10));
        ActionError refused = p.admit(big, null);
        require(refused != null && refused.code() == FailureCode.BUDGET,
                "wait_until on a radius-16 find_blocks is refused up front: " + refused);
        Map<String, Object> noPredicate = coerce("wait_until", m("query_name", "count", "args", m("item", "torch"),
                "predicate", "x > 3", "timeout_s", 10));
        ActionError text = p.admit(noPredicate, null);
        require(text != null && text.code() == FailureCode.BAD_ARGS, "a predicate must be a function: " + text);
    }

    // --- the shared check --------------------------------------------------------------------------

    private static void postconditionCatchesAFakedFinished(Case c) {
        Primitive p = Seam.primitive(c.name());
        require(p != null, c.label() + " is dispatched");
        Map<String, Object> args = coerce(c.name(), c.raw());

        SeamTestWorld done = new SeamTestWorld();
        c.setup().accept(done);
        Map<String, Object> pre = snapshot(p, args, done);
        c.real().accept(done);
        Outcome real = Seam.verify(p, args, pre, Primitive.TaskEnd.finished(null), done, List.of());
        require(real.ok(), c.label() + ": the postcondition passes on the real outcome: " + real);
        require(p.reconcile(args, pre, done) == Primitive.Reconcile.DONE,
                c.label() + ": an interrupted call whose effect shows is done");

        SeamTestWorld idle = new SeamTestWorld();
        c.setup().accept(idle);
        Map<String, Object> idlePre = snapshot(p, args, idle);
        Outcome faked = Seam.verify(p, args, idlePre, Primitive.TaskEnd.finished(null), idle, List.of());
        require(!faked.ok() && faked.error().code() == c.faked(), c.label() + ": a faked Finished fails with "
                + c.faked().wire() + ": " + faked);
        Primitive.Reconcile r = p.reconcile(args, idlePre, idle);
        require(r == (p.idempotent() ? Primitive.Reconcile.RERUN : Primitive.Reconcile.ASK),
                c.label() + ": an interrupted call with no effect " + (p.idempotent() ? "re-runs" : "asks") + ": " + r);

        // Red witness: the same faked Finished with the postcondition removed is a success.
        Outcome unchecked = Seam.verify(withoutPostcondition(p), args, idlePre, Primitive.TaskEnd.finished(null), idle,
                List.of());
        require(unchecked.ok(), c.label() + ": without its postcondition a faked Finished passes (red witness)");
    }

    private static void everyPrimitiveIsCovered(List<Case> cases) {
        for (Signature s : SignatureTable.all()) {
            if (s.bound() && s.kind() == Signature.Kind.PRIMITIVE && !List.of("goto", "excavate").contains(s.name())) {
                require(cases.stream().anyMatch(c -> c.name().equals(s.name())),
                        s.name() + " has a postcondition case here");
            }
        }
    }

    static Map<String, Object> snapshot(Primitive p, Map<String, Object> args, SeamTestWorld w) {
        try {
            return p.snapshot(args, w);
        } catch (Coercion.Failure f) {
            throw new IllegalStateException("snapshot failed: " + f.error);
        }
    }

    static Map<String, Object> coerce(String name, Map<String, Object> raw) {
        Coercion.Result r = Coercion.coerce(SignatureTable.get(name), raw, Coercion.Ids.REGISTRIES);
        if (!r.ok()) {
            throw new IllegalStateException(name + " " + raw + " does not coerce: " + r.error());
        }
        return r.args();
    }

    static Primitive withoutPostcondition(Primitive p) {
        return new Primitive() {
            @Override
            public Signature signature() {
                return p.signature();
            }

            @Override
            public LineArgs fromLine(String argsText, Context ctx) {
                return p.fromLine(argsText, ctx);
            }

            @Override
            public ActionError admit(Map<String, Object> args, Context ctx) {
                return p.admit(args, ctx);
            }

            @Override
            public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre,
                    Context ctx, Consumer<TaskEnd> ended) {
                p.start(args, options, pre, ctx, ended);
            }

            @Override
            public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
                return null;
            }
        };
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("primitives self-test failed: " + message);
        }
    }
}
