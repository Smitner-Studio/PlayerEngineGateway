package com.player2.playerengine.seam;

import com.player2.playerengine.seam.Signature.Arg;
import com.player2.playerengine.seam.Signature.Type;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Tolerant arguments (§5.4, E6): the seam coerces before it validates, and says what it changed so
 * the model learns the canonical form. What it never does is guess: an ambiguous id lists its
 * candidates, and a missing required argument is an error, not a default.
 */
public final class Coercion {
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 2304;
    /** How many candidates an ambiguous id lists. */
    static final int MAX_CANDIDATES = 12;

    private Coercion() {
    }

    /** The id registries, as a seam so the table tests can run over a fixed list. */
    public interface Ids {
        boolean has(boolean block, String namespacedId);

        /** Every registered id of the kind, namespaced. */
        Iterable<String> all(boolean block);

        Ids REGISTRIES = new Ids() {
            @Override
            public boolean has(boolean block, String id) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                return rl != null && registry(block).containsKey(rl);
            }

            @Override
            public Iterable<String> all(boolean block) {
                List<String> ids = new ArrayList<>();
                for (ResourceLocation rl : registry(block).keySet()) {
                    ids.add(rl.toString());
                }
                return ids;
            }

            private Registry<?> registry(boolean block) {
                return block ? BuiltInRegistries.BLOCK : BuiltInRegistries.ITEM;
            }
        };
    }

    /** Raised inside coercion; the caller turns it into the call's {@link ActionError}. */
    public static final class Failure extends Exception {
        public final ActionError error;

        Failure(ActionError error) {
            super(error.toLine(), null, false, false);
            this.error = error;
        }

        static Failure of(FailureCode code, String message) {
            return new Failure(ActionError.of(code, message));
        }
    }

    /** Coerced arguments by name, and a note per change. */
    public record Result(Map<String, Object> args, List<String> notes, ActionError error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Coerces {@code raw} (by argument name) against {@code sig}. Values arrive as the command path
     * parses them (strings) or as a program holds them (numbers, lists, maps, {@link AreaSpec.Pos},
     * {@link AreaSpec.Box}); both land on the same canonical values.
     */
    public static Result coerce(Signature sig, Map<String, Object> raw, Ids ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (String k : raw.keySet()) {
            if (sig.args().stream().noneMatch(a -> a.name().equals(k))) {
                return new Result(Map.of(), notes, ActionError.of(FailureCode.BAD_ARGS,
                        sig.name() + " has no argument '" + k + "'; it takes " + argNames(sig)));
            }
        }
        for (Arg a : sig.args()) {
            Object v = raw.get(a.name());
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (a.required()) {
                    return new Result(Map.of(), notes, ActionError.of(FailureCode.BAD_ARGS,
                            sig.name() + " needs " + a.name() + " (" + a.type().reference + "); it takes " + argNames(sig))
                            .with("missing", a.name()));
                }
                continue;
            }
            try {
                out.put(a.name(), value(a, v, ids, notes));
            } catch (Failure f) {
                return new Result(Map.of(), notes, f.error.with("arg", a.name()));
            }
        }
        return new Result(out, notes, null);
    }

    private static String argNames(Signature sig) {
        List<String> names = new ArrayList<>();
        for (Arg a : sig.args()) {
            names.add(a.name() + (a.required() ? "" : "?"));
        }
        return "(" + String.join(", ", names) + ")";
    }

    static Object value(Arg a, Object v, Ids ids, List<String> notes) throws Failure {
        return switch (a.type()) {
            case POS -> pos(v);
            case BOX -> box(v);
            case FACING -> facing(v);
            case ITEM -> id(v, false, ids, notes);
            case BLOCK -> id(v, true, ids, notes);
            case ITEMS -> items(v, ids, notes);
            case CONTAINER -> container(v);
            case COUNT -> clamp(a.name(), integer(v), MIN_COUNT, MAX_COUNT, notes);
            case INT -> a.max() > 0 ? clamp(a.name(), integer(v), a.min(), a.max(), notes) : integer(v);
            case TEXT -> text(a, v, notes);
            case QUERY_NAME -> queryName(v);
            case PREDICATE, ANY -> v;
        };
    }

    // --- ids -------------------------------------------------------------------------------------

    /**
     * The canonical id for a loose name: namespace optional, case and spaces normalised, a trailing
     * plural {@code s} dropped when the singular exists. Vanilla ids come back without the
     * {@code minecraft:} prefix, as every model-facing line prints them.
     */
    public static String id(Object raw, boolean block, Ids ids, List<String> notes) throws Failure {
        if (!(raw instanceof String s)) {
            throw Failure.of(FailureCode.BAD_ARGS, "expected a " + kind(block) + " id, got " + raw);
        }
        String n = s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        String ns = n.contains(":") ? n : "minecraft:" + n;
        String found = null;
        if (ids.has(block, ns)) {
            found = ns;
        } else if (ns.endsWith("s") && ids.has(block, ns.substring(0, ns.length() - 1))) {
            found = ns.substring(0, ns.length() - 1);
        } else if (ns.endsWith("es") && ids.has(block, ns.substring(0, ns.length() - 2))) {
            found = ns.substring(0, ns.length() - 2);
        }
        if (found != null) {
            String canonical = found.startsWith("minecraft:") ? found.substring("minecraft:".length()) : found;
            if (!canonical.equals(s)) {
                notes.add("'" + s + "' read as " + canonical);
            }
            return canonical;
        }
        String word = ns.substring(ns.indexOf(':') + 1);
        // Shortest first: the plain items (iron_ingot) come before the long tail (iron_horse_armor).
        TreeSet<String> candidates = new TreeSet<>(Comparator.comparingInt(String::length).thenComparing(c -> c));
        for (String id : ids.all(block)) {
            String path = id.substring(id.indexOf(':') + 1);
            if (("_" + path + "_").contains("_" + word + "_")) {
                candidates.add(id.startsWith("minecraft:") ? path : id);
            }
        }
        if (candidates.isEmpty()) {
            throw Failure.of(FailureCode.BAD_ARGS, "'" + s + "' is not a " + kind(block) + " id");
        }
        List<String> shown = new ArrayList<>(candidates).subList(0, Math.min(MAX_CANDIDATES, candidates.size()));
        throw new Failure(new ActionError(FailureCode.AMBIGUOUS, "'" + s + "' could be several " + kind(block)
                + "s; name one", Map.of("candidates", shown, "total", candidates.size())));
    }

    private static String kind(boolean block) {
        return block ? "block" : "item";
    }

    /**
     * An item set: {@code "a,b"}, {@code "a 3, b"}, {@code ["a","b"]} or {@code {a:1,b:2}}. The value
     * is the count, or null for "all of it"; {@code "all_except_tools"} passes through as itself.
     */
    public static Object items(Object raw, Ids ids, List<String> notes) throws Failure {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw instanceof String s) {
            if (s.trim().equalsIgnoreCase("all_except_tools")) {
                return "all_except_tools";
            }
            String body = s.trim();
            if (body.startsWith("[") && body.endsWith("]")) {
                body = body.substring(1, body.length() - 1);
            }
            for (String entry : body.split(",")) {
                String e = entry.trim().replace("\"", "").replace("'", "");
                if (e.isEmpty()) {
                    continue;
                }
                String[] t = e.split("[\\s:=]+");
                Integer n = null;
                String name = e;
                if (t.length >= 2 && isInt(t[t.length - 1])) {
                    n = clamp("count", Integer.parseInt(t[t.length - 1]), MIN_COUNT, MAX_COUNT, notes);
                    name = String.join(" ", java.util.Arrays.copyOf(t, t.length - 1));
                }
                merge(out, id(name, false, ids, notes), n);
            }
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                merge(out, id(o, false, ids, notes), null);
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Integer n = e.getValue() == null ? null : clamp("count", integer(e.getValue()), MIN_COUNT, MAX_COUNT, notes);
                merge(out, id(String.valueOf(e.getKey()), false, ids, notes), n);
            }
        } else {
            throw Failure.of(FailureCode.BAD_ARGS, "expected items as {item: n}, [\"a\",\"b\"] or \"a,b\", got " + raw);
        }
        if (out.isEmpty()) {
            throw Failure.of(FailureCode.BAD_ARGS, "no items given");
        }
        return out;
    }

    private static void merge(Map<String, Integer> out, String id, Integer n) {
        if (out.containsKey(id)) {
            Integer prior = out.get(id);
            out.put(id, prior == null || n == null ? null : Math.min(MAX_COUNT, prior + n));
        } else {
            out.put(id, n);
        }
    }

    // --- positions, boxes, containers -----------------------------------------------------------

    /** {@code {x,y,z}}, {@code [x,y,z]}, {@code "x y z"} or {@code "x,y,z"}; fractions floor to the block. */
    public static AreaSpec.Pos pos(Object raw) throws Failure {
        if (raw instanceof AreaSpec.Pos p) {
            return p;
        }
        if (raw instanceof ContainerHandle h) {
            return h.pos();
        }
        int[] n = numbers(raw, 3, "a position (x y z)");
        return new AreaSpec.Pos(n[0], n[1], n[2]);
    }

    /** {@code {min, max}}, six numbers as a list or text; corners may come in any order. */
    public static AreaSpec.Box box(Object raw) throws Failure {
        if (raw instanceof AreaSpec.Box b) {
            return b;
        }
        int[] n;
        if (raw instanceof Map<?, ?> m && m.containsKey("min") && m.containsKey("max")) {
            AreaSpec.Pos a = pos(m.get("min"));
            AreaSpec.Pos b = pos(m.get("max"));
            n = new int[] {a.x(), a.y(), a.z(), b.x(), b.y(), b.z()};
        } else {
            n = numbers(raw, 6, "a box (x1 y1 z1 x2 y2 z2, or {min, max})");
        }
        return new AreaSpec.Box(Math.min(n[0], n[3]), Math.min(n[1], n[4]), Math.min(n[2], n[5]),
                Math.max(n[0], n[3]), Math.max(n[1], n[4]), Math.max(n[2], n[5]), AreaSpec.Facing.NORTH);
    }

    /**
     * A container reference as the caller gave it. A {@link ContainerHandle} passes through; a
     * position becomes an unresolved handle that the primitive resolves against the world, so a
     * double chest named by either half is the whole chest (E8).
     */
    public static ContainerHandle container(Object raw) throws Failure {
        if (raw instanceof ContainerHandle h) {
            return h;
        }
        return ContainerHandle.at(pos(raw));
    }

    public static AreaSpec.Facing facing(Object raw) throws Failure {
        AreaSpec.Facing f = raw instanceof AreaSpec.Facing given ? given
                : raw instanceof String s ? switch (s.trim().toLowerCase(Locale.ROOT)) {
                    case "north", "n" -> AreaSpec.Facing.NORTH;
                    case "south", "s" -> AreaSpec.Facing.SOUTH;
                    case "east", "e" -> AreaSpec.Facing.EAST;
                    case "west", "w" -> AreaSpec.Facing.WEST;
                    default -> null;
                } : null;
        if (f == null) {
            throw Failure.of(FailureCode.BAD_ARGS, "facing must be north, south, east or west, got " + raw);
        }
        return f;
    }

    private static int[] numbers(Object raw, int n, String what) throws Failure {
        List<Object> parts = new ArrayList<>();
        if (raw instanceof Map<?, ?> m && n == 3 && m.containsKey("x") && m.containsKey("y") && m.containsKey("z")) {
            parts.add(m.get("x"));
            parts.add(m.get("y"));
            parts.add(m.get("z"));
        } else if (raw instanceof List<?> l) {
            parts.addAll(l);
        } else if (raw instanceof String s) {
            String body = s.trim().replaceAll("^[\\[({]|[\\])}]$", "");
            for (String t : body.split("[\\s,]+")) {
                if (!t.isEmpty()) {
                    parts.add(t);
                }
            }
        }
        if (parts.size() != n) {
            throw Failure.of(FailureCode.BAD_ARGS, "expected " + what + ", got " + raw);
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = floorInt(parts.get(i), what);
        }
        return out;
    }

    // --- numbers and text -----------------------------------------------------------------------

    static int integer(Object raw) throws Failure {
        return floorInt(raw, "a whole number");
    }

    private static int floorInt(Object raw, String what) throws Failure {
        try {
            double d = raw instanceof Number num ? num.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
            if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 30_000_000) {
                throw Failure.of(FailureCode.BAD_ARGS, "expected " + what + ", got " + raw);
            }
            return (int) Math.floor(d);
        } catch (NumberFormatException e) {
            throw Failure.of(FailureCode.BAD_ARGS, "expected " + what + ", got " + raw);
        }
    }

    static int clamp(String name, int v, int min, int max, List<String> notes) {
        if (v < min) {
            notes.add(name + " " + v + " raised to " + min);
            return min;
        }
        if (v > max) {
            notes.add(name + " " + v + " lowered to " + max);
            return max;
        }
        return v;
    }

    private static String text(Arg a, Object v, List<String> notes) {
        String s = String.valueOf(v);
        if (a.max() > 0 && s.length() > a.max()) {
            notes.add(a.name() + " cut to " + a.max() + " characters");
            return s.substring(0, a.max());
        }
        return s;
    }

    private static String queryName(Object v) throws Failure {
        Signature q = v instanceof String s ? SignatureTable.get(s.trim()) : null;
        if (q == null || q.kind() != Signature.Kind.QUERY) {
            throw Failure.of(FailureCode.BAD_ARGS, v + " is not a query");
        }
        return q.name();
    }

    static boolean isInt(String s) {
        return s != null && s.matches("-?\\d{1,9}");
    }
}
