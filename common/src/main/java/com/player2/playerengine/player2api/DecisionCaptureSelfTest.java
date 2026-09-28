package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The tier A capture (its default, off, is checked with the other rules): a captured line holds the messages, the reply and the
 * dispatched command, and lines append one per turn.
 */
public final class DecisionCaptureSelfTest {
    private static int checks;

    private DecisionCaptureSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "You are Ada.");
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Ada, dig out 1 2 3 4 5 6");
        JsonObject reply = JsonParser.parseString(
                "{\"reason\":\"An area to clear.\",\"command\":\"excavate 1 2 3 4 5 6\",\"message\":\"On it.\"}")
                .getAsJsonObject();
        Event.UserMessage asked = new Event.UserMessage("dig out 1 2 3 4 5 6", "Bob", false,
                UUID.nameUUIDFromBytes("bob".getBytes(StandardCharsets.UTF_8)));
        JsonObject line = DecisionCapture.line(1L, "Ada", "foreman-ada", asked, List.of(system, user), reply,
                "excavate 1 2 3 4 5 6");
        require(line.getAsJsonArray("messages").size() == 2, "the messages the model saw");
        require(line.getAsJsonObject("reply").get("command").getAsString().equals("excavate 1 2 3 4 5 6"), "the reply");
        require(line.get("dispatched").getAsString().equals("excavate 1 2 3 4 5 6"), "the dispatched command");
        require(line.get("player").getAsString().equals("Bob") && line.get("trigger").getAsString().equals("UserMessage"),
                "who asked, and what started the turn");
        JsonObject refused = DecisionCapture.line(2L, "Ada", "foreman-ada", new Event.InfoMessage("goto finished"),
                List.of(system), reply, null);
        require(refused.get("dispatched").isJsonNull() && !refused.has("player"), "a feedback turn that dispatched nothing");
        try {
            Path dir = Files.createTempDirectory("capture");
            Path file = dir.resolve(DecisionCapture.FILE_NAME);
            DecisionCapture.append(file, line);
            DecisionCapture.append(file, refused);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            require(lines.size() == 2 && JsonParser.parseString(lines.get(1)).getAsJsonObject().get("ts").getAsLong() == 2L,
                    "one JSON line per turn, appended: " + lines.size());
            Files.delete(file);
            Files.delete(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("capture self-test could not use a temp file: " + e, e);
        }
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("decision capture self-test failed: " + message);
        }
    }
}
