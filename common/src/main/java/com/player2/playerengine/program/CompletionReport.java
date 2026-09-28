package com.player2.playerengine.program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The completion report (§6.5, R5): a template filled from the calls log, with no model call. Only
 * verified calls count (done, ok, not rolled back by a restart), so a companion cannot report an
 * effect the world does not show.
 *
 * <p>Amounts come from the call's outcome when the seam returns an {@code {item: n}} map (what
 * actually moved), else from the call's coerced arguments.
 */
final class CompletionReport {
    private CompletionReport() {
    }

    /** One clause per kind of action, in the order they first happened. */
    private static final class Clause {
        final String verb;
        final String where;
        final Map<String, Long> items = new LinkedHashMap<>();
        int times;

        Clause(String verb, String where) {
            this.verb = verb;
            this.where = where;
        }
    }

    static String render(CallLog log) {
        Map<String, Clause> clauses = new LinkedHashMap<>();
        for (CallLog.Entry e : log.entries()) {
            if (!e.ok() || e.query() || e.rolledBack()) {
                continue;
            }
            Map<String, Object> a = e.args();
            switch (e.name()) {
                case "store" -> items(clauses, "stored", "in " + container(a.get("c")), e);
                case "withdraw" -> items(clauses, "took", "from " + container(a.get("c")), e);
                case "give_owner", "get", "craft", "smelt", "mine" -> {
                    String verb = switch (e.name()) {
                        case "give_owner" -> "handed over";
                        case "get" -> "gathered";
                        case "craft" -> "crafted";
                        case "smelt" -> "smelted";
                        default -> "mined";
                    };
                    Object item = a.getOrDefault("item", a.getOrDefault("output", a.get("block")));
                    Clause c = clauses.computeIfAbsent(verb, k -> new Clause(verb, null));
                    c.items.merge(name(item), count(a.get("n")), Long::sum);
                }
                case "place" -> {
                    Clause c = clauses.computeIfAbsent("placed", k -> new Clause("placed", null));
                    c.items.merge(name(a.get("block")), 1L, Long::sum);
                }
                case "excavate" -> area(clauses, "dug out", a.get("box"), null);
                case "fill" -> area(clauses, "filled", a.get("box"), name(a.get("block")));
                case "goto" -> {
                    Clause c = new Clause("went to " + pos(a.get("p")), null);
                    clauses.remove("goto");
                    clauses.put("goto", c);
                }
                case "pickup_drops" -> clauses.putIfAbsent("pickup", new Clause("picked up the drops", null));
                case "equip" -> {
                    Clause c = clauses.computeIfAbsent("equipped", k -> new Clause("equipped", null));
                    c.items.put(name(a.get("item")), 0L);
                }
                default -> {
                }
            }
        }
        if (clauses.isEmpty()) {
            return "Done.";
        }
        List<String> parts = new ArrayList<>();
        for (Clause c : clauses.values()) {
            parts.add(phrase(c));
        }
        String s = String.join("; ", parts) + ".";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void items(Map<String, Clause> clauses, String verb, String where, CallLog.Entry e) {
        Clause c = clauses.computeIfAbsent(verb + " " + where, k -> new Clause(verb, where));
        Map<String, Long> moved = itemMap(e.value());
        if (moved.isEmpty()) {
            moved = itemMap(e.args().get("items"));
        }
        moved.forEach((k, v) -> c.items.merge(k, v, Long::sum));
    }

    private static void area(Map<String, Clause> clauses, String verb, Object box, String block) {
        String key = verb + (block == null ? "" : " " + block);
        Clause c = clauses.computeIfAbsent(key, k -> new Clause(verb, block == null ? null : "with " + block));
        c.times++;
        c.items.merge("", cells(box), Long::sum);
    }

    private static String phrase(Clause c) {
        StringBuilder sb = new StringBuilder(c.verb);
        if (c.times > 0) {
            long n = c.items.getOrDefault("", 0L);
            sb.append(c.times == 1 ? " an area of " : " " + c.times + " areas, ").append(n).append(n == 1 ? " block"
                    : " blocks");
        } else if (!c.items.isEmpty()) {
            List<String> xs = new ArrayList<>();
            c.items.forEach((k, v) -> xs.add(v > 0 ? v + " " + k : k));
            sb.append(' ').append(join(xs));
        }
        if (c.where != null) {
            sb.append(' ').append(c.where);
        }
        return sb.toString();
    }

    private static String join(List<String> xs) {
        if (xs.size() == 1) {
            return xs.get(0);
        }
        return String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.get(xs.size() - 1);
    }

    private static Map<String, Long> itemMap(Object v) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, n) -> {
                if (n instanceof Number num) {
                    out.put(name(k), num.longValue());
                }
            });
        } else if (v instanceof List<?> l) {
            l.forEach(x -> out.put(name(x), 0L));
        } else if (v instanceof String s && !s.isBlank()) {
            out.put(s.equals("all_except_tools") ? "everything but the tools" : name(s), 0L);
        }
        return out;
    }

    private static long count(Object n) {
        return n instanceof Number num ? num.longValue() : 1;
    }

    private static long cells(Object box) {
        if (box instanceof Map<?, ?> b && b.get("min") instanceof Map<?, ?> lo && b.get("max") instanceof Map<?, ?> hi) {
            long n = 1;
            for (String k : List.of("x", "y", "z")) {
                n *= Math.abs(num(hi.get(k)) - num(lo.get(k))) + 1;
            }
            return n;
        }
        return 0;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String pos(Object p) {
        if (p instanceof Map<?, ?> m) {
            return num(m.get("x")) + " " + num(m.get("y")) + " " + num(m.get("z"));
        }
        return String.valueOf(p);
    }

    private static String container(Object c) {
        if (c == null) {
            return "storage";
        }
        if (c instanceof Map<?, ?> m && m.containsKey("x")) {
            return "the chest at " + pos(m);
        }
        if (c instanceof Map<?, ?> m && m.get("pos") != null) {
            return "the chest at " + pos(m.get("pos"));
        }
        return "the container";
    }

    private static String name(Object id) {
        String s = String.valueOf(id);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s.replace('_', ' ');
    }
}
