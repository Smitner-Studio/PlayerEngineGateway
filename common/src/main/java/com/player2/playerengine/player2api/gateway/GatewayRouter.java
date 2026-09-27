package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.player2api.utils.HttpApiException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Splits Player2 endpoints into the two OpenAI-compatible ones the gateway can serve and the
 * Player2-platform ones (auth, billing, characters, voice, cloud storage) that are answered locally,
 * and picks the endpoint profile a forwarded call goes to.
 */
public final class GatewayRouter {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String CHAT = "/v1/chat/completions";
    private static final String EMBEDDINGS = "/v1/embeddings";
    private static final long HOUR_MS = 3_600_000L;

    /** Binding value for a character id the characters file makes ambiguous; its calls are refused. */
    private static final String AMBIGUOUS = "\u0000ambiguous";

    private static final Map<String, ArrayDeque<Long>> CALLS = new HashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    static volatile LongSupplier clock = System::currentTimeMillis;

    private record Bindings(Path file, long modified, long size, Map<String, String> byId) {
    }

    private static volatile Bindings bindings;

    private GatewayRouter() {
    }

    /**
     * Returns the local answer for a Player2-only endpoint, or {@code null} when the request must be
     * forwarded to the gateway. Throws {@link HttpApiException} for features that have no local
     * equivalent, so callers take their existing failure/degrade path.
     */
    public static JsonElement localResponse(String method, String endpoint) throws HttpApiException {
        String path = path(endpoint);
        if (path.equals(CHAT)) {
            return null;
        }
        if (path.equals(EMBEDDINGS)) {
            if (GatewayConfig.get().embeddingModel().isEmpty()) {
                throw new HttpApiException("gateway: embeddings disabled (no embeddingModel)", 503);
            }
            return null;
        }
        if (path.equals("/v1/health")) {
            JsonObject o = new JsonObject();
            o.addProperty("status", "ok");
            return o;
        }
        if (path.equals("/v1/joules")) {
            JsonObject o = new JsonObject();
            o.addProperty("joules", 1_000_000_000L);
            o.addProperty("patron_tier", GatewayConfig.get().patronTier());
            o.addProperty("user_id", GatewayConfig.SYNTHETIC_USER);
            return o;
        }
        if (path.equals("/v1/ai_profiles")) {
            return new JsonArray();
        }
        if (path.equals("/v1/selected_characters")) {
            return characters();
        }
        if (path.startsWith("/v1/login/")) {
            JsonObject o = new JsonObject();
            o.addProperty("p2Key", GatewayConfig.TOKEN_PLACEHOLDER);
            return o;
        }
        if (path.equals("/v1/stt/start")) {
            return new JsonObject();
        }
        if (path.equals("/v1/stt/stop")) {
            JsonObject o = new JsonObject();
            o.addProperty("text", "");
            return o;
        }
        if (path.equals("/v1/minecraft/schematics/search")) {
            JsonObject o = new JsonObject();
            o.add("results", new JsonArray());
            return o;
        }
        if (path.startsWith("/v1/games/") && "PUT".equals(method)) {
            JsonObject o = new JsonObject();
            o.addProperty("success", false);
            return o;
        }
        throw new HttpApiException("gateway: Player2 endpoint not available: " + method + " " + path, 404);
    }

    /**
     * The profile a forwarded call goes to. Call only after {@link #localResponse} returned
     * {@code null}. Chat completions follow the calling companion's character; embeddings and calls
     * without a companion use the default profile. Throws before any network I/O when the profile is
     * unusable or its hourly cap is spent; the messages name the profile and never carry a key.
     */
    public static EndpointProfile forward(String endpoint) throws HttpApiException {
        GatewayConfig cfg = GatewayConfig.get();
        if (!path(endpoint).equals(CHAT)) {
            return cfg.defaultProfile();
        }
        GatewayCallContext.Frame frame = GatewayCallContext.current();
        EndpointProfile profile = profileFor(frame == null ? null : frame.characterId());
        if (!profile.usable()) {
            throw new HttpApiException("gateway: endpoint '" + profile.name() + "' is disabled: " + profile.problem(), 503);
        }
        admit(profile, frame == null ? null : frame.billingKey());
        return profile;
    }

    /**
     * Dedicated client-proxy mode relays the call to the player's client, whose own config and key
     * would serve it; a companion bound to a non-default profile must not silently land there.
     */
    public static void checkProxyAllowed() throws HttpApiException {
        if (!GatewayConfig.isEnabled()) {
            return;
        }
        GatewayCallContext.Frame frame = GatewayCallContext.current();
        EndpointProfile profile = profileFor(frame == null ? null : frame.characterId());
        if (!profile.name().equals(GatewayConfig.get().defaultEndpoint())) {
            throw new HttpApiException("gateway: endpoint '" + profile.name()
                    + "' cannot be served in dedicated client-proxy mode", 503);
        }
    }

    /** The profile for a companion's character id; {@code null} (no companion) means the default. */
    static EndpointProfile profileFor(String characterId) {
        GatewayConfig cfg = GatewayConfig.get();
        if (characterId == null) {
            return cfg.defaultProfile();
        }
        String bound = bindings().get(characterId);
        if (bound == null) {
            if (WARNED.add("unknown:" + characterId)) {
                LOGGER.warn("Gateway: character id '{}' is not in the characters file; using endpoint '{}'",
                        characterId, cfg.defaultEndpoint());
            }
            return cfg.defaultProfile();
        }
        if (bound.equals(AMBIGUOUS)) {
            return EndpointProfile.unusable("?", "character id '" + characterId
                    + "' is blank or repeated in the characters file with an endpoint");
        }
        return bound.isEmpty() ? cfg.defaultProfile() : cfg.profile(bound);
    }

    /** Body for the chosen profile; never mutates the caller's object. */
    public static JsonObject prepareBody(EndpointProfile profile, String endpoint, JsonObject body) {
        if (body == null) {
            return null;
        }
        if (path(endpoint).equals(EMBEDDINGS)) {
            JsonObject copy = body.deepCopy();
            String model = GatewayConfig.get().embeddingModel();
            if (!model.isEmpty()) {
                copy.addProperty("model", model);
            }
            return copy;
        }
        return profile.chatBody(body);
    }

    /** Only the chosen profile's own key; the Player2 headers of the caller are dropped. */
    public static Map<String, String> headers(EndpointProfile profile) {
        Map<String, String> h = new HashMap<>();
        if (!profile.apiKey().isEmpty()) {
            h.put("Authorization", "Bearer " + profile.apiKey());
        }
        return h;
    }

    private static void admit(EndpointProfile profile, String billingKey) throws HttpApiException {
        int cap = profile.callsPerHour();
        if (cap <= 0) {
            return;
        }
        String key = profile.name() + "|" + (billingKey == null ? GatewayConfig.SYNTHETIC_USER : billingKey);
        long now = clock.getAsLong();
        synchronized (CALLS) {
            ArrayDeque<Long> calls = CALLS.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!calls.isEmpty() && now - calls.peekFirst() >= HOUR_MS) {
                calls.pollFirst();
            }
            if (calls.size() >= cap) {
                throw new HttpApiException("gateway: endpoint '" + profile.name() + "' hourly cap of " + cap
                        + " calls reached", 429);
            }
            calls.addLast(now);
        }
    }

    static void resetCallCounts() {
        synchronized (CALLS) {
            CALLS.clear();
        }
        bindings = null;
    }

    private static String path(String endpoint) {
        int q = endpoint.indexOf('?');
        return q >= 0 ? endpoint.substring(0, q) : endpoint;
    }

    private static Path charactersPath() {
        GatewayConfig cfg = GatewayConfig.get();
        return cfg.configDir().resolve(cfg.charactersFile());
    }

    /** id → endpoint name ("" = default) from the characters file, re-read when the file changes. */
    private static Map<String, String> bindings() {
        Path file = charactersPath();
        long modified = -1;
        long size = -1;
        try {
            if (Files.exists(file)) {
                modified = Files.getLastModifiedTime(file).toMillis();
                size = Files.size(file);
            }
        } catch (IOException ignored) {
            // treated as absent
        }
        Bindings cached = bindings;
        if (cached != null && cached.file().equals(file) && cached.modified() == modified && cached.size() == size) {
            return cached.byId();
        }
        Map<String, String> byId = new HashMap<>();
        JsonElement parsed = readCharacters(file);
        if (parsed != null) {
            for (JsonElement el : parsed.getAsJsonObject().getAsJsonArray("characters")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                String id = string(c, "id");
                if (id.isEmpty()) {
                    id = string(c, "character_id");
                }
                String endpoint = string(c, "endpoint");
                String previous = byId.get(id);
                boolean clash = previous != null && !(previous.isEmpty() && endpoint.isEmpty());
                boolean blankBound = id.isEmpty() && !endpoint.isEmpty();
                if (clash || blankBound) {
                    LOGGER.error("Gateway characters file {}: character id '{}' is {} and carries an endpoint; "
                            + "its calls are refused", file, id, blankBound ? "blank" : "repeated");
                    byId.put(id, AMBIGUOUS);
                } else {
                    byId.put(id, endpoint);
                }
            }
        }
        GatewayConfig cfg = GatewayConfig.get();
        for (Map.Entry<String, String> e : byId.entrySet()) {
            String name = e.getValue();
            if (name.isEmpty() || name.equals(AMBIGUOUS)) {
                continue;
            }
            EndpointProfile profile = cfg.profile(name);
            if (!profile.usable()) {
                LOGGER.error("Gateway: character '{}' is bound to endpoint '{}', which is disabled ({}); it will not answer",
                        e.getKey(), name, profile.problem());
            }
        }
        Map<String, String> frozen = Collections.unmodifiableMap(byId);
        bindings = new Bindings(file, modified, size, frozen);
        return frozen;
    }

    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString().trim() : "";
    }

    /** The parsed file when it has a {@code characters} array, else {@code null}. */
    private static JsonElement readCharacters(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("characters")
                    && parsed.getAsJsonObject().get("characters").isJsonArray()) {
                return parsed;
            }
            LOGGER.warn("Gateway characters file {} has no 'characters' array; using default", file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Gateway characters file {} unreadable; using default: {}", file, e.getMessage());
        }
        return null;
    }

    private static JsonElement characters() {
        JsonElement parsed = readCharacters(charactersPath());
        if (parsed != null) {
            return parsed;
        }
        JsonObject c = new JsonObject();
        c.addProperty("id", "gateway-companion");
        c.addProperty("name", "Companion");
        c.addProperty("short_name", "Companion");
        c.addProperty("greeting", "Hello! I'm ready to help.");
        c.addProperty("description", "A friendly, capable Minecraft companion who helps their owner gather, build and fight.");
        c.add("voice_ids", new JsonArray());
        JsonObject meta = new JsonObject();
        meta.addProperty("skin_url", "");
        c.add("meta", meta);
        JsonArray arr = new JsonArray();
        arr.add(c);
        JsonObject root = new JsonObject();
        root.add("characters", arr);
        return root;
    }
}
