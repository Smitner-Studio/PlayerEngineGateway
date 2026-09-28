package com.player2.playerengine.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.program.Ast.*;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The yielding interpreter (§6.1). It runs statements off an explicit frame stack and stops only
 * between statements: when an {@code api.*} call must go to the world, when a tick's slice is used, or
 * when the program ends. Pure expressions recurse on the Java stack, which is safe because they cannot
 * yield and the linter bounds their depth.
 */
final class Machine {
    /** Why {@link #run} returned. */
    sealed interface Yield permits ApiCall, Slice, Finished, Failed {
    }

    /** An {@code api.*} call parked the program; answer with {@link #deliver} or {@link #raise}. */
    record ApiCall(String name, List<Object> args, Call node) implements Yield {
    }

    record Slice() implements Yield {
    }

    record Finished(Object value) implements Yield {
    }

    record Failed(Fault fault) implements Yield {
    }

    final Program program;
    private final List<Frame> frames = new ArrayList<>();
    long steps;
    private Yield ended;

    Machine(Program program) {
        this.program = program;
        frames.add(new Frame(Frame.Kind.BLOCK, program.root.id()));
    }

    private Machine(Program program, long steps) {
        this.program = program;
        this.steps = steps;
    }

    boolean waiting() {
        return !frames.isEmpty() && top().pending;
    }

    private Frame top() {
        return frames.get(frames.size() - 1);
    }

    // --- running ----------------------------------------------------------------------------------

    /** Runs at most {@code maxStatements} statements. */
    Yield run(int maxStatements) {
        int budget = maxStatements;
        while (true) {
            if (ended != null) {
                return ended;
            }
            if (frames.isEmpty()) {
                ended = new Finished(null);
                continue;
            }
            Frame f = top();
            if (f.pending) {
                throw new IllegalStateException("run while a call is pending");
            }
            if (budget-- <= 0) {
                return new Slice();
            }
            try {
                Yield y = stepFrame(f);
                if (y != null) {
                    return y;
                }
            } catch (Fault fault) {
                unwind(fault);
            }
        }
    }

    private void step() {
        steps++;
        if (steps > Caps.STATEMENTS) {
            throw Fault.budget("statements", Caps.STATEMENTS, progress());
        }
        if (steps % Caps.LIVE_CHECK_EVERY == 0) {
            checkLiveValues();
        }
    }

    Map<String, Object> progress() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("steps", steps);
        return p;
    }

    void checkLiveValues() {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        int n = 0;
        for (Frame f : frames) {
            for (Object v : f.vars.values()) {
                n += Values.count(v, seen, Caps.LIVE_VALUES - n);
            }
            if (f.items != null) {
                n += Values.count(f.items, seen, Caps.LIVE_VALUES - n);
            }
            if (n > Caps.LIVE_VALUES) {
                throw Fault.budget("live values", Caps.LIVE_VALUES, progress());
            }
        }
    }

    private List<Stmt> statements(Frame f) {
        return switch (f.kind) {
            case BLOCK -> program.block(f.node).body();
            case FUNCTION -> ((Func) program.stmt(f.node)).body().body();
            case TRY -> ((Try) program.stmt(f.node)).body().body();
            default -> throw new IllegalStateException(f.kind + " has no statements");
        };
    }

    private Yield stepFrame(Frame f) {
        switch (f.kind) {
            case BLOCK, FUNCTION, TRY -> {
                List<Stmt> body = statements(f);
                if (f.ip >= body.size()) {
                    pop();
                    if (f.kind == Frame.Kind.FUNCTION) {
                        completePending(null);
                    }
                    return null;
                }
                step();
                return exec(body.get(f.ip), f);
            }
            case FOR_OF -> {
                ForOf s = (ForOf) program.stmt(f.node);
                if (f.index >= f.items.size()) {
                    pop();
                    return null;
                }
                step();
                Frame body = push(Frame.Kind.BLOCK, s.body().id());
                declare(body, s.name(), f.items.get(f.index++), s.isConst(), s);
                return null;
            }
            case FOR_I -> {
                ForI s = (ForI) program.stmt(f.node);
                step();
                if (f.entered) {
                    assign(s.update());
                }
                f.entered = true;
                if (Values.truthy(eval(s.test()))) {
                    push(Frame.Kind.BLOCK, s.body().id());
                } else {
                    pop();
                }
                return null;
            }
            case WHILE -> {
                While s = (While) program.stmt(f.node);
                step();
                if (Values.truthy(eval(s.test()))) {
                    push(Frame.Kind.BLOCK, s.body().id());
                } else {
                    pop();
                }
                return null;
            }
        }
        return null;
    }

    private Frame push(Frame.Kind kind, int node) {
        Frame f = new Frame(kind, node);
        frames.add(f);
        return f;
    }

    private Frame pop() {
        return frames.remove(frames.size() - 1);
    }

    private Yield exec(Stmt s, Frame f) {
        Call call = Ast.statementCall(s);
        if (call != null) {
            String[] target = Ast.callTarget(call);
            if (target != null && (!target[0].isEmpty() || program.functions.containsKey(target[1])
                    && lookupFrame(target[1]) == null)) {
                return startCall(call, target, f);
            }
        }
        switch (s) {
            case Block b -> {
                f.ip++;
                push(Frame.Kind.BLOCK, b.id());
            }
            case Let l -> {
                f.ip++;
                declare(f, l.name(), l.init() == null ? null : eval(l.init()), l.isConst(), l);
            }
            case Assign a -> {
                f.ip++;
                assign(a);
            }
            case ExprStmt e -> {
                f.ip++;
                eval(e.expr());
            }
            case If i -> {
                f.ip++;
                Block branch = Values.truthy(eval(i.test())) ? i.then() : i.otherwise();
                if (branch != null) {
                    push(Frame.Kind.BLOCK, branch.id());
                }
            }
            case ForOf fo -> {
                f.ip++;
                Object it = eval(fo.iterable());
                if (!(it instanceof List<?>)) {
                    throw Fault.bad(fo, "for-of needs an array, got " + Values.typeName(it));
                }
                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>) it;
                push(Frame.Kind.FOR_OF, fo.id()).items = items;
            }
            case ForI fi -> {
                f.ip++;
                Frame loop = push(Frame.Kind.FOR_I, fi.id());
                declare(loop, fi.init().name(), eval(fi.init().init()), fi.init().isConst(), fi);
            }
            case While w -> {
                f.ip++;
                push(Frame.Kind.WHILE, w.id());
            }
            case Func fn -> f.ip++;
            case Return r -> {
                f.ip++;
                return doReturn(r.value() == null ? null : eval(r.value()));
            }
            case Break b -> {
                while (!top().isLoop()) {
                    pop();
                }
                pop();
            }
            case Continue c -> {
                while (!top().isLoop()) {
                    pop();
                }
            }
            case Throw t -> {
                f.ip++;
                throw Fault.thrown(eval(t.value()));
            }
            case Try t -> {
                f.ip++;
                push(Frame.Kind.TRY, t.id());
            }
        }
        return null;
    }

    private Yield startCall(Call call, String[] target, Frame f) {
        List<Object> args = new ArrayList<>(call.args().size());
        for (Expr a : call.args()) {
            args.add(eval(a));
        }
        switch (target[0]) {
            case "api" -> {
                f.pending = true;
                return new ApiCall(target[1], args, call);
            }
            case "skills" -> throw Fault.of(ActionError.of(FailureCode.DENIED,
                    "skills." + target[1] + " cannot run yet: the skill library arrives in stage 5"));
            default -> {
                int depth = 0;
                for (Frame x : frames) {
                    if (x.kind == Frame.Kind.FUNCTION) {
                        depth++;
                    }
                }
                if (depth >= Caps.CALL_FRAMES) {
                    throw Fault.budget("call-frame depth", Caps.CALL_FRAMES, progress());
                }
                Func fn = program.functions.get(target[1]);
                f.pending = true;
                Frame body = push(Frame.Kind.FUNCTION, fn.id());
                for (int i = 0; i < fn.params().size(); i++) {
                    declare(body, fn.params().get(i), i < args.size() ? args.get(i) : null, false, call);
                }
                return null;
            }
        }
    }

    /** Pops to the nearest function and hands it {@code value}; at the top level the program ends. */
    private Yield doReturn(Object value) {
        while (!frames.isEmpty() && top().kind != Frame.Kind.FUNCTION) {
            pop();
        }
        if (frames.isEmpty()) {
            ended = new Finished(value);
            return ended;
        }
        pop();
        completePending(value);
        return null;
    }

    /** The waiting statement at the top frame's ip gets its call's value and completes. */
    private void completePending(Object value) {
        Frame f = top();
        if (!f.pending) {
            throw new IllegalStateException("no statement is waiting for a value");
        }
        f.pending = false;
        Stmt s = statements(f).get(f.ip);
        f.ip++;
        switch (s) {
            case Let l -> declare(f, l.name(), value, l.isConst(), l);
            case Assign a -> store(a.target(), value, a);
            case Return r -> doReturn(value);
            default -> {
            }
        }
    }

    /** The parked {@code api.*} call succeeded with {@code value}. */
    void deliver(Object value) {
        try {
            completePending(value);
        } catch (Fault fault) {
            unwind(fault);
        }
    }

    /** The parked call failed: the error goes to the nearest {@code catch}, or ends the run. */
    void raise(Fault fault) {
        top().pending = false;
        top().ip++;
        unwind(fault);
    }

    /**
     * The parked call is to be made again: the statement stays at its ip and runs from the start on the
     * next {@link #run}. Its arguments are pure, so re-evaluating them gives the same call.
     */
    void retryPending() {
        top().pending = false;
        steps--;
    }

    private void unwind(Fault fault) {
        if (!fault.catchable() && !fault.error.state().containsKey("steps")) {
            // A cap hit inside a built-in (push) reports the same progress as the others.
            fault = Fault.of(fault.error.with("steps", steps));
        }
        if (fault.catchable()) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                Frame f = frames.get(i);
                if (f.kind == Frame.Kind.TRY) {
                    while (frames.size() > i) {
                        pop();
                    }
                    Try t = (Try) program.stmt(f.node);
                    Frame handler = push(Frame.Kind.BLOCK, t.handler().id());
                    if (t.catchName() != null) {
                        handler.vars.put(t.catchName(), fault.thrown);
                    }
                    return;
                }
            }
        }
        ended = new Failed(fault);
    }

    // --- variables --------------------------------------------------------------------------------

    private void declare(Frame f, String name, Object value, boolean isConst, Node at) {
        f.vars.put(name, value);
        if (isConst) {
            f.consts.add(name);
        }
        if (f.vars.size() > Caps.VARS_PER_FRAME) {
            throw Fault.budget("variables per frame", Caps.VARS_PER_FRAME, progress());
        }
    }

    /** The frame holding {@code name}: this function's frames, then the top level. */
    private Frame lookupFrame(String name) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            Frame f = frames.get(i);
            if (f.vars.containsKey(name)) {
                return f;
            }
            if (f.kind == Frame.Kind.FUNCTION) {
                Frame global = frames.get(0);
                return global.vars.containsKey(name) ? global : null;
            }
        }
        return null;
    }

    private void assign(Assign a) {
        Object value;
        switch (a.op()) {
            case "=" -> value = eval(a.value());
            case "++", "--" -> value = arith(a.op().equals("++") ? "+" : "-", eval(a.target()), 1.0, a);
            default -> value = arith(a.op().substring(0, 1), eval(a.target()), eval(a.value()), a);
        }
        store(a.target(), value, a);
    }

    private void store(Expr target, Object value, Node at) {
        switch (target) {
            case Ident id -> {
                Frame f = lookupFrame(id.name());
                if (f == null) {
                    throw Fault.bad(at, "unknown name " + id.name());
                }
                if (f.consts.contains(id.name())) {
                    throw Fault.bad(at, id.name() + " is const");
                }
                f.vars.put(id.name(), value);
            }
            case Member m -> setField(eval(m.object()), m.name(), value, at);
            case Index ix -> {
                Object obj = eval(ix.object());
                Object key = eval(ix.index());
                if (obj instanceof List<?> raw) {
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) raw;
                    int i = index(key, at);
                    if (i == l.size()) {
                        if (l.size() >= Caps.ARRAY_LENGTH) {
                            throw Fault.budget("array length", Caps.ARRAY_LENGTH, progress());
                        }
                        l.add(value);
                    } else if (i >= 0 && i < l.size()) {
                        l.set(i, value);
                    } else {
                        throw Fault.bad(at, "index " + i + " is past the end of an array of " + l.size());
                    }
                } else {
                    setField(obj, key instanceof String s ? s : Values.display(key), value, at);
                }
            }
            default -> throw Fault.bad(at, "cannot assign to this");
        }
    }

    private void setField(Object obj, String name, Object value, Node at) {
        if (obj instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            if (!m.containsKey(name) && m.size() >= Caps.ARRAY_LENGTH) {
                throw Fault.budget("object fields", Caps.ARRAY_LENGTH, progress());
            }
            m.put(name, value);
            return;
        }
        throw Fault.bad(at, "cannot set " + name + " on " + Values.typeName(obj));
    }

    private static int index(Object key, Node at) {
        if (key instanceof Double d && d == Math.rint(d)) {
            return (int) d.doubleValue();
        }
        throw Fault.bad(at, "an array index must be a whole number");
    }

    // --- pure expressions -------------------------------------------------------------------------

    Object eval(Expr e) {
        switch (e) {
            case Num n -> {
                return n.value();
            }
            case Str s -> {
                return s.value();
            }
            case Bool b -> {
                return b.value();
            }
            case Null n -> {
                return null;
            }
            case Ident id -> {
                Frame f = lookupFrame(id.name());
                if (f == null) {
                    throw Fault.bad(id, "unknown name " + id.name());
                }
                return f.vars.get(id.name());
            }
            case ArrayLit a -> {
                if (a.items().size() > Caps.ARRAY_LENGTH) {
                    throw Fault.budget("array length", Caps.ARRAY_LENGTH, progress());
                }
                ArrayList<Object> l = new ArrayList<>(a.items().size());
                for (Expr x : a.items()) {
                    l.add(eval(x));
                }
                return l;
            }
            case ObjectLit o -> {
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < o.keys().size(); i++) {
                    m.put(o.keys().get(i), eval(o.values().get(i)));
                }
                return m;
            }
            case Member m -> {
                return field(eval(m.object()), m.name(), m);
            }
            case Index ix -> {
                Object obj = eval(ix.object());
                Object key = eval(ix.index());
                if (obj instanceof List<?> l) {
                    int i = index(key, ix);
                    return i >= 0 && i < l.size() ? l.get(i) : null;
                }
                if (obj instanceof String s) {
                    int i = index(key, ix);
                    return i >= 0 && i < s.length() ? String.valueOf(s.charAt(i)) : null;
                }
                return field(obj, key instanceof String s ? s : Values.display(key), ix);
            }
            case Call c -> {
                return pureCall(c);
            }
            case Unary u -> {
                Object v = eval(u.operand());
                return switch (u.op()) {
                    case "!" -> !Values.truthy(v);
                    case "-" -> -Builtins.num(v, u);
                    default -> Builtins.num(v, u);
                };
            }
            case Binary b -> {
                return binary(b);
            }
            case Cond c -> {
                return Values.truthy(eval(c.test())) ? eval(c.then()) : eval(c.otherwise());
            }
        }
    }

    private Object field(Object obj, String name, Node at) {
        if (obj instanceof List<?> l && name.equals("length")) {
            return (double) l.size();
        }
        if (obj instanceof String s && name.equals("length")) {
            return (double) s.length();
        }
        if (obj instanceof Map<?, ?> m) {
            return m.get(name);
        }
        throw Fault.bad(at, "cannot read " + name + " of " + Values.typeName(obj));
    }

    private Object pureCall(Call c) {
        String[] target = Ast.callTarget(c);
        if (target != null && target[0].isEmpty() && Builtins.ARITY.containsKey(target[1])
                && lookupFrame(target[1]) == null) {
            List<Object> args = new ArrayList<>(c.args().size());
            for (Expr a : c.args()) {
                args.add(eval(a));
            }
            return Builtins.call(target[1], args, c);
        }
        if (c.callee() instanceof Member m && Builtins.METHODS.contains(m.name())) {
            Object obj = eval(m.object());
            List<Object> args = new ArrayList<>(c.args().size());
            for (Expr a : c.args()) {
                args.add(eval(a));
            }
            return Builtins.method(obj, m.name(), args, c);
        }
        // The linter keeps api.*, skills.* and user calls out of expressions; this is the backstop.
        throw Fault.bad(c, "this call may be made only as a whole statement");
    }

    private Object binary(Binary b) {
        switch (b.op()) {
            case "&&" -> {
                Object l = eval(b.left());
                return Values.truthy(l) ? eval(b.right()) : l;
            }
            case "||" -> {
                Object l = eval(b.left());
                return Values.truthy(l) ? l : eval(b.right());
            }
            case "??" -> {
                Object l = eval(b.left());
                return l != null ? l : eval(b.right());
            }
            default -> {
            }
        }
        Object l = eval(b.left());
        Object r = eval(b.right());
        return switch (b.op()) {
            // == is taken as ===: the subset has no coercing equality.
            case "===", "==" -> Values.strictEquals(l, r);
            case "!==", "!=" -> !Values.strictEquals(l, r);
            case "<", "<=", ">", ">=" -> compare(b.op(), l, r, b);
            default -> arith(b.op(), l, r, b);
        };
    }

    private static boolean compare(String op, Object l, Object r, Node at) {
        int c;
        if (l instanceof String a && r instanceof String z) {
            c = a.compareTo(z);
        } else {
            double x = Builtins.num(l, at);
            double y = Builtins.num(r, at);
            if (Double.isNaN(x) || Double.isNaN(y)) {
                return false;
            }
            c = Double.compare(x, y);
        }
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            default -> c >= 0;
        };
    }

    private Object arith(String op, Object l, Object r, Node at) {
        if (op.equals("+") && (l instanceof String || r instanceof String)) {
            String s = Values.display(l) + Values.display(r);
            if (s.length() > Caps.STRING_LENGTH) {
                throw Fault.budget("string length", Caps.STRING_LENGTH, progress());
            }
            return s;
        }
        double x = Builtins.num(l, at);
        double y = Builtins.num(r, at);
        return switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> x / y;
            case "%" -> x % y;
            default -> throw Fault.bad(at, "unknown operator " + op);
        };
    }

    // --- serialisation ----------------------------------------------------------------------------

    /** The frame stack as JSON. One encoder spans every frame, so shared arrays stay shared. */
    JsonObject toJson() {
        Values.Encoder enc = new Values.Encoder();
        JsonArray fs = new JsonArray();
        for (Frame f : frames) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", f.kind.name());
            o.addProperty("node", f.node);
            o.addProperty("ip", f.ip);
            JsonObject vars = new JsonObject();
            f.vars.forEach((k, v) -> vars.add(k, enc.encode(v)));
            o.add("vars", vars);
            if (!f.consts.isEmpty()) {
                JsonArray cs = new JsonArray();
                f.consts.forEach(cs::add);
                o.add("consts", cs);
            }
            if (f.items != null) {
                o.add("items", enc.encode(f.items));
                o.addProperty("index", f.index);
            }
            if (f.entered) {
                o.addProperty("entered", true);
            }
            if (f.pending) {
                o.addProperty("pending", true);
            }
            fs.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("steps", steps);
        root.add("frames", fs);
        return root;
    }

    @SuppressWarnings("unchecked")
    static Machine fromJson(Program program, JsonObject root) {
        Machine m = new Machine(program, root.get("steps").getAsLong());
        Values.Decoder dec = new Values.Decoder();
        for (JsonElement e : root.getAsJsonArray("frames")) {
            JsonObject o = e.getAsJsonObject();
            Frame f = new Frame(Frame.Kind.valueOf(o.get("kind").getAsString()), o.get("node").getAsInt());
            program.stmt(f.node);
            f.ip = o.get("ip").getAsInt();
            o.getAsJsonObject("vars").entrySet().forEach(v -> f.vars.put(v.getKey(), dec.decode(v.getValue())));
            if (o.has("consts")) {
                o.getAsJsonArray("consts").forEach(c -> f.consts.add(c.getAsString()));
            }
            if (o.has("items")) {
                f.items = (List<Object>) dec.decode(o.get("items"));
                f.index = o.get("index").getAsInt();
            }
            f.entered = o.has("entered");
            f.pending = o.has("pending");
            m.frames.add(f);
        }
        return m;
    }
}
