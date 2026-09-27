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
import java.util.HashMap;
import java.util.Map;

/**
 * Splits Player2 endpoints into the two OpenAI-compatible ones the gateway can serve and the
 * Player2-platform ones (auth, billing, characters, voice, cloud storage) that are answered locally.
 */
public final class GatewayRouter {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String CHAT = "/v1/chat/completions";
    private static final String EMBEDDINGS = "/v1/embeddings";

    private GatewayRouter() {
    }

    /**
     * Returns the local answer for a Player2-only endpoint, or {@code null} when the request must be
     * forwarded to the gateway. Throws {@link HttpApiException} for features that have no local
     * equivalent, so callers take their existing failure/degrade path.
     */
    public static JsonElement localResponse(String method, String endpoint) throws HttpApiException {
        String path = endpoint;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
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

    /** Adds the configured model where the Player2 cloud would have chosen one server-side. */
    public static JsonObject prepareBody(String endpoint, JsonObject body) {
        if (body == null) {
            return null;
        }
        GatewayConfig cfg = GatewayConfig.get();
        String model = endpoint.startsWith(EMBEDDINGS) ? cfg.embeddingModel() : cfg.model();
        if (model.isEmpty()) {
            return body;
        }
        JsonObject copy = body.deepCopy();
        copy.addProperty("model", model);
        return copy;
    }

    public static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        String key = GatewayConfig.get().apiKey();
        if (!key.isEmpty()) {
            h.put("Authorization", "Bearer " + key);
        }
        return h;
    }

    private static JsonElement characters() {
        Path file = GatewayConfig.get().configDir().resolve(GatewayConfig.get().charactersFile());
        if (Files.exists(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("characters")) {
                    return parsed;
                }
                LOGGER.warn("Gateway characters file {} has no 'characters' array; using default", file);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Gateway characters file {} unreadable; using default: {}", file, e.getMessage());
            }
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
