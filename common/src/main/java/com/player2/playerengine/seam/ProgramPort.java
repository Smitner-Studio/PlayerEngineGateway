package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.program.ActionPort;
import com.player2.playerengine.program.CallLog;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The program interpreter's door to the world (§3): every {@code api.*} call a job makes goes through
 * {@link Seam#call}, so a program is coerced, admitted, run and checked exactly as the seam runs any
 * primitive, under the job's region (§4.2).
 *
 * <p>Arguments and values cross in the interpreter's plain form ({@code {x,y,z}}, {@code {min,max}},
 * ids, counts); the seam's own types stay on this side. Outcomes are queued, not delivered inside
 * {@link #begin}, and the host hands them to the job on its next tick: a job never sees an outcome
 * while it is still dispatching the call. An aborted call's outcome is dropped, so the call stays
 * {@code started} for reconcile (§6.4).
 */
public final class ProgramPort implements ActionPort {
    /** An outcome waiting for the job, by the call's seq. */
    public record Delivery(long seq, Outcome outcome) {
    }

    private final PlayerEngineController mod;
    private final Coercion.Ids ids;
    private final Queue<Delivery> ready = new ConcurrentLinkedQueue<>();
    private final Set<Long> aborted = ConcurrentHashMap.newKeySet();
    private volatile MotionBounds.Region region;

    public ProgramPort(PlayerEngineController mod) {
        this(mod, Coercion.Ids.REGISTRIES);
    }

    ProgramPort(PlayerEngineController mod, Coercion.Ids ids) {
        this.mod = mod;
        this.ids = ids;
    }

    /** The region the active job's motion and area calls are bound to; null outside a job. */
    public void region(MotionBounds.Region region) {
        this.region = region;
    }

    public MotionBounds.Region region() {
        return region;
    }

    /** The next outcome for the job, or null. */
    public Delivery poll() {
        Delivery d;
        while ((d = ready.poll()) != null) {
            if (!aborted.remove(d.seq())) {
                return d;
            }
        }
        return null;
    }

    private Seam seam() {
        return mod.getCommandExecutor().seam();
    }

    @Override
    public Prepared prepare(String name, Map<String, Object> args) {
        Signature sig = SignatureTable.get(name);
        if (sig == null) {
            return new Prepared(args, List.of(), ActionError.of(FailureCode.BAD_ARGS, "there is no api." + name));
        }
        Coercion.Result r = Coercion.coerce(sig, args, ids);
        if (!r.ok()) {
            return new Prepared(args, r.notes(), r.error());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> plainArgs = (Map<String, Object>) plain(r.args());
        return new Prepared(plainArgs, r.notes(), null);
    }

    @Override
    public Map<String, Object> snapshot(String name, Map<String, Object> args) {
        Primitive p = Seam.primitive(name);
        Map<String, Object> coerced = p == null ? null : coerce(p, args);
        if (coerced == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = (Map<String, Object>) plain(p.snapshot(coerced, seam().world()));
            return snap;
        } catch (Coercion.Failure | RuntimeException e) {
            return Map.of();
        }
    }

    @Override
    public void begin(long seq, String name, Map<String, Object> args) {
        if (Seam.primitive(name) != null) {
            // A call is an order: it supersedes whatever the companion was doing, and it lifts an
            // earlier stop, whose flag would otherwise end this call as superseded too.
            mod.isStopping = false;
            mod.bumpCommandDispatchSeq();
        }
        seam().call(name, args, region, outcome -> ready.add(new Delivery(seq, toProgram(outcome))));
    }

    @Override
    public void abort(long seq) {
        aborted.add(seq);
        mod.bumpCommandDispatchSeq();
        mod.cancelUserTask();
    }

    @Override
    public boolean stillShows(CallLog.Entry entry) {
        Primitive p = Seam.primitive(entry.name());
        Map<String, Object> coerced = p == null ? null : coerce(p, entry.args());
        if (coerced == null || entry.post() == null) {
            return true;
        }
        try {
            return sameValue(plain(p.snapshot(coerced, seam().world())), entry.post());
        } catch (Coercion.Failure | RuntimeException e) {
            return false;
        }
    }

    @Override
    public Reconcile reconcile(CallLog.Entry entry) {
        Primitive p = Seam.primitive(entry.name());
        Map<String, Object> coerced = p == null ? null : coerce(p, entry.args());
        if (coerced == null) {
            return Reconcile.ASK;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pre = (Map<String, Object>) host(entry.pre() == null ? Map.of() : entry.pre());
        try {
            return switch (p.reconcile(coerced, pre, seam().world())) {
                case DONE -> Reconcile.DONE;
                case RERUN -> Reconcile.RERUN;
                case ASK -> Reconcile.ASK;
            };
        } catch (RuntimeException e) {
            return Reconcile.ASK;
        }
    }

    @Override
    public int cellsChanged(CallLog.Entry entry, Outcome outcome) {
        return switch (entry.name()) {
            case "excavate", "fill" -> {
                try {
                    AreaSpec.Box b = Coercion.box(entry.args().get("box"));
                    yield (b.maxX() - b.minX() + 1) * (b.maxY() - b.minY() + 1) * (b.maxZ() - b.minZ() + 1);
                } catch (Coercion.Failure e) {
                    yield 0;
                }
            }
            case "place" -> 1;
            default -> 0;
        };
    }

    private Map<String, Object> coerce(Primitive p, Map<String, Object> args) {
        Coercion.Result r = Coercion.coerce(p.signature(), args == null ? Map.of() : args, ids);
        return r.ok() ? r.args() : null;
    }

    // --- values across the door ----------------------------------------------------------------

    private static Outcome toProgram(Outcome o) {
        return o.ok() ? Outcome.ok(plain(o.value()), o.coercions()) : o;
    }

    /** The interpreter's plain form of a seam value. */
    static Object plain(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Number) {
            return v;
        }
        if (v instanceof AreaSpec.Pos p) {
            return pos(p);
        }
        if (v instanceof AreaSpec.Box b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("min", pos(new AreaSpec.Pos(b.minX(), b.minY(), b.minZ())));
            m.put("max", pos(new AreaSpec.Pos(b.maxX(), b.maxY(), b.maxZ())));
            return m;
        }
        if (v instanceof ContainerHandle h) {
            return pos(h.pos());
        }
        if (v instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            it.forEach(x -> out.add(plain(x)));
            return out;
        }
        return v.toString();
    }

    private static Map<String, Object> pos(AreaSpec.Pos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.x());
        m.put("y", p.y());
        m.put("z", p.z());
        return m;
    }

    /** A saved snapshot back in the primitives' form: a whole number read from JSON is an int again. */
    static Object host(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            long l = d.longValue();
            return l == (int) l ? (Object) (int) l : (Object) l;
        }
        if (v instanceof Long l && l == l.intValue()) {
            return l.intValue();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), host(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(host(x)));
            return out;
        }
        return v;
    }

    /** Equal as JSON: numbers compare by value, whatever their boxed type after a restart. */
    static boolean sameValue(Object a, Object b) {
        return Objects.equals(host(a), host(b));
    }
}
