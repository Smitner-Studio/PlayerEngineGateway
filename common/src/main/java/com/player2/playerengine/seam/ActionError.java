package com.player2.playerengine.seam;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Why an action failed: a code from the fixed enum, one sentence for the model, and the state the
 * repair needs (for example {@code {needed: 23, have: 0, item: "cobblestone"}}). Design §5.5, C9.
 */
public record ActionError(FailureCode code, String message, Map<String, Object> state) {
    private static final Gson GSON = new Gson();

    public ActionError {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
        state = state == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    public static ActionError of(FailureCode code, String message) {
        return new ActionError(code, message, Map.of());
    }

    /** A copy with {@code key} added to the state. */
    public ActionError with(String key, Object value) {
        Map<String, Object> s = new LinkedHashMap<>(state);
        s.put(key, value);
        return new ActionError(code, message, s);
    }

    /**
     * The one-line form the command path hands the model: {@code code: message {state}}. It starts
     * with the code, as {@code CommandPolicy.DENIED} lines already do, so both read the same way.
     */
    public String toLine() {
        return code.wire() + ": " + message + (state.isEmpty() ? "" : " " + GSON.toJson(state));
    }

    @Override
    public String toString() {
        return toLine();
    }
}
