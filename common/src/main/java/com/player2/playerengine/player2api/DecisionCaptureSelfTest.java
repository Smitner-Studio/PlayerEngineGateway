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
 * program the turn started, and lines append one per turn.
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
                "{\"say\":\"On it.\",\"program\":\"api.excavate(box(pos(1, 2, 3), pos(4, 5, 6)));\"}")
                .getAsJsonObject();
        Event.UserMessage asked = new Event.UserMessage("dig out 1 2 3 4 5 6", "Bob", false,
                UUID.nameUUIDFromBytes("bob".getBytes(StandardCharsets.UTF_8)));
        String program = "api.excavate(box(pos(1, 2, 3), pos(4, 5, 6)));";
        JsonObject line = DecisionCapture.line(1L, "Ada", "foreman-ada", asked, List.of(system, user), reply,
                program);
        require(line.getAsJsonArray("messages").size() == 2, "the messages the model saw");
        require(line.getAsJsonObject("reply").get("program").getAsString().equals(program), "the reply");
        require(line.get("dispatched").getAsString().equals(program), "the program the turn started");
        require(line.get("player").getAsString().equals("Bob") && line.get("trigger").getAsString().equals("UserMessage"),
                "who asked, and what started the turn");
        JsonObject refused = DecisionCapture.line(2L, "Ada", "foreman-ada", new Event.InfoMessage("goto finished"),
                List.of(system), reply, null);
        require(refused.get("dispatched").isJsonNull() && !refused.has("player"), "a feedback turn that started nothing");
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
