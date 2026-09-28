package com.player2.playerengine.program;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The interpreter (stage 3) over a stub world: parser and linter tables, the yield and restore round
 * trip, every cap, reconcile after a restart, the job lifecycle and the completion template. Pure: it
 * needs no Minecraft bootstrap.
 */
public final class ProgramSelfTest {
    private static int checks;

    private ProgramSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        checks = 0;
        signatureTableIsRead();
        parserTable();
        lintTable();
        callInsideAnExpressionFailsTheLint();
        restrictedProfile();
        programRunsAndYields();
        yieldAndRestoreRoundTrip();
        sharingSurvivesRestore();
        everyCapFailsWithBudget();
        reconcileGivesOnce();
        skippingReconcileGivesTwice();
        rolledBackDoneIsRerunOnce();
        ambiguousCallAsksTheInitiator();
        interruptedIdempotentCallReruns();
        lifecycleAndShelving();
        boardPersistsAtomically();
        completionTemplate();
        System.out.println("program self-test: " + checks + " checks passed");
    }

    // --- tables -----------------------------------------------------------------------------------

    private static void signatureTableIsRead() {
        ApiTable t = ApiTable.published();
        require(t.all().size() == 29, "the published table has 29 signatures: " + t.all().size());
        require(!t.get("give_owner").idempotent() && !t.get("give_owner").query(), "give_owner is a non-idempotent primitive");
        require(t.get("count").query() && t.get("count").args().get(0).type().equals("ItemId"), "count(item: ItemId) is a query");
        require(t.get("find_blocks").args().get(1).max() == 32, "find_blocks radius is bounded at 32");
    }

    private static void parserTable() {
        String[] ok = {
                "let a = 1;",
                "const b = [1, 2, 3]; let c = {x: 1, 'y': 2, z};",
                "let a = 1\nlet b = a + 2",
                "if (true) { } else if (false) { } else { }",
                "for (const x of [1,2]) { continue; }",
                "for (let i = 0; i < 3; i++) { break; }",
                "while (false) { }",
                "function f(a, b) { return a + b; }",
                "try { throw 'x'; } catch (e) { }",
                "try { } catch { }",
                "let s = 'a\\'b' + \"c\";",
                "let t = true ? 1 : 2; let n = null ?? 3; let u = undefined;",
                "// comment\n/* block */ let z = -1 * (2 + 3) % 4;",
        };
        for (String s : ok) {
            try {
                Parser.parse(s);
                require(true, "parses: " + s);
            } catch (ProgramError.Failure f) {
                throw new AssertionError("should parse: " + s + " -> " + f.error);
            }
        }
        String[][] bad = {
                {"var a = 1;", "'var' is not supported; write it as let"},
                {"let f = (x) => x;", "arrow functions are not supported"},
                {"let s = `a${b}`;", "template literals are not supported"},
                {"let o = new Map();", "'new' is not supported"},
                {"let r = /a+/;", "regular expressions are not supported"},
                {"for (const k in o) { }", "'in' is not supported"},
                {"switch (a) { }", "'switch' is not supported"},
                {"try { } finally { }", "'finally' is not supported"},
                {"let [a, b] = c;", "destructuring is not supported"},
                {"let x = 2 ** 3;", "'**' is not supported"},
                {"let x = a?.b;", "'?.' is not supported"},
                {"let a = 1, b = 2;", "declare one variable per let"},
                {"async function f() { }", "'async' is not supported"},
                {"let x = f(...xs);", "spread is not supported"},
                {"let x = (1 + 2;", "expected )"},
                {"let x = 'abc", "unterminated string"},
                {"x = y++ + 1;", "expected ; before '++'"},
                {"f() = 1;", "only a variable"},
        };
        for (String[] b : bad) {
            try {
                Parser.parse(b[0]);
                throw new AssertionError("should not parse: " + b[0]);
            } catch (ProgramError.Failure f) {
                require(f.error.message().contains(b[1]) && f.error.line() >= 1,
                        b[0] + " -> expected '" + b[1] + "', got " + f.error);
            }
        }
        ProgramError at = null;
        try {
            Parser.parse("let a = 1;\n  var b = 2;");
        } catch (ProgramError.Failure f) {
            at = f.error;
        }
        require(at != null && at.line() == 2 && at.col() == 3, "errors carry line and column: " + at);
    }

    private static final Linter.Ids IDS = (type, id) -> id.equals("iron")
            ? new ActionError(FailureCode.AMBIGUOUS, "iron is ambiguous: iron_ingot, iron_ore, raw_iron", Map.of())
            : null;

    private static Linter.Result lint(String src) {
        return Linter.lint(src, Linter.Profile.FULL, ApiTable.published(), IDS, Linter.Skills.NONE);
    }

    private static void lintTable() {
        String[] ok = {
                "let p = api.owner_pos(); let f = api.owner_facing(); api.excavate(box_rel(p, 7, 3, 7, f));"
                        + " api.pickup_drops(8);",
                "let n = api.count('cobblestone'); if (n > 10) { api.store(null, {cobblestone: n}); }",
                "const N = 4; for (let i = 0; i < N; i++) { api.wait(20); }",
                "let xs = api.find_blocks('oak_log', 16, 8); for (const x of xs) { api.goto(x); }",
                "function dig(b) { api.excavate(b); return 1; } let r = dig(box(pos(0,64,0), pos(2,66,2)));",
                "let v = 0; v = api.count('dirt'); let a = [1]; a.push(v); let s = a.slice(0, 1);",
                "try { api.give_owner('diamond', 3); } catch (e) { api.say('no: ' + e.code); }",
                "let d = dist(pos(0,0,0), pos(3,4,0)); assert(d === 5, 'pythagoras'); let m = max(1, min(2, abs(-3)));",
                "function f() { return api.position(); } let q = f();",
        };
        for (String s : ok) {
            Linter.Result r = lint(s);
            require(r.ok(), "lints clean: " + s + " -> " + r.repairMessage());
        }
        String[][] bad = {
                {"api.dig(1);", "api.dig does not exist"},
                {"api.excavat(b);", "did you mean api.excavate"},
                {"api.give_owner('iron_ingot');", "takes (item, n), not 1 arguments"},
                {"api.find_blocks('stone', 64, 8);", "radius must be 1..32"},
                {"api.goto(5);", "api.goto p must be a Pos"},
                {"api.goto({x: 1, y: 2});", "needs x, y and z"},
                {"api.count(3);", "must be an id string"},
                {"api.count('iron');", "iron is ambiguous"},
                {"api.say('" + "x".repeat(201) + "');", "longer than 200"},
                {"api.wait_until('dig', {}, 1, 10);", "dig is not a query"},
                {"let a = 1; let a = 2;", "already declared"},
                {"const a = 1; a = 2;", "a is const"},
                {"b = 1;", "unknown name b"},
                {"let x = y + 1;", "unknown name y"},
                {"let x = 1; for (let i = 0; i < x; i++) { }", "N a number or a const"},
                {"if (true) { function g() { } }", "only at the top level"},
                {"break;", "break outside a loop"},
                {"let x = foo(1);", "unknown function foo"},
                {"let x = min();", "min takes 1 or more arguments"},
                {"skills.dig_room(1);", "no skill named dig_room"},
                {"let a = api;", "api is only for calls"},
                {"1 + 2;", "this statement does nothing"},
                {"let x = api.count;", "must be called, as a whole statement"},
                {"let pos = 1;", "pos is a built-in name"},
        };
        for (String[] b : bad) {
            Linter.Result r = lint(b[0]);
            require(!r.ok() && r.repairMessage().contains(b[1]),
                    b[0] + " -> expected '" + b[1] + "', got: " + r.repairMessage());
        }
        Linter.Result many = lint("api.dig(1);\nlet x = y;\napi.count(3);");
        require(many.errors().size() == 3 && many.repairMessage().split("\n").length == 3,
                "every finding in one repair message: " + many.repairMessage());
        require(lint("api.count('iron');").toActionError().code() == FailureCode.AMBIGUOUS,
                "an ambiguous id is ambiguous, not bad_args");
    }

    /** Red witness for the statement-only call rule (§6.1): each of these must fail the lint. */
    private static void callInsideAnExpressionFailsTheLint() {
        String[] inExpr = {
                "let n = 1 + api.count('dirt');",
                "if (api.count('dirt') > 3) { }",
                "while (api.count('dirt') > 0) { api.wait(1); }",
                "let p = offset(api.position(), 0, 1, 0);",
                "api.goto(api.owner_pos());",
                "let xs = [api.position()];",
                "function f() { return 1; } let n = f() + 1;",
                "for (const x of api.find_blocks('stone', 8, 4)) { }",
                "let b = true && api.count('dirt');",
        };
        for (String s : inExpr) {
            Linter.Result r = lint(s);
            require(!r.ok() && r.repairMessage().contains("only as a whole statement"),
                    "a call inside an expression fails the lint: " + s + " -> " + r.repairMessage());
        }
        for (String s : new String[] {"api.wait(1);", "let v = api.count('dirt');", "let v = 0; v = api.count('dirt');",
                "function f() { return api.position(); }"}) {
            require(lint(s).ok(), "the four whole-statement forms lint clean: " + s);
        }
    }

    private static void restrictedProfile() {
        String[][] cases = {
                {"while (false) { }", "while is not in the restricted profile"},
                {"function f() { }", "user functions are not in the restricted profile"},
                {"let xs = api.find_blocks('stone', 8, 4); for (const x of xs) { }", "cannot loop over a query"},
                {"const N = 3; for (let i = 0; i < N; i++) { }", "N a number"},
        };
        for (String[] c : cases) {
            Linter.Result r = Linter.lint(c[0], Linter.Profile.RESTRICTED, ApiTable.published(), IDS,
                    Linter.Skills.NONE);
            require(!r.ok() && r.repairMessage().contains(c[1]), "restricted: " + c[0] + " -> " + r.repairMessage());
            require(lint(c[0]).ok(), "full allows: " + c[0]);
        }
        String straight = "let p = api.position(); for (let i = 0; i < 3; i++) { if (i > 1) { api.wait(1); } }"
                + " try { api.goto(p); } catch (e) { }";
        require(Linter.lint(straight, Linter.Profile.RESTRICTED, ApiTable.published(), IDS, Linter.Skills.NONE).ok(),
                "restricted keeps straight-line code, literal-bound for, if on variables and try/catch");
    }

    // --- running ----------------------------------------------------------------------------------

    static final String MAIN = String.join("\n",
            "const want = 3;",
            "let have = api.count('iron_ingot');",
            "if (have < want) { api.get('iron_ingot', want); }",
            "let gave = 0;",
            "for (let i = 0; i < want; i++) { api.give_owner('iron_ingot', 1); gave += 1; }",
            "function half(n) { return floor(n / 2); }",
            "let h = half(9);",
            "let p = api.position();",
            "let b = box_rel(p, 3, 2, 3, 'north');",
            "api.excavate(b);",
            "let caught = 'none';",
            "try { api.give_owner('diamond', 5); } catch (e) { caught = e.code; api.say('no diamonds: ' + e.code); }",
            "let names = [];",
            "for (const k of ['a', 'b']) { names.push(k + h); }",
            "return {gave: gave, h: h, caught: caught, names: names, box: b};");

    private static Job job(String src, StubWorld w) {
        Linter.Result r = lint(src);
        require(r.ok(), "test program lints: " + r.repairMessage());
        return Job.start("j1", "give the owner iron", "Steve", r, Linter.Profile.FULL, ApiTable.published()).bind(w);
    }

    /** Ticks until the job stops running; returns the ticks used. */
    private static int drive(Job j, StubWorld w, int maxTicks) {
        int t = 0;
        while (j.state() == Job.State.RUNNING && t < maxTicks) {
            j.tick();
            w.flush(j);
            t++;
        }
        return t;
    }

    private static void programRunsAndYields() {
        StubWorld w = new StubWorld();
        Job j = job(MAIN, w);
        int ticks = drive(j, w, 1000);
        require(j.state() == Job.State.DONE, "the program finishes: " + j.state() + " " + j.lastError());
        require(w.owner.getOrDefault("iron_ingot", 0) == 3, "the owner got 3 iron: " + w.owner);
        require(w.said.equals(List.of("no diamonds: missing_item")), "the failed give was caught: " + w.said);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) j.result();
        require(res.get("gave").equals(3.0) && res.get("h").equals(4.0) && res.get("caught").equals("missing_item"),
                "the return value: " + res);
        require(res.get("names").equals(List.of("a4", "b4")), "for-of and push: " + res.get("names"));
        require(Values.display(res.get("box")).equals("{\"min\":{\"x\":-1,\"y\":64,\"z\":-3},\"max\":{\"x\":1,\"y\":65,\"z\":-1}}"),
                "box_rel matches the area commands' relative box: " + Values.display(res.get("box")));
        int calls = j.log().entries().size();
        require(calls == 9, "every api call is logged: " + calls);
        require(ticks >= calls, "each call yields at least one tick: " + ticks + " ticks for " + calls + " calls");
        for (CallLog.Entry e : j.log().entries()) {
            require(e.status() == CallLog.Status.DONE, "every call is done: " + e.describe());
        }
    }

    private static void yieldAndRestoreRoundTrip() {
        StubWorld plain = new StubWorld();
        Job reference = job(MAIN, plain);
        drive(reference, plain, 1000);

        StubWorld w = new StubWorld();
        Job j = job(MAIN, w);
        int restores = 0;
        for (int t = 0; t < 1000 && !j.ended(); t++) {
            j.tick();
            w.flush(j);
            if (j.ended()) {
                break;
            }
            JsonObject saved = JsonParser.parseString(j.toJson().toString()).getAsJsonObject();
            Job back = Job.fromJson(saved, ApiTable.published()).bind(w);
            require(back.toJson().get("machine").equals(saved.get("machine")),
                    "the frame stack serialises exactly");
            require(back.state() == Job.State.PAUSED && back.pause() == Job.Pause.RESTART,
                    "a restored job loads PAUSED");
            require(back.resume(), "and resumes");
            j = back;
            restores++;
        }
        require(restores >= 9, "restored at every yield: " + restores);
        require(j.state() == Job.State.DONE, "the restored job finishes: " + j.state() + " " + j.lastError());
        require(Values.display(j.result()).equals(Values.display(reference.result())),
                "the result matches an uninterrupted run: " + Values.display(j.result()));
        require(w.owner.equals(plain.owner) && w.gives == plain.gives, "the world matches: " + w.owner);
    }

    private static void sharingSurvivesRestore() {
        StubWorld w = new StubWorld();
        Job j = job("let a = []; let b = a; b.push(1); api.wait(1); b.push(2); return a.length;", w);
        j.tick();
        Job back = Job.fromJson(j.toJson(), ApiTable.published()).bind(w);
        back.resume();
        w.flush(back);
        drive(back, w, 10);
        require(Double.valueOf(2).equals(back.result()), "two names for one array stay one array: " + back.result());
    }

    // --- caps -------------------------------------------------------------------------------------

    private static void everyCapFailsWithBudget() {
        lintBudget("let s = '" + "x".repeat(9000) + "';", "longer than 8192 bytes");
        lintBudget("let x = " + "(".repeat(70) + "1" + ")".repeat(70) + ";", "nesting deeper than 64");
        lintBudget("if (true) { ".repeat(70) + "}".repeat(70), "nesting deeper than 64");
        lintBudget("let x = " + "1 + ".repeat(80) + "1;", "deep, more than 64");
        StringBuilder vars = new StringBuilder();
        for (int i = 0; i < 257; i++) {
            vars.append("let v").append(i).append(" = ").append(i).append(";\n");
        }
        lintBudget(vars.toString(), "more than 256 variables");
        lintBudget("let a = [" + "1,".repeat(1100) + "1];", "array longer than 1024");
        lintBudget("let s = '" + "y".repeat(1100) + "';", "string longer than 1024");

        runBudget("function f(n) { f(n + 1); } f(0);", "call-frame depth", null);
        runBudget("let a = []; for (let i = 0; i < 1000; i++) { a.push([1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,"
                + "19,20,21,22,23,24,25,26,27,28,29,30]); }", "live values", null);
        runBudget("let a = []; while (true) { a.push(1); }", "array length", null);
        runBudget("let s = 'x'; while (true) { s = s + s; }", "string length", null);
        runBudget("let i = 0; while (true) { i = i + 1; }", "statements", null);
        runBudget("let a = []; for (let i = 0; i < 70; i++) { let s = 'x'; for (let j = 0; j < 10; j++) { s = s + s; }"
                + " a.push(s); } api.wait(1);", "serialised state", null);
        runBudget("for (let i = 0; i < 300; i++) { api.wait(1); }", "primitive calls", null);
        runBudget("for (let i = 0; i < 10; i++) { api.excavate(box(pos(0, 60, 0), pos(9, 69, 9))); }",
                "cells changed", null);
        runBudget("api.wait(1200);", "per-call time", w -> {
            w.hang = true;
            w.callTicks = 5;
        });
        StubWorld w = new StubWorld();
        Job j = job("try { while (true) { } } catch (e) { api.say('caught'); }", w);
        drive(j, w, 1000);
        require(j.state() == Job.State.FAILED && j.lastError().code() == FailureCode.BUDGET && w.said.isEmpty(),
                "a program cannot catch its own budget");
    }

    private static void lintBudget(String src, String what) {
        Linter.Result r = lint(src);
        require(!r.ok() && r.toActionError().code() == FailureCode.BUDGET && r.repairMessage().contains(what),
                "lint cap " + what + " is budget: " + r.repairMessage());
    }

    private static void runBudget(String src, String cap, java.util.function.Consumer<StubWorld> setup) {
        StubWorld w = new StubWorld();
        if (setup != null) {
            setup.accept(w);
        }
        Job j = job(src, w);
        drive(j, w, 5000);
        require(j.state() == Job.State.FAILED && j.lastError() != null
                        && j.lastError().code() == FailureCode.BUDGET && cap.equals(j.lastError().state().get("cap")),
                "cap " + cap + " fails with budget: " + j.state() + " " + j.lastError());
        require(j.lastError().state().containsKey("steps"), "budget reports progress: " + j.lastError());
    }

    // --- reconcile --------------------------------------------------------------------------------

    static final String GIVE = "api.give_owner('iron_ingot', 5); api.say('done');";

    /** Starts GIVE, lets the world give, and "crashes" before the outcome is logged; returns the saved board. */
    private static JsonObject crashMidGive(StubWorld w) {
        JobBoard board = new JobBoard(null, ApiTable.published(), w);
        board.give(job(GIVE, w));
        w.crash = true;
        board.tick();
        w.flush(board.active());
        require(w.owner.getOrDefault("iron_ingot", 0) == 5, "the world gave before the crash");
        CallLog.Entry e = board.active().log().entries().get(0);
        require(e.status() == CallLog.Status.STARTED && e.pre().get("owner").equals(0), "started is logged, done is not");
        w.crash = false;
        return JsonParser.parseString(board.toJson().toString()).getAsJsonObject();
    }

    private static JobBoard restart(JsonObject saved, StubWorld w) throws Exception {
        Path dir = Files.createTempDirectory("program-selftest");
        Path file = dir.resolve("job.json");
        Files.writeString(file, saved.toString());
        return JobBoard.load(file, ApiTable.published(), w);
    }

    private static void reconcileGivesOnce() throws Exception {
        StubWorld w = new StubWorld();
        w.inv.put("iron_ingot", 10);
        JobBoard board = restart(crashMidGive(w), w);
        Job j = board.active();
        require(j.state() == Job.State.PAUSED && j.pause() == Job.Pause.RESTART, "restored PAUSED");
        JobBoard.Notice n = board.restoreNotice();
        require(n != null && n.initiator().equals("Steve"), "the notice goes to the initiator (R19)");
        require(board.continueActive(), "continue resumes it");
        drive(j, w, 100);
        require(j.state() == Job.State.DONE, "it finishes: " + j.state() + " " + j.lastError());
        require(w.owner.get("iron_ingot") == 5 && w.gives == 1, "reconcile gives once: owner has "
                + w.owner.get("iron_ingot") + " after " + w.gives + " gives");
        require("the world shows it".equals(j.log().entries().get(0).reconciled()), "reconcile settled it");
        require(w.said.equals(List.of("done")), "the program went on past the call");
    }

    /** Red witness for §6.4: without reconcile, the restart gives twice. */
    private static void skippingReconcileGivesTwice() throws Exception {
        StubWorld w = new StubWorld();
        w.inv.put("iron_ingot", 10);
        JobBoard board = restart(crashMidGive(w), w);
        Job j = board.active();
        require(j.resume(false), "resumes without reconcile");
        drive(j, w, 100);
        require(w.gives == 2 && w.owner.get("iron_ingot") == 10,
                "skipping reconcile gives twice: " + w.gives + " gives, owner has " + w.owner.get("iron_ingot"));
    }

    private static void rolledBackDoneIsRerunOnce() throws Exception {
        StubWorld w = new StubWorld();
        w.inv.put("iron_ingot", 5);
        JobBoard board = new JobBoard(null, ApiTable.published(), w);
        board.give(job("api.give_owner('iron_ingot', 5); api.wait(1); api.say('done');", w));
        board.tick();
        w.flush(board.active());
        board.tick();
        JsonObject saved = JsonParser.parseString(board.toJson().toString()).getAsJsonObject();
        w.queue.clear();
        // The world rolls back to its last save, from before the give; job.json does not.
        w.inv.put("iron_ingot", 5);
        w.owner.put("iron_ingot", 0);
        JobBoard back = restart(saved, w);
        Job j = back.active();
        require(j.resume(), "resumes");
        drive(j, w, 100);
        require(j.state() == Job.State.DONE && w.owner.get("iron_ingot") == 5 && w.gives == 2,
                "a done give the world lost is given again, once: owner " + w.owner + ", " + w.gives + " gives");
        require(j.log().entries().get(0).rolledBack(), "the lost call is marked rolled back");
        require(j.report().equals("Handed over 5 iron ingot."), "the report counts it once: " + j.report());
    }

    private static void ambiguousCallAsksTheInitiator() throws Exception {
        StubWorld w = new StubWorld();
        w.inv.put("cobblestone", 64);
        JobBoard board = new JobBoard(null, ApiTable.published(), w);
        board.give(job("api.store(pos(1, 64, 1), {cobblestone: 64}); api.say('stored');", w));
        w.crash = true;
        board.tick();
        w.flush(board.active());
        w.crash = false;
        JobBoard back = restart(JsonParser.parseString(board.toJson().toString()).getAsJsonObject(), w);
        Job j = back.active();
        require(!j.resume() && j.pause() == Job.Pause.CONFIRM && j.question() != null
                && j.question().contains("api.store"), "an ambiguous store asks: " + j.question());
        JobBoard again = restart(JsonParser.parseString(back.toJson().toString()).getAsJsonObject(), w);
        require(again.active().pause() == Job.Pause.CONFIRM, "the question survives another restart");
        j = again.active();
        require(j.answer(true), "yes resumes");
        drive(j, w, 100);
        require(j.state() == Job.State.DONE && w.stores == 1 && w.said.equals(List.of("stored")),
                "a yes does not store twice: " + w.stores);
    }

    private static void interruptedIdempotentCallReruns() {
        StubWorld w = new StubWorld();
        Job j = job("api.goto(pos(5, 64, 5)); api.say('there');", w);
        j.tick();
        j.pause(Job.Pause.STOPPED);
        require(w.aborted == 1, "a pause aborts the call in flight");
        w.queue.clear();
        require(j.resume(), "resumes");
        drive(j, w, 100);
        require(j.state() == Job.State.DONE && w.gotos == 1 && j.log().entries().get(0).status()
                == CallLog.Status.ABANDONED, "an interrupted goto runs again: " + w.gotos);
    }

    // --- lifecycle --------------------------------------------------------------------------------

    private static void lifecycleAndShelving() {
        StubWorld w = new StubWorld();
        JobBoard board = new JobBoard(null, ApiTable.published(), w);
        Job iron = Job.start("j1", "bring the owner iron", "Steve", lint("api.wait(5); api.say('iron');"),
                Linter.Profile.FULL, ApiTable.published());
        board.give(iron);
        board.tick();
        iron.pause(Job.Pause.STOPPED);
        w.queue.clear();
        require(board.promptTail().startsWith("job: bring the owner iron | paused (stopped) | step 1"),
                "the tail line: " + board.promptTail());
        Job dig = Job.start("j2", "dig a room", "Alex", lint("api.wait(1); api.say('dug');"), Linter.Profile.FULL,
                ApiTable.published());
        board.give(dig);
        require(iron.state() == Job.State.SHELVED, "a new job shelves the paused one at once (R6)");
        require(!board.promptTail().contains("iron") && board.promptTail().contains("dig a room"),
                "the shelf is not in the prompt tail");
        require(board.jobs().equals(List.of("dig a room | running", "bring the owner iron | shelved")),
                "jobs() lists the shelf: " + board.jobs());
        require(!board.continueActive(), "bare continue never un-shelves");
        while (board.active().state() == Job.State.RUNNING) {
            board.tick();
            w.flush(board.active());
        }
        require(dig.state() == Job.State.DONE, "the new job ran");
        require(board.resume("dig something") == null, "no deterministic match falls back to the model");
        require(board.resume("resume the iron") == iron && iron.state() == Job.State.RUNNING,
                "resume X un-shelves the job whose goal matches");
        drive(iron, w, 100);
        require(w.said.equals(List.of("dug", "iron")), "both finished: " + w.said);

        Job failing = job("let x = null; let y = x.field;", w);
        drive(failing, w, 10);
        require(failing.state() == Job.State.PAUSED && failing.pause() == Job.Pause.REPAIR && !failing.resume(),
                "an uncaught error pauses for repair: " + failing.lastError());
        Job running = job("api.wait(100);", w);
        running.tick();
        running.cancel();
        require(running.state() == Job.State.CANCELLED && w.aborted >= 1, "cancel ends it and stops the call");

        JobBoard b2 = new JobBoard(null, ApiTable.published(), w);
        Job a = job("api.wait(100);", w);
        b2.give(a);
        b2.tick();
        b2.give(job("api.say('new');", w));
        require(a.state() == Job.State.SHELVED && a.pause() == Job.Pause.SUPERSEDED,
                "a running job is paused then shelved by a new one");
    }

    private static void boardPersistsAtomically() throws Exception {
        Path dir = Files.createTempDirectory("program-board");
        Path file = dir.resolve("job.json");
        StubWorld w = new StubWorld();
        JobBoard board = new JobBoard(file, ApiTable.published(), w);
        board.give(job("api.wait(1); api.wait(1);", w));
        board.tick();
        require(Files.isRegularFile(file) && !Files.exists(dir.resolve("job.json.tmp")),
                "job.json is written through a tmp file and moved into place");
        require(JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("active")
                .getAsJsonArray("log").size() == 1, "the started entry is on disk before the call runs");
        board.give(job("api.say('x');", w));
        JobBoard back = JobBoard.load(file, ApiTable.published(), w);
        require(back.active() != null && back.active().pause() == Job.Pause.RESTART && back.shelf().size() == 1
                && back.shelf().get(0).state() == Job.State.SHELVED, "the board restores: active PAUSED, shelf SHELVED");
        Files.writeString(file, "{not json");
        JobBoard corrupt = JobBoard.load(file, ApiTable.published(), w);
        require(corrupt.active() == null && Files.exists(dir.resolve("job.json.corrupt")),
                "a corrupt job.json is moved aside");
    }

    private static void completionTemplate() {
        StubWorld w = new StubWorld();
        w.inv.put("cobblestone", 64);
        w.inv.put("dirt", 12);
        Job j = job("api.store(pos(120, 64, -30), {cobblestone: 64, dirt: 12});"
                + " try { api.give_owner('diamond', 1); } catch (e) { }", w);
        drive(j, w, 100);
        require(j.report().equals("Stored 64 cobblestone and 12 dirt in the chest at 120 64 -30."),
                "the report is filled from verified calls only: " + j.report());
        StubWorld w2 = new StubWorld();
        Job main = job(MAIN, w2);
        drive(main, w2, 1000);
        require(main.report().equals("Gathered 3 iron ingot; handed over 3 iron ingot; dug out an area of 18 blocks."),
                "the main program's report: " + main.report());
    }

    // --- stub world -------------------------------------------------------------------------------

    /** A world of three inventories and a chest; outcomes are delivered on {@link #flush}. */
    static final class StubWorld implements ActionPort {
        final Map<String, Integer> inv = new HashMap<>();
        final Map<String, Integer> owner = new HashMap<>();
        final Map<String, Integer> chest = new HashMap<>();
        final List<Object[]> queue = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        Map<String, Object> position = Map.of("x", 0, "y", 64, "z", 0);
        int gives;
        int stores;
        int gotos;
        int aborted;
        /** Effects happen but outcomes are lost, as in a crash between started and done. */
        boolean crash;
        /** Calls never finish. */
        boolean hang;
        int callTicks = Caps.CALL_TICKS;

        @Override
        public Map<String, Object> snapshot(String name, Map<String, Object> args) {
            if (name.equals("give_owner")) {
                return new LinkedHashMap<>(Map.of("owner", owner.getOrDefault((String) args.get("item"), 0)));
            }
            if (name.equals("store")) {
                return new LinkedHashMap<>(Map.of("chest", chest.values().stream().mapToInt(Integer::intValue).sum()));
            }
            return Map.of();
        }

        @Override
        public void begin(long seq, String name, Map<String, Object> args) {
            queue.add(new Object[] {seq, name, args});
        }

        @Override
        public void abort(long seq) {
            aborted++;
        }

        @SuppressWarnings("unchecked")
        void flush(Job job) {
            if (hang) {
                return;
            }
            List<Object[]> q = new ArrayList<>(queue);
            queue.clear();
            for (Object[] c : q) {
                Outcome o = apply((String) c[1], (Map<String, Object>) c[2]);
                if (!crash) {
                    job.deliver((Long) c[0], o);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private Outcome apply(String name, Map<String, Object> a) {
            switch (name) {
                case "count" -> {
                    return Outcome.ok(inv.getOrDefault((String) a.get("item"), 0), List.of());
                }
                case "position" -> {
                    return Outcome.ok(position, List.of());
                }
                case "get" -> {
                    String item = (String) a.get("item");
                    inv.put(item, Math.max(inv.getOrDefault(item, 0), (Integer) a.get("n")));
                    return Outcome.ok(null, List.of());
                }
                case "give_owner" -> {
                    String item = (String) a.get("item");
                    int n = (Integer) a.get("n");
                    int have = inv.getOrDefault(item, 0);
                    if (have < n) {
                        return Outcome.failed(new ActionError(FailureCode.MISSING_ITEM, "not enough " + item,
                                Map.of("needed", n, "have", have, "item", item)), List.of());
                    }
                    inv.put(item, have - n);
                    owner.merge(item, n, Integer::sum);
                    gives++;
                    return Outcome.ok(null, List.of());
                }
                case "store" -> {
                    Map<String, Object> moved = new LinkedHashMap<>();
                    ((Map<String, Object>) a.get("items")).forEach((k, v) -> {
                        int n = Math.min((Integer) v, inv.getOrDefault(k, 0));
                        inv.merge(k, -n, Integer::sum);
                        chest.merge(k, n, Integer::sum);
                        moved.put(k, n);
                    });
                    stores++;
                    return Outcome.ok(moved, List.of());
                }
                case "goto" -> {
                    gotos++;
                    position = (Map<String, Object>) a.get("p");
                    return Outcome.ok(null, List.of());
                }
                case "say" -> {
                    said.add((String) a.get("text"));
                    return Outcome.ok(null, List.of());
                }
                default -> {
                    return Outcome.ok(null, List.of());
                }
            }
        }

        @Override
        public boolean stillShows(CallLog.Entry e) {
            if (e.name().equals("give_owner")) {
                return e.post() != null && e.post().get("owner").equals(owner.getOrDefault((String) e.args().get("item"), 0));
            }
            return true;
        }

        @Override
        public Reconcile reconcile(CallLog.Entry e) {
            if (e.name().equals("give_owner")) {
                int now = owner.getOrDefault((String) e.args().get("item"), 0);
                return now - (Integer) e.pre().get("owner") >= (Integer) e.args().get("n") ? Reconcile.DONE
                        : Reconcile.RERUN;
            }
            return Reconcile.ASK;
        }

        @Override
        public int cellsChanged(CallLog.Entry e, Outcome o) {
            if (!e.name().equals("excavate")) {
                return 0;
            }
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Integer>> b = (Map<String, Map<String, Integer>>) e.args().get("box");
            int n = 1;
            for (String k : List.of("x", "y", "z")) {
                n *= b.get("max").get(k) - b.get("min").get(k) + 1;
            }
            return n;
        }

        @Override
        public int callTicks(String name, Map<String, Object> args) {
            return callTicks;
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
