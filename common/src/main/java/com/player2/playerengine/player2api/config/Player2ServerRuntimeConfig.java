package com.player2.playerengine.player2api.config;

import com.player2.playerengine.executor.BudgetFallbackBehavior;

/**
 * Persisted server-side settings for Player2 API routing (see DESIGN.md §6).
 *
 * <p>Implements {@link BudgetThresholds} so it can be passed directly to budget enforcement
 * utilities (used in OWNER_PAYS_ALL mode or as the server-wide fallback).
 */
public class Player2ServerRuntimeConfig implements BudgetThresholds {
    private Player2PayerMode payerMode = Player2PayerMode.PROMPTER_PAYS;
    private boolean dedicatedClientProxy = false;
    private boolean ownerOfflineServerContinuation = false;
    /** When true, automatons only receive chat that opens with their character name or short name. */
    private boolean callByNameChat = true;
    /** Client id used for optional client-side heartbeat when enabled (e.g. NPC game id). */
    private String heartbeatClientId = "player2-ai-npc-minecraft";
    /** Max AI companions alive in the world per owning player (1–20). */
    private int maxSpawnedCompanionsPerPlayer = 3;
    /** Max distinct character IDs with on-disk storage per player; 0 = unlimited (0–100). */
    private int maxStoredCharacterIdsPerPlayer = 20;
    /** Default max output tokens for mod-built /v1/chat/completions requests. Clamped [1, 100000]. */
    private int chatCompletionMaxOutputTokens = 10000;

    // --- Phase A4: Cost-safety controls ---

    /** Warn/switch profile when call count exceeds this per window; 0 = disabled. */
    private int softBudgetCallsPerWindow = 0;
    /** Block new AI calls when call count exceeds this per window; 0 = disabled. */
    private int hardBudgetCallsPerWindow = 0;
    /** Rolling window duration for call-count limits, in minutes [1–1440]. */
    private int budgetWindowMinutes = 60;

    /** Warn/switch profile when cached Joules balance falls below this; 0 = disabled. */
    private int softJoulesThreshold = 0;
    /** Block new AI calls when cached Joules balance falls below this; 0 = disabled. */
    private int hardJoulesThreshold = 0;
    /** How often to re-poll GET /v1/joules per billing key, in seconds [60–86400]. */
    private int joulesRefreshIntervalSeconds = 300;

    /** Named profile for soft-limit fallback; null means the implicit Default profile. */
    private String fallbackProfile = null;
    /** What to do at soft limits: SWITCH_PROFILE or HARD_STOP. */
    private BudgetFallbackBehavior budgetFallbackBehavior = BudgetFallbackBehavior.HARD_STOP;

    // --- Phase B3: RAG live prompt ---

    /** Max tools injected into the NPC system prompt per turn (clamped 1–50 on read). */
    private int ragTopK = 12;
    /** When retrieval fails, fall back to the full command list instead of always-include only. */
    private boolean ragFallbackToFullList = true;
    /** When false, NPC prompts use the full command list (pre-B3 behavior). */
    private boolean ragLiveEnabled = true;
    /** Minimum alphanumeric goal length before running retrieval (clamped 1–16 on read). */
    private int ragMinGoalChars = 3;

    // --- Phase D: GraphRAG roleplay memory (W7 — master gate + memory-pipeline windowed cap) ---

    /**
     * Master on/off switch for the Phase D GraphRAG long-term memory pipeline. Default {@code true}
     * as of W9 — memory is the PRIMARY, free path and is available to NON-PATRON owners (see
     * {@code masterplan/phase-d-w8-embeddings-build-plan.md} §B.2). This is now a plain feature
     * on/off, NOT a patron gate: the patron wall is preserved only behind the manual-only
     * {@code MemoryPatronFallback.MEMORY_PATRON_FALLBACK} toggle in {@code MemoryGate}. A server owner
     * may still set this {@code false} to disable the whole memory engine. The later integration pass
     * adds the W3/W4/W5/W6 tuning keys; W7 owns only this flag + the two window keys below.
     */
    private boolean enableGraphRagMemory = true;

    /**
     * Master switch for companion mood roleplay (the model declares a mood change + the current mood is
     * injected into the per-turn tail). Default {@code true} — mood roleplay is on by default for every
     * companion (owner-ratified). Off → no mood parse, no tail {@code currentMood} key, no mood writes;
     * the <b>per-turn tail</b> is then byte-identical to pre-feature (the static system-block mood
     * instruction is unconditional). mood→memory is governed SEPARATELY by {@link #enableGraphRagMemory}
     * + the patron gate, so it can never fire when memory is off even if this flag is on.
     */
    private boolean enableCompanionMood = true;
    /** Hard per-billing-key memory LLM calls per window (worst-case spend lever). Clamped [0, 1000]. */
    private int memoryCallsPerWindow = 50;
    /** Memory-pipeline window length in minutes. Clamped [1, 1440]. */
    private int memoryWindowMinutes = 60;
    // --- Phase D: GraphRAG roleplay memory (W3/W4/W5/W6 tuning keys — integration pass) ---
    // Key NAMES + defaults below are a cross-branch parity contract (must be byte-identical on 1.21.1).

    /** W3: gated-turn batch size floor before one extraction fires. */
    private int memoryExtractionBatchMin = 5;
    /** W3: gated-turn batch buffer ceiling (recency-biased drop above this). */
    private int memoryExtractionBatchMax = 10;
    /** W3: zero-LLM extraction eligibility length floor (chars). */
    private int memoryExtractionLengthThreshold = 40;
    /** W4: MinHash fuzzy-resolution length floor; mentions shorter than this skip layer 2. */
    private int memoryLayer2MinChars = 20;
    /** W5: ego-graph BFS hop bound. */
    private int memoryMaxHops = 2;
    /** W5: ego-graph BFS visited-node bound. */
    private int memoryMaxEgoNodes = 64;
    /** W5: tail-injected memory block char cap (~400 tokens). */
    private int memoryBlockCharCap = 1600;
    /** W5: knowledge-boundary confidence threshold. Clamped [0, 1]. */
    private double memoryMinConfidence = 0.20;
    /** W5/W6: per-game-hour recency decay base. */
    private double memoryDecayBase = 0.995;
    /** W5/W6: ticks per game-time unit used for recency decay. */
    private long memoryGameTimeUnit = 24000;
    /** W6/W5: scored hits injected per turn. Clamped [1, 50]. */
    private int memoryRetrievalTopK = 5;
    /** W6: cumulative-importance reflection trigger. Clamped [1, 100000]. */
    private int reflectionImportanceThreshold = 150;
    /** W6: relationship-summary char cap (prefix-size budget). Clamped [0, 1000]. */
    private int relationshipSummaryCharCap = 280;
    /** W6: importance rubric version (advisory; pairs with MemoryStore.setImportanceRubricVersion). */
    private int importanceRubricVersion = 1;

    // --- Phase B4: Mod intelligence ---

    private boolean modIntelligenceEnabled = true;
    private boolean modIntelligenceInspectOnLaunch = true;
    private boolean modIntelligenceEnrichmentEnabled = false;
    private int modIntelligenceMaxInspectEntriesPerLaunch = 5000;
    /** Per-batch enrichment API call cap; 0 = unlimited (the joules budget remains the spend safeguard). */
    private int modIntelligenceMaxEnrichmentCallsPerLaunch = 50;
    private int modIntelligenceMaxEnrichmentFailuresPerLaunch = 20;
    private int modIntelligenceQueryTopK = 12;
    private double modIntelligenceMinQueryConfidence = 0.5;
    private boolean modIntelligenceObserveRuntimeNbt = false;
    private int modIntelligenceMaxObservedSamplesPerSubject = 5;
    /**
     * When true on integrated singleplayer, skip B4.5 large-queue budget gate.
     * Ignored on dedicated servers (per-player limits always apply there).
     */
    private boolean modIntelligenceBypassLargeQueueBudgetGate = false;
    /**
     * When true on integrated singleplayer, skip B4.5 model blacklist checks for enrichment.
     * Ignored on dedicated servers.
     */
    private boolean modIntelligenceBypassModelBlacklist = false;

    /** When true, owner clients may send {@code tts_playback_done} to early-clear speaker cooldown. */
    private boolean botTtsPlaybackAckEnabled = false;

    /**
     * Bodylang TTS-timed gestures: legacy inter-chunk pause beat (ms). Clamped to [0, 2000].
     *
     * <p><strong>DEPRECATED / no longer consumed:</strong> client TTS playback is now unconditionally
     * gapless (no pause beat), so this key no longer affects playback. Retained only for config/wire
     * compatibility.
     */
    private int bodylangMarkerPauseMs = 250;
    /**
     * Bodylang TTS-timed gestures: when true, prefetch/synthesize the next chunk during the current
     * chunk's playback to minimize the inter-chunk gap; the marker pause beat then becomes 0.
     *
     * <p><strong>DEPRECATED / no longer consumed:</strong> client TTS playback is now UNCONDITIONALLY
     * gapless (prefetch always on, no pause beat) in {@code PlayerEngineClient.playChunksSequentially},
     * so this key no longer affects playback. Retained only for config/wire compatibility.
     */
    private boolean bodylangGaplessPrefetch = true;

    // --- Bot lifecycle: auto-respawn + permadeath toggles ---

    /**
     * When true, the server-level lifecycle config wins over each player's per-player config
     * (default ON: server overrides). Consulted only on dedicated servers; integrated/LAN/SP
     * always use the server config. See {@code BotLifecycleSettingsResolver}.
     */
    private boolean serverOverridesPlayerConfig = true;
    /** Server-level auto-respawn value (used in SP/LAN, or on a dedicated server when override is ON). */
    private boolean serverAutoRespawn = true;
    /**
     * Server-level bot permadeath, tri-state (Model A): {@code null} = unset, so the resolver falls back
     * to {@code world.isHardcore()}; explicit {@code true}/{@code false} is honored verbatim (OFF works
     * even in hardcore). Gson omits the key when {@code null}, and an absent key deserializes back to
     * {@code null} (= unset). Nullable on purpose — do not change to a primitive boolean.
     */
    private Boolean serverBotPermadeath = null;
    /** Server-sided override for bot keepInventory. Default FOLLOW_GAMERULE = obey the world gamerule. */
    private KeepInventoryOverride botKeepInventoryOverride = KeepInventoryOverride.FOLLOW_GAMERULE;

    public Player2PayerMode getPayerMode() {
        return payerMode == null ? Player2PayerMode.PROMPTER_PAYS : payerMode;
    }

    public void setPayerMode(Player2PayerMode payerMode) {
        this.payerMode = payerMode == null ? Player2PayerMode.PROMPTER_PAYS : payerMode;
    }

    public boolean isDedicatedClientProxy() {
        return dedicatedClientProxy;
    }

    public void setDedicatedClientProxy(boolean dedicatedClientProxy) {
        this.dedicatedClientProxy = dedicatedClientProxy;
    }

    public boolean isOwnerOfflineServerContinuation() {
        return ownerOfflineServerContinuation;
    }

    public void setOwnerOfflineServerContinuation(boolean ownerOfflineServerContinuation) {
        this.ownerOfflineServerContinuation = ownerOfflineServerContinuation;
    }

    public boolean isCallByNameChat() {
        return callByNameChat;
    }

    public void setCallByNameChat(boolean callByNameChat) {
        this.callByNameChat = callByNameChat;
    }

    public String getHeartbeatClientId() {
        return heartbeatClientId == null || heartbeatClientId.isBlank()
                ? "player2-ai-npc-minecraft"
                : heartbeatClientId;
    }

    public void setHeartbeatClientId(String heartbeatClientId) {
        this.heartbeatClientId = heartbeatClientId;
    }

    public int getMaxSpawnedCompanionsPerPlayer() {
        return maxSpawnedCompanionsPerPlayer;
    }

    public void setMaxSpawnedCompanionsPerPlayer(int maxSpawnedCompanionsPerPlayer) {
        this.maxSpawnedCompanionsPerPlayer = maxSpawnedCompanionsPerPlayer;
    }

    public int getMaxStoredCharacterIdsPerPlayer() {
        return maxStoredCharacterIdsPerPlayer;
    }

    public void setMaxStoredCharacterIdsPerPlayer(int maxStoredCharacterIdsPerPlayer) {
        this.maxStoredCharacterIdsPerPlayer = maxStoredCharacterIdsPerPlayer;
    }

    public int getChatCompletionMaxOutputTokens() {
        return chatCompletionMaxOutputTokens;
    }

    public void setChatCompletionMaxOutputTokens(int chatCompletionMaxOutputTokens) {
        this.chatCompletionMaxOutputTokens = chatCompletionMaxOutputTokens;
    }

    /** Clamped to [1, 100000]. */
    public int getChatCompletionMaxOutputTokensClamped() {
        int n = chatCompletionMaxOutputTokens;
        if (n < 1) return 1;
        if (n > 100000) return 100000;
        return n;
    }

    // --- Phase A4 getters/setters ---

    public int getSoftBudgetCallsPerWindow() { return softBudgetCallsPerWindow; }
    public void setSoftBudgetCallsPerWindow(int v) { this.softBudgetCallsPerWindow = Math.max(0, v); }

    public int getHardBudgetCallsPerWindow() { return hardBudgetCallsPerWindow; }
    public void setHardBudgetCallsPerWindow(int v) { this.hardBudgetCallsPerWindow = Math.max(0, v); }

    public int getBudgetWindowMinutes() { return budgetWindowMinutes; }
    public void setBudgetWindowMinutes(int v) { this.budgetWindowMinutes = v; }

    public int getSoftJoulesThreshold() { return softJoulesThreshold; }
    public void setSoftJoulesThreshold(int v) { this.softJoulesThreshold = Math.max(0, v); }

    public int getHardJoulesThreshold() { return hardJoulesThreshold; }
    public void setHardJoulesThreshold(int v) { this.hardJoulesThreshold = Math.max(0, v); }

    public int getJoulesRefreshIntervalSeconds() { return joulesRefreshIntervalSeconds; }
    public void setJoulesRefreshIntervalSeconds(int v) { this.joulesRefreshIntervalSeconds = v; }

    public String getFallbackProfile() {
        return fallbackProfile == null || fallbackProfile.isBlank()
                || "Default".equalsIgnoreCase(fallbackProfile.trim()) ? null : fallbackProfile;
    }
    public void setFallbackProfile(String v) {
        this.fallbackProfile = v == null || v.isBlank()
                || "Default".equalsIgnoreCase(v.trim()) ? null : v;
    }

    public BudgetFallbackBehavior getBudgetFallbackBehavior() {
        return budgetFallbackBehavior == null ? BudgetFallbackBehavior.HARD_STOP : budgetFallbackBehavior;
    }
    public void setBudgetFallbackBehavior(BudgetFallbackBehavior v) {
        this.budgetFallbackBehavior = v == null ? BudgetFallbackBehavior.HARD_STOP : v;
    }

    // --- Phase B3 getters/setters ---

    public int getRagTopK() { return ragTopK; }

    public void setRagTopK(int v) { this.ragTopK = v; }

    /** Clamped to [1, 50]. */
    public int getRagTopKClamped() {
        int k = ragTopK;
        if (k < 1) return 1;
        if (k > 50) return 50;
        return k;
    }

    public boolean isRagFallbackToFullList() { return ragFallbackToFullList; }

    public void setRagFallbackToFullList(boolean ragFallbackToFullList) {
        this.ragFallbackToFullList = ragFallbackToFullList;
    }

    public boolean isRagLiveEnabled() { return ragLiveEnabled; }

    public void setRagLiveEnabled(boolean ragLiveEnabled) {
        this.ragLiveEnabled = ragLiveEnabled;
    }

    public int getRagMinGoalChars() { return ragMinGoalChars; }

    public void setRagMinGoalChars(int v) { this.ragMinGoalChars = v; }

    /** Clamped to [1, 16]. */
    public int getRagMinGoalCharsClamped() {
        int m = ragMinGoalChars;
        if (m < 1) return 1;
        if (m > 16) return 16;
        return m;
    }

    public boolean isModIntelligenceEnabled() { return modIntelligenceEnabled; }
    public void setModIntelligenceEnabled(boolean v) { this.modIntelligenceEnabled = v; }

    public boolean isModIntelligenceInspectOnLaunch() { return modIntelligenceInspectOnLaunch; }
    public void setModIntelligenceInspectOnLaunch(boolean v) { this.modIntelligenceInspectOnLaunch = v; }

    public boolean isModIntelligenceEnrichmentEnabled() { return modIntelligenceEnrichmentEnabled; }
    public void setModIntelligenceEnrichmentEnabled(boolean v) { this.modIntelligenceEnrichmentEnabled = v; }

    public int getModIntelligenceMaxInspectEntriesPerLaunch() { return modIntelligenceMaxInspectEntriesPerLaunch; }
    public void setModIntelligenceMaxInspectEntriesPerLaunch(int v) {
        this.modIntelligenceMaxInspectEntriesPerLaunch = v;
    }

    public int getModIntelligenceMaxEnrichmentCallsPerLaunch() { return modIntelligenceMaxEnrichmentCallsPerLaunch; }
    public void setModIntelligenceMaxEnrichmentCallsPerLaunch(int v) {
        this.modIntelligenceMaxEnrichmentCallsPerLaunch = v;
    }

    public int getModIntelligenceMaxEnrichmentFailuresPerLaunch() {
        return modIntelligenceMaxEnrichmentFailuresPerLaunch;
    }
    public void setModIntelligenceMaxEnrichmentFailuresPerLaunch(int v) {
        this.modIntelligenceMaxEnrichmentFailuresPerLaunch = v;
    }

    public int getModIntelligenceQueryTopK() { return modIntelligenceQueryTopK; }
    public void setModIntelligenceQueryTopK(int v) { this.modIntelligenceQueryTopK = v; }

    public int getModIntelligenceQueryTopKClamped() {
        int k = modIntelligenceQueryTopK;
        if (k < 1) return 1;
        if (k > 50) return 50;
        return k;
    }

    public double getModIntelligenceMinQueryConfidence() { return modIntelligenceMinQueryConfidence; }
    public void setModIntelligenceMinQueryConfidence(double v) { this.modIntelligenceMinQueryConfidence = v; }

    public boolean isModIntelligenceObserveRuntimeNbt() { return modIntelligenceObserveRuntimeNbt; }
    public void setModIntelligenceObserveRuntimeNbt(boolean v) { this.modIntelligenceObserveRuntimeNbt = v; }

    public int getModIntelligenceMaxObservedSamplesPerSubject() {
        return modIntelligenceMaxObservedSamplesPerSubject;
    }
    public void setModIntelligenceMaxObservedSamplesPerSubject(int v) {
        this.modIntelligenceMaxObservedSamplesPerSubject = v;
    }

    public boolean isModIntelligenceBypassLargeQueueBudgetGate() {
        return modIntelligenceBypassLargeQueueBudgetGate;
    }

    public void setModIntelligenceBypassLargeQueueBudgetGate(boolean v) {
        this.modIntelligenceBypassLargeQueueBudgetGate = v;
    }

    public boolean isModIntelligenceBypassModelBlacklist() {
        return modIntelligenceBypassModelBlacklist;
    }

    public void setModIntelligenceBypassModelBlacklist(boolean v) {
        this.modIntelligenceBypassModelBlacklist = v;
    }

    public boolean isBotTtsPlaybackAckEnabled() {
        return botTtsPlaybackAckEnabled;
    }

    public void setBotTtsPlaybackAckEnabled(boolean botTtsPlaybackAckEnabled) {
        this.botTtsPlaybackAckEnabled = botTtsPlaybackAckEnabled;
    }

    public int getBodylangMarkerPauseMs() {
        return bodylangMarkerPauseMs;
    }

    /** Clamped to [0, 2000]. */
    public void setBodylangMarkerPauseMs(int v) {
        if (v < 0) {
            v = 0;
        } else if (v > 2000) {
            v = 2000;
        }
        this.bodylangMarkerPauseMs = v;
    }

    public boolean isBodylangGaplessPrefetch() {
        return bodylangGaplessPrefetch;
    }

    public void setBodylangGaplessPrefetch(boolean bodylangGaplessPrefetch) {
        this.bodylangGaplessPrefetch = bodylangGaplessPrefetch;
    }

    // --- Bot lifecycle getters/setters ---

    public boolean isServerOverridesPlayerConfig() {
        return serverOverridesPlayerConfig;
    }

    public void setServerOverridesPlayerConfig(boolean serverOverridesPlayerConfig) {
        this.serverOverridesPlayerConfig = serverOverridesPlayerConfig;
    }

    public boolean isServerAutoRespawn() {
        return serverAutoRespawn;
    }

    public void setServerAutoRespawn(boolean serverAutoRespawn) {
        this.serverAutoRespawn = serverAutoRespawn;
    }

    /** Nullable on purpose: {@code null} = unset (resolver falls back to {@code world.isHardcore()}). */
    public Boolean getServerBotPermadeath() {
        return serverBotPermadeath;
    }

    /** Accepts {@code null} (unset) or an explicit {@code true}/{@code false} (Model A). */
    public void setServerBotPermadeath(Boolean serverBotPermadeath) {
        this.serverBotPermadeath = serverBotPermadeath;
    }

    public KeepInventoryOverride getBotKeepInventoryOverride() {
        return botKeepInventoryOverride == null ? KeepInventoryOverride.FOLLOW_GAMERULE : botKeepInventoryOverride;
    }

    public void setBotKeepInventoryOverride(KeepInventoryOverride botKeepInventoryOverride) {
        this.botKeepInventoryOverride = botKeepInventoryOverride == null ? KeepInventoryOverride.FOLLOW_GAMERULE : botKeepInventoryOverride;
    }

    // --- Phase D getters/setters (W7) ---

    public boolean isEnableGraphRagMemory() { return enableGraphRagMemory; }
    public void setEnableGraphRagMemory(boolean v) { this.enableGraphRagMemory = v; }

    public boolean isEnableCompanionMood() { return enableCompanionMood; }
    public void setEnableCompanionMood(boolean v) { this.enableCompanionMood = v; }

    public int getMemoryCallsPerWindow() { return memoryCallsPerWindow; }
    public void setMemoryCallsPerWindow(int v) { this.memoryCallsPerWindow = v; }

    /** Clamped to [0, 1000]. */
    public int getMemoryCallsPerWindowClamped() {
        int n = memoryCallsPerWindow;
        if (n < 0) return 0;
        if (n > 1000) return 1000;
        return n;
    }

    public int getMemoryWindowMinutes() { return memoryWindowMinutes; }
    public void setMemoryWindowMinutes(int v) { this.memoryWindowMinutes = v; }

    /** Clamped to [1, 1440]. */
    public int getMemoryWindowMinutesClamped() {
        int m = memoryWindowMinutes;
        if (m < 1) return 1;
        if (m > 1440) return 1440;
        return m;
    }

    // --- Phase D getters/setters (W3/W4/W5/W6 tuning keys — integration pass) ---

    public int getMemoryExtractionBatchMin() { return memoryExtractionBatchMin; }
    public void setMemoryExtractionBatchMin(int v) { this.memoryExtractionBatchMin = v; }

    public int getMemoryExtractionBatchMax() { return memoryExtractionBatchMax; }
    public void setMemoryExtractionBatchMax(int v) { this.memoryExtractionBatchMax = v; }

    public int getMemoryExtractionLengthThreshold() { return memoryExtractionLengthThreshold; }
    public void setMemoryExtractionLengthThreshold(int v) { this.memoryExtractionLengthThreshold = v; }

    public int getMemoryLayer2MinChars() { return memoryLayer2MinChars; }
    public void setMemoryLayer2MinChars(int v) { this.memoryLayer2MinChars = v; }

    /** Clamped to [0, 200]. */
    public int getMemoryLayer2MinCharsClamped() {
        int n = memoryLayer2MinChars;
        if (n < 0) return 0;
        if (n > 200) return 200;
        return n;
    }

    public int getMemoryMaxHops() { return memoryMaxHops; }
    public void setMemoryMaxHops(int v) { this.memoryMaxHops = v; }

    /** Clamped to [1, 6]. */
    public int getMemoryMaxHopsClamped() {
        int n = memoryMaxHops;
        if (n < 1) return 1;
        if (n > 6) return 6;
        return n;
    }

    public int getMemoryMaxEgoNodes() { return memoryMaxEgoNodes; }
    public void setMemoryMaxEgoNodes(int v) { this.memoryMaxEgoNodes = v; }

    /** Clamped to [1, 4096]. */
    public int getMemoryMaxEgoNodesClamped() {
        int n = memoryMaxEgoNodes;
        if (n < 1) return 1;
        if (n > 4096) return 4096;
        return n;
    }

    public int getMemoryBlockCharCap() { return memoryBlockCharCap; }
    public void setMemoryBlockCharCap(int v) { this.memoryBlockCharCap = v; }

    /** Clamped to [0, 8000]. */
    public int getMemoryBlockCharCapClamped() {
        int n = memoryBlockCharCap;
        if (n < 0) return 0;
        if (n > 8000) return 8000;
        return n;
    }

    public double getMemoryMinConfidence() { return memoryMinConfidence; }
    public void setMemoryMinConfidence(double v) { this.memoryMinConfidence = v; }

    /** Clamped to [0.0, 1.0]. */
    public double getMemoryMinConfidenceClamped() {
        double d = memoryMinConfidence;
        if (d < 0.0) return 0.0;
        if (d > 1.0) return 1.0;
        return d;
    }

    public double getMemoryDecayBase() { return memoryDecayBase; }
    public void setMemoryDecayBase(double v) { this.memoryDecayBase = v; }

    /** Clamped to (0.0, 1.0] (a decay base outside this range is meaningless). */
    public double getMemoryDecayBaseClamped() {
        double d = memoryDecayBase;
        if (d <= 0.0 || d > 1.0) return 0.995;
        return d;
    }

    public long getMemoryGameTimeUnit() { return memoryGameTimeUnit; }
    public void setMemoryGameTimeUnit(long v) { this.memoryGameTimeUnit = v; }

    /** Clamped to >= 1 (division basis). */
    public long getMemoryGameTimeUnitClamped() {
        return memoryGameTimeUnit < 1 ? 1 : memoryGameTimeUnit;
    }

    public int getMemoryRetrievalTopK() { return memoryRetrievalTopK; }
    public void setMemoryRetrievalTopK(int v) { this.memoryRetrievalTopK = v; }

    /** Clamped to [1, 50]. */
    public int getMemoryRetrievalTopKClamped() {
        int n = memoryRetrievalTopK;
        if (n < 1) return 1;
        if (n > 50) return 50;
        return n;
    }

    public int getReflectionImportanceThreshold() { return reflectionImportanceThreshold; }
    public void setReflectionImportanceThreshold(int v) { this.reflectionImportanceThreshold = v; }

    /** Clamped to [1, 100000]. */
    public int getReflectionImportanceThresholdClamped() {
        int n = reflectionImportanceThreshold;
        if (n < 1) return 1;
        if (n > 100000) return 100000;
        return n;
    }

    public int getRelationshipSummaryCharCap() { return relationshipSummaryCharCap; }
    public void setRelationshipSummaryCharCap(int v) { this.relationshipSummaryCharCap = v; }

    /** Clamped to [0, 1000]. */
    public int getRelationshipSummaryCharCapClamped() {
        int n = relationshipSummaryCharCap;
        if (n < 0) return 0;
        if (n > 1000) return 1000;
        return n;
    }

    public int getImportanceRubricVersion() { return importanceRubricVersion; }
    public void setImportanceRubricVersion(int v) { this.importanceRubricVersion = v; }
}
