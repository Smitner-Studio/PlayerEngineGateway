package com.player2.playerengine.program;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;

/**
 * One lint or parse finding, at a line and column of the program source (§6.7). A cap exceeded at
 * lint time is {@code budget}; everything else the model must rewrite is {@code bad_args}, and an id
 * that names several things is {@code ambiguous}.
 */
public record ProgramError(FailureCode code, int line, int col, String message) {
    public static ProgramError bad(int line, int col, String message) {
        return new ProgramError(FailureCode.BAD_ARGS, line, col, message);
    }

    public static ProgramError budget(int line, int col, String message) {
        return new ProgramError(FailureCode.BUDGET, line, col, message);
    }

    /** {@code line:col code: message}, the form the one repair message lists. */
    public String toLine() {
        return line + ":" + col + " " + code.wire() + ": " + message;
    }

    public ActionError toActionError() {
        return new ActionError(code, message, java.util.Map.of("line", line, "col", col));
    }

    @Override
    public String toString() {
        return toLine();
    }

    /** Thrown by the lexer and parser: parsing stops at the first syntax error. */
    public static final class Failure extends RuntimeException {
        public final ProgramError error;

        public Failure(ProgramError error) {
            super(error.toLine(), null, false, false);
            this.error = error;
        }
    }
}
