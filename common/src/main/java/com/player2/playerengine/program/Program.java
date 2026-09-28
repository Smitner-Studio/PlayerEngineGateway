package com.player2.playerengine.program;

import com.player2.playerengine.program.Ast.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed program: its source, the top-level block, every statement by id, and its top-level
 * functions. Parsing the same source always yields the same ids, which is what lets a serialised
 * frame stack be restored against a re-parse.
 */
final class Program {
    final String source;
    final Block root;
    final Map<String, Func> functions;
    /** The deepest node, counting statements and expressions (§6.3 AST nesting). */
    final int depth;
    private final Map<Integer, Stmt> byId;

    private Program(String source, Block root, Map<Integer, Stmt> byId, Map<String, Func> functions, int depth) {
        this.source = source;
        this.root = root;
        this.byId = byId;
        this.functions = functions;
        this.depth = depth;
    }

    static Program of(String source, Block root) {
        Map<Integer, Stmt> byId = new HashMap<>();
        int depth = index(root, byId, 1);
        Map<String, Func> functions = new LinkedHashMap<>();
        for (Stmt s : root.body()) {
            if (s instanceof Func f) {
                functions.putIfAbsent(f.name(), f);
            }
        }
        return new Program(source, root, Collections.unmodifiableMap(byId), Collections.unmodifiableMap(functions),
                depth);
    }

    Stmt stmt(int id) {
        Stmt s = byId.get(id);
        if (s == null) {
            throw new IllegalStateException("no statement " + id + " in this program");
        }
        return s;
    }

    Block block(int id) {
        if (stmt(id) instanceof Block b) {
            return b;
        }
        throw new IllegalStateException("statement " + id + " is not a block");
    }

    private static int index(Stmt s, Map<Integer, Stmt> byId, int d) {
        byId.put(s.id(), s);
        int max = d;
        for (Stmt c : children(s)) {
            max = Math.max(max, index(c, byId, d + 1));
        }
        for (Expr e : exprs(s)) {
            max = Math.max(max, depth(e, d + 1));
        }
        return max;
    }

    static List<Stmt> children(Stmt s) {
        return switch (s) {
            case Block b -> b.body();
            case If i -> i.otherwise() == null ? List.of(i.then()) : List.of(i.then(), i.otherwise());
            case ForOf f -> List.of(f.body());
            case ForI f -> List.of(f.init(), f.update(), f.body());
            case While w -> List.of(w.body());
            case Func f -> List.of(f.body());
            case Try t -> List.of(t.body(), t.handler());
            default -> List.of();
        };
    }

    static List<Expr> exprs(Stmt s) {
        List<Expr> out = new java.util.ArrayList<>(2);
        switch (s) {
            case Let l -> add(out, l.init());
            case Assign a -> {
                add(out, a.target());
                add(out, a.value());
            }
            case ExprStmt e -> add(out, e.expr());
            case If i -> add(out, i.test());
            case ForOf f -> add(out, f.iterable());
            case ForI f -> add(out, f.test());
            case While w -> add(out, w.test());
            case Return r -> add(out, r.value());
            case Throw t -> add(out, t.value());
            default -> {
            }
        }
        return out;
    }

    private static void add(List<Expr> out, Expr e) {
        if (e != null) {
            out.add(e);
        }
    }

    static List<Expr> children(Expr e) {
        return switch (e) {
            case ArrayLit a -> a.items();
            case ObjectLit o -> {
                List<Expr> l = new java.util.ArrayList<>(o.values().size() * 2);
                for (Expr k : o.computed()) {
                    if (k != null) {
                        l.add(k);
                    }
                }
                l.addAll(o.values());
                yield l;
            }
            case Member m -> List.of(m.object());
            case Index i -> List.of(i.object(), i.index());
            case Call c -> {
                List<Expr> l = new java.util.ArrayList<>(c.args().size() + 1);
                l.add(c.callee());
                l.addAll(c.args());
                yield l;
            }
            case Unary u -> List.of(u.operand());
            case Binary b -> List.of(b.left(), b.right());
            case Cond c -> List.of(c.test(), c.then(), c.otherwise());
            default -> List.of();
        };
    }

    private static int depth(Expr e, int d) {
        int max = d;
        for (Expr c : children(e)) {
            max = Math.max(max, depth(c, d + 1));
        }
        return max;
    }
}
