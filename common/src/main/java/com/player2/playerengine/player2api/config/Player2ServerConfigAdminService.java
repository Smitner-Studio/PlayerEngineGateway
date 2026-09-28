package com.player2.playerengine.player2api.config;

import com.player2.playerengine.player2api.ProfileUrlResolver;
import com.player2.playerengine.player2api.network.Player2ServerNetworking;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Permission-neutral service boundary for the server-wide Player2 config UI.
 * Callers must enforce operator permission before invoking {@link #update}.
 */
public final class Player2ServerConfigAdminService {
    private static final Logger LOGGER = LogManager.getLogger();

    public enum UpdateResult {
        OK,
        INVALID_VALUE,
        UNSUPPORTED_SETTING,
        LOAD_FAILED,
        SAVED_LIVE_APPLY_FAILED,
        SAVE_FAILED
    }

    public record SnapshotResult(Map<String, String> values, boolean loadFailed) {
    }

    public record LivePublicationResult(
            boolean cacheInvalidationRequired,
            boolean cacheInvalidationSucceeded,
            boolean clientSyncRequired,
            Player2ServerNetworking.ConfigSyncResult clientSyncResult) {

        public boolean fullyApplied() {
            return (!cacheInvalidationRequired || cacheInvalidationSucceeded)
                    && (!clientSyncRequired
                    || (clientSyncResult != null && clientSyncResult.fullySynchronized()));
        }
    }

    private Player2ServerConfigAdminService() {
    }

    /** Returns an immutable snapshot using the same integer-only wire representation as {@link #update}. */
    public static Map<String, String> snapshot() {
        return snapshotResult().values();
    }

    public static SnapshotResult snapshotResult() {
        Player2ServerConfigHolder.FreshLoadResult fresh = Player2ServerConfigHolder.freshLoadResult();
        return new SnapshotResult(snapshotOf(fresh.config()), fresh.loadFailed());
    }

    private static Map<String, String> snapshotOf(Player2ServerRuntimeConfig cfg) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();

        fields.put("payerMode", cfg.getPayerMode().name());
        fields.put("ownerOfflineServerContinuation", bool(cfg.isOwnerOfflineServerContinuation()));
        fields.put("callByNameChat", bool(cfg.isCallByNameChat()));
        fields.put("maxSpawnedCompanionsPerPlayer", integer(cfg.getMaxSpawnedCompanionsPerPlayer()));
        fields.put("maxStoredCharacterIdsPerPlayer", integer(cfg.getMaxStoredCharacterIdsPerPlayer()));
        fields.put("chatCompletionMaxOutputTokens", integer(cfg.getChatCompletionMaxOutputTokensClamped()));

        fields.put("ragFallbackToFullList", bool(cfg.isRagFallbackToFullList()));
        fields.put("ragLiveEnabled", bool(cfg.isRagLiveEnabled()));
        fields.put("ragMinGoalChars", integer(cfg.getRagMinGoalCharsClamped()));

        fields.put("memoryWindowMinutes", integer(cfg.getMemoryWindowMinutesClamped()));
        fields.put("memoryExtractionBatchMin", integer(cfg.getMemoryExtractionBatchMin()));
        fields.put("memoryExtractionBatchMax", integer(cfg.getMemoryExtractionBatchMax()));
        fields.put("memoryExtractionLengthThreshold", integer(cfg.getMemoryExtractionLengthThreshold()));
        fields.put("memoryMaxHops", integer(cfg.getMemoryMaxHopsClamped()));
        fields.put("memoryMaxEgoNodes", integer(cfg.getMemoryMaxEgoNodesClamped()));
        fields.put("memoryBlockCharCap", integer(cfg.getMemoryBlockCharCapClamped()));
        fields.put("memoryMinConfidence", percent(cfg.getMemoryMinConfidenceClamped(), 0.20));
        fields.put("memoryDecayBase", permille(cfg.getMemoryDecayBaseClamped()));
        fields.put("memoryRetrievalTopK", integer(cfg.getMemoryRetrievalTopKClamped()));
        fields.put("reflectionImportanceThreshold", integer(cfg.getReflectionImportanceThresholdClamped()));
        fields.put("relationshipSummaryCharCap", integer(cfg.getRelationshipSummaryCharCapClamped()));

        fields.put("modIntelligenceInspectOnLaunch", bool(cfg.isModIntelligenceInspectOnLaunch()));
        fields.put("modIntelligenceMaxInspectEntriesPerLaunch",
                integer(cfg.getModIntelligenceMaxInspectEntriesPerLaunch()));
        fields.put("modIntelligenceMaxEnrichmentFailuresPerLaunch",
                integer(cfg.getModIntelligenceMaxEnrichmentFailuresPerLaunch()));
        fields.put("modIntelligenceQueryTopK", integer(cfg.getModIntelligenceQueryTopKClamped()));
        fields.put("modIntelligenceMinQueryConfidence",
                percent(cfg.getModIntelligenceMinQueryConfidence(), 0.50));

        fields.put("botTtsPlaybackAckEnabled", bool(cfg.isBotTtsPlaybackAckEnabled()));
        fields.put("serverOverridesPlayerConfig", bool(cfg.isServerOverridesPlayerConfig()));
        fields.put("botKeepInventoryOverride", cfg.getBotKeepInventoryOverride().name());
        return Collections.unmodifiableMap(fields);
    }

    /**
     * Applies one exact config key. Values are deliberately integer-only: unit-interval doubles use
     * whole percentages (0..100), while {@code memoryDecayBase} uses permille (1..1000).
     */
    public static synchronized UpdateResult update(MinecraftServer server, String key, String value) {
        if (server == null) {
            return UpdateResult.INVALID_VALUE;
        }
        if (key == null) {
            return UpdateResult.UNSUPPORTED_SETTING;
        }

        Player2ServerRuntimeConfig previousLive = Player2ServerConfigHolder.copyCurrent();
        Player2ServerConfigHolder.FreshLoadResult fresh = Player2ServerConfigHolder.freshLoadResult();
        if (fresh.loadFailed()) {
            return UpdateResult.LOAD_FAILED;
        }
        Player2ServerRuntimeConfig cfg = fresh.config();
        if (cfg == null) {
            return UpdateResult.SAVE_FAILED;
        }

        UpdateResult applyResult = apply(cfg, key, value);
        if (applyResult != UpdateResult.OK) {
            return applyResult;
        }
        if (!Player2ServerConfigHolder.hasValidBudgetThresholdPairs(cfg)) {
            return UpdateResult.INVALID_VALUE;
        }
        try {
            if (!Player2ServerConfigHolder.setAndSaveChecked(cfg)) {
                return UpdateResult.SAVE_FAILED;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to persist a Player2 server-config admin update.", e);
            return UpdateResult.SAVE_FAILED;
        }

        LivePublicationResult publication = publishLiveChanges(server, previousLive, cfg);
        return publication.fullyApplied()
                ? UpdateResult.OK
                : UpdateResult.SAVED_LIVE_APPLY_FAILED;
    }

    private static LivePublicationResult publishLiveChanges(
            MinecraftServer server,
            Player2ServerRuntimeConfig previous,
            Player2ServerRuntimeConfig current) {
        boolean cacheRequired = previous == null || previous.getPayerMode() != current.getPayerMode();
        boolean cacheSucceeded = !cacheRequired;
        if (cacheRequired) {
            try {
                ProfileUrlResolver.invalidateCache();
                cacheSucceeded = true;
            } catch (RuntimeException e) {
                LOGGER.warn("Unable to invalidate the Player2 profile cache after a config update; continuing.", e);
            }
        }

        boolean syncRequired = clientSyncFieldsChanged(previous, current);
        Player2ServerNetworking.ConfigSyncResult syncResult = null;
        if (syncRequired) {
            try {
                syncResult = Player2ServerNetworking.sendConfigSyncToAll(server);
            } catch (RuntimeException e) {
                LOGGER.warn("Unable to publish a Player2 server-config update to connected clients.", e);
            }
        }
        return new LivePublicationResult(cacheRequired, cacheSucceeded, syncRequired, syncResult);
    }

    private static boolean clientSyncFieldsChanged(
            Player2ServerRuntimeConfig previous,
            Player2ServerRuntimeConfig current) {
        return previous == null
                || previous.isDedicatedClientProxy() != current.isDedicatedClientProxy()
                || previous.getPayerMode() != current.getPayerMode()
                || previous.isOwnerOfflineServerContinuation() != current.isOwnerOfflineServerContinuation()
                || !Objects.equals(previous.getHeartbeatClientId(), current.getHeartbeatClientId())
                || previous.getBodylangMarkerPauseMs() != current.getBodylangMarkerPauseMs()
                || previous.isBodylangGaplessPrefetch() != current.isBodylangGaplessPrefetch();
    }

    private static UpdateResult apply(Player2ServerRuntimeConfig cfg, String key, String value) {
        return switch (key) {
            case "payerMode" -> setPayerMode(cfg, value);
            case "ownerOfflineServerContinuation" -> setOwnerOfflineContinuation(cfg, value);
            case "callByNameChat" -> setBoolean(value, cfg::setCallByNameChat);
            case "maxSpawnedCompanionsPerPlayer" -> setInteger(value, 1, 20,
                    cfg::setMaxSpawnedCompanionsPerPlayer);
            case "maxStoredCharacterIdsPerPlayer" -> setInteger(value, 0, 100,
                    cfg::setMaxStoredCharacterIdsPerPlayer);
            case "chatCompletionMaxOutputTokens" -> setInteger(value, 1, 100_000,
                    cfg::setChatCompletionMaxOutputTokens);

            case "ragFallbackToFullList" -> setBoolean(value, cfg::setRagFallbackToFullList);
            case "ragLiveEnabled" -> setBoolean(value, cfg::setRagLiveEnabled);
            case "ragMinGoalChars" -> setInteger(value, 1, 16, cfg::setRagMinGoalChars);

            case "memoryWindowMinutes" -> setInteger(value, 1, 1_440, cfg::setMemoryWindowMinutes);
            case "memoryExtractionBatchMin" -> setMemoryBatchMin(cfg, value);
            case "memoryExtractionBatchMax" -> setMemoryBatchMax(cfg, value);
            case "memoryExtractionLengthThreshold" -> setInteger(value, 1,
                    Player2ServerConfigHolder.MEMORY_EXTRACTION_LENGTH_THRESHOLD_MAX_VALUE,
                    cfg::setMemoryExtractionLengthThreshold);
            case "memoryMaxHops" -> setInteger(value, 1, 6, cfg::setMemoryMaxHops);
            case "memoryMaxEgoNodes" -> setInteger(value, 1, 4_096, cfg::setMemoryMaxEgoNodes);
            case "memoryBlockCharCap" -> setInteger(value, 0, 8_000, cfg::setMemoryBlockCharCap);
            case "memoryMinConfidence" -> setPercent(value, cfg::setMemoryMinConfidence);
            case "memoryDecayBase" -> setPermille(value, cfg::setMemoryDecayBase);
            case "memoryRetrievalTopK" -> setInteger(value, 1, 50, cfg::setMemoryRetrievalTopK);
            case "reflectionImportanceThreshold" -> setInteger(value, 1, 100_000,
                    cfg::setReflectionImportanceThreshold);
            case "relationshipSummaryCharCap" -> setInteger(value, 0, 1_000,
                    cfg::setRelationshipSummaryCharCap);

            case "modIntelligenceInspectOnLaunch" -> setBoolean(value,
                    cfg::setModIntelligenceInspectOnLaunch);
            case "modIntelligenceMaxInspectEntriesPerLaunch" -> setInteger(value, 0, 50_000,
                    cfg::setModIntelligenceMaxInspectEntriesPerLaunch);
            case "modIntelligenceMaxEnrichmentFailuresPerLaunch" -> setInteger(value, 1, 500,
                    cfg::setModIntelligenceMaxEnrichmentFailuresPerLaunch);
            case "modIntelligenceQueryTopK" -> setInteger(value, 1, 50,
                    cfg::setModIntelligenceQueryTopK);
            case "modIntelligenceMinQueryConfidence" -> setPercent(value,
                    cfg::setModIntelligenceMinQueryConfidence);

            case "botTtsPlaybackAckEnabled" -> setBoolean(value, cfg::setBotTtsPlaybackAckEnabled);
            case "serverOverridesPlayerConfig" -> setBoolean(value, cfg::setServerOverridesPlayerConfig);
            case "botKeepInventoryOverride" -> setKeepInventoryOverride(cfg, value);
            default -> UpdateResult.UNSUPPORTED_SETTING;
        };
    }

    private static UpdateResult setPayerMode(Player2ServerRuntimeConfig cfg, String raw) {
        String value = normalize(raw);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        try {
            cfg.setPayerMode(Player2PayerMode.valueOf(value));
            return UpdateResult.OK;
        } catch (IllegalArgumentException ignored) {
            return UpdateResult.INVALID_VALUE;
        }
    }

    private static UpdateResult setOwnerOfflineContinuation(Player2ServerRuntimeConfig cfg, String raw) {
        Boolean value = parseBoolean(raw);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        if (value && (cfg.getPayerMode() != Player2PayerMode.OWNER_PAYS_ALL
                || cfg.isDedicatedClientProxy())) {
            return UpdateResult.INVALID_VALUE;
        }
        cfg.setOwnerOfflineServerContinuation(value);
        return UpdateResult.OK;
    }

    private static UpdateResult setKeepInventoryOverride(Player2ServerRuntimeConfig cfg, String raw) {
        String value = normalize(raw);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        try {
            cfg.setBotKeepInventoryOverride(KeepInventoryOverride.valueOf(value));
            return UpdateResult.OK;
        } catch (IllegalArgumentException ignored) {
            return UpdateResult.INVALID_VALUE;
        }
    }

    private static UpdateResult setMemoryBatchMin(Player2ServerRuntimeConfig cfg, String raw) {
        Integer value = parseInteger(raw, 1, Player2ServerConfigHolder.MEMORY_EXTRACTION_BATCH_MAX_VALUE);
        if (value == null || value > cfg.getMemoryExtractionBatchMax()) {
            return UpdateResult.INVALID_VALUE;
        }
        cfg.setMemoryExtractionBatchMin(value);
        return UpdateResult.OK;
    }

    private static UpdateResult setMemoryBatchMax(Player2ServerRuntimeConfig cfg, String raw) {
        Integer value = parseInteger(raw, 1, Player2ServerConfigHolder.MEMORY_EXTRACTION_BATCH_MAX_VALUE);
        if (value == null || value < cfg.getMemoryExtractionBatchMin()) {
            return UpdateResult.INVALID_VALUE;
        }
        cfg.setMemoryExtractionBatchMax(value);
        return UpdateResult.OK;
    }

    private static UpdateResult setBoolean(String raw, Consumer<Boolean> setter) {
        Boolean value = parseBoolean(raw);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        setter.accept(value);
        return UpdateResult.OK;
    }

    private static UpdateResult setInteger(String raw, int min, int max, IntConsumer setter) {
        Integer value = parseInteger(raw, min, max);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        setter.accept(value);
        return UpdateResult.OK;
    }

    private static UpdateResult setPercent(String raw, DoubleConsumer setter) {
        Integer value = parseInteger(raw, 0, 100);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        setter.accept(value / 100.0);
        return UpdateResult.OK;
    }

    private static UpdateResult setPermille(String raw, DoubleConsumer setter) {
        Integer value = parseInteger(raw, 1, 1_000);
        if (value == null) {
            return UpdateResult.INVALID_VALUE;
        }
        setter.accept(value / 1000.0);
        return UpdateResult.OK;
    }

    private static Boolean parseBoolean(String raw) {
        String value = normalize(raw);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer parseInteger(String raw, int min, int max) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static String bool(boolean value) {
        return String.valueOf(value);
    }

    private static String integer(int value) {
        return String.valueOf(value);
    }

    private static String percent(double value, double fallback) {
        double safe = Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : fallback;
        return integer((int) Math.round(safe * 100.0));
    }

    private static String permille(double value) {
        double safe = Double.isFinite(value) && value > 0.0 && value <= 1.0 ? value : 0.995;
        return integer((int) Math.round(safe * 1000.0));
    }
}
