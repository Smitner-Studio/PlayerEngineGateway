
package com.player2.playerengine.player2api;
import java.util.Deque;
import java.util.Optional;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Objects;
import java.util.UUID;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.Event.InfoMessage;
import com.player2.playerengine.automaton.utils.DirUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;


public class AIPersistantData {
    public static final int ADDITIONAL_PROMPT_MAX_CHARS = 300;

    // contains data relating to AI processing, only including data that is
    // permanent,
    // and persists across game state (not queue stuff)

    private ConversationHistory conversationHistory;
    private Character character;
    private PlayerEngineController mod;
    private String characterId;
    private Path conversationHistoryFile;

    /**
     * Per-companion current mood (Lightweight Companion Mood System — WS1). Never null; defaults to
     * {@link com.player2.playerengine.player2api.mood.CompanionMood#neutral()}. Loaded tolerantly from
     * {@code mood.json} beside {@code conversation.jsonl}; a missing/corrupt file degrades to neutral
     * with a {@code Debug.logWarning} (never crashes, never reaches a prompt).
     */
    private com.player2.playerengine.player2api.mood.CompanionMood currentMood =
            com.player2.playerengine.player2api.mood.CompanionMood.neutral();
    private Path moodFile;
    private String additionalPrompt = "";
    private Path additionalPromptFile;

    public AIPersistantData(PlayerEngineController mod, Character character) {
        this.character = character;
        this.mod = mod;
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
        this.characterId = character == null ? null : character.id();
        Path worldRoot = resolveWorldRootOrNull(mod);
        this.conversationHistoryFile = getConversationHistoryFileOrNull(mod, worldRoot, this.characterId);
        String basePrompt = withRelationshipSuffix(systemPrompt);
        if (this.conversationHistoryFile != null) {
            migrateLegacyHistoryIfPresent(character, this.characterId, worldRoot, this.conversationHistoryFile, mod);
            this.conversationHistory = new ConversationHistory(basePrompt, this.conversationHistoryFile);
        } else {
            // Fallback to non-persistent history if we can't resolve world root or characterId.
            this.conversationHistory = new ConversationHistory(basePrompt);
        }
        // Mood (WS1): resolve mood.json beside conversation.jsonl and load tolerantly. A null path
        // (unresolvable world root / characterId) keeps the in-memory neutral default — non-persistent,
        // exactly like the conversation-history fallback above.
        this.moodFile = getMoodFileOrNull(mod, worldRoot, this.characterId);
        loadMoodFromDiskTolerant();
        this.additionalPromptFile = getAdditionalPromptFileOrNull(mod, worldRoot, this.characterId);
        loadAdditionalPromptFromDiskTolerant();
    }

    /** {@code plan.json} beside this companion's mood and conversation files, or null when unresolved. */
    public Path getPlanFileOrNull() {
        Path mood = this.moodFile;
        return mood == null ? null : mood.resolveSibling("plan.json");
    }

    /**
     * Re-resolves owner-scoped persistence after a controller gains its owner later in entity load.
     * This only moves from the current fallback/legacy path to the current owner's canonical path; it
     * never scans sibling owner directories, which would risk leaking another player's companion data.
     */
    public void rebindOwnerPersistenceIfChanged() {
        Path worldRoot = resolveWorldRootOrNull(mod);
        Path targetHistoryFile = getConversationHistoryFileOrNull(mod, worldRoot, this.characterId);
        if (targetHistoryFile != null && !Objects.equals(this.conversationHistoryFile, targetHistoryFile)) {
            Path previousHistoryFile = this.conversationHistoryFile;
            migrateLegacyHistoryIfPresent(this.character, this.characterId, worldRoot, targetHistoryFile, mod);
            copyFileIfTargetMissing(previousHistoryFile, targetHistoryFile);
            this.conversationHistoryFile = targetHistoryFile;
            String systemPrompt = Prompts.getAINPCSystemPrompt(character, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
            this.conversationHistory = new ConversationHistory(withRelationshipSuffix(systemPrompt), this.conversationHistoryFile);
        }

        Path targetMoodFile = getMoodFileOrNull(mod, worldRoot, this.characterId);
        if (targetMoodFile != null && !Objects.equals(this.moodFile, targetMoodFile)) {
            copyFileIfTargetMissing(this.moodFile, targetMoodFile);
            this.moodFile = targetMoodFile;
            loadMoodFromDiskTolerant();
        }

        Path targetAdditionalPromptFile = getAdditionalPromptFileOrNull(mod, worldRoot, this.characterId);
        if (targetAdditionalPromptFile != null && !Objects.equals(this.additionalPromptFile, targetAdditionalPromptFile)) {
            copyFileIfTargetMissing(this.additionalPromptFile, targetAdditionalPromptFile);
            this.additionalPromptFile = targetAdditionalPromptFile;
            loadAdditionalPromptFromDiskTolerant();
        }
    }

    private static void copyFileIfTargetMissing(Path source, Path target) {
        if (source == null || target == null || Objects.equals(source, target)) {
            return;
        }
        try {
            if (!Files.exists(source) || Files.exists(target)) {
                return;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(source, target);
        } catch (Exception ignored) {
            // Best-effort owner-scope rebind; callers continue with the target path either way.
        }
    }

    /**
     * Phase D (W6) — the SINGLE combine point for "base prompt + optional {@code [Relationship]} suffix".
     * EVERY system-prompt rebuild path ({@link #updateSystemPrompt}, {@link #updateSystemPromptWithBlock},
     * {@link #updateSystemPromptStatic}, and the constructor) routes the freshly-built base prompt through
     * here so the relationship-summary suffix is <b>byte-identical across all rebuild paths</b> for a given
     * {@code summaryVersion} — the prefix-cache stability invariant (plan §W6). The summary changes only on
     * reflection; between reflections this suffix is constant, so two consecutive turns taking different
     * rebuild paths produce the same system block.
     *
     * <p>Empty / absent summary (memory off, store not loaded, non-patron) → suffix omitted → the system
     * block is <b>byte-identical to today</b>. The summary is normalized + hard-capped by
     * {@link com.player2.playerengine.memory.reflection.RelationshipSummary} (write-time egress boundary);
     * {@code setBaseSystemPrompt} additionally applies {@code LogEgressGuard.capForModel(..., "system")}.
     */
    private String withRelationshipSuffix(String basePrompt) {
        String base = basePrompt != null ? basePrompt : "";
        String summary = currentRelationshipSummaryOrEmpty();
        return base + com.player2.playerengine.memory.reflection.RelationshipSummary.suffixFor(summary);
    }

    /**
     * Reads this companion's current relationship summary from its loaded {@link
     * com.player2.playerengine.memory.MemoryStore}, or {@code ""} when memory is off / the store is not
     * loaded / there is no summary. Best-effort and never throws — a failure simply omits the suffix
     * (byte-identical to baseline). Does not load the store (read-only {@code peek}); the store is loaded
     * lazily by the lifecycle/ingestion/retrieval paths.
     */
    private String currentRelationshipSummaryOrEmpty() {
        try {
            com.player2.playerengine.memory.MemoryScope scope = resolveMemoryScopeOrNull();
            if (scope == null) {
                return "";
            }
            com.player2.playerengine.memory.MemoryStore store =
                    com.player2.playerengine.memory.MemoryStoreRegistry.peek(scope);
            if (store == null) {
                return "";
            }
            String summary = store.relationshipSummary();
            return summary != null ? summary : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Resolves this companion's {@link com.player2.playerengine.memory.MemoryScope} (owner-keyed, entity
     *  fallback), mirroring the conversation-history layout. {@code null} when the companion id is unknown. */
    private com.player2.playerengine.memory.MemoryScope resolveMemoryScopeOrNull() {
        if (this.characterId == null || this.characterId.isBlank()) {
            return null;
        }
        UUID ownerUuid = resolveOwnerUuidOrNull(mod);
        if (ownerUuid != null) {
            return com.player2.playerengine.memory.MemoryScope.of(ownerUuid, this.characterId);
        }
        if (mod != null && mod.getPlayer() != null) {
            return com.player2.playerengine.memory.MemoryScope.ofEntityFallback(
                    mod.getPlayer().getUUID(), this.characterId);
        }
        return null;
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public Event getGreetingEvent() {
        String suffix = " IMPORTANT: SINCE THIS IS THE FIRST MESSAGE, ONLY USE COMMAND `bodylang greeting`";
        if (conversationHistory.isLoadedFromFile()) {
            return (new InfoMessage("You want to welcome user back." + suffix));
        } else {
            return (new InfoMessage(character.greetingInfo() + suffix));
        }
    }

    /** Whether {@link #getReturnEvent} is a greeting: a first-ever meeting greets, even on a RETURNING reason. */
    public boolean returnGreets() {
        return !conversationHistory.isLoadedFromFile();
    }

    /**
     * Builds the FIRST_MEETING/RETURNING model event: a true greeting when this is the first-ever
     * spawn for this per-world history (no history file yet), otherwise a "&lt;owner&gt; has respawned
     * you" return line.
     * <p>
     * PRECONDITION: never call this for a DEATH_RESPAWN spawn. A bot that just died and was
     * auto-revived must go through {@link #getDeathRevivalEvent(String)} so the model is told it died
     * (and how). Routing a death respawn here would silently convert death context into a greeting
     * for any bot with no history file yet (first-tick death) -- exactly the truthfulness bug
     * (DESIGN.md §3) this feature exists to prevent. The caller dispatch in
     * {@code AutomatoneEntity.init} guarantees this: DEATH_RESPAWN goes to {@code sendDeathRevival},
     * only FIRST_MEETING/RETURNING reach {@code sendReturnMessage} -> here.
     */
    public Event getReturnEvent(String ownerName) {
        if (returnGreets()) {
            return getGreetingEvent(); // greetingInfo + greeting bodylang suffix
        }
        String who = (ownerName == null || ownerName.isBlank()) ? "Your owner" : ownerName;
        // Q3 default: no forced bodylang suffix on return.
        return new InfoMessage(who + " has respawned you. You are returning to them; do not greet as if "
            + "meeting for the first time.");
    }

    public Event getDeathRevivalEvent(String deathCause) {
        String cause = (deathCause == null || deathCause.isBlank()) ? "You died." : deathCause;
        // Q3 default: no forced bodylang suffix. Model-facing; instruct truthfulness (DESIGN.md §3).
        return new InfoMessage("You just died and the game automatically revived you. Death: " + cause
            + ". Do not claim you respawned yourself, and do not greet as if meeting for the first time;"
            + " you may briefly react to how you died.");
    }

    public Event dumpEventQueueToConversationHistoryAndReturnLastEvent(Deque<Event> eventQueue, Player2APIService player2apiService){
        Event lastEvent = null;
        java.util.List<String> committedTurns = new java.util.ArrayList<>();
        while(!eventQueue.isEmpty()){
            Event event = eventQueue.poll();
            String turn = event.getConversationHistoryString();
            conversationHistory.addUserMessage(turn, player2apiService);
            committedTurns.add(turn);
            lastEvent = event;
        }
        // Phase D memory ingestion (W3): self-gates on patron status + the master flag — a complete
        // no-op (zero LLM calls, zero writes) for non-patrons / when memory is off. Cheap to call
        // unconditionally; returns promptly (any extraction is scheduled async, never on the tick).
        com.player2.playerengine.memory.ingest.MemoryIngestionService.onCuratedTurnsReady(this.mod, committedTurns);
        return lastEvent;
    }
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString, Optional<String> validCommandsBlock){
        // Existing-arity delegate (no memory block) — byte-identical to pre-Phase-D.
        return getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                player2apiService, reminderString, validCommandsBlock, Optional.empty());
    }

    /**
     * Phase D (W5) overload: threads the per-turn memory block into the throwaway wrapped copy so it
     * is injected at the user-tail (after {@code validCommands}) and never persisted. Non-patron /
     * empty → caller passes {@link Optional#empty()} → request byte-identical to today.
     */
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString, Optional<String> validCommandsBlock, Optional<String> memoryBlock){
        // Mood overload delegate (no mood block) — tail byte-identical to pre-mood-feature.
        return getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                player2apiService, reminderString, validCommandsBlock, memoryBlock, Optional.empty());
    }

    /**
     * Mood overload: threads the per-turn {@code currentMood} block into the throwaway wrapped copy so it
     * is injected at the user-tail (after {@code memory}) and never persisted. Flag-off / neutral →
     * caller passes {@link Optional#empty()} → tail byte-identical to pre-mood-feature. Current mood must
     * never enter the static system block (prefix-cache invariant).
     */
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString, Optional<String> validCommandsBlock, Optional<String> memoryBlock, Optional<String> moodBlock){
        return this.conversationHistory
                .copyThenWrapLatestWithStatus(worldStatus, agentStatus, altoClefDebugMsgs, player2apiService, reminderString, validCommandsBlock, memoryBlock, moodBlock, additionalPromptBlock());
    }
    public void addAssistantMessage(String llmMessage, Player2APIService player2apiService){
        this.conversationHistory.addAssistantMessage(llmMessage, player2apiService);
    }

    public Optional<String> getLastAssistantContent() {
        return this.conversationHistory.getLastAssistantContent();
    }

    public Character getCharacter(){
        return this.character;
    }

    public void updateSystemPrompt(){
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(withRelationshipSuffix(systemPrompt));
    }

    /**
     * Updates the system prompt using a pre-built valid-commands block from {@code RagPromptBuilder}
     * rather than the full command list (Phase B3 live RAG path).
     *
     * @param validCommandsBlock formatted commands block from
     *        {@link com.player2.playerengine.retrieval.RagPromptBuilder#buildValidCommandsBlock}
     */
    public void updateSystemPromptWithBlock(String validCommandsBlock) {
        String block = validCommandsBlock != null ? validCommandsBlock : "";
        String systemPrompt = Prompts.getAINPCSystemPromptWithValidCommandsBlock(character, block, mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(withRelationshipSuffix(systemPrompt));
    }

    /**
     * Live RAG path: sets message 0 to the byte-stable base prompt with NO command list/section, so the
     * system message is byte-identical across turns. The per-turn retrieved command subset is delivered
     * in the latest user turn under the {@code validCommands} key (see
     * {@link ConversationHistory#copyThenWrapLatestWithStatus}), not in the system message.
     */
    public void updateSystemPromptStatic() {
        String systemPrompt = Prompts.getAINPCSystemPromptNoCommandsBlock(character, mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(withRelationshipSuffix(systemPrompt));
    }

    public void saveHistoryNow() {
        conversationHistory.saveNow();
    }

    public void reloadHistoryFromDisk() {
        conversationHistory.reloadNow();
        // After reload, ensure the system prompt is updated to match the current command list/owner.
        updateSystemPrompt();
    }

    public String getAdditionalPrompt() {
        return this.additionalPrompt;
    }

    public void updateAdditionalPrompt(String prompt) {
        this.additionalPrompt = cleanAdditionalPrompt(prompt);
    }

    public void saveAdditionalPromptNow() {
        try {
            writeAdditionalPromptFile(this.additionalPromptFile, this.additionalPrompt);
        } catch (Exception e) {
            com.player2.playerengine.util.Debug.logWarning("saveAdditionalPromptNow: failed to write additional prompt for character %s", this.characterId);
        }
    }

    public void reloadAdditionalPromptFromDisk() {
        loadAdditionalPromptFromDiskTolerant();
    }

    public static String cleanAdditionalPrompt(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder(Math.min(normalized.length(), ADDITIONAL_PROMPT_MAX_CHARS));
        for (int i = 0; i < normalized.length() && cleaned.length() < ADDITIONAL_PROMPT_MAX_CHARS; i++) {
            char c = normalized.charAt(i);
            if (java.lang.Character.isISOControl(c) && c != '\n' && c != '\t') {
                continue;
            }
            cleaned.append(c);
        }
        return cleaned.toString().trim();
    }

    public static String readAdditionalPrompt(MinecraftServer server, UUID ownerUuid, String characterId) {
        if (server == null || ownerUuid == null || characterId == null || characterId.isBlank()) {
            return "";
        }
        try {
            Path file = Player2NpcPersistencePaths.additionalPromptFile(server, ownerUuid, characterId);
            if (!Files.exists(file)) {
                return "";
            }
            return cleanAdditionalPrompt(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    public static void saveAdditionalPrompt(MinecraftServer server, UUID ownerUuid, String characterId, String prompt) throws java.io.IOException {
        if (server == null || ownerUuid == null || characterId == null || characterId.isBlank()) {
            throw new java.io.IOException("missing_target");
        }
        writeAdditionalPromptFile(Player2NpcPersistencePaths.additionalPromptFile(server, ownerUuid, characterId), prompt);
    }

    private Optional<String> additionalPromptBlock() {
        String prompt = cleanAdditionalPrompt(this.additionalPrompt);
        return prompt.isBlank() ? Optional.empty() : Optional.of(prompt);
    }

    private void loadAdditionalPromptFromDiskTolerant() {
        if (this.additionalPromptFile == null || !Files.exists(this.additionalPromptFile)) {
            this.additionalPrompt = "";
            return;
        }
        try {
            this.additionalPrompt = cleanAdditionalPrompt(Files.readString(this.additionalPromptFile, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            this.additionalPrompt = "";
            com.player2.playerengine.util.Debug.logWarning("loadAdditionalPrompt: failed to read additional prompt for character %s", this.characterId);
        }
    }

    private static void writeAdditionalPromptFile(Path file, String prompt) throws java.io.IOException {
        if (file == null) {
            return;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(Player2NpcPersistencePaths.ADDITIONAL_PROMPT_FILE_NAME + ".tmp");
        Files.writeString(tmp, cleanAdditionalPrompt(prompt), java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -------------------------------------------------------------------------
    // Companion mood (Lightweight Companion Mood System — WS1)
    // -------------------------------------------------------------------------

    /** Current per-companion mood (never null; neutral default). */
    public com.player2.playerengine.player2api.mood.CompanionMood getCurrentMood() {
        return this.currentMood;
    }

    /**
     * Replaces the in-memory current mood. A {@code null} argument is coerced to
     * {@link com.player2.playerengine.player2api.mood.CompanionMood#neutral()} so the field stays
     * non-null. Does NOT persist — the caller follows with {@link #saveMoodNow()} (mirroring the
     * conversation {@code updateMood → saveHistoryNow} sequence).
     */
    public void updateMood(com.player2.playerengine.player2api.mood.CompanionMood newMood) {
        this.currentMood = (newMood != null)
                ? newMood
                : com.player2.playerengine.player2api.mood.CompanionMood.neutral();
    }

    /**
     * Persists the current mood to {@code mood.json} via atomic temp-then-rename (mirrors the deferred
     * store / EllieGPS write pattern). No-op when the mood path is unresolvable (non-persistent
     * fallback). Best-effort: an I/O failure logs a warning and never throws into the caller.
     */
    public void saveMoodNow() {
        if (this.moodFile == null) {
            return;
        }
        try {
            if (this.moodFile.getParent() != null) {
                Files.createDirectories(this.moodFile.getParent());
            }
            String json = this.currentMood.toJson().toString();
            Path tmp = this.moodFile.resolveSibling(
                    com.player2.playerengine.player2api.Player2NpcPersistencePaths.MOOD_FILE_NAME + ".tmp");
            Files.writeString(tmp, json, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, this.moodFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, this.moodFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // Never crash on a mood save; a templated, bounded warning only (no model-facing text).
            com.player2.playerengine.util.Debug.logWarning("saveMoodNow: failed to write mood.json for character %s", this.characterId);
        }
    }

    /** Re-reads the current mood from {@code mood.json}, tolerantly (missing/corrupt → neutral). */
    public void reloadMoodFromDisk() {
        loadMoodFromDiskTolerant();
    }

    /**
     * Loads {@code mood.json} into {@link #currentMood}. Missing file → neutral (no warning — a
     * first-ever companion is expected to have none). Corrupt/unparseable → neutral + a single bounded
     * {@code Debug.logWarning}. Never throws into the load path (DESIGN.md: degrade visibly, never crash;
     * no log/stack/unbounded text reaches a prompt).
     */
    private void loadMoodFromDiskTolerant() {
        if (this.moodFile == null) {
            this.currentMood = com.player2.playerengine.player2api.mood.CompanionMood.neutral();
            return;
        }
        if (!Files.exists(this.moodFile)) {
            this.currentMood = com.player2.playerengine.player2api.mood.CompanionMood.neutral();
            return;
        }
        try {
            String raw = Files.readString(this.moodFile, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            this.currentMood = com.player2.playerengine.player2api.mood.CompanionMood.fromJson(obj);
        } catch (Exception e) {
            // Truncated/corrupt mood.json — degrade to neutral with a bounded warning (no file content,
            // no stack trace, ever reaches a prompt). Never crashes the companion load.
            this.currentMood = com.player2.playerengine.player2api.mood.CompanionMood.neutral();
            com.player2.playerengine.util.Debug.logWarning("loadMood: corrupt mood.json for character %s; using neutral", this.characterId);
        }
    }

    /**
     * Canonical: {@code player2npc/persistentdata/owners/<ownerUuid>/<characterId>/mood.json}, beside
     * {@code conversation.jsonl}. Falls back to the legacy entity-UUID segment when the owner UUID is
     * unresolvable (mirroring {@link #getConversationHistoryFileOrNull}). {@code null} when world root or
     * characterId is unknown (mood then stays the in-memory neutral default, non-persistent).
     */
    private static Path getMoodFileOrNull(PlayerEngineController mod, Path worldRoot, String characterId) {
        if (mod == null || mod.getPlayer() == null) return null;
        if (characterId == null || characterId.isBlank()) return null;
        if (worldRoot == null) return null;
        try {
            UUID ownerUuid = resolveOwnerUuidOrNull(mod);
            if (ownerUuid != null) {
                return Player2NpcPersistencePaths.moodFile(worldRoot, ownerUuid, characterId);
            }
            UUID entityUuid = mod.getPlayer().getUUID();
            return Player2NpcPersistencePaths.persistentDataRoot(worldRoot)
                    .resolve(entityUuid.toString())
                    .resolve(characterId)
                    .resolve(Player2NpcPersistencePaths.MOOD_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path getAdditionalPromptFileOrNull(PlayerEngineController mod, Path worldRoot, String characterId) {
        if (mod == null || mod.getPlayer() == null) return null;
        if (characterId == null || characterId.isBlank()) return null;
        if (worldRoot == null) return null;
        try {
            UUID ownerUuid = resolveOwnerUuidOrNull(mod);
            if (ownerUuid != null) {
                return Player2NpcPersistencePaths.additionalPromptFile(worldRoot, ownerUuid, characterId);
            }
            UUID entityUuid = mod.getPlayer().getUUID();
            return Player2NpcPersistencePaths.persistentDataRoot(worldRoot)
                    .resolve(entityUuid.toString())
                    .resolve(characterId)
                    .resolve(Player2NpcPersistencePaths.ADDITIONAL_PROMPT_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    public Path getConversationHistoryFile() {
        return this.conversationHistoryFile;
    }

    public String getCharacterId() {
        return this.characterId;
    }

    private static Path resolveWorldRootOrNull(PlayerEngineController mod) {
        if (mod == null || mod.getPlayer() == null) {
            return null;
        }
        try {
            MinecraftServer server = Objects.requireNonNull(mod.getPlayer().level().getServer(), "server");
            return server.getWorldPath(LevelResource.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Canonical: {@code player2npc/persistentdata/owners/<ownerUuid>/<characterId>/conversation.jsonl}.
     * If owner is unknown, uses legacy entity-UUID segment (same as pre-owner layout).
     */
    private static Path getConversationHistoryFileOrNull(PlayerEngineController mod, Path worldRoot, String characterId) {
        if (mod == null || mod.getPlayer() == null) return null;
        if (characterId == null || characterId.isBlank()) return null;
        if (worldRoot == null) return null;

        try {
            // Resolve the STABLE owner UUID (independent of whether the owner ServerPlayer is attached at
            // construction time). The owner entity is frequently null at summon — which previously dropped
            // this to the transient entity-UUID path below, so EVERY re-summon got a fresh empty
            // conversation file: the bot lost all prior-session memory AND greeted as if meeting for the
            // first time (isLoadedFromFile()==false). Resolving via the owner USERNAME (always known) keeps
            // the canonical owners/<ownerUuid>/<characterId>/ path stable across sessions, matching the
            // per-owner inventory/settings layout.
            UUID ownerUuid = resolveOwnerUuidOrNull(mod);
            if (ownerUuid != null) {
                return worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve("owners")
                        .resolve(ownerUuid.toString())
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
            }
            // Last resort only (owner truly unresolvable): legacy entity-UUID segment — not stable across
            // re-summons, but better than no persistence for the current session.
            UUID entityUuid = mod.getPlayer().getUUID();
            return worldRoot
                    .resolve("player2npc")
                    .resolve("persistentdata")
                    .resolve(entityUuid.toString())
                    .resolve(characterId)
                    .resolve("conversation.jsonl");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve the bot's owner UUID stably, NOT depending on the owner {@code ServerPlayer} being attached
     * at construction time. Tries the direct owner entity first, then resolves the owner username (always
     * known via {@code getOwnerUsername()}) through the shared {@link Player2NpcInitiatorUuidResolve}
     * (online list -> server username cache -> profile cache) — the same stable resolution the per-owner
     * inventory/settings paths rely on. Returns {@code null} only when the owner cannot be resolved at all.
     */
    private static UUID resolveOwnerUuidOrNull(PlayerEngineController mod) {
        try {
            if (mod.getOwner() != null) {
                return mod.getOwner().getUUID();
            }
        } catch (Exception ignored) {
            // fall through to username-based resolution
        }
        try {
            String ownerName = mod.getOwnerUsername();
            if (ownerName != null && !ownerName.isBlank() && mod.getPlayer() != null) {
                MinecraftServer server = mod.getPlayer().level().getServer();
                if (server != null) {
                    return Player2NpcInitiatorUuidResolve.resolve(server, ownerName);
                }
            }
        } catch (Exception ignored) {
            // owner UUID unresolvable
        }
        return null;
    }

    /**
     * If the UUID-scoped file is missing, copy from older locations (do not delete sources).
     */
    private static void migrateLegacyHistoryIfPresent(Character character, String characterId, Path worldRoot, Path newHistoryFile, PlayerEngineController mod) {
        if (newHistoryFile == null) return;
        try {
            if (Files.exists(newHistoryFile)) return;

            if (newHistoryFile.getParent() != null) {
                Files.createDirectories(newHistoryFile.getParent());
            }

            // 1) Legacy: global config dir, name-keyed .txt (duplicate-name or canonical slug)
            if (character != null) {
                String characterName = character.name();
                if (characterName != null && !characterName.isBlank()) {
                    Path legacyFile = resolveLegacyGlobalHistoryFile(characterName);
                    if (legacyFile != null) {
                        Files.copy(legacyFile, newHistoryFile);
                    }
                }
            }
            if (Files.exists(newHistoryFile)) return;

            // 2) Intermediate: per-world persistentdata/<characterId>/conversation.jsonl (no entity UUID segment)
            if (worldRoot != null && characterId != null && !characterId.isBlank()) {
                Path intermediate = worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
                if (Files.exists(intermediate)) {
                    Files.copy(intermediate, newHistoryFile);
                }
            }
            if (Files.exists(newHistoryFile)) return;

            // 3) Stranded owner-scoped path from older owner-resolution bugs. Only copy a same-character
            // file whose system header names the currently resolved owner username.
            migrateStrandedOwnerHistoryIfPresent(mod.getOwnerUsername(), characterId, worldRoot, newHistoryFile);
            if (Files.exists(newHistoryFile)) return;

            // 4) Old entity-scoped path (same layout whether or not owner is now known)
            if (worldRoot != null && characterId != null && !characterId.isBlank() && mod.getPlayer() != null) {
                UUID entityUuid = mod.getPlayer().getUUID();
                Path oldEntityScoped = worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve(entityUuid.toString())
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
                if (Files.exists(oldEntityScoped)) {
                    Files.copy(oldEntityScoped, newHistoryFile);
                }
            }
        } catch (Exception e) {
            // Best-effort migration; ignore failures.
        }
    }

    private static void migrateStrandedOwnerHistoryIfPresent(String ownerUsername, String characterId,
                                                             Path worldRoot, Path newHistoryFile) {
        if (worldRoot == null || newHistoryFile == null) return;
        if (characterId == null || characterId.isBlank()) return;
        if (ownerUsername == null || ownerUsername.isBlank() || "UNKNOWN OWNER".equals(ownerUsername)) return;
        Path ownersRoot = Player2NpcPersistencePaths.ownersRoot(worldRoot);
        if (!Files.isDirectory(ownersRoot)) return;
        Path target = newHistoryFile.normalize();
        Path[] best = new Path[1];
        try (java.util.stream.Stream<Path> owners = Files.list(ownersRoot)) {
            owners.filter(Files::isDirectory).forEach(ownerDir -> {
                Path candidate = ownerDir.resolve(characterId).resolve("conversation.jsonl");
                if (candidate.normalize().equals(target) || !Files.isRegularFile(candidate)) {
                    return;
                }
                if (!historyHeaderNamesOwner(candidate, ownerUsername)) {
                    return;
                }
                if (best[0] == null || isNewerFile(candidate, best[0])) {
                    best[0] = candidate;
                }
            });
            if (best[0] != null && !Files.exists(newHistoryFile)) {
                Files.copy(best[0], newHistoryFile);
            }
        } catch (Exception ignored) {
            // Best-effort migration; ignore failures.
        }
    }

    private static boolean historyHeaderNamesOwner(Path historyFile, String ownerUsername) {
        try (java.io.BufferedReader reader = Files.newBufferedReader(historyFile, java.nio.charset.StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                return false;
            }
            String lower = firstLine.toLowerCase(java.util.Locale.ROOT);
            String owner = ownerUsername.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("owner") && lower.contains("username") && lower.contains("\"" + owner + "\"");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isNewerFile(Path candidate, Path current) {
        try {
            return Files.getLastModifiedTime(candidate).toMillis() > Files.getLastModifiedTime(current).toMillis();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Legacy config-dir history files (pre-UUID paths). */
    private static Path resolveLegacyGlobalHistoryFile(String characterName) {
        String slug = characterName.replaceAll("\\s+", "_");
        Path configDir = DirUtil.getConfigDir();
        Path duplicateNameFile = configDir.resolve(slug + "_" + slug + ".txt");
        if (Files.exists(duplicateNameFile)) {
            return duplicateNameFile;
        }

        Path canonicalFile = configDir.resolve(slug + "_conversation.txt");
        if (Files.exists(canonicalFile)) {
            return canonicalFile;
        }

        return null;
    }
}
