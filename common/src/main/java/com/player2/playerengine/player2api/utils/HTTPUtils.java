package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.player2.playerengine.player2api.gateway.GatewayConfig;
import com.player2.playerengine.player2api.gateway.GatewayRouter;
import com.player2.playerengine.player2api.LLMCompleter; // [DEBUG-INSTR:llm-latency-2026-06-13] import for debug flag
import org.jetbrains.annotations.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class HTTPUtils {

    /** Default timeouts so shutdown and network stalls cannot block workers indefinitely (ms). */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 30_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 120_000;

    /**
     * Extended read timeout for background mod-intelligence ingestion/enrichment calls.
     * Local LLMs (via the Player2 desktop app) can be significantly slower than cloud models;
     * enrichment runs on a background thread with no player-facing watchdog, so a longer ceiling
     * is safe. This is a production constant — NOT tied to the debug flag.
     * To revert: change callers in Player2HTTPUtils and ModIntelligenceEnrichmentClient back to
     * the no-timeout overloads, then remove this constant.
     */
    public static final int INGESTION_READ_TIMEOUT_MS = 600_000; // 10 minutes


    public static Map<String, JsonElement> sendRequest(String baseUrl, String endpoint, boolean postRequest, JsonObject requestBody,
                                                       @Nullable Map<String, String> extraHeaders)
            throws Exception {
        return sendRequest(baseUrl, endpoint, postRequest ? "POST" : "GET", requestBody, extraHeaders);
    }

    /**
     * Like {@link #sendRequest} but returns a {@link JsonElement} (object or array) instead of a
     * pre-parsed map. Use this for endpoints that return a JSON array (e.g., {@code /v1/ai_profiles}).
     */
    public static JsonElement sendRequestElement(String baseUrl, String endpoint, String method,
                                                 JsonObject requestBody,
                                                 @Nullable Map<String, String> extraHeaders)
            throws Exception {
        return sendRequestElement(baseUrl, endpoint, method, requestBody, extraHeaders, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Like {@link #sendRequestElement(String, String, String, JsonObject, Map)} but with an explicit
     * read timeout. Use only for calls that require a non-default ceiling (e.g., background ingestion
     * against a slow local model — see {@link #INGESTION_READ_TIMEOUT_MS}).
     * All other callers must use the no-timeout-param overload.
     */
    public static JsonElement sendRequestElement(String baseUrl, String endpoint, String method,
                                                 JsonObject requestBody,
                                                 @Nullable Map<String, String> extraHeaders,
                                                 int readTimeoutMs)
            throws Exception {
        // Every Player2 call in both mods funnels through here or sendRequest below, which makes
        // these two methods the single place the gateway can take over without per-caller edits.
        if (GatewayConfig.isEnabled()) {
            JsonElement local = GatewayRouter.localResponse(method, endpoint);
            if (local != null) {
                return local;
            }
            baseUrl = GatewayConfig.get().baseUrl();
            requestBody = GatewayRouter.prepareBody(endpoint, requestBody);
            extraHeaders = GatewayRouter.headers();
        }
        URL url = new URI(baseUrl + endpoint).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
        // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 3b: infinite read timeout under debug flag so local LLM has unlimited time to respond. Original: DEFAULT_READ_TIMEOUT_MS (120_000 ms)
        connection.setReadTimeout(LLMCompleter.DEBUG_LLM_PROBE ? 0 : readTimeoutMs); // [DEBUG-INSTR:llm-latency-2026-06-13]
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json; charset=utf-8");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if ((method.equals("POST") || method.equals("PUT")) && requestBody != null) {
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            } catch (Throwable ignored) {
            }
        }
        int responseCode = connection.getResponseCode();
        if (responseCode >= 400) {
            BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorResponse.append(line);
            }
            errorReader.close();
            throw new HttpApiException("HTTP " + responseCode + ": " + connection.getResponseMessage()
                    + " Body: " + errorResponse, responseCode);
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return JsonParser.parseString(response.toString());
    }

    public static Map<String, JsonElement> sendRequest(String baseUrl, String endpoint, String method, JsonObject requestBody,
                                                       @Nullable Map<String, String> extraHeaders)
            throws Exception {
        return sendRequest(baseUrl, endpoint, method, requestBody, extraHeaders, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Like {@link #sendRequest(String, String, String, JsonObject, Map)} but with an explicit read
     * timeout. Use only for calls that require a non-default ceiling (e.g., background ingestion
     * against a slow local model — see {@link #INGESTION_READ_TIMEOUT_MS}).
     * All other callers must use the no-timeout-param overload.
     */
    public static Map<String, JsonElement> sendRequest(String baseUrl, String endpoint, String method, JsonObject requestBody,
                                                       @Nullable Map<String, String> extraHeaders,
                                                       int readTimeoutMs)
            throws Exception {
        if (GatewayConfig.isEnabled()) {
            JsonElement local = GatewayRouter.localResponse(method, endpoint);
            if (local != null) {
                Map<String, JsonElement> localMap = new HashMap<>();
                if (local.isJsonObject()) {
                    for (Entry<String, JsonElement> entry : local.getAsJsonObject().entrySet()) {
                        localMap.put(entry.getKey(), entry.getValue());
                    }
                }
                return localMap;
            }
            baseUrl = GatewayConfig.get().baseUrl();
            requestBody = GatewayRouter.prepareBody(endpoint, requestBody);
            extraHeaders = GatewayRouter.headers();
        }
        URL url = new URI(baseUrl + endpoint).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
        // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 3b: infinite read timeout under debug flag so local LLM has unlimited time to respond. Original: DEFAULT_READ_TIMEOUT_MS (120_000 ms)
        connection.setReadTimeout(LLMCompleter.DEBUG_LLM_PROBE ? 0 : readTimeoutMs); // [DEBUG-INSTR:llm-latency-2026-06-13]
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json; charset=utf-8");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        if ((method.equals("POST") || method.equals("PUT")) && requestBody != null) {
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            } catch (Throwable v) {
            }
        }

        JsonObject jsonResponse = getJsonObject(connection);
        Map<String, JsonElement> responseMap = new HashMap<>();

        for (Entry<String, JsonElement> entry : jsonResponse.entrySet()) {
            responseMap.put(entry.getKey(), entry.getValue());
        }

        return responseMap;
    }

    private static JsonObject getJsonObject(HttpURLConnection connection) throws IOException {
        int responseCode = connection.getResponseCode();

        if (responseCode >= 400) {
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorResponse.append(line);
            }
            errorReader.close();
            throw new HttpApiException("HTTP " + responseCode + ": " + connection.getResponseMessage() + " Body: " + errorResponse, responseCode);
        }

        if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode + ": " + connection.getResponseMessage());
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();

        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }

        reader.close();

        // Some endpoints (e.g. /v1/stt/start) return an empty body on HTTP 200 by contract; a few may
        // return a non-object JSON value. Treat either as "no fields" instead of throwing
        // IllegalStateException ("Not a JSON Object: null"), which previously turned a successful
        // empty-body response into a spurious failure. Genuine errors still surface via the 4xx/5xx
        // branches above (with their JSON error body).
        String body = response.toString().trim();
        if (body.isEmpty()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();

    }
}