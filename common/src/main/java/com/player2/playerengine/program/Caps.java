package com.player2.playerengine.program;

/**
 * The interpreter's caps (§6.3). Each one fails with {@code budget} and reports progress. The rows
 * §6.3 lists that are not here are owned elsewhere: perception and the read budget by the seam, idle
 * expiry and model calls by the conversation loop.
 */
final class Caps {
    static final int SOURCE_BYTES = 8 * 1024;
    static final int AST_NESTING = 64;
    static final int CALL_FRAMES = 32;
    static final int VARS_PER_FRAME = 256;
    static final int LIVE_VALUES = 20_000;
    static final int ARRAY_LENGTH = 1024;
    static final int STRING_LENGTH = 1024;
    static final int STATE_BYTES = 64 * 1024;
    static final int STATEMENTS = 50_000;
    static final int PRIMITIVE_CALLS = 200;
    static final int CELLS_CHANGED = 4096;
    /** 25 minutes at 20 ticks a second: the per-call time when the seam gives no estimate. */
    static final int CALL_TICKS = 25 * 60 * 20;
    /** Pure statements a job runs in one tick before it gives the tick back (§6.1, k = 500). */
    static final int STATEMENTS_PER_TICK = 500;
    /** How often, in statements, the live-value count is taken between yields. */
    static final int LIVE_CHECK_EVERY = 256;

    private Caps() {
    }
}
