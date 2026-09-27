package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.player2api.utils.HTTPUtils;
import com.player2.playerengine.player2api.utils.HttpApiException;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks the gateway contract through the production HTTP path: a Player2-addressed call must
 * reach the configured gateway with the operator key, and Player2-only endpoints must never
 * leave the process. Run with {@code ./gradlew :common:gatewaySelfTest}.
 */
public final class GatewaySelfTest {
    private GatewaySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        int checks = runAll();
        System.out.println("gateway self-test: " + checks + " checks passed");
    }

    private interface Check {
        void run(Path dir) throws Exception;
    }

    public static int runAll() throws Exception {
        Path dir = Files.createTempDirectory("gateway-selftest");
        Check[] checks = {
                GatewaySelfTest::baseUrlDropsV1Suffix,
                GatewaySelfTest::chatIsForwardedWithKeyAndModel,
                GatewaySelfTest::player2OnlyEndpointsStayLocal,
                GatewaySelfTest::embeddingsRefusedWithoutModel,
                GatewaySelfTest::charactersDefaultAndFile,
                GatewaySelfTest::prepareBodyDoesNotMutateCaller,
                GatewaySelfTest::disabledConfigLeavesUpstreamBehaviour,
                GatewaySelfTest::keyFileUsedOnlyWithoutDirectKey,
                GatewayProfilesSelfTest::eachCharacterReachesItsOwnEndpoint,
                GatewayProfilesSelfTest::missingProfileKeyFailsClosedWithoutNetwork,
                GatewayProfilesSelfTest::hourlyCapIsPerProfileAndPerBillingKey,
                GatewayProfilesSelfTest::ambiguousCharacterIdIsRefused,
                GatewayProfilesSelfTest::clientProxyRefusesNonDefaultProfile,
                GatewayProfilesSelfTest::profileConfigParsing,
                GatewayProfilesSelfTest::contextRestoresOuterFrame,
        };
        try {
            for (Check check : checks) {
                GatewayRouter.resetCallCounts();
                check.run(dir);
            }
        } finally {
            GatewayConfig.install(null);
            GatewayRouter.resetCallCounts();
        }
        return checks.length;
    }

    private static GatewayConfig config(Path dir, String... kv) {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("apiKey", "selftest-key");
        p.setProperty("model", "selftest-model");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        GatewayConfig cfg = new GatewayConfig(p, dir);
        GatewayConfig.install(cfg);
        return cfg;
    }

    private static void baseUrlDropsV1Suffix(Path dir) {
        require(config(dir, "baseUrl", "http://gw.example:4001/v1/").baseUrl().equals("http://gw.example:4001"),
                "baseUrl must lose a trailing /v1 because Player2 endpoints already carry it");
        require(config(dir, "baseUrl", "http://gw.example:4001").baseUrl().equals("http://gw.example:4001"),
                "baseUrl without /v1 must be kept");
    }

    private static void chatIsForwardedWithKeyAndModel(Path dir) throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> gameKey = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            gameKey.set(ex.getRequestHeaders().getFirst("player2-game-key"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        try {
            config(dir, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            JsonObject req = new JsonObject();
            req.add("messages", new JsonArray());
            Map<String, String> player2Headers = new HashMap<>();
            player2Headers.put("Authorization", "Bearer player2-token");
            player2Headers.put("player2-game-key", "player2npc");
            Map<String, JsonElement> resp = HTTPUtils.sendRequest("https://api.player2.game",
                    "/v1/chat/completions", "POST", req, player2Headers);
            require(resp.containsKey("choices"), "gateway response must be returned to the caller");
            require("/v1/chat/completions".equals(path.get()), "chat must reach the gateway, got " + path.get());
            require("Bearer selftest-key".equals(auth.get()), "gateway key must replace the Player2 token");
            require(gameKey.get() == null, "player2-game-key must not be sent to the gateway");
            JsonObject sent = JsonParser.parseString(body.get()).getAsJsonObject();
            require("selftest-model".equals(sent.get("model").getAsString()), "configured model must be injected");
        } finally {
            server.stop(0);
        }
    }

    private static void player2OnlyEndpointsStayLocal(Path dir) throws Exception {
        // Port 9 (discard) on loopback: any request that escaped would fail loudly.
        config(dir, "baseUrl", "http://127.0.0.1:9");
        require(HTTPUtils.sendRequest("https://api.player2.game", "/v1/health", "GET", null, null)
                .get("status").getAsString().equals("ok"), "health must be answered locally");
        require(HTTPUtils.sendRequest("https://api.player2.game", "/v1/stt/stop", "POST", null, null)
                .get("text").getAsString().isEmpty(), "stt stop must be answered locally");
        require(HTTPUtils.sendRequestElement("https://api.player2.game", "/v1/ai_profiles", "GET", null, null)
                .isJsonArray(), "ai_profiles must be an empty array");
        for (String endpoint : new String[]{"/v1/tts/stream", "/v1/tts/speak", "/v1/minecraft/schematics/abc",
                "/v1/games/player2npc/data/user?key=x"}) {
            try {
                HTTPUtils.sendRequest("https://api.player2.game", endpoint, "GET", null, null);
                throw new AssertionError(endpoint + " must not be forwarded");
            } catch (HttpApiException expected) {
                require(expected.getStatusCode() == 404, endpoint + " must fail as not available");
            }
        }
    }

    private static void embeddingsRefusedWithoutModel(Path dir) {
        config(dir, "baseUrl", "http://127.0.0.1:9");
        try {
            GatewayRouter.localResponse("POST", "/v1/embeddings");
            throw new AssertionError("embeddings without embeddingModel must be refused");
        } catch (HttpApiException expected) {
            require(expected.getStatusCode() == 503, "embeddings refusal must be 503");
        }
        config(dir, "baseUrl", "http://127.0.0.1:9", "embeddingModel", "embed");
        try {
            require(GatewayRouter.localResponse("POST", "/v1/embeddings") == null,
                    "embeddings with a model must be forwarded");
        } catch (HttpApiException e) {
            throw new AssertionError("embeddings with a model must be forwarded", e);
        }
    }

    private static void charactersDefaultAndFile(Path dir) throws Exception {
        config(dir, "charactersFile", "missing.json");
        JsonObject def = GatewayRouter.localResponse("GET", "/v1/selected_characters").getAsJsonObject();
        JsonObject first = def.getAsJsonArray("characters").get(0).getAsJsonObject();
        require(first.has("name") && first.has("short_name") && first.has("meta"),
                "default character must carry the fields CharacterUtils requires");

        Path file = dir.resolve("chars.json");
        Files.writeString(file, "{\"characters\":[{\"name\":\"Ada\",\"short_name\":\"Ada\",\"meta\":{\"skin_url\":\"\"}}]}");
        config(dir, "charactersFile", "chars.json");
        JsonObject custom = GatewayRouter.localResponse("GET", "/v1/selected_characters").getAsJsonObject();
        require("Ada".equals(custom.getAsJsonArray("characters").get(0).getAsJsonObject().get("name").getAsString()),
                "characters file must override the default");
    }

    private static void prepareBodyDoesNotMutateCaller(Path dir) {
        config(dir);
        JsonObject original = new JsonObject();
        original.addProperty("model", "caller-choice");
        JsonObject prepared = GatewayRouter.prepareBody(GatewayConfig.get().defaultProfile(), "/v1/chat/completions", original);
        require("selftest-model".equals(prepared.get("model").getAsString()), "configured model wins");
        require("caller-choice".equals(original.get("model").getAsString()), "caller body must be untouched");
    }

    private static void disabledConfigLeavesUpstreamBehaviour(Path dir) throws IOException {
        config(dir, "enabled", "false");
        require(!GatewayConfig.isEnabled(), "enabled=false must disable the gateway");
    }

    private static void keyFileUsedOnlyWithoutDirectKey(Path dir) throws IOException {
        Files.writeString(dir.resolve("playerengine-gateway.key"), "\n  file-key  \n");
        require("file-key".equals(config(dir, "apiKey", "").apiKey()),
                "without apiKey the first non-blank line of the key file must be used");
        require("selftest-key".equals(config(dir).apiKey()), "a direct apiKey must win over the key file");
        Files.delete(dir.resolve("playerengine-gateway.key"));
        require(config(dir, "apiKey", "").apiKey().isEmpty(), "no key anywhere must yield an empty key");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
