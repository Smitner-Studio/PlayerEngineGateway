package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-character endpoint profiles, through the production HTTP path: two loopback gateways stand in
 * for the LAN gateway and OpenAI, and each companion's calls must reach its own with its own key,
 * model and parameters. Run by {@link GatewaySelfTest}.
 */
final class GatewayProfilesSelfTest {
    private static final String CHAT = "/v1/chat/completions";
    private static final String LAN_KEY = "lan-key-selftest";
    private static final String OPENAI_KEY = "openai-key-selftest";
    private static final String OPENAI_KEY_ENV = "SELFTEST_OPENAI_KEY";

    private GatewayProfilesSelfTest() {
    }

    /** A loopback gateway that records every request it receives. */
    private record Mock(HttpServer server, List<String> auth, List<JsonObject> bodies) implements AutoCloseable {
        static Mock start() throws IOException {
            List<String> auth = new CopyOnWriteArrayList<>();
            List<JsonObject> bodies = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", ex -> {
                auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                bodies.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject());
                byte[] out = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            return new Mock(server, auth, bodies);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int hits() {
            return bodies.size();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void writeCharacters(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve("profiles-chars.json"), json);
    }

    private static final String ROSTER = """
            {"characters":[
              {"id":"foreman-ada","name":"Foreman Ada","short_name":"Ada","endpoint":"gx10","meta":{"skin_url":""}},
              {"id":"rivet","name":"Rivet","short_name":"Rivet","endpoint":"openai","meta":{"skin_url":""}},
              {"id":"plain","name":"Plain","short_name":"Plain","meta":{"skin_url":""}}
            ]}""";

    /** The pack's shape: LAN gateway as the default profile, OpenAI as an extra one. */
    private static GatewayConfig twoProfiles(Path dir, Mock lan, Mock openai, String openaiKey, String... extra) {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("defaultEndpoint", "gx10");
        p.setProperty("baseUrl", lan.url());
        p.setProperty("model", "director");
        p.setProperty("apiKey", LAN_KEY);
        p.setProperty("charactersFile", "profiles-chars.json");
        p.setProperty("endpoint.openai.baseUrl", openai.url());
        p.setProperty("endpoint.openai.model", "gpt-6-luna");
        p.setProperty("endpoint.openai.apiKeyEnv", OPENAI_KEY_ENV);
        p.setProperty("endpoint.openai.apiKeyFile", "selftest-missing-openai.key");
        p.setProperty("endpoint.openai.tokenParam", "max_completion_tokens");
        p.setProperty("endpoint.openai.maxOutputTokens", "25000");
        p.setProperty("endpoint.openai.dropParams", "temperature, top_p");
        p.setProperty("endpoint.openai.param.reasoning_effort", "max");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            p.setProperty(extra[i], extra[i + 1]);
        }
        Map<String, String> env = new HashMap<>();
        if (openaiKey != null) {
            env.put(OPENAI_KEY_ENV, openaiKey);
        }
        GatewayConfig cfg = new GatewayConfig(p, dir, env::get);
        GatewayConfig.install(cfg);
        return cfg;
    }

    private static JsonObject chatRequest() {
        JsonObject req = new JsonObject();
        req.add("messages", new JsonArray());
        req.addProperty("max_tokens", 2048);
        req.addProperty("temperature", 0.7);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        req.add("response_format", format);
        return req;
    }

    private static void chatAs(String characterId, String billingKey) throws Exception {
        Map<String, String> player2Headers = new HashMap<>();
        player2Headers.put("Authorization", "Bearer player2-token");
        GatewayCallContext.call(characterId, billingKey,
                () -> HTTPUtils.sendRequest("https://api.player2.game", CHAT, "POST", chatRequest(), player2Headers));
    }

    private static int refusedStatus(String characterId, String billingKey) throws Exception {
        try {
            chatAs(characterId, billingKey);
        } catch (HttpApiException e) {
            return e.getStatusCode();
        }
        throw new AssertionError("call as " + characterId + " must be refused");
    }

    static void eachCharacterReachesItsOwnEndpoint(Path dir) throws Exception {
        writeCharacters(dir, ROSTER);
        try (Mock lan = Mock.start(); Mock openai = Mock.start()) {
            twoProfiles(dir, lan, openai, OPENAI_KEY);

            chatAs("rivet", "player-1");
            require(openai.hits() == 1 && lan.hits() == 0, "OpenAI-bound character must reach only the OpenAI endpoint");
            require(("Bearer " + OPENAI_KEY).equals(openai.auth().get(0)), "OpenAI endpoint must get its own key");
            JsonObject sent = openai.bodies().get(0);
            require("gpt-6-luna".equals(sent.get("model").getAsString()), "OpenAI profile model must be injected");
            require("max".equals(sent.get("reasoning_effort").getAsString()), "profile param must be added");
            require(sent.get("max_completion_tokens").getAsInt() == 25000, "token limit must move to max_completion_tokens at the profile value");
            require(!sent.has("max_tokens"), "max_tokens must not reach a max_completion_tokens target");
            require(!sent.has("temperature"), "dropParams must strip temperature");
            require(sent.has("response_format"), "jsonMode defaults to keeping response_format");

            chatAs("foreman-ada", "player-1");
            chatAs("plain", "player-1");
            chatAs(null, null);
            require(lan.hits() == 3 && openai.hits() == 1, "LAN-bound, unbound and companion-less calls must reach the default endpoint");
            for (int i = 0; i < 3; i++) {
                require(("Bearer " + LAN_KEY).equals(lan.auth().get(i)), "default endpoint must get the default key");
                JsonObject lanBody = lan.bodies().get(i);
                require("director".equals(lanBody.get("model").getAsString()), "default model must be injected");
                require(lanBody.get("max_tokens").getAsInt() == 2048, "default profile keeps the caller's max_tokens");
                require(!lanBody.has("reasoning_effort") && lanBody.has("temperature"), "OpenAI tuning must not leak into the default profile");
            }
        }
    }

    static void missingProfileKeyFailsClosedWithoutNetwork(Path dir) throws Exception {
        writeCharacters(dir, ROSTER);
        try (Mock lan = Mock.start(); Mock openai = Mock.start()) {
            GatewayConfig cfg = twoProfiles(dir, lan, openai, null);
            require(!cfg.profile("openai").usable(), "a profile without a key must be unusable");
            require(cfg.profile("openai").problem().contains(OPENAI_KEY_ENV), "the problem must name where the key is looked for");

            require(refusedStatus("rivet", "player-1") == 503, "OpenAI-bound call without a key must fail with 503");
            require(openai.hits() == 0 && lan.hits() == 0, "a refused call must contact no endpoint, and never fall back to the default");

            chatAs("foreman-ada", "player-1");
            require(lan.hits() == 1 && ("Bearer " + LAN_KEY).equals(lan.auth().get(0)), "the default profile must be unaffected");

            Files.writeString(dir.resolve("selftest-missing-openai.key"), "﻿" + OPENAI_KEY + "\r\n");
            try {
                twoProfiles(dir, lan, openai, null);
                chatAs("rivet", "player-1");
                require(openai.hits() == 1 && ("Bearer " + OPENAI_KEY).equals(openai.auth().get(0)),
                        "the key file must serve when the env var is unset, without its byte-order mark");
            } finally {
                Files.delete(dir.resolve("selftest-missing-openai.key"));
            }
        }
    }

    static void hourlyCapIsPerProfileAndPerBillingKey(Path dir) throws Exception {
        writeCharacters(dir, ROSTER);
        AtomicLong now = new AtomicLong(1_000_000L);
        GatewayRouter.clock = now::get;
        try (Mock lan = Mock.start(); Mock openai = Mock.start()) {
            twoProfiles(dir, lan, openai, OPENAI_KEY, "endpoint.openai.callsPerHour", "2");
            chatAs("rivet", "player-1");
            chatAs("rivet", "player-1");
            require(refusedStatus("rivet", "player-1") == 429, "the third call in an hour must hit the cap");
            require(openai.hits() == 2, "a capped call must not reach the endpoint");
            chatAs("rivet", "player-2");
            require(openai.hits() == 3, "the cap is per billing key");
            chatAs("rivet", null);
            require(openai.hits() == 4, "calls with no billing key share one synthetic bucket");
            for (int i = 0; i < 5; i++) {
                chatAs("foreman-ada", "player-1");
            }
            require(lan.hits() == 5, "the OpenAI cap must not limit the default profile");
            now.addAndGet(3_600_000L);
            chatAs("rivet", "player-1");
            require(openai.hits() == 5, "the window rolls over after an hour");
        } finally {
            GatewayRouter.clock = System::currentTimeMillis;
        }
    }

    static void ambiguousCharacterIdIsRefused(Path dir) throws Exception {
        writeCharacters(dir, """
                {"characters":[
                  {"id":"twin","name":"A","short_name":"A","meta":{"skin_url":""}},
                  {"id":"twin","name":"B","short_name":"B","endpoint":"openai","meta":{"skin_url":""}},
                  {"name":"NoId","short_name":"NoId","endpoint":"openai","meta":{"skin_url":""}}
                ]}""");
        try (Mock lan = Mock.start(); Mock openai = Mock.start()) {
            twoProfiles(dir, lan, openai, OPENAI_KEY);
            require(refusedStatus("twin", "p") == 503, "an id bound twice must be refused");
            require(refusedStatus("", "p") == 503, "a blank id carrying an endpoint must be refused");
            require(lan.hits() + openai.hits() == 0, "ambiguous characters must contact no endpoint");
            writeCharacters(dir, ROSTER.replace("\"rivet\"", "\"rivet2\""));
            twoProfiles(dir, lan, openai, OPENAI_KEY);
            GatewayRouter.resetCallCounts();
            chatAs("rivet2", "p");
            require(openai.hits() == 1, "an edited characters file must be picked up");
        }
    }

    static void clientProxyRefusesNonDefaultProfile(Path dir) throws Exception {
        writeCharacters(dir, ROSTER);
        try (Mock lan = Mock.start(); Mock openai = Mock.start()) {
            twoProfiles(dir, lan, openai, OPENAI_KEY);
            GatewayCallContext.call("foreman-ada", "p", () -> {
                GatewayRouter.checkProxyAllowed();
                return null;
            });
            try {
                GatewayCallContext.call("rivet", "p", () -> {
                    GatewayRouter.checkProxyAllowed();
                    return null;
                });
                throw new AssertionError("client-proxy relay of an OpenAI-bound character must be refused");
            } catch (HttpApiException expected) {
                require(expected.getStatusCode() == 503, "proxy refusal must be 503");
            }
        }
    }

    static void profileConfigParsing(Path dir) throws Exception {
        try (Mock lan = Mock.start(); Mock openai = Mock.start()) {
            GatewayConfig cfg = twoProfiles(dir, lan, openai, OPENAI_KEY,
                    "endpoint.gx10.baseUrl", "http://elsewhere.example/v1",
                    "endpoint.gx10.callsPerHour", "7",
                    "endpoint.openai.jsonMode", "false",
                    "endpoint.openai.param.parallel_tool_calls", "false",
                    "endpoint.openai.param.seed", "42",
                    "endpoint.Bad Name.model", "x",
                    "endpoint.broken.baseUrl", "http://x.example",
                    "endpoint.broken.model", "m",
                    "endpoint.broken.apiKeyEnv", OPENAI_KEY_ENV,
                    "endpoint.broken.param.model", "sneaky");
            EndpointProfile def = cfg.defaultProfile();
            require("gx10".equals(def.name()) && def.baseUrl().equals(lan.url().replace("/v1", "")),
                    "the default profile's URL comes from the top-level baseUrl, not endpoint.<default>.baseUrl");
            require(def.callsPerHour() == 7, "tuning keys apply to the default profile");
            require(!cfg.profiles().containsKey("Bad Name"), "an invalid profile name is ignored");
            require(!cfg.profile("broken").usable(), "param.model must disable the profile");
            require(!cfg.profile("nope").usable(), "an undeclared profile is unusable");
            JsonObject body = cfg.profile("openai").chatBody(chatRequest());
            require(!body.has("response_format"), "jsonMode=false strips response_format");
            require(!body.get("parallel_tool_calls").getAsBoolean() && body.get("seed").getAsInt() == 42,
                    "param values are typed as JSON booleans and numbers");
            require(!cfg.profile("openai").describe().contains(OPENAI_KEY), "the log form must never carry the key");
        }
    }

    static void contextRestoresOuterFrame(Path dir) throws Exception {
        require(GatewayCallContext.current() == null, "no context outside a companion call");
        GatewayCallContext.call("outer", "b1", () -> {
            GatewayCallContext.call("inner", "b2", () -> {
                require("inner".equals(GatewayCallContext.current().characterId()), "inner frame visible inside");
                return null;
            });
            require("outer".equals(GatewayCallContext.current().characterId()), "outer frame restored after a nested call");
            return null;
        });
        require(GatewayCallContext.current() == null, "context cleared after the outermost call");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
