package com.player2.playerengine.player2api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEnginePaths;
import com.player2.playerengine.companion.CompanionRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The tier A replay corpus (design §8.1): with {@code captureDecisions=true} in the companion rules,
 * every decision turn appends one JSON line to {@code playerengine/data/decisions.jsonl}: the
 * messages the model saw, its reply, and the command that turn dispatched after the plan rules.
 * A command's outcome reaches the model as the next turn's feedback, so it is in the next line's
 * messages. Off by default: the lines carry players' chat.
 */
public final class DecisionCapture {
    private static final Logger LOGGER = LogManager.getLogger("playerengine-capture");
    private static final Gson GSON = new Gson();
    public static final String FILE_NAME = "decisions.jsonl";
    private static final Object LOCK = new Object();

    private DecisionCapture() {
    }

    /** One capture line. */
    static JsonObject line(long timeMillis, String bot, String characterId, Event lastEvent,
            List<JsonObject> messages, JsonObject reply, String dispatched) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", timeMillis);
        o.addProperty("bot", bot);
        o.addProperty("character", characterId);
        o.addProperty("trigger", lastEvent == null ? "none" : lastEvent.getClass().getSimpleName());
        if (lastEvent instanceof Event.UserMessage um) {
            o.addProperty("player", um.userName());
        }
        JsonArray m = new JsonArray();
        for (JsonObject msg : messages) {
            m.add(msg);
        }
        o.add("messages", m);
        o.add("reply", reply == null ? new JsonObject() : reply);
        o.addProperty("dispatched", dispatched);
        return o;
    }

    /** Appends a line when the operator turned capture on; never throws into the turn. */
    static void record(AgentConversationData data, Event lastEvent, ConversationHistory history,
            JsonObject reply, String dispatched) {
        if (!CompanionRules.get().captureDecisions()) {
            return;
        }
        try {
            String characterId = data.getCharacter() == null ? null : data.getCharacter().id();
            JsonObject o = line(System.currentTimeMillis(), data.getName(), characterId, lastEvent,
                    history.getListJSON(), reply, dispatched);
            append(PlayerEnginePaths.dataRoot().resolve(FILE_NAME), o);
        } catch (RuntimeException | IOException e) {
            LOGGER.warn("Decision capture failed: {}", e.toString());
        }
    }

    static void append(Path file, JsonObject o) throws IOException {
        String text = GSON.toJson(o) + "\n";
        synchronized (LOCK) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
