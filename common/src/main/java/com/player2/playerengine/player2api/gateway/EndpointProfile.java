package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * One OpenAI-compatible endpoint a companion's chat completions can be sent to.
 *
 * <p>The key belongs to this profile alone: requests for a profile carry its own key and never the
 * default profile's. {@code problem} is non-null when the profile cannot be used (no key, no URL, no
 * model, unknown name); calls routed to it are refused before any network I/O.
 *
 * @param tokenParam      body field that carries the output-token limit ({@code max_tokens} or
 *                        {@code max_completion_tokens}); callers always write {@code max_tokens}
 * @param maxOutputTokens replaces the caller's limit when positive. Reasoning models spend reasoning
 *                        tokens from this same budget, so the mod-wide 2048 cap would starve them.
 * @param jsonMode        false strips {@code response_format} for targets that reject it
 * @param callsPerHour    chat completions per billing key per rolling hour; 0 = no cap here
 * @param maxRequestChars content characters one chat request may carry; 0 = the mod-wide
 *                        {@code LogEgressGuard} budget, sized for Player2's small-context models
 */
public record EndpointProfile(String name, String baseUrl, String model, String apiKey, String keySource,
                              String tokenParam, int maxOutputTokens, boolean jsonMode, Set<String> dropParams,
                              JsonObject extraParams, int callsPerHour, int maxRequestChars,
                              String problem) {

    public static final String MAX_TOKENS = "max_tokens";
    public static final String MAX_COMPLETION_TOKENS = "max_completion_tokens";

    public boolean usable() {
        return problem == null;
    }

    static EndpointProfile unusable(String name, String problem) {
        return new EndpointProfile(name, "", "", "", "", MAX_TOKENS, 0, true, Set.of(), new JsonObject(), 0, 0, problem);
    }

    /** Chat-completion body for this profile. Never mutates {@code body}. */
    JsonObject chatBody(JsonObject body) {
        JsonObject copy = body.deepCopy();
        if (!model.isEmpty()) {
            copy.addProperty("model", model);
        }
        for (String param : dropParams) {
            copy.remove(param);
        }
        if (!jsonMode) {
            copy.remove("response_format");
        }
        JsonElement limit = copy.remove(MAX_TOKENS);
        JsonElement other = copy.remove(MAX_COMPLETION_TOKENS);
        if (limit == null) {
            limit = other;
        }
        if (maxOutputTokens > 0) {
            copy.addProperty(tokenParam, maxOutputTokens);
        } else if (limit != null) {
            copy.add(tokenParam, limit);
        }
        for (var entry : extraParams.entrySet()) {
            copy.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }

    /** Log form. Never includes the key. */
    String describe() {
        return String.format("endpoint '%s': baseUrl=%s model=%s apiKeySet=%s keySource=%s tokenParam=%s maxOutputTokens=%d"
                        + " jsonMode=%s dropParams=%s params=%s callsPerHour=%d maxRequestChars=%d%s",
                name, baseUrl, model, !apiKey.isEmpty(), keySource, tokenParam, maxOutputTokens, jsonMode, dropParams,
                extraParams, callsPerHour, maxRequestChars, problem == null ? "" : " DISABLED: " + problem);
    }
}
