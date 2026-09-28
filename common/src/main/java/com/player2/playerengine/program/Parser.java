package com.player2.playerengine.program;

import com.player2.playerengine.program.Ast.*;
import com.player2.playerengine.program.Lexer.Kind;
import com.player2.playerengine.program.Lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recursive descent over the subset (§6.1). Stops at the first syntax error; syntax outside the
 * subset gets "not supported; write it as …" (§6.7 step 1). Semantic rules (names, the
 * statement-only call rule, profiles) are the {@link Linter}'s.
 */
final class Parser {
    /** Syntax the subset leaves out, with the rewrite the model is pointed at. */
    private static final Map<String, String> UNSUPPORTED = Map.ofEntries(
            Map.entry("var", "write it as let"),
            Map.entry("new", "use object literals and the built-ins"),
            Map.entry("this", "pass values as function arguments"),
            Map.entry("class", "use plain objects and top-level functions"),
            Map.entry("async", "calls to api.* already wait for their result"),
            Map.entry("await", "calls to api.* already wait for their result"),
            Map.entry("yield", "calls to api.* already wait for their result"),
            Map.entry("import", "everything is already in scope"),
            Map.entry("export", "everything is already in scope"),
            Map.entry("switch", "write it as if / else if"),
            Map.entry("case", "write it as if / else if"),
            Map.entry("default", "write it as if / else if"),
            Map.entry("do", "write it as while (...) { }"),
            Map.entry("typeof", "compare against a value instead"),
            Map.entry("instanceof", "compare against a value instead"),
            Map.entry("delete", "build a new object instead"),
            Map.entry("void", "leave it out"),
            Map.entry("with", "name the object each time"),
            Map.entry("debugger", "leave it out"),
            Map.entry("super", "use plain objects and top-level functions"),
            Map.entry("finally", "write the cleanup after the try / catch"),
            Map.entry("in", "write it as for (const x of arr) or arr.includes(x)"));

    private final List<Token> toks;
    private int p;
    private int nextId;
    private int depth;

    private Parser(List<Token> toks) {
        this.toks = toks;
    }

    /** The parsed program, or a {@link ProgramError.Failure} at the first syntax error. */
    static Program parse(String source) {
        if (source.length() > Caps.SOURCE_BYTES
                || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Caps.SOURCE_BYTES) {
            throw new ProgramError.Failure(ProgramError.budget(1, 1,
                    "the program is longer than " + Caps.SOURCE_BYTES + " bytes; split the job"));
        }
        Parser ps = new Parser(Lexer.lex(source));
        int rootId = ps.nextId++;
        List<Stmt> body = new ArrayList<>();
        while (ps.peek().kind() != Kind.EOF) {
            body.add(ps.statement());
        }
        return Program.of(source, new Block(rootId, body, 1, 1));
    }

    // --- tokens -----------------------------------------------------------------------------------

    private Token peek() {
        return toks.get(p);
    }

    private Token peek(int ahead) {
        return toks.get(Math.min(p + ahead, toks.size() - 1));
    }

    private Token take() {
        Token t = toks.get(p);
        if (t.kind() != Kind.EOF) {
            p++;
        }
        return t;
    }

    private boolean accept(String s) {
        if (peek().is(s)) {
            p++;
            return true;
        }
        return false;
    }

    private Token expect(String s, String what) {
        Token t = peek();
        if (!t.is(s)) {
            throw error(t, "expected " + s + " " + what + ", found " + describe(t));
        }
        return take();
    }

    private String ident(String what) {
        Token t = peek();
        if (t.kind() != Kind.IDENT) {
            if (t.kind() == Kind.KEYWORD && UNSUPPORTED.containsKey(t.text())) {
                throw unsupported(t);
            }
            if (t.is("{") || t.is("[")) {
                throw error(t, "destructuring is not supported; write one let per value");
            }
            throw error(t, "expected " + what + ", found " + describe(t));
        }
        return take().text();
    }

    private static String describe(Token t) {
        return t.kind() == Kind.EOF ? "the end of the program" : "'" + t.text() + "'";
    }

    private static ProgramError.Failure error(Token t, String msg) {
        return new ProgramError.Failure(ProgramError.bad(t.line(), t.col(), msg));
    }

    private static ProgramError.Failure unsupported(Token t) {
        return error(t, "'" + t.text() + "' is not supported; " + UNSUPPORTED.get(t.text()));
    }

    private void enter(Token at) {
        if (++depth > Caps.AST_NESTING) {
            throw new ProgramError.Failure(ProgramError.budget(at.line(), at.col(),
                    "nesting deeper than " + Caps.AST_NESTING + "; flatten it into more statements"));
        }
    }

    /** A statement ends at ';', at '}', at the end, or at a line break before the next token. */
    private void endStatement() {
        if (accept(";")) {
            return;
        }
        Token next = peek();
        if (next.is("}") || next.kind() == Kind.EOF || (p > 0 && next.line() > toks.get(p - 1).line())) {
            return;
        }
        throw error(next, "expected ; before " + describe(next));
    }

    // --- statements -------------------------------------------------------------------------------

    private Stmt statement() {
        Token t = peek();
        enter(t);
        try {
            return statementBody(t);
        } finally {
            depth--;
        }
    }

    private Stmt statementBody(Token t) {
        if (t.kind() == Kind.KEYWORD) {
            switch (t.text()) {
                case "let", "const" -> {
                    Let l = let();
                    endStatement();
                    return l;
                }
                case "if" -> {
                    return ifStatement();
                }
                case "for" -> {
                    return forStatement();
                }
                case "while" -> {
                    int id = nextId++;
                    take();
                    expect("(", "after while");
                    Expr test = expression();
                    expect(")", "to close the while condition");
                    return new While(id, test, body(), t.line(), t.col());
                }
                case "function" -> {
                    int id = nextId++;
                    take();
                    String name = ident("a function name");
                    expect("(", "after the function name");
                    List<String> params = new ArrayList<>();
                    if (!peek().is(")")) {
                        do {
                            if (peek().is("...")) {
                                throw error(peek(), "rest parameters are not supported; pass an array");
                            }
                            params.add(ident("a parameter name"));
                            if (peek().is("=")) {
                                throw error(peek(), "default parameters are not supported; pass every argument");
                            }
                        } while (accept(","));
                    }
                    expect(")", "to close the parameters");
                    return new Func(id, name, params, block(), t.line(), t.col());
                }
                case "return" -> {
                    int id = nextId++;
                    take();
                    Expr v = peek().is(";") || peek().is("}") || peek().kind() == Kind.EOF ? null : expression();
                    endStatement();
                    return new Return(id, v, t.line(), t.col());
                }
                case "break" -> {
                    take();
                    endStatement();
                    return new Break(nextId++, t.line(), t.col());
                }
                case "continue" -> {
                    take();
                    endStatement();
                    return new Continue(nextId++, t.line(), t.col());
                }
                case "throw" -> {
                    int id = nextId++;
                    take();
                    Expr v = expression();
                    endStatement();
                    return new Throw(id, v, t.line(), t.col());
                }
                case "try" -> {
                    int id = nextId++;
                    take();
                    Block body = block();
                    if (peek().is("finally")) {
                        throw unsupported(peek());
                    }
                    if (!peek().is("catch")) {
                        throw error(peek(), "try needs a catch");
                    }
                    take();
                    String name = null;
                    if (accept("(")) {
                        name = ident("the caught error's name");
                        expect(")", "to close the catch");
                    }
                    Block handler = block();
                    if (peek().is("finally")) {
                        throw unsupported(peek());
                    }
                    return new Try(id, body, name, handler, t.line(), t.col());
                }
                default -> {
                    if (UNSUPPORTED.containsKey(t.text())) {
                        throw unsupported(t);
                    }
                }
            }
        }
        if (t.is("{")) {
            return block();
        }
        if (t.is(";")) {
            throw error(t, "empty statement; remove the extra ';'");
        }
        return simpleStatement(true);
    }

    private Let let() {
        Token t = take();
        int id = nextId++;
        boolean isConst = t.text().equals("const");
        String name = ident("a variable name");
        Expr init = null;
        if (accept("=")) {
            init = expression();
        } else if (isConst) {
            throw error(peek(), "const " + name + " needs a value");
        }
        if (peek().is(",")) {
            throw error(peek(), "declare one variable per let");
        }
        return new Let(id, isConst, name, init, t.line(), t.col());
    }

    /** An assignment, {@code x++}, or an expression statement. */
    private Stmt simpleStatement(boolean terminated) {
        Token t = peek();
        int id = nextId++;
        if (t.is("++") || t.is("--")) {
            take();
            Expr target = postfix();
            if (terminated) {
                endStatement();
            }
            return new Assign(id, target, t.text(), null, t.line(), t.col());
        }
        Expr e = expression();
        Token op = peek();
        Stmt s;
        if (op.is("=") || op.is("+=") || op.is("-=") || op.is("*=") || op.is("/=") || op.is("%=")) {
            take();
            s = new Assign(id, e, op.text(), expression(), t.line(), t.col());
        } else if (op.is("++") || op.is("--")) {
            take();
            s = new Assign(id, e, op.text(), null, t.line(), t.col());
        } else if (op.is("**=") || op.is("&&=") || op.is("||=") || op.is("??=")) {
            throw error(op, "'" + op.text() + "' is not supported; write it as x = x " + op.text().substring(0,
                    op.text().length() - 1) + " y");
        } else {
            s = new ExprStmt(id, e, t.line(), t.col());
        }
        if (s instanceof Assign a && !(a.target() instanceof Ident || a.target() instanceof Member
                || a.target() instanceof Index)) {
            throw error(t, "only a variable, a field or an element can be assigned");
        }
        if (terminated) {
            endStatement();
        }
        return s;
    }

    private Stmt ifStatement() {
        Token t = take();
        int id = nextId++;
        expect("(", "after if");
        Expr test = expression();
        expect(")", "to close the if condition");
        Block then = body();
        Block otherwise = null;
        if (accept("else")) {
            if (peek().is("if")) {
                Token at = peek();
                int bid = nextId++;
                enter(at);
                try {
                    otherwise = new Block(bid, List.of(ifStatement()), at.line(), at.col());
                } finally {
                    depth--;
                }
            } else {
                otherwise = body();
            }
        }
        return new If(id, test, then, otherwise, t.line(), t.col());
    }

    private Stmt forStatement() {
        Token t = take();
        int id = nextId++;
        expect("(", "after for");
        Token decl = peek();
        if (!(decl.is("let") || decl.is("const"))) {
            if (decl.is("var")) {
                throw unsupported(decl);
            }
            throw error(decl, "write the loop as for (const x of arr) or for (let i = 0; i < N; i++)");
        }
        if (peek(2).is("of")) {
            take();
            String name = ident("the loop variable");
            take();
            Expr iterable = expression();
            expect(")", "to close the for");
            return new ForOf(id, decl.is("const"), name, iterable, body(), t.line(), t.col());
        }
        if (peek(2).is("in")) {
            // for (const k in obj) is a loop over Object.keys(obj): an object's field names in order,
            // an array's indices.
            take();
            Token nameTok = peek();
            String name = ident("the loop variable");
            take();
            Expr object = expression();
            expect(")", "to close the for");
            Expr keys = new Call(new Ident("Object.keys", nameTok.line(), nameTok.col()), List.of(object),
                    nameTok.line(), nameTok.col());
            return new ForOf(id, decl.is("const"), name, keys, body(), t.line(), t.col());
        }
        Let init = let();
        if (init.init() == null) {
            throw error(decl, "the loop variable needs a start value");
        }
        expect(";", "after the loop's start");
        Expr test = expression();
        expect(";", "after the loop condition");
        Stmt update = simpleStatement(false);
        if (!(update instanceof Assign a)) {
            throw error(decl, "the loop update must be i++, i--, i += k or i = ...");
        }
        expect(")", "to close the for");
        return new ForI(id, init, test, a, body(), t.line(), t.col());
    }

    /** A loop or branch body; a single statement is wrapped as a block. */
    private Block body() {
        if (peek().is("{")) {
            return block();
        }
        Token at = peek();
        int id = nextId++;
        return new Block(id, List.of(statement()), at.line(), at.col());
    }

    private Block block() {
        Token open = expect("{", "to open a block");
        int id = nextId++;
        enter(open);
        try {
            List<Stmt> body = new ArrayList<>();
            while (!peek().is("}")) {
                if (peek().kind() == Kind.EOF) {
                    throw error(open, "this { is never closed");
                }
                body.add(statement());
            }
            take();
            return new Block(id, body, open.line(), open.col());
        } finally {
            depth--;
        }
    }

    // --- expressions ------------------------------------------------------------------------------

    private Expr expression() {
        Token t = peek();
        enter(t);
        try {
            return conditional();
        } finally {
            depth--;
        }
    }

    private Expr conditional() {
        Expr test = binary(0);
        if (peek().is("?")) {
            Token q = take();
            Expr then = expression();
            expect(":", "in the ?: expression");
            Expr otherwise = expression();
            return new Cond(test, then, otherwise, q.line(), q.col());
        }
        if (peek().is("=>")) {
            throw error(peek(), "arrow functions are not supported; write a top-level function");
        }
        return test;
    }

    private static final List<List<String>> LEVELS = List.of(
            List.of("??"), List.of("||"), List.of("&&"),
            List.of("===", "!==", "==", "!="),
            List.of("<", "<=", ">", ">="),
            List.of("+", "-"),
            List.of("*", "/", "%"));

    private Expr binary(int level) {
        if (level == LEVELS.size()) {
            return unary();
        }
        Expr left = binary(level + 1);
        while (true) {
            Token t = peek();
            if (t.kind() != Kind.PUNCT || !LEVELS.get(level).contains(t.text())) {
                if (t.is("**")) {
                    throw error(t, "'**' is not supported; write it as x * x");
                }
                if (t.is("&") || t.is("|") || t.is("^")) {
                    throw error(t, "bitwise operators are not supported; use && and ||");
                }
                if (t.is("in") || t.is("instanceof")) {
                    throw unsupported(t);
                }
                return left;
            }
            take();
            Expr right = binary(level + 1);
            left = new Binary(t.text(), left, right, t.line(), t.col());
        }
    }

    private Expr unary() {
        Token t = peek();
        if (t.is("!") || t.is("-") || t.is("+")) {
            take();
            enter(t);
            try {
                return new Unary(t.text(), unary(), t.line(), t.col());
            } finally {
                depth--;
            }
        }
        if (t.is("~")) {
            throw error(t, "bitwise operators are not supported");
        }
        if (t.is("++") || t.is("--")) {
            throw error(t, "'" + t.text() + "' only works as its own statement: x" + t.text() + ";");
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            Token t = peek();
            if (t.is(".")) {
                take();
                Token name = peek();
                if (name.kind() != Kind.IDENT && name.kind() != Kind.KEYWORD) {
                    throw error(name, "expected a field name after '.'");
                }
                take();
                if (e instanceof Ident ns && Builtins.NAMESPACES.contains(ns.name())) {
                    // Object.keys, JSON.stringify, Math.floor: one built-in name each.
                    e = new Ident(ns.name() + "." + name.text(), ns.line(), ns.col());
                } else {
                    e = new Member(e, name.text(), t.line(), t.col());
                }
            } else if (t.is("[")) {
                take();
                Expr idx = expression();
                expect("]", "to close the index");
                e = new Index(e, idx, t.line(), t.col());
            } else if (t.is("(")) {
                take();
                List<Expr> args = new ArrayList<>();
                if (!peek().is(")")) {
                    do {
                        if (peek().is("...")) {
                            throw error(peek(), "spread is not supported; pass the values one by one");
                        }
                        args.add(expression());
                    } while (accept(","));
                }
                expect(")", "to close the call");
                e = new Call(e, args, t.line(), t.col());
            } else if (t.is("?.")) {
                throw error(t, "'?.' is not supported; check the value with if first");
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        Token t = take();
        switch (t.kind()) {
            case NUMBER -> {
                return new Num(t.number(), t.line(), t.col());
            }
            case STRING -> {
                return new Str(t.text(), t.line(), t.col());
            }
            case IDENT -> {
                return new Ident(t.text(), t.line(), t.col());
            }
            case KEYWORD -> {
                switch (t.text()) {
                    case "true", "false" -> {
                        return new Bool(t.text().equals("true"), t.line(), t.col());
                    }
                    case "null", "undefined" -> {
                        return new Null(t.line(), t.col());
                    }
                    case "function" -> throw error(t, "function expressions are not supported; "
                            + "write a top-level function");
                    default -> {
                        if (UNSUPPORTED.containsKey(t.text())) {
                            throw unsupported(t);
                        }
                        throw error(t, "unexpected '" + t.text() + "'");
                    }
                }
            }
            case EOF -> throw error(t, "the program ends in the middle of an expression");
            default -> {
            }
        }
        if (t.is("(")) {
            Expr e = expression();
            expect(")", "to close the parenthesis");
            return e;
        }
        if (t.is("[")) {
            List<Expr> items = new ArrayList<>();
            if (!peek().is("]")) {
                do {
                    if (peek().is("]")) {
                        break;
                    }
                    if (peek().is("...")) {
                        throw error(peek(), "spread is not supported; use push or slice");
                    }
                    items.add(expression());
                } while (accept(","));
            }
            expect("]", "to close the array");
            return new ArrayLit(items, t.line(), t.col());
        }
        if (t.is("{")) {
            List<String> keys = new ArrayList<>();
            List<Expr> computed = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            if (!peek().is("}")) {
                do {
                    if (peek().is("}")) {
                        break;
                    }
                    Token k = take();
                    Expr key = null;
                    if (k.kind() == Kind.IDENT || k.kind() == Kind.STRING || k.kind() == Kind.KEYWORD) {
                        keys.add(k.text());
                    } else if (k.kind() == Kind.NUMBER) {
                        keys.add(Values.numberText(k.number()));
                    } else if (k.is("...")) {
                        throw error(k, "spread is not supported; copy the fields one by one");
                    } else if (k.is("[")) {
                        key = expression();
                        expect("]", "to close the computed key");
                        keys.add("");
                        if (!peek().is(":")) {
                            throw error(peek(), "expected ':' after the computed key");
                        }
                    } else {
                        throw error(k, "expected a field name, found " + describe(k));
                    }
                    computed.add(key);
                    if (accept(":")) {
                        values.add(expression());
                    } else if (k.kind() == Kind.IDENT && (peek().is(",") || peek().is("}"))) {
                        values.add(new Ident(k.text(), k.line(), k.col()));
                    } else if (peek().is("(")) {
                        throw error(k, "methods are not supported; write a top-level function");
                    } else {
                        throw error(peek(), "expected ':' after the field name");
                    }
                } while (accept(","));
            }
            expect("}", "to close the object");
            return new ObjectLit(keys, computed, values, t.line(), t.col());
        }
        if (t.is("/")) {
            throw error(t, "regular expressions are not supported; use includes or ===");
        }
        if (t.is("=>")) {
            throw error(t, "arrow functions are not supported; write a top-level function");
        }
        throw error(t, "unexpected " + describe(t));
    }
}
