package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.LLMCompleter;
import com.player2.playerengine.player2api.Player2APIService;
import com.player2.playerengine.player2api.manager.LlmLanes;
import com.player2.playerengine.player2api.utils.HTTPUtils;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM dispatch lanes against two slow loopback endpoints: companions on different endpoint profiles
 * think at the same time, calls on one lane never overlap, and hourly caps still hold. Companions are
 * driven through {@link LlmLanes#dispatch} and real {@link LLMCompleter} workers, and every call goes
 * through the production HTTP path to its profile's endpoint. Run by {@link GatewaySelfTest}.
 */
final class GatewayLanesSelfTest {
    private static final String CHAT = "/v1/chat/completions";
    private static final long DELAY_MS = 400;
    private static final long TIMEOUT_MS = 20_000;

    private static final String ROSTER = """
            {"characters":[
              {"id":"foreman-ada","name":"Foreman Ada","short_name":"Ada","meta":{"skin_url":""}},
              {"id":"rivet","name":"Rivet","short_name":"Rivet","endpoint":"openai","meta":{"skin_url":""}},
              {"id":"plain","name":"Plain","short_name":"Plain","meta":{"skin_url":""}}
            ]}""";

    private GatewayLanesSelfTest() {
    }

    /** A loopback endpoint that answers every chat completion after a fixed delay, serving requests in parallel. */
    private record SlowMock(HttpServer server, ExecutorService pool, AtomicInteger hits) implements AutoCloseable {
        static SlowMock start(long delayMs) throws IOException {
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"{\\\"message\\\":\\\"ok\\\"}\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            AtomicInteger hits = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lanes-selftest-mock");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                hits.incrementAndGet();
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            return new SlowMock(server, pool, hits);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    private record Interval(String who, long startNs, long endNs) {
        boolean overlaps(Interval o) {
            return startNs < o.endNs && o.startNs < endNs;
        }
    }

    /** Makes the companion's chat call exactly as the gateway sees it, and times it. */
    private static final class TimedService extends Player2APIService {
        private final Companion companion;

        TimedService(Companion companion) {
            super(null, "self-test");
            this.companion = companion;
        }

        @Override
        public JsonObject completeConversation(ConversationHistory history, AiTaskClass taskClass) throws Exception {
            long start = System.nanoTime();
            try {
                GatewayCallContext.call(companion.characterId, companion.billingKey,
                        () -> HTTPUtils.sendRequest("https://api.player2.game", CHAT, "POST", chatRequest(), new HashMap<>()));
                return new JsonObject();
            } finally {
                companion.intervals.add(new Interval(companion.characterId, start, System.nanoTime()));
            }
        }
    }

    /**
     * Stands in for {@code AgentConversationData}: ready while it has calls left. A {@code greedy}
     * companion reports ready even while its call is in flight, so only its lane can keep it ordered.
     */
    private static final class Companion {
        final String characterId;
        final String billingKey;
        final boolean greedy;
        final AtomicInteger remaining;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<Interval> intervals = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final TimedService service;

        Companion(String characterId, String billingKey, int calls, boolean greedy) {
            this.characterId = characterId;
            this.billingKey = billingKey;
            this.greedy = greedy;
            this.remaining = new AtomicInteger(calls);
            this.service = new TimedService(this);
        }

        long priority() {
            if (remaining.get() == 0 || (!greedy && inFlight.get() > 0)) {
                return 0;
            }
            return 1;
        }

        boolean done() {
            return remaining.get() == 0 && inFlight.get() == 0;
        }

        void start(LLMCompleter completer) {
            remaining.decrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            LLMCompleter.Submission s = completer.processToJson(service, new ConversationHistory("system"),
                    resp -> inFlight.decrementAndGet(),
                    err -> {
                        errors.add(err);
                        inFlight.decrementAndGet();
                    },
                    true, AiTaskClass.DECISION);
            require(s.accepted(), "an idle lane must accept the call for " + characterId);
        }
    }

    private static JsonObject chatRequest() {
        JsonObject req = new JsonObject();
        req.add("messages", new JsonArray());
        req.addProperty("max_tokens", 64);
        return req;
    }

    private static void install(Path dir, SlowMock lan, SlowMock openai, String... extra) throws IOException {
        Files.writeString(dir.resolve("lanes-chars.json"), ROSTER);
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("defaultEndpoint", "gx10");
        p.setProperty("baseUrl", lan.url());
        p.setProperty("model", "director");
        p.setProperty("apiKey", "lan-key");
        p.setProperty("charactersFile", "lanes-chars.json");
        p.setProperty("endpoint.openai.baseUrl", openai.url());
        p.setProperty("endpoint.openai.model", "gpt-selftest");
        p.setProperty("endpoint.openai.apiKeyEnv", "SELFTEST_LANES_KEY");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            p.setProperty(extra[i], extra[i + 1]);
        }
        GatewayConfig.install(new GatewayConfig(p, dir, name -> "SELFTEST_LANES_KEY".equals(name) ? "openai-key" : null));
    }

    /** Ticks the dispatcher like the server loop does until every companion has finished. */
    private static void drive(LlmLanes lanes, Companion... companions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (true) {
            boolean allDone = true;
            List<Companion> ready = new ArrayList<>();
            for (Companion c : companions) {
                allDone &= c.done();
                if (c.priority() > 0) {
                    ready.add(c);
                }
            }
            if (allDone) {
                return;
            }
            require(System.nanoTime() < deadline, "companions must finish within " + TIMEOUT_MS + " ms");
            lanes.dispatch(ready, c -> LlmLanes.LaneKey.of(c.billingKey, c.characterId), Companion::priority,
                    Companion::start);
            Thread.sleep(5);
        }
    }

    private static List<Interval> all(Companion... companions) {
        List<Interval> out = new ArrayList<>();
        for (Companion c : companions) {
            out.addAll(c.intervals);
        }
        return out;
    }

    private static boolean anyOverlap(List<Interval> a, List<Interval> b) {
        for (Interval x : a) {
            for (Interval y : b) {
                if (x != y && x.overlaps(y)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void differentProfilesThinkAtOnce(Path dir) throws Exception {
        try (SlowMock lan = SlowMock.start(DELAY_MS); SlowMock openai = SlowMock.start(DELAY_MS)) {
            install(dir, lan, openai);
            LlmLanes lanes = new LlmLanes();
            try {
                Companion ada = new Companion("foreman-ada", "player-1", 2, false);
                Companion rivet = new Companion("rivet", "player-1", 2, false);
                long start = System.nanoTime();
                drive(lanes, ada, rivet);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                require(ada.errors.isEmpty() && rivet.errors.isEmpty(), "no call may fail: " + ada.errors + rivet.errors);
                require(lan.hits().get() == 2 && openai.hits().get() == 2, "each companion must reach its own endpoint");
                require(anyOverlap(ada.intervals, rivet.intervals),
                        "companions of one player on different profiles must have calls in flight at the same time");
                require(!anyOverlap(ada.intervals, ada.intervals) && !anyOverlap(rivet.intervals, rivet.intervals),
                        "a companion's own calls must never overlap");
                require(elapsedMs < 4 * DELAY_MS, "four " + DELAY_MS + " ms calls on two lanes must beat serial time, took "
                        + elapsedMs + " ms");
                require(lanes.size() == 2, "one player's companions on two profiles must get two lanes, got " + lanes.size());
                System.out.println("lanes: 2 companions x 2 calls x " + DELAY_MS + " ms on two profiles took " + elapsedMs
                        + " ms (serial " + 4 * DELAY_MS + " ms)");
            } finally {
                lanes.shutdownAll();
            }
        }
    }

    static void oneLaneNeverOverlaps(Path dir) throws Exception {
        try (SlowMock lan = SlowMock.start(DELAY_MS / 4); SlowMock openai = SlowMock.start(DELAY_MS / 4)) {
            install(dir, lan, openai);
            LlmLanes lanes = new LlmLanes();
            try {
                Companion greedyAda = new Companion("foreman-ada", "player-1", 3, true);
                Companion plain = new Companion("plain", "player-1", 2, true);
                drive(lanes, greedyAda, plain);
                require(greedyAda.errors.isEmpty() && plain.errors.isEmpty(), "no call may fail");
                require(greedyAda.maxInFlight.get() == 1, "a companion must never have two calls in flight, even when it asks");
                List<Interval> shared = all(greedyAda, plain);
                require(shared.size() == 5, "every call must run");
                require(!anyOverlap(shared, shared), "calls on one (billing key, profile) lane must never overlap");
                require(lanes.size() == 1, "two companions on the same profile must share one lane");
            } finally {
                lanes.shutdownAll();
            }
        }
    }

    static void hourlyCapHoldsAcrossLanes(Path dir) throws Exception {
        try (SlowMock lan = SlowMock.start(DELAY_MS / 4); SlowMock openai = SlowMock.start(DELAY_MS / 4)) {
            install(dir, lan, openai, "endpoint.openai.callsPerHour", "2");
            LlmLanes lanes = new LlmLanes();
            try {
                Companion ada = new Companion("foreman-ada", "player-1", 3, false);
                Companion rivet = new Companion("rivet", "player-1", 3, false);
                drive(lanes, ada, rivet);
                require(openai.hits().get() == 2, "the OpenAI profile's hourly cap must hold with parallel lanes, got "
                        + openai.hits().get());
                require(rivet.errors.size() == 1 && rivet.errors.get(0).contains("hourly cap"),
                        "the capped call must fail with the cap message, got " + rivet.errors);
                require(lan.hits().get() == 3 && ada.errors.isEmpty(), "the cap must not touch the other profile's lane");
            } finally {
                lanes.shutdownAll();
            }
        }
    }

    static void lanesAreLazyAndCleanedUp(Path dir) throws Exception {
        try (SlowMock lan = SlowMock.start(1); SlowMock openai = SlowMock.start(1)) {
            install(dir, lan, openai);
            AtomicLong now = new AtomicLong(0);
            LlmLanes lanes = new LlmLanes(now::get);
            try {
                require(lanes.size() == 0, "no lane exists before the first dispatch");
                Companion ada = new Companion("foreman-ada", "player-1", 1, false);
                Companion rivet = new Companion("rivet", "player-1", 1, false);
                Companion other = new Companion("rivet", "player-2", 1, false);
                drive(lanes, ada, rivet, other);
                require(lanes.size() == 3, "lanes are per billing key and profile, got " + lanes.size());

                require(lanes.shutdownBilling("player-2") == 1 && lanes.size() == 2,
                        "a departed billing key drops only its own lanes");

                now.addAndGet(LlmLanes.IDLE_NANOS + LlmLanes.SWEEP_EVERY_NANOS);
                lanes.dispatch(List.<Companion>of(), c -> null, Companion::priority, Companion::start);
                require(lanes.size() == 0, "idle lanes must be dropped");

                Companion again = new Companion("rivet", "player-1", 1, false);
                drive(lanes, again);
                require(again.errors.isEmpty() && lanes.size() == 1, "a dropped lane is rebuilt on the next call");

                Properties off = new Properties();
                off.setProperty("enabled", "false");
                GatewayConfig.install(new GatewayConfig(off, dir, name -> null));
                require(LlmLanes.LaneKey.of("player-1", "rivet").equals(LlmLanes.LaneKey.of("player-1", "foreman-ada")),
                        "with the gateway off every call of a billing key shares one lane");
            } finally {
                lanes.shutdownAll();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
