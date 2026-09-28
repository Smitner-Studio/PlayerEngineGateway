package com.player2.playerengine.program;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An error travelling up the program: a failed call, a runtime type error, a {@code throw}, or a cap.
 * A cap ({@code budget}) is not catchable, so a program cannot swallow its own limit and run on.
 *
 * @param thrown the value a {@code catch} binds: what {@code throw} threw, or {@code {code, message,
 *               state}} for an {@link ActionError}
 */
final class Fault extends RuntimeException {
    final ActionError error;
    final Object thrown;

    private Fault(ActionError error, Object thrown) {
        super(error.toLine(), null, false, false);
        this.error = error;
        this.thrown = thrown;
    }

    static Fault of(ActionError error) {
        LinkedHashMap<String, Object> v = new LinkedHashMap<>();
        v.put("code", error.code().wire());
        v.put("message", error.message());
        v.put("state", Values.fromHost(error.state()));
        return new Fault(error, v);
    }

    static Fault bad(Ast.Node at, String message) {
        return of(new ActionError(FailureCode.BAD_ARGS, message, at == null ? Map.of()
                : Map.of("line", at.line(), "col", at.col())));
    }

    static Fault budget(String cap, long limit, Map<String, Object> progress) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("cap", cap);
        state.put("limit", limit);
        state.putAll(progress);
        return of(new ActionError(FailureCode.BUDGET, cap + " is over its cap of " + limit, state));
    }

    /** A user {@code throw}: a thrown error object keeps its code, anything else is {@code bad_args}. */
    static Fault thrown(Object value) {
        FailureCode code = FailureCode.BAD_ARGS;
        String message = Values.display(value);
        if (value instanceof Map<?, ?> m && m.get("code") instanceof String c) {
            for (FailureCode f : FailureCode.values()) {
                // A program cannot raise the one uncatchable code on itself.
                if (f.wire().equals(c) && f != FailureCode.BUDGET) {
                    code = f;
                }
            }
            if (m.get("message") instanceof String s) {
                message = s;
            }
        }
        return new Fault(new ActionError(code, "uncaught: " + message, Map.of()), value);
    }

    boolean catchable() {
        return error.code() != FailureCode.BUDGET;
    }
}
