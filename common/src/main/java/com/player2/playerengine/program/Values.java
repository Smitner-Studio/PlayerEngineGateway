package com.player2.playerengine.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Program values are JSON-able only (§6.1): {@code null}, {@link Boolean}, {@link Double},
 * {@link String}, {@code ArrayList<Object>} and {@code LinkedHashMap<String, Object>}. A Pos is
 * {@code {x,y,z}}, a Box {@code {min,max}}, and a seam handle whatever JSON the seam returned.
 * Arrays and objects are shared by reference, as in JavaScript, and {@link Encoder} keeps that
 * sharing through a serialise and restore.
 */
final class Values {
    private Values() {
    }

    static String numberText(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Double d) {
            return d != 0 && !d.isNaN();
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    /** {@code ===}: numbers, strings and booleans by value; arrays and objects by identity. */
    static boolean strictEquals(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) {
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof String || a instanceof Boolean) {
            return a.equals(b);
        }
        return a == b;
    }

    static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Double) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof List) {
            return "array";
        }
        return "object";
    }

    /** The text {@code +} joins: arrays as {@code a,b}, objects as JSON. Cycles print once. */
    static String display(Object v) {
        StringBuilder sb = new StringBuilder();
        display(v, sb, new IdentityHashMap<>(), false);
        return sb.toString();
    }

    private static void display(Object v, StringBuilder sb, IdentityHashMap<Object, Boolean> seen, boolean quoted) {
        if (sb.length() > Caps.STRING_LENGTH) {
            return;
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Double d) {
            sb.append(numberText(d));
        } else if (v instanceof String s) {
            sb.append(quoted ? "\"" + s + "\"" : s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (seen.put(v, Boolean.TRUE) != null) {
            sb.append(quoted ? "\"<cycle>\"" : "");
        } else if (v instanceof List<?> l) {
            if (quoted) {
                sb.append('[');
            }
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                display(l.get(i), sb, seen, quoted);
            }
            if (quoted) {
                sb.append(']');
            }
            seen.remove(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                display(e.getValue(), sb, seen, true);
            }
            sb.append('}');
            seen.remove(v);
        } else {
            sb.append(v);
        }
    }

    /** Values reachable from {@code v}, each array, object and scalar once (§6.3 live values). */
    static int count(Object v, IdentityHashMap<Object, Boolean> seen, int limit) {
        if (!(v instanceof List || v instanceof Map)) {
            return 1;
        }
        if (seen.put(v, Boolean.TRUE) != null) {
            return 0;
        }
        int n = 1;
        Iterable<?> items = v instanceof List<?> l ? l : ((Map<?, ?>) v).values();
        for (Object o : items) {
            n += count(o, seen, limit - n);
            if (n > limit) {
                return n;
            }
        }
        return n;
    }

    /** A seam value in program form: numbers as doubles, maps and lists copied. */
    static Object fromHost(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Double) {
            return v;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof CharSequence || v instanceof Enum<?>) {
            return v.toString().toLowerCase(java.util.Locale.ROOT);
        }
        if (v instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), fromHost(x)));
            return out;
        }
        if (v instanceof Iterable<?> it) {
            ArrayList<Object> out = new ArrayList<>();
            it.forEach(x -> out.add(fromHost(x)));
            return out;
        }
        if (v instanceof JsonElement j) {
            return decodePlain(j);
        }
        return v.toString();
    }

    /** A program value in the seam's form: whole numbers as ints (or longs), copies of containers. */
    static Object toHost(Object v) {
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !d.isInfinite()) {
                long l = (long) d.doubleValue();
                return l == (int) l ? (Object) (int) l : (Object) l;
            }
            return d;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(toHost(x)));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put((String) k, toHost(x)));
            return out;
        }
        return v;
    }

    /** Plain JSON (no sharing), for host snapshots and log arguments. */
    static JsonElement plain(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return new JsonPrimitive(numberText(d));
            }
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
        }
        if (v instanceof List<?> l) {
            JsonArray a = new JsonArray();
            l.forEach(x -> a.add(plain(x)));
            return a;
        }
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((k, x) -> o.add(String.valueOf(k), plain(x)));
            return o;
        }
        return new JsonPrimitive(v.toString());
    }

    /** The inverse of {@link #plain}, with numbers as doubles. */
    static Object decodePlain(JsonElement j) {
        if (j == null || j.isJsonNull()) {
            return null;
        }
        if (j.isJsonPrimitive()) {
            JsonPrimitive p = j.getAsJsonPrimitive();
            return p.isBoolean() ? (Object) p.getAsBoolean() : p.isNumber() ? (Object) p.getAsDouble() : p.getAsString();
        }
        if (j.isJsonArray()) {
            ArrayList<Object> out = new ArrayList<>();
            j.getAsJsonArray().forEach(x -> out.add(decodePlain(x)));
            return out;
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        j.getAsJsonObject().entrySet().forEach(e -> out.put(e.getKey(), decodePlain(e.getValue())));
        return out;
    }

    /**
     * Program values to JSON with reference sharing kept: an array is {@code {"a":[…],"id":n}}, an
     * object {@code {"o":{…},"id":n}}, a second sight of either {@code {"r":n}}, and a non-finite
     * number {@code {"num":"NaN"}}. One encoder spans a whole job, so frames that share an array
     * still share it after a restore.
     */
    static final class Encoder {
        private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();

        JsonElement encode(Object v) {
            if (v == null) {
                return JsonNull.INSTANCE;
            }
            if (v instanceof Boolean b) {
                return new JsonPrimitive(b);
            }
            if (v instanceof String s) {
                return new JsonPrimitive(s);
            }
            if (v instanceof Double d) {
                if (d.isNaN() || d.isInfinite()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("num", numberText(d));
                    return o;
                }
                return plain(d);
            }
            Integer seen = ids.get(v);
            if (seen != null) {
                JsonObject o = new JsonObject();
                o.addProperty("r", seen);
                return o;
            }
            int id = ids.size();
            ids.put(v, id);
            JsonObject o = new JsonObject();
            if (v instanceof List<?> l) {
                JsonArray a = new JsonArray();
                l.forEach(x -> a.add(encode(x)));
                o.add("a", a);
            } else if (v instanceof Map<?, ?> m) {
                JsonObject fields = new JsonObject();
                m.forEach((k, x) -> fields.add((String) k, encode(x)));
                o.add("o", fields);
            } else {
                throw new IllegalStateException("not a program value: " + v.getClass().getName());
            }
            o.addProperty("id", id);
            return o;
        }
    }

    static final class Decoder {
        private final Map<Integer, Object> byId = new HashMap<>();

        Object decode(JsonElement j) {
            if (j == null || j.isJsonNull()) {
                return null;
            }
            if (j.isJsonPrimitive()) {
                JsonPrimitive p = j.getAsJsonPrimitive();
                if (p.isBoolean()) {
                    return p.getAsBoolean();
                }
                return p.isNumber() ? (Object) p.getAsDouble() : p.getAsString();
            }
            JsonObject o = j.getAsJsonObject();
            if (o.has("r")) {
                Object v = byId.get(o.get("r").getAsInt());
                if (v == null) {
                    throw new IllegalStateException("reference to an unseen value " + o.get("r"));
                }
                return v;
            }
            if (o.has("num")) {
                return Double.parseDouble(o.get("num").getAsString());
            }
            int id = o.get("id").getAsInt();
            if (o.has("a")) {
                ArrayList<Object> l = new ArrayList<>();
                byId.put(id, l);
                o.getAsJsonArray("a").forEach(x -> l.add(decode(x)));
                return l;
            }
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            byId.put(id, m);
            o.getAsJsonObject("o").entrySet().forEach(e -> m.put(e.getKey(), decode(e.getValue())));
            return m;
        }
    }
}
