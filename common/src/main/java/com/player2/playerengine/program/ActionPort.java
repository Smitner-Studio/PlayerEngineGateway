package com.player2.playerengine.program;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Outcome;
import java.util.List;
import java.util.Map;

/**
 * The interpreter's one door to the world. The seam adapter implements it over {@code Seam},
 * {@code Primitive} and the query queue; this package sees only argument maps, {@link Outcome} and
 * {@link ActionError}, so it stays free of Minecraft types. Tests implement it over a stub world.
 *
 * <p>Arguments arrive named by the signature table, with whole numbers as {@code Integer} or
 * {@code Long}, a Pos as {@code {x,y,z}} and a Box as {@code {min,max}}.
 */
public interface ActionPort {
    /** Coerced arguments (§5.4) and the notes that say what was changed, or why they do not coerce. */
    record Prepared(Map<String, Object> args, List<String> coercions, ActionError error) {
        public static Prepared as(Map<String, Object> args) {
            return new Prepared(args, List.of(), null);
        }
    }

    /** What a call interrupted by a restart did (§6.4); the seam's {@code Primitive.Reconcile}. */
    enum Reconcile {
        /** The world shows the effect: count it as done, do not run it again. */
        DONE,
        /** The world does not show it: run it again. */
        RERUN,
        /** The world cannot tell: ask the initiator. */
        ASK
    }

    /** Coercion before validation. The default takes the arguments as they are. */
    default Prepared prepare(String name, Map<String, Object> args) {
        return Prepared.as(args);
    }

    /**
     * The fields the call's postcondition reads, before it runs (§6.4 pre-snapshot) and again after
     * it verified. JSON-able; an empty map for a call with no postcondition.
     */
    Map<String, Object> snapshot(String name, Map<String, Object> args);

    /**
     * Starts the call. Its {@link Outcome} comes back through {@link Job#deliver}, on this tick or a
     * later one; an ok outcome means the postcondition held (§6.5).
     */
    void begin(long seq, String name, Map<String, Object> args);

    /** Stops a call that has begun and not delivered (pause, cancel, per-call time). */
    default void abort(long seq) {
    }

    /**
     * After a restart: whether the world still shows a {@code done} call's effect, in the fields its
     * postcondition reads (its post snapshot). False sends the call to {@link #reconcile}; a false
     * alarm costs a check, never a second effect.
     */
    boolean stillShows(CallLog.Entry entry);

    /** For a non-idempotent call: done, run again, or ask, by the world against the pre-snapshot. */
    Reconcile reconcile(CallLog.Entry entry);

    /** Block cells a verified call changed, charged to the per-job cap. */
    default int cellsChanged(CallLog.Entry entry, Outcome outcome) {
        return 0;
    }

    /** The per-call time in ticks: the seam's estimate (AreaScan's for area calls), else 25 minutes. */
    default int callTicks(String name, Map<String, Object> args) {
        return Caps.CALL_TICKS;
    }
}
