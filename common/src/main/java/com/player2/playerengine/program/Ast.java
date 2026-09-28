package com.player2.playerengine.program;

import java.util.List;

/**
 * The syntax tree of the program subset (§6.1). Statements carry a preorder id, which is how a
 * serialised frame names the statement list or loop it is in: the source is re-parsed on restore and
 * the ids come out the same.
 */
final class Ast {
    private Ast() {
    }

    interface Node {
        int line();

        int col();
    }

    // --- expressions: pure, evaluated on the Java stack, never yield ------------------------------

    sealed interface Expr extends Node permits Num, Str, Bool, Null, Ident, ArrayLit, ObjectLit, Member, Index,
            Call, Unary, Binary, Cond {
    }

    record Num(double value, int line, int col) implements Expr {
    }

    record Str(String value, int line, int col) implements Expr {
    }

    record Bool(boolean value, int line, int col) implements Expr {
    }

    record Null(int line, int col) implements Expr {
    }

    record Ident(String name, int line, int col) implements Expr {
    }

    record ArrayLit(List<Expr> items, int line, int col) implements Expr {
    }

    /**
     * {@code computed} has one entry per field: null for a plain key ({@code keys} holds it), else the
     * expression of a computed key {@code [k]: v} (its {@code keys} entry is empty).
     */
    record ObjectLit(List<String> keys, List<Expr> computed, List<Expr> values, int line, int col) implements Expr {
    }

    record Member(Expr object, String name, int line, int col) implements Expr {
    }

    record Index(Expr object, Expr index, int line, int col) implements Expr {
    }

    record Call(Expr callee, List<Expr> args, int line, int col) implements Expr {
    }

    record Unary(String op, Expr operand, int line, int col) implements Expr {
    }

    record Binary(String op, Expr left, Expr right, int line, int col) implements Expr {
    }

    record Cond(Expr test, Expr then, Expr otherwise, int line, int col) implements Expr {
    }

    // --- statements: the interpreter yields only between these -------------------------------------

    sealed interface Stmt extends Node permits Block, Let, Assign, ExprStmt, If, ForOf, ForI, While, Func, Return,
            Break, Continue, Throw, Try {
        int id();
    }

    record Block(int id, List<Stmt> body, int line, int col) implements Stmt {
    }

    record Let(int id, boolean isConst, String name, Expr init, int line, int col) implements Stmt {
    }

    /** {@code op} is {@code =}, a compound {@code +=}-style operator, or {@code ++}/{@code --} (value null). */
    record Assign(int id, Expr target, String op, Expr value, int line, int col) implements Stmt {
    }

    record ExprStmt(int id, Expr expr, int line, int col) implements Stmt {
    }

    record If(int id, Expr test, Block then, Block otherwise, int line, int col) implements Stmt {
    }

    record ForOf(int id, boolean isConst, String name, Expr iterable, Block body, int line, int col) implements Stmt {
    }

    record ForI(int id, Let init, Expr test, Assign update, Block body, int line, int col) implements Stmt {
    }

    record While(int id, Expr test, Block body, int line, int col) implements Stmt {
    }

    record Func(int id, String name, List<String> params, Block body, int line, int col) implements Stmt {
    }

    record Return(int id, Expr value, int line, int col) implements Stmt {
    }

    record Break(int id, int line, int col) implements Stmt {
    }

    record Continue(int id, int line, int col) implements Stmt {
    }

    record Throw(int id, Expr value, int line, int col) implements Stmt {
    }

    record Try(int id, Block body, String catchName, Block handler, int line, int col) implements Stmt {
    }

    /** The call a whole statement makes, or null when the statement makes none (§6.1). */
    static Call statementCall(Stmt s) {
        Expr e = switch (s) {
            case ExprStmt x -> x.expr();
            case Let x -> x.init();
            case Assign x -> "=".equals(x.op()) ? x.value() : null;
            case Return x -> x.value();
            default -> null;
        };
        return e instanceof Call c ? c : null;
    }

    /** {@code api.name}, {@code skills.name}, or a user function {@code name}; null for a built-in. */
    static String[] callTarget(Call c) {
        if (c.callee() instanceof Member m && m.object() instanceof Ident ns
                && (ns.name().equals("api") || ns.name().equals("skills"))) {
            return new String[] {ns.name(), m.name()};
        }
        if (c.callee() instanceof Ident id) {
            return new String[] {"", id.name()};
        }
        return null;
    }
}
