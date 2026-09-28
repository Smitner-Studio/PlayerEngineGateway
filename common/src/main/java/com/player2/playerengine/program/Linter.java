package com.player2.playerengine.program;

import com.player2.playerengine.program.Ast.*;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The linter (§6.7): parse, names and arity, literal types, registry ids, the statement-only call rule
 * and the loop forms, and the caps a program's text can break. Every finding is reported at once, so
 * one repair call can fix them all. A program runs only when the result is clean.
 */
public final class Linter {
    /** §6.7 profiles. Ada runs {@code full} (R3); {@code restricted} is plans v2, kept as an eval arm. */
    public enum Profile {
        FULL,
        /** No {@code while}, no user functions, no {@code for-of} over a query's result. */
        RESTRICTED
    }

    /**
     * Registry check for a literal item or block id (§6.7 step 4). The seam's coercion backs it at
     * run time; this package cannot reach the registries.
     */
    public interface Ids {
        Ids ANY = (type, id) -> null;

        /** Null when the id names one thing, else why not (an ambiguous id lists its candidates). */
        ActionError check(String type, String id);
    }

    /** The caller's skills, by schema (§7). Stage 5 fills it; until then there are none. */
    public interface Skills {
        Skills NONE = name -> -1;

        /** The skill's parameter count, or -1 when the caller has no such skill. */
        int params(String name);
    }

    public record Result(Program program, List<ProgramError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }

        /** The one repair message: every finding, one per line (§6.7). */
        public String repairMessage() {
            StringBuilder sb = new StringBuilder();
            for (ProgramError e : errors) {
                sb.append(e.toLine()).append('\n');
            }
            return sb.toString().trim();
        }

        /** {@code budget} when a cap was hit, else {@code ambiguous} when an id was, else {@code bad_args}. */
        public ActionError toActionError() {
            FailureCode code = FailureCode.BAD_ARGS;
            for (ProgramError e : errors) {
                if (e.code() == FailureCode.BUDGET) {
                    code = FailureCode.BUDGET;
                } else if (e.code() == FailureCode.AMBIGUOUS && code != FailureCode.BUDGET) {
                    code = FailureCode.AMBIGUOUS;
                }
            }
            List<String> lines = new ArrayList<>();
            errors.forEach(e -> lines.add(e.toLine()));
            return new ActionError(code, "the program does not lint", Map.of("errors", lines));
        }
    }

    private record Var(boolean isConst, boolean fromQuery) {
    }

    private final Profile profile;
    private final ApiTable api;
    private final Ids ids;
    private final Skills skills;
    private final List<ProgramError> errors = new ArrayList<>();
    private final Deque<Map<String, Var>> scopes = new ArrayDeque<>();
    private Map<String, Var> globals = new LinkedHashMap<>();
    private Program program;
    private int loopDepth;
    private boolean inFunction;

    private Linter(Profile profile, ApiTable api, Ids ids, Skills skills) {
        this.profile = profile;
        this.api = api;
        this.ids = ids;
        this.skills = skills;
    }

    public static Result lint(String source, Profile profile) {
        return lint(source, profile, ApiTable.published(), Ids.ANY, Skills.NONE);
    }

    public static Result lint(String source, Profile profile, ApiTable api, Ids ids, Skills skills) {
        Linter l = new Linter(profile, api, ids, skills);
        Program program;
        try {
            program = Parser.parse(source);
        } catch (ProgramError.Failure f) {
            return new Result(null, List.of(f.error));
        } catch (StackOverflowError e) {
            return new Result(null, List.of(ProgramError.budget(1, 1, "the program nests too deeply")));
        }
        l.program = program;
        if (program.depth > Caps.AST_NESTING) {
            l.errors.add(ProgramError.budget(1, 1, "the program nests " + program.depth + " deep, more than "
                    + Caps.AST_NESTING + "; flatten it into more statements"));
            return new Result(program, List.copyOf(l.errors));
        }
        l.checkProgram();
        return new Result(program, List.copyOf(l.errors));
    }

    private void err(Node at, String msg) {
        errors.add(ProgramError.bad(at.line(), at.col(), msg));
    }

    private void budget(Node at, String msg) {
        errors.add(ProgramError.budget(at.line(), at.col(), msg));
    }

    // --- program and statements -------------------------------------------------------------------

    private void checkProgram() {
        Set<String> fnames = new HashSet<>();
        for (Stmt s : program.root.body()) {
            if (s instanceof Func f) {
                if (!fnames.add(f.name())) {
                    err(f, "function " + f.name() + " is declared twice");
                }
                if (reserved(f.name())) {
                    err(f, f.name() + " is a built-in name; pick another");
                }
            }
        }
        scopes.push(globals);
        for (Stmt s : program.root.body()) {
            if (!(s instanceof Func)) {
                stmt(s);
            }
        }
        // Function bodies see the top-level variables, as a module's functions do.
        Map<String, Var> top = globals;
        for (Stmt s : program.root.body()) {
            if (s instanceof Func f) {
                func(f, top);
            }
        }
    }

    private boolean reserved(String name) {
        return name.equals("api") || name.equals("skills") || Builtins.ARITY.containsKey(name);
    }

    private void func(Func f, Map<String, Var> top) {
        if (profile == Profile.RESTRICTED) {
            err(f, "user functions are not in the restricted profile; write the steps out");
        }
        if (f.params().size() > Caps.VARS_PER_FRAME) {
            budget(f, "more than " + Caps.VARS_PER_FRAME + " parameters");
        }
        scopes.clear();
        scopes.push(top);
        Map<String, Var> params = new LinkedHashMap<>();
        for (String p : f.params()) {
            if (params.put(p, new Var(false, false)) != null) {
                err(f, "parameter " + p + " is repeated");
            }
        }
        scopes.push(params);
        boolean was = inFunction;
        int loops = loopDepth;
        inFunction = true;
        loopDepth = 0;
        block(f.body());
        inFunction = was;
        loopDepth = loops;
        scopes.pop();
    }

    private void block(Block b) {
        scopes.push(new LinkedHashMap<>());
        for (Stmt s : b.body()) {
            stmt(s);
        }
        scopes.pop();
    }

    private void declare(Node at, String name, Var v) {
        if (reserved(name)) {
            err(at, name + " is a built-in name; pick another");
        }
        if (program.functions.containsKey(name)) {
            err(at, name + " is already a function");
        }
        Map<String, Var> scope = scopes.peek();
        if (scope.containsKey(name)) {
            err(at, name + " is already declared here");
        }
        scope.put(name, v);
        if (scope.size() > Caps.VARS_PER_FRAME) {
            budget(at, "more than " + Caps.VARS_PER_FRAME + " variables in one block");
        }
    }

    private Var lookup(String name) {
        for (Map<String, Var> s : scopes) {
            Var v = s.get(name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private void stmt(Stmt s) {
        Call statementCall = Ast.statementCall(s);
        switch (s) {
            case Block b -> block(b);
            case Let l -> {
                if (l.init() != null) {
                    expr(l.init(), statementCall);
                }
                declare(l, l.name(), new Var(l.isConst(), producesQuery(l.init())));
            }
            case Assign a -> {
                if (a.target() instanceof Ident id) {
                    Var v = lookup(id.name());
                    if (v == null) {
                        err(id, "unknown name " + id.name() + "; declare it with let first");
                    } else if (v.isConst()) {
                        err(id, id.name() + " is const; declare it with let to change it");
                    } else if (producesQuery(a.value()) && !v.fromQuery()) {
                        replace(id.name(), new Var(false, true));
                    }
                } else {
                    expr(a.target(), null);
                }
                if (a.value() != null) {
                    expr(a.value(), statementCall);
                }
            }
            case ExprStmt e -> {
                if (!(e.expr() instanceof Call)) {
                    err(e, "this statement does nothing; call something or assign the value");
                }
                expr(e.expr(), statementCall);
            }
            case If i -> {
                expr(i.test(), null);
                block(i.then());
                if (i.otherwise() != null) {
                    block(i.otherwise());
                }
            }
            case ForOf f -> {
                expr(f.iterable(), null);
                if (profile == Profile.RESTRICTED && producesQuery(f.iterable())) {
                    err(f, "the restricted profile cannot loop over a query's result; use for with a literal bound");
                }
                scopes.push(new LinkedHashMap<>());
                declare(f, f.name(), new Var(f.isConst(), false));
                loop(f.body());
                scopes.pop();
            }
            case ForI f -> {
                scopes.push(new LinkedHashMap<>());
                stmt(f.init());
                forBound(f);
                expr(f.test(), null);
                stmt(f.update());
                loop(f.body());
                scopes.pop();
            }
            case While w -> {
                if (profile == Profile.RESTRICTED) {
                    err(w, "while is not in the restricted profile; use for with a literal bound");
                }
                expr(w.test(), null);
                loop(w.body());
            }
            case Func f -> err(f, "functions are allowed only at the top level (no closures)");
            case Return r -> {
                if (r.value() != null) {
                    expr(r.value(), statementCall);
                }
            }
            case Break b -> {
                if (loopDepth == 0) {
                    err(b, "break outside a loop");
                }
            }
            case Continue c -> {
                if (loopDepth == 0) {
                    err(c, "continue outside a loop");
                }
            }
            case Throw t -> expr(t.value(), null);
            case Try t -> {
                block(t.body());
                scopes.push(new LinkedHashMap<>());
                if (t.catchName() != null) {
                    declare(t, t.catchName(), new Var(false, false));
                }
                block(t.handler());
                scopes.pop();
            }
        }
    }

    private void replace(String name, Var v) {
        for (Map<String, Var> s : scopes) {
            if (s.containsKey(name)) {
                s.put(name, v);
                return;
            }
        }
    }

    private void loop(Block body) {
        loopDepth++;
        block(body);
        loopDepth--;
    }

    /** {@code i < N}: N a literal, or (full profile) a const (§6.1). */
    private void forBound(ForI f) {
        String var = f.init().name();
        boolean ok = false;
        if (f.test() instanceof Binary b && List.of("<", "<=", ">", ">=").contains(b.op())
                && b.left() instanceof Ident i && i.name().equals(var)) {
            if (b.right() instanceof Num || (b.right() instanceof Unary u && u.operand() instanceof Num)) {
                ok = true;
            } else if (b.right() instanceof Ident n && profile == Profile.FULL) {
                Var v = lookup(n.name());
                ok = v != null && v.isConst();
            }
        }
        if (!ok) {
            err(f, profile == Profile.FULL
                    ? "the for condition must be " + var + " < N with N a number or a const; use while otherwise"
                    : "the restricted profile's for condition must be " + var + " < N with N a number");
        }
        if (!(f.update().target() instanceof Ident t && t.name().equals(var))) {
            err(f.update(), "the loop update must change " + var);
        }
    }

    /** True when {@code e} is a query call, or reads a variable that holds one's result. */
    private boolean producesQuery(Expr e) {
        if (e == null) {
            return false;
        }
        if (e instanceof Call c) {
            String[] t = Ast.callTarget(c);
            if (t != null && t[0].equals("api")) {
                ApiTable.Sig sig = api.get(t[1]);
                return sig != null && sig.query();
            }
        }
        if (e instanceof Ident id) {
            Var v = lookup(id.name());
            return v != null && v.fromQuery();
        }
        for (Expr c : Program.children(e)) {
            if (producesQuery(c)) {
                return true;
            }
        }
        return false;
    }

    // --- expressions ------------------------------------------------------------------------------

    /** @param allowed the one call this statement may make as a whole statement, or null */
    private void expr(Expr e, Call allowed) {
        switch (e) {
            case Str s -> {
                if (s.value().length() > Caps.STRING_LENGTH) {
                    budget(s, "a string longer than " + Caps.STRING_LENGTH + " characters");
                }
            }
            case ArrayLit a -> {
                if (a.items().size() > Caps.ARRAY_LENGTH) {
                    budget(a, "an array longer than " + Caps.ARRAY_LENGTH + " items");
                }
                a.items().forEach(x -> expr(x, null));
            }
            case ObjectLit o -> {
                if (new HashSet<>(o.keys()).size() != o.keys().size()) {
                    err(o, "an object names the same field twice");
                }
                o.values().forEach(x -> expr(x, null));
            }
            case Ident id -> {
                if (id.name().equals("api") || id.name().equals("skills")) {
                    err(id, id.name() + " is only for calls: " + id.name() + ".name(...)");
                } else if (lookup(id.name()) == null) {
                    err(id, program.functions.containsKey(id.name()) || Builtins.ARITY.containsKey(id.name())
                            ? id.name() + " is a function; call it" : "unknown name " + id.name());
                }
            }
            case Member m -> {
                if (m.object() instanceof Ident ns && (ns.name().equals("api") || ns.name().equals("skills"))) {
                    err(m, ns.name() + "." + m.name() + " must be called, as a whole statement");
                } else {
                    expr(m.object(), null);
                }
            }
            case Call c -> call(c, c == allowed);
            default -> Program.children(e).forEach(x -> expr(x, null));
        }
    }

    private void call(Call c, boolean wholeStatement) {
        String[] target = Ast.callTarget(c);
        if (target != null && !target[0].isEmpty()) {
            String name = target[0] + "." + target[1];
            if (!wholeStatement) {
                err(c, name + " may be called only as a whole statement; write let v = " + name
                        + "(...); first and use v here");
            }
            if (target[0].equals("api")) {
                apiCall(c, target[1]);
            } else {
                int n = skills.params(target[1]);
                if (n < 0) {
                    err(c, "no skill named " + target[1]);
                } else if (n != c.args().size()) {
                    err(c, name + " takes " + n + " arguments, not " + c.args().size());
                }
            }
            c.args().forEach(a -> expr(a, null));
            return;
        }
        if (target != null) {
            String name = target[1];
            Func f = program.functions.get(name);
            if (f != null && lookup(name) == null) {
                if (!wholeStatement) {
                    err(c, name + "() may be called only as a whole statement; write let v = " + name
                            + "(...); first and use v here");
                }
                if (profile == Profile.RESTRICTED) {
                    err(c, "user functions are not in the restricted profile");
                }
                if (f.params().size() != c.args().size()) {
                    err(c, name + " takes " + f.params().size() + " arguments, not " + c.args().size());
                }
            } else if (Builtins.ARITY.containsKey(name) && lookup(name) == null) {
                int[] ar = Builtins.ARITY.get(name);
                int n = c.args().size();
                if (n < ar[0] || (ar[1] >= 0 && n > ar[1])) {
                    err(c, name + " takes " + (ar[0] == ar[1] ? String.valueOf(ar[0])
                            : ar[1] < 0 ? ar[0] + " or more" : ar[0] + " to " + ar[1]) + " arguments, not " + n);
                }
            } else {
                err(c, lookup(name) != null ? name + " is not a function" : "unknown function " + name
                        + "; the built-ins are " + String.join(", ", new java.util.TreeSet<>(Builtins.ARITY.keySet())));
            }
            c.args().forEach(a -> expr(a, null));
            return;
        }
        if (c.callee() instanceof Member m && Builtins.METHODS.contains(m.name())) {
            expr(m.object(), null);
            c.args().forEach(a -> expr(a, null));
            return;
        }
        err(c, "only api.*, skills.*, your functions, the built-ins and push, slice and includes can be called");
        c.args().forEach(a -> expr(a, null));
    }

    private void apiCall(Call c, String name) {
        ApiTable.Sig sig = api.get(name);
        if (sig == null) {
            err(c, "api." + name + " does not exist" + suggest(name));
            return;
        }
        int n = c.args().size();
        if (n < sig.requiredArgs() || n > sig.args().size()) {
            List<String> names = new ArrayList<>();
            sig.args().forEach(a -> names.add(a.name() + (a.required() ? "" : "?")));
            err(c, "api." + name + " takes (" + String.join(", ", names) + "), not " + n + " arguments");
        }
        for (int i = 0; i < Math.min(n, sig.args().size()); i++) {
            literalType(sig, sig.args().get(i), c.args().get(i));
        }
    }

    private String suggest(String name) {
        for (ApiTable.Sig s : api.all()) {
            if (s.name().startsWith(name) || name.startsWith(s.name()) || s.name().replace("_", "")
                    .equals(name.replace("_", "").toLowerCase(java.util.Locale.ROOT))) {
                return "; did you mean api." + s.name() + "?";
            }
        }
        return "";
    }

    /** §6.7 steps 3 and 4: a literal argument's type, bounds and registry id. */
    private void literalType(ApiTable.Sig sig, ApiTable.Arg arg, Expr e) {
        String where = "api." + sig.name() + " " + arg.name();
        boolean scalarLiteral = e instanceof Num || e instanceof Str || e instanceof Bool || e instanceof Null;
        if (e instanceof Null && !arg.required()) {
            return;
        }
        switch (arg.type()) {
            case "int", "Count" -> {
                if (e instanceof Num num) {
                    if (num.value() != Math.rint(num.value())) {
                        err(e, where + " must be a whole number");
                    } else if ("int".equals(arg.type()) && arg.max() > 0
                            && (num.value() < arg.min() || num.value() > arg.max())) {
                        err(e, where + " must be " + arg.min() + ".." + arg.max());
                    }
                } else if (scalarLiteral) {
                    err(e, where + " must be a number");
                }
            }
            case "string" -> {
                if (e instanceof Str s) {
                    if (arg.max() > 0 && s.value().length() > arg.max()) {
                        err(e, where + " is longer than " + arg.max() + " characters");
                    }
                } else if (scalarLiteral && !(e instanceof Num)) {
                    err(e, where + " must be text");
                }
            }
            case "ItemId", "BlockId" -> {
                if (e instanceof Str s) {
                    ActionError bad = ids.check(arg.type(), s.value());
                    if (bad != null) {
                        errors.add(new ProgramError(bad.code(), e.line(), e.col(), where + ": " + bad.message()));
                    }
                } else if (scalarLiteral || e instanceof ArrayLit || e instanceof ObjectLit) {
                    err(e, where + " must be an id string like \"cobblestone\"");
                }
            }
            case "Pos", "Box", "Container" -> {
                if (scalarLiteral || e instanceof ArrayLit) {
                    err(e, where + " must be a " + arg.type() + ("Box".equals(arg.type())
                            ? "; build one with box(p1, p2) or box_rel(...)" : "; build one with pos(x, y, z)"));
                } else if (e instanceof ObjectLit o && !"Box".equals(arg.type())
                        && !new HashSet<>(o.keys()).containsAll(List.of("x", "y", "z"))) {
                    err(e, where + " needs x, y and z");
                }
            }
            case "QueryName" -> {
                if (e instanceof Str s) {
                    ApiTable.Sig q = api.get(s.value());
                    if (q == null || !q.query()) {
                        err(e, where + ": " + s.value() + " is not a query");
                    }
                } else if (scalarLiteral) {
                    err(e, where + " must be a query name in quotes");
                }
            }
            default -> {
            }
        }
    }
}
