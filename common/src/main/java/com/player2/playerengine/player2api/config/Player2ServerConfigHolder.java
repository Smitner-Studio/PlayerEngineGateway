package com.player2.playerengine.player2api.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEnginePaths;
import com.player2.playerengine.util.helpers.ConfigHelper;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads {@code playerengine/server_player2.json}. Thread-safe reads.
 */
public final class Player2ServerConfigHolder {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String CONFIG_PATH = "server_player2.json";

    public static final int MEMORY_EXTRACTION_BATCH_MAX_VALUE = 100;
    public static final int MEMORY_EXTRACTION_LENGTH_THRESHOLD_MAX_VALUE = 32_768;

    private static final Gson READER_GSON = new Gson();

    private static volatile Player2ServerRuntimeConfig cached = defaultConfig();

    private Player2ServerConfigHolder() {
    }

    private static Player2ServerRuntimeConfig defaultConfig() {
        return new Player2ServerRuntimeConfig();
    }

    public static Player2ServerRuntimeConfig get() {
        return cached;
    }

    public static Player2ServerRuntimeConfig copyCurrent() {
        return ConfigHelper.copyConfig(cached, Player2ServerRuntimeConfig.class);
    }

    /**
     * A fresh disk read used by administrative UIs and guarded writes. A failed read returns a
     * defensive live fallback for display only; callers must honor
     * {@link FreshLoadResult#loadFailed()} and must
     * not persist that fallback over the unreadable file.
     */
    public record FreshLoadResult(Player2ServerRuntimeConfig config, boolean loadFailed) {
    }

    public static FreshLoadResult freshLoadResult() {
        Player2ServerRuntimeConfig fresh = null;
        boolean loadFailed = false;
        Path path = PlayerEnginePaths.userFile(CONFIG_PATH);
        if (Files.notExists(path)) {
            fresh = defaultConfig();
        } else {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root == null || !root.isJsonObject()) {
                    loadFailed = true;
                } else {
                    fresh = READER_GSON.fromJson(root, Player2ServerRuntimeConfig.class);
                    loadFailed = fresh == null;
                }
            } catch (IOException | RuntimeException ignored) {
                loadFailed = true;
            }
        }

        if (fresh == null) {
            fresh = copyCurrent();
        }
        if (fresh == null) {
            fresh = defaultConfig();
        }
        validateAndFix(fresh);
        return new FreshLoadResult(fresh, loadFailed);
    }

    public static void load() {
        Player2ServerRuntimeConfig next = ConfigHelper.getConfig(CONFIG_PATH, Player2ServerConfigHolder::defaultConfig,
                Player2ServerRuntimeConfig.class);
        validateAndFix(next);
        cached = next;
        LOGGER.info("Player2 server config: payerMode={} dedicated={} ownerOfflineContinue={} callByNameChat={} maxSpawnedCompanions={} maxStoredCharacterIds={}",
                cached.getPayerMode(), cached.isDedicatedClientProxy(), cached.isOwnerOfflineServerContinuation(),
                cached.isCallByNameChat(), cached.getMaxSpawnedCompanionsPerPlayer(), cached.getMaxStoredCharacterIdsPerPlayer());
        LOGGER.info("Player2 chat completion cap: maxOutputTokens={}",
                cached.getChatCompletionMaxOutputTokensClamped());
        LOGGER.info("Player2 RAG config: ragLiveEnabled={} ragTopK={} ragFallbackToFullList={} ragMinGoalChars={}",
                cached.isRagLiveEnabled(), cached.getRagTopKClamped(), cached.isRagFallbackToFullList(),
                cached.getRagMinGoalCharsClamped());
        LOGGER.info("Player2 TTS pacing config: botTtsPlaybackAckEnabled={} bodylangMarkerPauseMs={} bodylangGaplessPrefetch={}",
                cached.isBotTtsPlaybackAckEnabled(), cached.getBodylangMarkerPauseMs(), cached.isBodylangGaplessPrefetch());
        LOGGER.info("Player2 bot lifecycle config: serverOverridesPlayerConfig={} serverAutoRespawn={} serverBotPermadeath={}",
                cached.isServerOverridesPlayerConfig(), cached.isServerAutoRespawn(),
                cached.getServerBotPermadeath() == null ? "unset" : cached.getServerBotPermadeath());
        LOGGER.info("Player2 ModIntelligence config: enabled={} enrichmentEnabled={} maxEnrichmentCallsPerLaunch={} maxEnrichmentFailuresPerLaunch={}",
                cached.isModIntelligenceEnabled(), cached.isModIntelligenceEnrichmentEnabled(),
                cached.getModIntelligenceMaxEnrichmentCallsPerLaunch() <= 0
                        ? "unlimited" : cached.getModIntelligenceMaxEnrichmentCallsPerLaunch(),
                cached.getModIntelligenceMaxEnrichmentFailuresPerLaunch());
    }

    public static void save() {
        saveChecked();
    }

    public static synchronized boolean saveChecked() {
        Player2ServerRuntimeConfig current = cached;
        if (!hasValidBudgetThresholdPairs(current) || freshLoadResult().loadFailed()) {
            return false;
        }
        validateAndFix(current);
        // Re-publish after mutation so readers that acquire the volatile reference observe all field writes.
        cached = current;
        return ConfigHelper.saveConfigChecked(CONFIG_PATH, current);
    }

    public static synchronized void setAndSave(Player2ServerRuntimeConfig cfg) {
        setAndSaveChecked(cfg);
    }

    public static synchronized boolean setAndSaveChecked(Player2ServerRuntimeConfig cfg) {
        if (cfg == null || !hasValidBudgetThresholdPairs(cfg) || freshLoadResult().loadFailed()) {
            return false;
        }
        validateAndFix(cfg);
        if (!ConfigHelper.saveConfigChecked(CONFIG_PATH, cfg)) {
            return false;
        }
        cached = cfg;
        return true;
    }

    public static void validateAndFix(Player2ServerRuntimeConfig c) {
        if (c == null) {
            return;
        }
        // Gson may assign null directly, bypassing the null-normalising enum setters.
        c.setPayerMode(c.getPayerMode());
        c.setBotKeepInventoryOverride(c.getBotKeepInventoryOverride());
        if (c.isDedicatedClientProxy() && c.isOwnerOfflineServerContinuation()) {
            LOGGER.warn("Player2 config: dedicatedClientProxy and ownerOfflineServerContinuation are mutually exclusive; disabling ownerOfflineServerContinuation.");
            c.setOwnerOfflineServerContinuation(false);
        }
        if (c.getPayerMode() == Player2PayerMode.PROMPTER_PAYS && c.isOwnerOfflineServerContinuation()) {
            LOGGER.warn("Player2 config: ownerOfflineServerContinuation only applies to OWNER_PAYS_ALL; disabling.");
            c.setOwnerOfflineServerContinuation(false);
        }
        int maxSpawn = c.getMaxSpawnedCompanionsPerPlayer();
        if (maxSpawn < 1 || maxSpawn > 20) {
            LOGGER.warn("Player2 config: maxSpawnedCompanionsPerPlayer out of range 1–20 (got {}); using 3.", maxSpawn);
            c.setMaxSpawnedCompanionsPerPlayer(3);
        }
        int maxStored = c.getMaxStoredCharacterIdsPerPlayer();
        if (maxStored < 0 || maxStored > 100) {
            LOGGER.warn("Player2 config: maxStoredCharacterIdsPerPlayer out of range 0–100 (got {}); using 20.", maxStored);
            c.setMaxStoredCharacterIdsPerPlayer(20);
        }
        int chatOutputCap = c.getChatCompletionMaxOutputTokens();
        if (chatOutputCap < 1 || chatOutputCap > 100000) {
            LOGGER.warn("Player2 config: chatCompletionMaxOutputTokens out of range 1-100000 (got {}); using clamped value {}.",
                    chatOutputCap, c.getChatCompletionMaxOutputTokensClamped());
            c.setChatCompletionMaxOutputTokens(c.getChatCompletionMaxOutputTokensClamped());
        }

        // Phase A4: budget field validation
        int window = c.getBudgetWindowMinutes();
        if (window < 1 || window > 1440) {
            LOGGER.warn("Player2 config: budgetWindowMinutes out of range 1–1440 (got {}); using 60.", window);
            c.setBudgetWindowMinutes(60);
        }
        int joulesRefresh = c.getJoulesRefreshIntervalSeconds();
        if (joulesRefresh < 60 || joulesRefresh > 86400) {
            LOGGER.warn("Player2 config: joulesRefreshIntervalSeconds out of range 60–86400 (got {}); using 300.", joulesRefresh);
            c.setJoulesRefreshIntervalSeconds(300);
        }
        int soft = c.getSoftBudgetCallsPerWindow();
        int hard = c.getHardBudgetCallsPerWindow();
        if (soft > 0 && hard > 0 && soft >= hard) {
            c.setSoftBudgetCallsPerWindow(0);
            LOGGER.warn("Player2 config: softBudgetCallsPerWindow ({}) >= hardBudgetCallsPerWindow ({}) — soft should be lower than hard.", soft, hard);
        }

        int softJoules = c.getSoftJoulesThreshold();
        int hardJoules = c.getHardJoulesThreshold();
        if (softJoules > 0 && hardJoules > 0 && softJoules >= hardJoules) {
            LOGGER.warn("Player2 config: softJoulesThreshold ({}) >= hardJoulesThreshold ({}); disabling the invalid soft limit.", softJoules, hardJoules);
            c.setSoftJoulesThreshold(0);
        }

        int ragTopK = c.getRagTopK();
        if (ragTopK < 1 || ragTopK > 50) {
            LOGGER.warn("Player2 config: ragTopK out of range 1–50 (got {}); using clamped value {}.", ragTopK, c.getRagTopKClamped());
            c.setRagTopK(c.getRagTopKClamped());
        }
        int ragMin = c.getRagMinGoalChars();
        if (ragMin < 1 || ragMin > 16) {
            LOGGER.warn("Player2 config: ragMinGoalChars out of range 1–16 (got {}); using clamped value {}.", ragMin, c.getRagMinGoalCharsClamped());
            c.setRagMinGoalChars(c.getRagMinGoalCharsClamped());
        }

        // Phase D: keep manual edits and GUI edits on one bounded, persisted contract.
        c.setMemoryWindowMinutes(c.getMemoryWindowMinutesClamped());
        int batchMin = clamp(c.getMemoryExtractionBatchMin(), 1, MEMORY_EXTRACTION_BATCH_MAX_VALUE);
        int batchMax = clamp(c.getMemoryExtractionBatchMax(), 1, MEMORY_EXTRACTION_BATCH_MAX_VALUE);
        if (batchMax < batchMin) {
            batchMax = batchMin;
        }
        c.setMemoryExtractionBatchMin(batchMin);
        c.setMemoryExtractionBatchMax(batchMax);
        c.setMemoryExtractionLengthThreshold(clamp(c.getMemoryExtractionLengthThreshold(),
                1, MEMORY_EXTRACTION_LENGTH_THRESHOLD_MAX_VALUE));
        c.setMemoryMaxHops(c.getMemoryMaxHopsClamped());
        c.setMemoryMaxEgoNodes(c.getMemoryMaxEgoNodesClamped());
        c.setMemoryBlockCharCap(c.getMemoryBlockCharCapClamped());
        c.setMemoryMinConfidence(normalizeUnitInterval(c.getMemoryMinConfidence(), 0.20));
        double decayBase = c.getMemoryDecayBase();
        c.setMemoryDecayBase(Double.isFinite(decayBase) && decayBase > 0.0 && decayBase <= 1.0
                ? decayBase : 0.995);
        c.setMemoryRetrievalTopK(c.getMemoryRetrievalTopKClamped());
        c.setReflectionImportanceThreshold(c.getReflectionImportanceThresholdClamped());
        c.setRelationshipSummaryCharCap(c.getRelationshipSummaryCharCapClamped());

        int inspectCap = c.getModIntelligenceMaxInspectEntriesPerLaunch();
        if (inspectCap < 0 || inspectCap > 50000) {
            LOGGER.warn("Player2 config: modIntelligenceMaxInspectEntriesPerLaunch out of range 0–50000 (got {}); using 5000.", inspectCap);
            c.setModIntelligenceMaxInspectEntriesPerLaunch(5000);
        }
        int enrichCalls = c.getModIntelligenceMaxEnrichmentCallsPerLaunch();
        if (enrichCalls < 0) {
            LOGGER.warn("Player2 config: modIntelligenceMaxEnrichmentCallsPerLaunch is negative (got {}); using 50. Use 0 for unlimited.", enrichCalls);
            c.setModIntelligenceMaxEnrichmentCallsPerLaunch(50);
        }
        int enrichFails = c.getModIntelligenceMaxEnrichmentFailuresPerLaunch();
        if (enrichFails < 1 || enrichFails > 500) {
            LOGGER.warn("Player2 config: modIntelligenceMaxEnrichmentFailuresPerLaunch out of range 1–500 (got {}); using 20.", enrichFails);
            c.setModIntelligenceMaxEnrichmentFailuresPerLaunch(20);
        }
        int capTopK = c.getModIntelligenceQueryTopK();
        if (capTopK < 1 || capTopK > 50) {
            c.setModIntelligenceQueryTopK(c.getModIntelligenceQueryTopKClamped());
        }
        double minConf = c.getModIntelligenceMinQueryConfidence();
        if (!Double.isFinite(minConf) || minConf < 0.0 || minConf > 1.0) {
            LOGGER.warn("Player2 config: modIntelligenceMinQueryConfidence out of range 0–1 (got {}); using 0.5.", minConf);
            c.setModIntelligenceMinQueryConfidence(0.5);
        }
        int obsCap = c.getModIntelligenceMaxObservedSamplesPerSubject();
        if (obsCap < 0 || obsCap > 50) {
            LOGGER.warn("Player2 config: modIntelligenceMaxObservedSamplesPerSubject out of range 0–50 (got {}); using 5.", obsCap);
            c.setModIntelligenceMaxObservedSamplesPerSubject(5);
        }

        int markerPause = c.getBodylangMarkerPauseMs();
        if (markerPause < 0 || markerPause > 2000) {
            LOGGER.warn("Player2 config: bodylangMarkerPauseMs out of range 0–2000 (got {}); clamping.", markerPause);
            c.setBodylangMarkerPauseMs(markerPause); // setter clamps to [0, 2000]
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeUnitInterval(double value, double nonFiniteFallback) {
        if (!Double.isFinite(value)) {
            return nonFiniteFallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Strict persistence invariant for the specialized Budget & Joules controls. */
    public static boolean hasValidBudgetThresholdPairs(BudgetThresholds thresholds) {
        if (thresholds == null) {
            return false;
        }
        int softCalls = thresholds.getSoftBudgetCallsPerWindow();
        int hardCalls = thresholds.getHardBudgetCallsPerWindow();
        if (softCalls > 0 && hardCalls > 0 && softCalls >= hardCalls) {
            return false;
        }
        int softJoules = thresholds.getSoftJoulesThreshold();
        int hardJoules = thresholds.getHardJoulesThreshold();
        return softJoules <= 0 || hardJoules <= 0 || softJoules < hardJoules;
    }

    /** When true, companion controller tick may send /v1/health from the server for owner-paid mode. */
    public static boolean shouldSendServerControllerHeartbeat(Player2ServerRuntimeConfig c,
            boolean ownerTokenPresent, boolean ownerClientOnline) {
        if (c.getPayerMode() != Player2PayerMode.OWNER_PAYS_ALL) {
            return false;
        }
        if (c.isDedicatedClientProxy()) {
            return false;
        }
        if (ownerClientOnline) {
            return true;
        }
        return c.isOwnerOfflineServerContinuation() && ownerTokenPresent;
    }

    /** True when each player's client should periodically send /v1/health for usage attribution. */
    public static boolean shouldClientSendPlayerHeartbeat(Player2ServerRuntimeConfig c) {
        return c.isDedicatedClientProxy() || c.getPayerMode() == Player2PayerMode.PROMPTER_PAYS;
    }

    public static boolean serverHasPlayer2ConnectivityGuess(MinecraftServer server) {
        return com.player2.playerengine.player2api.utils.LocalAPIDiscovery.getLocalApiUrl() != null
                || true;
    }
}
