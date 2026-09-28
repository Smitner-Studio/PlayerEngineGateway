package com.player2.playerengine.seam;

import java.util.List;
import java.util.Objects;

/**
 * What one seam call ended with: a value, or an {@link ActionError}. The coercions applied to the
 * arguments ride along either way (§5.4), so the model learns the canonical form from its own calls.
 */
public record Outcome(Object value, ActionError error, List<String> coercions) {
    public Outcome {
        coercions = coercions == null ? List.of() : List.copyOf(coercions);
    }

    public static Outcome ok(Object value, List<String> coercions) {
        return new Outcome(value, null, coercions);
    }

    public static Outcome failed(ActionError error, List<String> coercions) {
        return new Outcome(null, Objects.requireNonNull(error, "error"), coercions);
    }

    public boolean ok() {
        return error == null;
    }
}
