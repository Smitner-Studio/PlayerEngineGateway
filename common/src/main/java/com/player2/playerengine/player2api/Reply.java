package com.player2.playerengine.player2api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The model's reply (design §5.1, C1): {@code {say, program?, save?, mood?}}. A reply that carries
 * {@code command} or {@code plan} is a format error, handled like unparseable JSON, and nothing in it
 * runs: those fields have no reader (R10, zero aliases).
 *
 * @param say     what the companion says now; empty for silence
 * @param program the program to run as a job, or null for pure conversation
 * @param save    the skill-library request (§7), or null
 */
public record Reply(String say, String program, JsonObject save) {
    public static final int MAX_PROGRAM_CHARS = 8 * 1024;

    /** A parse: the reply, or why it is not one. */
    public record Parsed(Reply reply, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static Parsed parse(JsonObject o) {
        if (o == null) {
            return new Parsed(null, "the reply is not a JSON object");
        }
        for (String legacy : new String[] {"command", "plan"}) {
            if (o.has(legacy)) {
                return new Parsed(null, "it has a \"" + legacy + "\" field, which is not part of the reply;"
                        + " actions go in \"program\"");
            }
        }
        String say = "";
        JsonElement s = o.get("say");
        if (s != null && !s.isJsonNull()) {
            if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isString()) {
                return new Parsed(null, "\"say\" must be a string");
            }
            say = s.getAsString();
        }
        String program = null;
        JsonElement p = o.get("program");
        if (p != null && !p.isJsonNull()) {
            if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString()) {
                return new Parsed(null, "\"program\" must be a string of code");
            }
            program = p.getAsString().isBlank() ? null : p.getAsString();
            if (program != null && program.length() > MAX_PROGRAM_CHARS) {
                return new Parsed(null, "\"program\" is longer than " + MAX_PROGRAM_CHARS + " characters");
            }
        }
        JsonObject save = o.has("save") && o.get("save").isJsonObject() ? o.getAsJsonObject("save") : null;
        return new Parsed(new Reply(say, program, save), null);
    }
}
