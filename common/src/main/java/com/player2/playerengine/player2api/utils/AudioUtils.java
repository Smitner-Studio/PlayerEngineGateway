package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

public class AudioUtils {
    private static final String WEB_API_URL = "https://api.player2.game";
    private static final int HTTP_CONNECT_TIMEOUT_MS = 30_000;
    private static final int HTTP_READ_TIMEOUT_MS = 120_000;

    /**
     * Count of WAV clips currently being played through {@link #playAudioBytes}. Read-only diagnostic
     * signal so STT can record whether the bot's own TTS was playing at push-to-talk press (a candidate
     * aggravator of STT start latency — both hit the same local Player2 app). Bounded counter; never
     * model-facing.
     */
    private static final java.util.concurrent.atomic.AtomicInteger activePlaybackCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** True if at least one TTS clip is currently being played (diagnostic signal for STT). */
    public static boolean isPlaybackActive() {
        return activePlaybackCount.get() > 0;
    }

    /**
     * Synthesize {@code text} to speech and play it synchronously (returns when playback finishes).
     * Convenience wrapper around {@link #fetchAudioBytes} + {@link #playAudioBytes} for single-shot
     * callers that do not need to prefetch.
     */
    public static void streamAudio(String clientId, String token, String text, double speed, String[] voiceIds) {
        playAudioBytes(fetchAudioBytes(clientId, token, text, speed, voiceIds));
    }

    /**
     * Fetch + decode TTS audio to ready-to-play WAV bytes WITHOUT playing it. Splitting fetch from play
     * lets a caller prefetch the NEXT chunk on a background thread while the CURRENT chunk is still
     * playing, so playback is gapless (no synthesis/network stall between chunks). Prefers the local
     * Player2 API ({@code /v1/tts/speak}) and falls back to the web API ({@code /v1/tts/stream}).
     * Returns {@code null} on failure (the caller should treat that chunk as skipped/degraded).
     */
    public static byte[] fetchAudioBytes(String clientId, String token, String text, double speed, String[] voiceIds) {
        if (com.player2.playerengine.player2api.gateway.GatewayConfig.isEnabled()) {
            // Voice is a Player2-platform feature with no gateway equivalent; text chat still works.
            return null;
        }
        String localApiUrl = LocalAPIDiscovery.getLocalApiUrl();
        if (localApiUrl != null) {
            byte[] local = fetchViaLocalApi(localApiUrl, clientId, token, text, speed, voiceIds);
            if (local != null) {
                return local;
            }
            // local failed — fall through to the web API, mirroring the previous fallback behavior
        }
        return fetchViaWebApi(clientId, token, text, speed, voiceIds);
    }

    /**
     * Play already-fetched WAV bytes synchronously through the Java sound system; blocks until the clip
     * finishes ({@code SourceDataLine.drain()}). A {@code null}/empty array is a no-op (e.g. the empty
     * chunk produced by a leading marker).
     */
    public static void playAudioBytes(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length == 0) {
            return;
        }
        activePlaybackCount.incrementAndGet();
        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(wavBytes)))) {

            AudioFormat format = audioStream.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            try (SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info)) {
                sourceDataLine.open(format);
                sourceDataLine.start();

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = audioStream.read(buffer)) != -1) {
                    sourceDataLine.write(buffer, 0, bytesRead);
                }

                sourceDataLine.drain();
                sourceDataLine.stop();
            }
        } catch (Exception e) {
            System.err.println("Error playing TTS audio: " + e.getMessage());
        } finally {
            activePlaybackCount.decrementAndGet();
        }
    }

    /**
     * Local Player2 API {@code /v1/tts/speak} (play_in_app=false): returns the base64-decoded WAV bytes,
     * or {@code null} on any failure so {@link #fetchAudioBytes} can fall back to the web API.
     */
    private static byte[] fetchViaLocalApi(String localApiUrl, String clientId, String token, String text, double speed, String[] voiceIds) {
        HttpURLConnection connection = null;
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("text", text);
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("play_in_app", false);
            requestBody.addProperty("audio_format", "wav");
            JsonArray voiceIdsArray = new JsonArray();
            for (String id : voiceIds) {
                voiceIdsArray.add(id);
            }
            requestBody.add("voice_ids", voiceIdsArray);

            URL url = new URL(localApiUrl + "/v1/tts/speak");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("player2-game-key", clientId);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                System.err.println("Local TTS /tts/speak returned HTTP " + responseCode + ", falling back to web API.");
                return null;
            }

            // Read the JSON response containing base64-encoded audio data
            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            JsonObject responseJson = JsonParser.parseString(responseBuilder.toString()).getAsJsonObject();
            String audioBase64 = responseJson.get("data").getAsString();

            if (audioBase64 == null || audioBase64.isEmpty()) {
                System.err.println("Local TTS returned empty audio data, falling back to web API.");
                return null;
            }
            return Base64.getDecoder().decode(audioBase64);

        } catch (Exception e) {
            System.err.println("Error during local TTS, falling back to web API: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Web API {@code /v1/tts/stream}: reads the full WAV response into memory and returns the bytes so it
     * can be prefetched and played gaplessly. Returns {@code null} on failure.
     */
    private static byte[] fetchViaWebApi(String clientId, String token, String text, double speed, String[] voiceIds) {
        HttpURLConnection connection = null;
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("text", text);
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("audio_format", "wav");
            JsonArray voiceIdsArray = new JsonArray();
            for (String id : voiceIds) {
                voiceIdsArray.add(id);
            }
            requestBody.add("voice_ids", voiceIdsArray);

            URL url = new URL(WEB_API_URL + "/v1/tts/stream");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "audio/wav");
            connection.setRequestProperty("player2-game-key", clientId);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try (InputStream inputStream = connection.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            System.err.println("Error during TTS streaming: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
