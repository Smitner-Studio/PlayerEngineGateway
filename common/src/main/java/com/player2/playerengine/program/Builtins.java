package com.player2.playerengine.program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure built-ins (§5.2). They never yield, so they may appear inside expressions. Array and string
 * methods are {@link #method}.
 */
final class Builtins {
    /** Name to {min, max} argument count; max -1 is variadic. */
    static final Map<String, int[]> ARITY = Map.of(
            "pos", new int[] {3, 3},
            "offset", new int[] {4, 4},
            "box", new int[] {2, 2},
            "box_rel", new int[] {5, 5},
            "dist", new int[] {2, 2},
            "min", new int[] {1, -1},
            "max", new int[] {1, -1},
            "abs", new int[] {1, 1},
            "floor", new int[] {1, 1},
            "assert", new int[] {1, 2});

    static final Set<String> METHODS = Set.of("push", "slice", "includes");

    private Builtins() {
    }

    static Object call(String name, List<Object> a, Ast.Node at) {
        return switch (name) {
            case "pos" -> pos(num(a.get(0), at), num(a.get(1), at), num(a.get(2), at));
            case "offset" -> {
                Map<String, Object> p = asPos(a.get(0), at);
                yield pos((Double) p.get("x") + num(a.get(1), at), (Double) p.get("y") + num(a.get(2), at),
                        (Double) p.get("z") + num(a.get(3), at));
            }
            case "box" -> box(asPos(a.get(0), at), asPos(a.get(1), at));
            case "box_rel" -> boxRel(asPos(a.get(0), at), intArg(a.get(1), at), intArg(a.get(2), at),
                    intArg(a.get(3), at), a.get(4), at);
            case "dist" -> {
                Map<String, Object> p = asPos(a.get(0), at);
                Map<String, Object> q = asPos(a.get(1), at);
                double dx = (Double) p.get("x") - (Double) q.get("x");
                double dy = (Double) p.get("y") - (Double) q.get("y");
                double dz = (Double) p.get("z") - (Double) q.get("z");
                yield Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            case "min", "max" -> {
                List<?> xs = a.size() == 1 && a.get(0) instanceof List<?> l ? l : a;
                if (xs.isEmpty()) {
                    throw Fault.bad(at, name + " of nothing");
                }
                double r = name.equals("min") ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                for (Object x : xs) {
                    double d = num(x, at);
                    r = name.equals("min") ? Math.min(r, d) : Math.max(r, d);
                }
                yield r;
            }
            case "abs" -> Math.abs(num(a.get(0), at));
            case "floor" -> Math.floor(num(a.get(0), at));
            case "assert" -> {
                if (!Values.truthy(a.get(0))) {
                    String msg = a.size() > 1 ? Values.display(a.get(1)) : "assertion failed";
                    throw Fault.of(new com.player2.playerengine.seam.ActionError(
                            com.player2.playerengine.seam.FailureCode.BAD_ARGS, "assert: " + msg,
                            Map.of("assert", msg, "line", at.line())));
                }
                yield null;
            }
            default -> throw Fault.bad(at, "unknown function " + name);
        };
    }

    /** {@code arr.push(x)}, {@code arr.slice(a, b)}, {@code arr.includes(x)}, and the string forms. */
    static Object method(Object target, String name, List<Object> a, Ast.Node at) {
        if (target instanceof List<?> raw) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) raw;
            switch (name) {
                case "push" -> {
                    if (l.size() + a.size() > Caps.ARRAY_LENGTH) {
                        throw Fault.budget("array length", Caps.ARRAY_LENGTH, Map.of("length", l.size()));
                    }
                    l.addAll(a);
                    return (double) l.size();
                }
                case "slice" -> {
                    int[] r = range(l.size(), a, at);
                    return new ArrayList<>(l.subList(r[0], r[1]));
                }
                case "includes" -> {
                    Object x = a.isEmpty() ? null : a.get(0);
                    for (Object o : l) {
                        if (Values.strictEquals(o, x)) {
                            return true;
                        }
                    }
                    return false;
                }
                default -> {
                }
            }
        } else if (target instanceof String s) {
            switch (name) {
                case "slice" -> {
                    int[] r = range(s.length(), a, at);
                    return s.substring(r[0], r[1]);
                }
                case "includes" -> {
                    return s.contains(Values.display(a.isEmpty() ? null : a.get(0)));
                }
                default -> {
                }
            }
        }
        throw Fault.bad(at, Values.typeName(target) + " has no method " + name + "; the methods are push, slice"
                + " and includes on arrays, slice and includes on strings");
    }

    private static int[] range(int size, List<Object> a, Ast.Node at) {
        int from = a.isEmpty() ? 0 : clampIndex(num(a.get(0), at), size);
        int to = a.size() < 2 ? size : clampIndex(num(a.get(1), at), size);
        return new int[] {from, Math.max(from, to)};
    }

    private static int clampIndex(double d, int size) {
        int i = (int) Math.floor(d);
        if (i < 0) {
            i = Math.max(0, size + i);
        }
        return Math.min(i, size);
    }

    static LinkedHashMap<String, Object> pos(double x, double y, double z) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("x", x);
        p.put("y", y);
        p.put("z", z);
        return p;
    }

    static LinkedHashMap<String, Object> box(Map<String, Object> a, Map<String, Object> b) {
        LinkedHashMap<String, Object> box = new LinkedHashMap<>();
        box.put("min", pos(Math.min((Double) a.get("x"), (Double) b.get("x")),
                Math.min((Double) a.get("y"), (Double) b.get("y")), Math.min((Double) a.get("z"), (Double) b.get("z"))));
        box.put("max", pos(Math.max((Double) a.get("x"), (Double) b.get("x")),
                Math.max((Double) a.get("y"), (Double) b.get("y")), Math.max((Double) a.get("z"), (Double) b.get("z"))));
        return box;
    }

    /**
     * {@code w} wide, {@code h} high and {@code d} deep, starting one block ahead of the anchor in the
     * facing, centred across it with an even width's extra column on the right: the same box the
     * area commands' relative form makes ({@code AreaSpec}).
     */
    static LinkedHashMap<String, Object> boxRel(Map<String, Object> anchor, int w, int h, int d, Object facing,
            Ast.Node at) {
        if (w < 1 || h < 1 || d < 1) {
            throw Fault.bad(at, "box_rel sizes must be at least 1");
        }
        int fx;
        int fz;
        String f = facing instanceof String s ? s.toLowerCase(java.util.Locale.ROOT) : "";
        switch (f) {
            case "north" -> { fx = 0; fz = -1; }
            case "south" -> { fx = 0; fz = 1; }
            case "east" -> { fx = 1; fz = 0; }
            case "west" -> { fx = -1; fz = 0; }
            default -> throw Fault.bad(at, "facing must be north, south, east or west, not "
                    + Values.display(facing));
        }
        double bx = Math.floor((Double) anchor.get("x"));
        double by = Math.floor((Double) anchor.get("y"));
        double bz = Math.floor((Double) anchor.get("z"));
        int rightX = -fz;
        int rightZ = fx;
        int left = (w - 1) / 2;
        double x1 = bx + fx - rightX * left;
        double z1 = bz + fz - rightZ * left;
        double x2 = bx + fx * d + rightX * (w - 1 - left);
        double z2 = bz + fz * d + rightZ * (w - 1 - left);
        return box(pos(x1, by, z1), pos(x2, by + h - 1, z2));
    }

    static double num(Object v, Ast.Node at) {
        if (v instanceof Double d) {
            return d;
        }
        throw Fault.bad(at, "expected a number, got " + Values.typeName(v));
    }

    private static int intArg(Object v, Ast.Node at) {
        return (int) Math.floor(num(v, at));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asPos(Object v, Ast.Node at) {
        if (v instanceof Map<?, ?> m && m.get("x") instanceof Double && m.get("y") instanceof Double
                && m.get("z") instanceof Double) {
            return (Map<String, Object>) m;
        }
        throw Fault.bad(at, "expected a Pos {x, y, z}, got " + Values.display(v));
    }
}
