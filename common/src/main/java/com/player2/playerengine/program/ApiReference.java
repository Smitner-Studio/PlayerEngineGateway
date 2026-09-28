package com.player2.playerengine.program;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code api.*} reference the system prompt carries (§5.2), generated from the seam's published
 * signature table so the prompt, the linter and the seam name the same calls.
 *
 * <p>The pack's replay checker ({@code scripts/companion_replay.py}) renders the same text from the
 * same JSON; a change to this format changes both.
 */
public final class ApiReference {
    private ApiReference() {
    }

    /** The reference for the table on the classpath. */
    public static String published() {
        try (InputStream in = ApiReference.class.getClassLoader().getResourceAsStream(ApiTable.RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(ApiTable.RESOURCE + " is not on the classpath");
            }
            return render(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + ApiTable.RESOURCE, e);
        }
    }

    /**
     * Queries, then actions, one {@code - <reference> -- <doc>} line each, then the failure codes.
     * Signatures that are not bound are left out: a call to one fails with {@code denied}.
     */
    static String render(String signaturesJson) {
        JsonObject root = JsonParser.parseString(signaturesJson).getAsJsonObject();
        List<String> queries = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("signatures")) {
            JsonObject s = e.getAsJsonObject();
            if (!s.get("bound").getAsBoolean()) {
                continue;
            }
            String line = "- " + s.get("reference").getAsString() + " -- " + s.get("doc").getAsString();
            ("query".equals(s.get("kind").getAsString()) ? queries : actions).add(line);
        }
        List<String> codes = new ArrayList<>();
        root.getAsJsonArray("failureCodes").forEach(c -> codes.add(c.getAsString()));
        return "Queries (they return a value):\n" + String.join("\n", queries)
                + "\nActions (each one waits until it is done and checked):\n" + String.join("\n", actions)
                + "\nA failed call throws ActionError {code, message, state}; code is one of: "
                + String.join(", ", codes) + ".";
    }
}
