package com.player2.playerengine.seam;

import java.util.Locale;

/**
 * The fixed set of reasons an action can fail (design §5.5). The model repairs against these codes,
 * the stage-3 linter reports its own findings in them, and the tier A checker counts them, so a new
 * code is a contract change, not a local edit.
 */
public enum FailureCode {
    MISSING_ITEM,
    UNREACHABLE,
    NO_CONTAINER,
    CONTAINER_FULL,
    LIQUID,
    PROTECTED,
    OUT_OF_REGION,
    NOT_LOADED,
    BUDGET,
    SUPERSEDED,
    TIMEOUT,
    DENIED,
    AMBIGUOUS,
    BAD_ARGS,
    /** Nothing a player standing there could perceive matches (§5.6). */
    NOT_FOUND;

    /** The name the model and the logs see: lower snake case, as §5.5 spells it. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
