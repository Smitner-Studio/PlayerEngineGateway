package com.player2.playerengine.program;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One entry of the interpreter's stack (§6.1: a statement list, an ip and an environment). A frame
 * names its statement by id, so it serialises without the tree and restores against a re-parse.
 */
final class Frame {
    enum Kind {
        /** A block's statements; the bottom frame is the program's top level. */
        BLOCK,
        /** A user function's body; the boundary of variable lookup below the top level. */
        FUNCTION,
        /** A {@code try} block's statements; an error unwinds to the nearest one. */
        TRY,
        FOR_OF,
        FOR_I,
        WHILE
    }

    final Kind kind;
    /** The block id for BLOCK, the Func id for FUNCTION, the Try id for TRY, the loop's id otherwise. */
    final int node;
    int ip;
    final LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
    final Set<String> consts = new LinkedHashSet<>();
    /** FOR_OF: the array being walked (live, as in JavaScript) and the next index. */
    List<Object> items;
    int index;
    /** FOR_I: whether the body ran once, so the update runs before the next test. */
    boolean entered;
    /** The statement at {@link #ip} made a call and waits for its value. */
    boolean pending;

    Frame(Kind kind, int node) {
        this.kind = kind;
        this.node = node;
    }

    boolean isLoop() {
        return kind == Kind.FOR_OF || kind == Kind.FOR_I || kind == Kind.WHILE;
    }

    boolean runsStatements() {
        return kind == Kind.BLOCK || kind == Kind.FUNCTION || kind == Kind.TRY;
    }
}
