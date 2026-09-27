package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.player2api.DecisionTurnProbe;
import com.player2.playerengine.player2api.LogEgressGuard;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The decision turn's JSON contract through the production path: a JSON-mode profile asks for a
 * JSON object, an oversized turn is trimmed without losing the player's words or the format
 * reminder, and a prose reply becomes a silent retry rather than a player-visible line.
 */
final class GatewayJsonSelfTest {
    private GatewayJsonSelfTest() {
    }

    private static final String JSON_REPLY = "{\"reason\":\"asked to wait\",\"command\":\"idle\",\"message\":\"On it.\"}";
    /** Verbatim from the 2026-09-27 server log: a whole decision reply in prose. */
    private static final String PROSE_REPLY =
            "I've got 20 spruce logs. To make armor and weapons I need more wood for planks and sticks, plus iron for the gear.";
    private static final String REMINDERS = "Owner is nearby | REMEMBER TO OUTPUT ONLY VALID JSON OUTPUT";
    private static final String USER_MESSAGE = "Smitner: Ada, gear up and come defend the base";

    private static final String ROSTER = """
            {"characters":[
              {"id":"foreman-ada","name":"Foreman Ada","short_name":"Ada","endpoint":"gx10","meta":{"skin_url":""}},
              {"id":"rivet","name":"Rivet","short_name":"Rivet","endpoint":"openai","meta":{"skin_url":""}}
            ]}""";

    /** A loopback endpoint that answers with {@code replies} in order (the last repeats) and records bodies. */
    private record Mock(HttpServer server, List<JsonObject> bodies) implements AutoCloseable {
        static Mock start(String... replies) throws IOException {
            List<JsonObject> bodies = new CopyOnWriteArrayList<>();
            AtomicInteger next = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", ex -> {
                bodies.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject());
                String content = replies[Math.min(next.getAndIncrement(), replies.length - 1)];
                JsonObject message = new JsonObject();
                message.addProperty("content", content);
                JsonObject choice = new JsonObject();
                choice.add("message", message);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject reply = new JsonObject();
                reply.add("choices", choices);
                byte[] out = reply.toString().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            return new Mock(server, bodies);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static GatewayConfig install(Path dir, Mock lan, Mock openai, String... extra) throws IOException {
        Files.writeString(dir.resolve("json-chars.json"), ROSTER);
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("defaultEndpoint", "gx10");
        p.setProperty("baseUrl", lan.url());
        p.setProperty("model", "director");
        p.setProperty("apiKey", "lan-key");
        p.setProperty("charactersFile", "json-chars.json");
        p.setProperty("endpoint.openai.baseUrl", openai.url());
        p.setProperty("endpoint.openai.model", "gpt-selftest");
        p.setProperty("endpoint.openai.apiKeyEnv", "SELFTEST_JSON_KEY");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            p.setProperty(extra[i], extra[i + 1]);
        }
        GatewayConfig cfg = new GatewayConfig(p, dir, name -> "SELFTEST_JSON_KEY".equals(name) ? "openai-key" : null);
        GatewayConfig.install(cfg);
        return cfg;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String filler(String line, int chars) {
        StringBuilder sb = new StringBuilder(chars + line.length());
        for (int i = 0; sb.length() < chars; i++) {
            sb.append(line).append(i).append('\n');
        }
        return sb.substring(0, chars);
    }

    private static List<JsonObject> smallTurn() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message("system", "You are Foreman Ada. Reply with one JSON object: reason, command, message."));
        messages.add(message("user", USER_MESSAGE));
        return messages;
    }

    /**
     * The request shape the 2026-09-27 server log shows on most turns: an 8.8k system prompt, about
     * 1.3k of history over nine messages, and an 18.6k status turn whose command list alone is 15.4k.
     */
    private static List<JsonObject> liveShapeTurn() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message("system", filler("General Instructions: be Foreman Ada, reply in JSON. ", 8_815)));
        for (int i = 0; i < 9; i++) {
            messages.add(message(i % 2 == 0 ? "user" : "assistant", "history-" + i + " " + filler("chat ", 140)));
        }
        JsonObject status = new JsonObject();
        status.addProperty("userMessage", USER_MESSAGE);
        status.addProperty("reminders", REMINDERS);
        status.addProperty("memory", filler("remembered fact ", 534));
        status.addProperty("currentMood", "{\"label\":\"determined\",\"intensity\":0.6}");
        status.addProperty("worldStatus", filler("{\"weather\":\"clear\",\"nearbyBlocks\":\"spruce_log\"} ", 1_175));
        status.addProperty("agentStatus", filler("{\"health\":20,\"hunger\":18} ", 561));
        status.addProperty("gameDebugMessages", "");
        status.addProperty("validCommands", filler("get <item> <count>: collect or craft an item ", 15_364));
        messages.add(message("user", status.toString()));
        return messages;
    }

    private static int contentChars(JsonArray messages) {
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            total += messages.get(i).getAsJsonObject().get("content").getAsString().length();
        }
        return total;
    }

    private static String lastContent(JsonArray messages) {
        return messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
    }

    static void jsonObjectOnlyForJsonModeProfiles(Path dir) throws Exception {
        try (Mock lan = Mock.start(JSON_REPLY); Mock openai = Mock.start(JSON_REPLY)) {
            install(dir, lan, openai, "endpoint.openai.jsonMode", "false");

            JsonObject reply = DecisionTurnProbe.complete("foreman-ada", smallTurn());
            require("idle".equals(reply.get("command").getAsString()), "the decision reply must be parsed");
            JsonObject sent = lan.bodies().get(0);
            require(sent.has("response_format")
                            && "json_object".equals(sent.getAsJsonObject("response_format").get("type").getAsString()),
                    "a jsonMode profile's decision turn must ask for response_format json_object");

            DecisionTurnProbe.complete("rivet", smallTurn());
            require(!openai.bodies().get(0).has("response_format"), "a jsonMode=false profile must not get response_format");
        }
    }

    static void proseReplyIsASilentRetry(Path dir) throws Exception {
        try (Mock lan = Mock.start(PROSE_REPLY, JSON_REPLY); Mock openai = Mock.start(JSON_REPLY)) {
            install(dir, lan, openai);

            String first = DecisionTurnProbe.completerError("foreman-ada", smallTurn());
            require(first != null, "a prose decision reply must fail the turn");
            require(DecisionTurnProbe.isSilentToPlayer(first),
                    "a prose reply must reach the conversation layer as the parse failure it retries silently, got: " + first);
            require(DecisionTurnProbe.completerError("foreman-ada", smallTurn()) == null,
                    "the JSON reply to the retry must parse");
            require(!DecisionTurnProbe.isSilentToPlayer("gateway: endpoint 'openai' is disabled: no API key"),
                    "errors other than a parse failure must still reach the player");
        }
    }

    static void oversizedTurnKeepsInstructionsAndStaysJson(Path dir) throws Exception {
        try (Mock lan = Mock.start(JSON_REPLY); Mock openai = Mock.start(JSON_REPLY)) {
            install(dir, lan, openai);

            DecisionTurnProbe.complete("foreman-ada", liveShapeTurn());
            JsonArray sent = lan.bodies().get(0).getAsJsonArray("messages");
            require(contentChars(sent) <= LogEgressGuard.MAX_CHAT_COMPLETION_CONTENT_CHARS,
                    "the mod-wide budget must still bound the request");
            JsonObject status;
            try {
                status = JsonParser.parseString(lastContent(sent)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new AssertionError("the trimmed status turn must still be one JSON object: " + e.getMessage());
            }
            require(USER_MESSAGE.equals(status.get("userMessage").getAsString()), "the player's words must survive whole");
            require(REMINDERS.equals(status.get("reminders").getAsString()), "the format reminder must survive whole");
            require(status.get("validCommands").getAsString().contains("trimmed"),
                    "the bulky command list is what gets trimmed");
            require(status.has("agentStatus") && status.has("worldStatus"), "no status field may disappear");
        }
    }

    static void largeContextProfileSendsWholeTurn(Path dir) throws Exception {
        try (Mock lan = Mock.start(JSON_REPLY); Mock openai = Mock.start(JSON_REPLY)) {
            GatewayConfig cfg = install(dir, lan, openai,
                    "endpoint.gx10.maxRequestChars", "49152",
                    "endpoint.openai.maxRequestChars", "12");
            require(cfg.defaultProfile().maxRequestChars() == 49_152, "maxRequestChars must parse on the default profile");
            require(cfg.profile("openai").maxRequestChars() == GatewayConfig.MIN_REQUEST_CHARS,
                    "a too-small maxRequestChars must clamp to the minimum");
            require(GatewayRouter.requestCharsFor("foreman-ada") == 49_152 && GatewayRouter.requestCharsFor("rivet") == 4096,
                    "each companion gets its own profile's budget");

            List<JsonObject> turn = liveShapeTurn();
            DecisionTurnProbe.complete("foreman-ada", turn);
            JsonArray sent = lan.bodies().get(0).getAsJsonArray("messages");
            require(sent.size() == turn.size(), "no history may be omitted when the turn fits the profile's budget");
            require(lastContent(sent).equals(turn.get(turn.size() - 1).get("content").getAsString()),
                    "the status turn must be sent whole");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
