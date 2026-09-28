package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.companion.CompanionRules;
import com.player2.playerengine.player2api.Event.InfoMessage;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.status.AgentStatus;
import com.player2.playerengine.player2api.status.StatusUtils;
import com.player2.playerengine.player2api.status.WorldStatus;
import com.player2.playerengine.program.Job;
import com.player2.playerengine.program.JobResults;
import com.player2.playerengine.program.Linter;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.tasks.LookAtOwnerTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public class AgentConversationData {

    private static short MAX_EVENT_QUEUE_SIZE = 10;
    private static final int MAX_DEFERRED_INFO_QUEUE_SIZE = 4;
    private static final int MAX_DEFERRED_INFO_MESSAGE_LENGTH = 512;
    /** A lint repair shows the model at most this much of its own program. */
    private static final int MAX_PROGRAM_ECHO = 1500;
    /** A job's goal is the request that started it, cut to this length. */
    private static final int MAX_GOAL = 80;
    static final String CANNOT_WRITE_LINE = "I couldn't work out how to do that. Can you put it another way?";

    /**
     * Marker prefix for a {@code finishWithNote} note that carries an informational RESULT payload
     * (e.g. {@code locate_storage} coordinates) rather than a degradation. Commands wrap their payload
     * with {@link com.player2.playerengine.commands.base.Command#finishWithInfo(String)}.
     */
    public static final String INFO_RESULT_NOTE_PREFIX = "[info-result] ";

    public static final Logger LOGGER = LogManager.getLogger();

    private final PlayerEngineController mod;

    private final Deque<Event> eventQueue = new ConcurrentLinkedDeque<>();
    /** Passive context that must never make this conversation dispatchable by itself. */
    private final Deque<Event.InfoMessage> deferredInfoQueue = new ConcurrentLinkedDeque<>();
    private long lastProcessTime = 0L;
    private static final long NO_ACTIVE_TURN = Long.MIN_VALUE;
    private volatile boolean isProcessing = false;
    private long activeTurnTicket = NO_ACTIVE_TURN;
    private boolean enabled = true;

    /**
     * A greeting event sits in the queue ({@link #onGreeting}, or a first-meeting {@link #onReturn}).
     * The next batch consumes it: {@link #isGreetingResponse} is decided per batch from it.
     */
    private boolean greetingQueued = false;
    /**
     * This turn answers a greeting: its program is not run, and its {@code say} carries the
     * {@code [bl:greeting]} marker. Never true for a batch that carries an owner message: that turn
     * runs normally, so an order in it is not dropped.
     */
    private boolean isGreetingResponse = false;

    private MessageBuffer playerEngineMsgBuffer = new MessageBuffer(10);

    /** Latest prompting username from the current batch (prompter-pays billing). */
    private String chainInitiatorUsername;

    /**
     * The per-turn currentMood block (already-bounded {@code toPromptString()}), resolved once per turn
     * gated on {@code enableCompanionMood}. {@link Optional#empty()} when the flag is off (tail
     * byte-identical). TAIL ONLY — must never enter the static system block (prefix-cache).
     */
    private Optional<String> turnCurrentMood = Optional.empty();

    /** Invalidates queued/in-flight model turns when an authenticated stop bypasses the model. */
    private final ConversationTurnGate conversationTurnGate = new ConversationTurnGate();
    /** Matching billing-bucket request, retained so a stop can retire only this bot's HTTP worker. */
    private LLMCompleter activeLlmCompleter;
    private LLMCompleter.Submission activeLlmSubmission;

    /**
     * Consecutive replies that failed to parse, or carried a field the reply does not have, since the
     * last good one. Bounds the "resend valid JSON" retry loop so a model that keeps emitting bad output
     * does not spin forever (DESIGN.md §3 reporting must not turn into an infinite re-prompt).
     */
    private int consecutiveParseFailures = 0;

    /** Max consecutive format re-prompts before giving up this chain. */
    private static final int MAX_PARSE_RETRY = 2;

    /**
     * Transient (no NBT): how many consecutive non-silent replies this bot has made to peer
     * (CharacterMessage) turns without an intervening human/owner turn. Drives the count-aware
     * peer-talk reminder ({@link #getReminderStringFromLastEvent}). Reset on human activity; credited
     * (reset) when the bot chooses silence. See masterplan/peer-talk-restraint-plan.md.
     */
    private int consecutivePeerReplies = 0;

    /**
     * Transient: lines from other companions that woke this bot's model since a human last spoke to
     * it. {@link PeerTalkPolicy} caps it at the operator's {@code peerReplies}; peer lines arrive on
     * the LLM worker while human lines arrive on the server thread.
     */
    private final AtomicInteger peerLinesAnsweredSinceHuman = new AtomicInteger();

    /**
     * Per-bot TTS pacing: nanoTime() after which this specific bot is allowed to start a new
     * LLM/conversation round. Replaces the previous server-wide TTS lock so other bots can be
     * processed while this one is still "speaking" client-side.
     */
    private volatile long ttsCooldownUntilNanos = 0L;

    /** Approx TTS characters/second (matches TTSManager). */
    private static final int TTS_CHARS_PER_SECOND = 25;

    // --- Bodylang TTS-timed gestures: per-message marker state ---
    /**
     * Ordered, VALID-ONLY body-language boundaries for THIS bot's most recent message. Built by
     * {@link MarkerParser} in {@link #handleLlmResponse} and overwritten each message. This is the
     * authoritative server-side list the {@code segment_done} handler indexes into (by the same
     * valid-only numbering used on the wire), and that the fallback timer fires from. Invalid markers
     * are NOT stored here — they are reported to both audiences at parse time and never fire.
     */
    private volatile List<MarkerParser.SegmentBoundary> pendingSegmentActions = List.of();
    /**
     * The stripped text split at marker positions for THIS bot's most recent message (the wire chunk
     * list). Read by {@link AgentSideEffects#onEntityMessage} to build the {@code stream_tts} payload.
     */
    private volatile List<String> pendingChunks = List.of();
    /**
     * Raw tokens of INVALID markers from the most recent message (e.g. {@code "wave"}). Reported to
     * the model here at parse time (InfoMessage) and to the player in {@code onEntityMessage}
     * (Workstream 6 player-facing line). Overwritten each message; empty when all markers were valid.
     */
    private volatile List<String> pendingInvalidMarkers = List.of();
    /**
     * Per-bot fallback-timer cancel hook. Set by the WS4 dispatch path (server-side) when the safety
     * timer is scheduled; invoked by {@link #cancelFallbackTimer()} when {@code message_done} arrives so
     * a late prompter ACK does not double-clear / double-fire. {@code null} when no timer is pending.
     */
    private volatile Runnable fallbackTimerCancel = null;

    /** This companion's programs, run as jobs on the server tick (§6.6). */
    private final ProgramJobs jobs;
    /** Model turns a job may spend on repairs (§6.3). */
    private final RepairLimit repairs = new RepairLimit();
    /**
     * Set when a program that did not lint was sent back for its one repair (§6.7). A second program
     * that does not lint ends the attempt; a player's next line clears it.
     */
    private volatile boolean lintRepairOpen;
    /** Results turns (the loop) since the last player line; see {@link #feedResults}. */
    static final int MAX_FOLLOW_UPS = 2;
    private volatile int followUpsThisAsk;
    /** What happened to the last reply's program, for the smoke harness and the log. */
    private volatile String lastProgramVerdict = "";
    /** Said instead of calling the model when a turn cap is reached ({@link TurnCaps}). */
    static final String TIRED_LINE = "I'm worn out. Give me a little while before the next thing.";
    private final java.util.concurrent.atomic.AtomicLong modelRequests = new java.util.concurrent.atomic.AtomicLong();
    /** The authenticated player (UUID, never name) whose chat started the chain in progress, or null. */
    private volatile UUID chainInitiator;

    public AgentConversationData(PlayerEngineController mod) {
        this.mod = mod;
        this.jobs = new ProgramJobs(mod, new JobReports());
    }

    public String getChainInitiatorUsername() {
        return chainInitiatorUsername;
    }

    /** Valid-only ordered boundaries for the most recent message (segment_done indexes into this). */
    public List<MarkerParser.SegmentBoundary> getPendingSegmentActions() {
        return pendingSegmentActions;
    }

    /** Wire chunk list for the most recent message (read by onEntityMessage for the stream_tts payload). */
    public List<String> getPendingChunks() {
        return pendingChunks;
    }

    /** Invalid marker tokens from the most recent message (player-facing reporting in onEntityMessage). */
    public List<String> getPendingInvalidMarkers() {
        return pendingInvalidMarkers;
    }

    /**
     * Register a cancel hook for the WS4 server-side fallback timer (keyed by this bot). Replaces any
     * previously pending hook (a new dispatch supersedes the old timer).
     */
    public void setFallbackTimerCancel(Runnable cancel) {
        this.fallbackTimerCancel = cancel;
    }

    /**
     * Cancel the pending fallback timer if one is registered (idempotent). Called from the
     * {@code message_done} handler so the prompter ACK pre-empts the liveness backstop.
     */
    public void cancelFallbackTimer() {
        Runnable c = this.fallbackTimerCancel;
        this.fallbackTimerCancel = null;
        if (c != null) {
            c.run();
        }
    }

    /**
     * Workstream 6: reflect a partial-speech (degraded {@code message_done}) outcome into the MODEL's
     * feedback channel so the AI knows some of its speech did not play and cannot claim full success
     * (DESIGN.md §3). The player-facing line is broadcast separately at the {@code message_done} site.
     */
    public void reportPartialSpeechToModel() {
        addEventToQueue(new InfoMessage(
                "Note: part of your spoken reply did not play for the listener (a TTS chunk failed). "
                + "Do not claim you said everything; if it matters, you may briefly restate the key point."));
    }

    // ## Processing

    public long getTtsCooldownUntilNanos() {
        return ttsCooldownUntilNanos;
    }

    // 0 => should not process, otherwise a number that grows with priority (ns since the last round).
    public long getPriority() {
        if (!enabled || isProcessing || !hasDispatchableEvents(eventQueue)) {
            return 0;
        }
        // Self-pace: don't start a new LLM round while this bot's last response is still
        // being spoken client-side.
        if (System.nanoTime() < ttsCooldownUntilNanos) {
            return 0;
        }
        // Listener-pace: defer until another bot's line at the head of the queue has finished
        // playing (estimated cooldown on the sender, or early-clear via tts_playback_done ACK).
        Event head = eventQueue.peek();
        if (head instanceof Event.CharacterMessage charMsg) {
            AgentConversationData sender = charMsg.sendingCharacterData();
            if (!sender.getUUID().equals(getUUID())
                    && System.nanoTime() < sender.getTtsCooldownUntilNanos()) {
                return 0;
            }
        }
        return System.nanoTime() - lastProcessTime;
    }

    /**
     * Read-only billing snapshot for the next dispatch round. Mirrors the initiator selection
     * used inside {@link #process} so the bucket key the dispatcher picks matches the eventual
     * API call. Side-effect free; safe to call from the conversation dispatch loop.
     */
    public Player2PayerResolution.ApiBillingContext previewBilling() {
        String lastUserInBatch = null;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserInBatch = um.userName();
            }
        }
        String relayInitiator = lastUserInBatch != null ? lastUserInBatch : chainInitiatorUsername;
        return Player2PayerResolution.resolve(mod, relayInitiator,
                mod.getPlayer2APIService().getClientId());
    }

    /**
     * Record an estimated speech duration for this bot's most recent message and start a per-bot
     * cooldown. Called from {@link AgentSideEffects#onEntityMessage} right after submitting the
     * TTS payload so dispatch defers this bot (only) for the playback window.
     */
    public void markSpeakingFor(String message) {
        if (message == null) {
            return;
        }
        int waitTimeSec = (int) Math.ceil(message.length() / (double) TTS_CHARS_PER_SECOND) + 1;
        long waitNanos = TimeUnit.SECONDS.toNanos(waitTimeSec);
        ttsCooldownUntilNanos = System.nanoTime() + waitNanos;
    }

    /** Test/clear helper: drop any pending self-pace (e.g. on queue clear / disconnect). */
    public void clearTtsCooldown() {
        ttsCooldownUntilNanos = 0L;
    }

    /** Clear pending events and per-round flags without disturbing persisted history. */
    public void resetForClear() {
        resetForClear(true);
    }

    /**
     * As {@link #resetForClear()}; with {@code dropJob} false the active job stays in memory and in
     * job.json, so a server stop keeps it for the next start (where it loads PAUSED).
     */
    public void resetForClear(boolean dropJob) {
        invalidateProcessingTurnAndClearEvents();
        synchronized (deferredInfoQueue) {
            deferredInfoQueue.clear();
        }
        chainInitiatorUsername = null;
        chainInitiator = null;
        synchronized (this) {
            // The greeting event went with the queue; the flag must not force a later turn.
            greetingQueued = false;
            isGreetingResponse = false;
        }
        if (dropJob) {
            jobs.stop();
        }
        clearTtsCooldown();
        turnCurrentMood = Optional.empty();
        repairs.reset();
        lintRepairOpen = false;
        followUpsThisAsk = 0;
        consecutiveParseFailures = 0;
        consecutivePeerReplies = 0;
        peerLinesAnsweredSinceHuman.set(0);
    }

    // get LLM response and add to conversation history
    public void process(
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> extOnErrMsg,
            LLMCompleter completer) {

        final long turnTicket;
        synchronized (this) {
            if (isProcessing) {
                LOGGER.warn("Called queueData.process even though it was already processing! this should not happen");
                return;
            }
            if (eventQueue.isEmpty()) {
                LOGGER.warn("queueData.process called on empty event queue! this should not happen");
                return;
            }
            turnTicket = conversationTurnGate.issueTicket();
            activeTurnTicket = turnTicket;
            isProcessing = true;
        }

        // A passive note can add context to a real turn, but can never create a turn. Prepending keeps
        // the independently dispatchable event last so reminder, payer and provenance selection
        // continue to describe the event that actually caused this request.
        mergeDeferredInfoForProcessing(eventQueue, deferredInfoQueue);

        Consumer<String> onErrMsg = errMsg -> {
            synchronized (this) {
                if (!conversationTurnGate.accepts(turnTicket)) {
                    LOGGER.info("Discarding stale model error after authenticated stop for bot={}", getName());
                    releaseProcessing(turnTicket);
                    return;
                }
                try {
                    // DESIGN.md §3: a model JSON parse failure is reflected to the model as an
                    // InfoMessage so it retries truthfully next round. The player hears nothing: a
                    // system-voiced line from the companion breaks character, and the retry usually
                    // lands. Giving up leaves the current job running. The raw payload is already
                    // logged in Utils.parseCleanedJson; do not surface it here.
                    if (isModelParseFailure(errMsg)) {
                        retryBadReply("it was not valid JSON");
                        return;
                    }
                    extOnErrMsg.accept(errMsg);
                } finally {
                    releaseProcessing(turnTicket);
                }
            }
        };

        this.lastProcessTime = System.nanoTime();
        turnCurrentMood = Optional.empty();

        String lastUserInBatch = null;
        UUID lastUserUuid = null;
        boolean ownerInBatch = false;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserInBatch = um.userName();
                lastUserUuid = um.authenticatedUserUuid();
                if (isAuthenticatedOwner(um)) {
                    ownerInBatch = true;
                    mod.markOwnerMessage(System.currentTimeMillis());
                }
            }
        }
        synchronized (this) {
            isGreetingResponse = greetingQueued && !ownerInBatch;
            if (greetingQueued && ownerInBatch) {
                LOGGER.info("[Greeting] owner message in the greeting batch; running the turn normally for bot={}",
                        getName());
            }
            greetingQueued = false;
        }
        if (lastUserInBatch != null) {
            chainInitiatorUsername = lastUserInBatch;
            chainInitiator = lastUserUuid;
        }

        final String relayInitiator = lastUserInBatch != null ? lastUserInBatch : chainInitiatorUsername;
        if (relayInitiator != null && !relayInitiator.isBlank()) {
            MinecraftServer srv = mod.getPlayer().getServer();
            if (srv != null && BotBlacklistPolicy.isBlocked(srv, relayInitiator, this)) {
                LOGGER.info("Skipping LLM/API: bot blacklist blocks initiator={} for bot={}", relayInitiator, getName());
                eventQueue.clear();
                releaseProcessing(turnTicket);
                return;
            }
            if (srv != null && UserBlacklistPolicy.isBlocked(srv, relayInitiator, this)) {
                LOGGER.info("Skipping LLM/API: user blacklist blocks initiator={} for bot={}", relayInitiator, getName());
                eventQueue.clear();
                releaseProcessing(turnTicket);
                return;
            }
        }

        // A batch with a player's line is that player's turn; a feedback turn is the chain's.
        UUID turnPlayer = lastUserInBatch != null ? lastUserUuid : chainInitiator;
        if (!TurnCaps.SHARED.tryCharge(turnPlayer, mod.getPlayer().getUUID())) {
            LOGGER.info("Turn cap reached for player={} or bot={}; no model call", turnPlayer, getName());
            eventQueue.clear();
            releaseProcessing(turnTicket);
            onCharacterEvent.accept(new Event.CharacterMessage(TIRED_LINE, this, relayInitiator));
            return;
        }

        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(mod, chainInitiatorUsername,
                mod.getPlayer2APIService().getClientId());
        mod.getPlayer2APIService().setActiveBillingContext(billing);
        if (billing.onlinePayer() == null && !billing.useStoredToken()) {
            releaseProcessing(turnTicket);
            onErrMsg.accept("Player2: no billing player/token available for this API request.");
            return;
        }

        // The last user message of the batch, captured before the queue is drained: memory retrieval
        // reads it, and a program this turn starts takes it as its goal.
        Event.UserMessage lastUserMsg = null;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserMsg = um;
            }
        }

        // prepare conversation history for LLM call
        Event lastEvent = mod.getAIPersistantData().dumpEventQueueToConversationHistoryAndReturnLastEvent(eventQueue,
                mod.getPlayer2APIService());
        Optional<String> reminderString = getReminderStringFromLastEvent(lastEvent);

        String defaultReminderString = " | REMEMBER TO OUTPUT ONLY VALID JSON OUTPUT";
        reminderString = reminderString.map(a -> a + defaultReminderString);
        reminderString = Optional.of(reminderString.orElse(defaultReminderString));

        String agentStatus = AgentStatus.fromMod(this.mod).toString();
        String worldStatus = WorldStatus.fromMod(this.mod).toString();
        String altoClefDebugMsgs = this.playerEngineMsgBuffer.dumpAndGetString();
        // Phase D (W5): zero-LLM memory retrieval, hard-gated by the OWNER's patron status (fail-closed
        // → Optional.empty(), request byte-identical to today). Injected at the user-tail only.
        Optional<String> memoryBlock = resolveMemoryBlock(lastUserMsg);
        // Mood (WS3): inject the persisted current mood into the per-turn TAIL only, gated on
        // enableCompanionMood. Flag-off → Optional.empty() → tail byte-identical to pre-mood-feature.
        this.turnCurrentMood = resolveCurrentMoodBlock();
        ConversationHistory historyWithWrappedStatus = mod.getAIPersistantData()
                .getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                        mod.getPlayer2APIService(), reminderString, memoryBlock, this.turnCurrentMood);

        // A stop can arrive while status assembly is in progress. Avoid dispatching an already-stale
        // request; the callback ticket remains the final race backstop if invalidation happens after here.
        if (!conversationTurnGate.accepts(turnTicket)) {
            LOGGER.info("Skipping stale model request after authenticated stop for bot={}", getName());
            releaseProcessing(turnTicket);
            return;
        }

        LOGGER.info("[AICommandBridge/processChatWithAPI]: Calling LLM: history={}",
                new Object[] { historyWithWrappedStatus.toString() });

        final Event.UserMessage turnUserMsg = lastUserMsg;
        final UUID turnInitiator = turnPlayer;
        Consumer<JsonObject> onLLMResponse = jsonResp -> handleLlmResponse(
                jsonResp, lastEvent, relayInitiator, turnInitiator, onCharacterEvent, historyWithWrappedStatus,
                turnUserMsg, turnTicket);
        submitDecisionIfCurrent(turnTicket, completer, historyWithWrappedStatus, onLLMResponse, onErrMsg, "initial");
    }

    /**
     * Phase D (W5) per-turn memory retrieval. Resolves the OWNER's billing context, runs the W7 patron
     * gate, and only on an {@code allowed} (patron + master-flag-on) decision performs the zero-LLM
     * graph retrieval over the companion's published snapshot. Non-patron / disabled / no store / empty
     * graph → {@link Optional#empty()} (request byte-identical to today). Never throws — any failure
     * degrades to no memory block. Graph retrieval itself is deterministic on-device; the ONE Player2
     * call this may make is the W8d single turn-embedding ({@code POST /v1/embeddings}, off-tick inside
     * {@link com.player2.playerengine.memory.retrieval.MemoryRetriever}), which degrades to null (no
     * dense) on any failure — the retrieval result is unchanged when the embed is unavailable.
     */
    private Optional<String> resolveMemoryBlock(Event.UserMessage lastUserMsgForRag) {
        try {
            String turnText = lastUserMsgForRag != null ? lastUserMsgForRag.message() : null;
            if (turnText == null || turnText.isBlank()) {
                return Optional.empty();
            }
            LivingEntity self = mod.getPlayer();
            if (self == null) {
                return Optional.empty();
            }
            MinecraftServer server = self.getServer();
            if (server == null) {
                return Optional.empty();
            }
            Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
            // Resolve the OWNER billing context (never the prompter's), then the fail-closed patron gate.
            com.player2.playerengine.player2api.Player2PayerResolution.ApiBillingContext ownerBilling =
                    com.player2.playerengine.player2api.Player2PayerResolution.resolve(
                            mod, mod.getOwnerUsername(), cfg.getHeartbeatClientId());
            com.player2.playerengine.memory.budget.MemoryGateDecision decision =
                    com.player2.playerengine.memory.budget.MemoryGate.preflight(server, ownerBilling);
            com.player2.playerengine.memory.budget.Layer3Context ctx =
                    com.player2.playerengine.memory.budget.Layer3Context.fromGate(ownerBilling, decision);
            if (!ctx.layer3Enabled()) {
                return Optional.empty(); // not a patron / disabled / over budget
            }

            // Resolve this companion's scope + live store (lazy-load via the registry).
            Character character = mod.getAIPersistantData() != null
                    ? mod.getAIPersistantData().getCharacter() : null;
            String companionId = character != null ? character.id() : null;
            if (companionId == null || companionId.isBlank()) {
                return Optional.empty();
            }
            UUID ownerUuid = (mod.getOwner() != null) ? mod.getOwner().getUUID() : null;
            com.player2.playerengine.memory.MemoryScope scope = (ownerUuid != null)
                    ? com.player2.playerengine.memory.MemoryScope.of(ownerUuid, companionId)
                    : com.player2.playerengine.memory.MemoryScope.ofEntityFallback(self.getUUID(), companionId);

            com.player2.playerengine.memory.MemoryStore store =
                    com.player2.playerengine.memory.MemoryStoreRegistry.getOrLoad(server, scope);
            if (store == null) {
                return Optional.empty();
            }
            // H1 backfill trigger (Bug-2 fix, 2026-07-01): kick the off-tick dense backfill whenever the
            // store is resolved and not already fully embedded. The prior gate keyed on the store being
            // ABSENT at THIS chat hook (peek == null before getOrLoad); but the store is loaded much
            // earlier — at companion SUMMON (ensureCompanionExists -> MemoryStoreRegistry.getOrLoad) —
            // so by the time the first chat runs peek != null, the guard never fired, and the pre-existing
            // vectorless nodes were never embedded. backfillDenseVectors is idempotent, off-tick
            // (MEMORY_EXECUTOR), and EMBED_IN_FLIGHT-guarded, so calling it every turn is safe; the
            // denseBackfillComplete() check keeps a fully-embedded store from scheduling a no-op pass each
            // turn (a completed store schedules nothing). Never touches the tick.
            if (!store.denseBackfillComplete()) {
                com.player2.playerengine.memory.ingest.MemoryIngestionService.backfillDenseVectors(
                        mod, server, ownerBilling, scope);
            }

            long gameTime = server.overworld() != null ? server.overworld().getGameTime() : 0L;
            com.player2.playerengine.memory.retrieval.MemoryRetriever.Thresholds thresholds =
                    new com.player2.playerengine.memory.retrieval.MemoryRetriever.Thresholds(
                            cfg.getMemoryMaxHopsClamped(),
                            cfg.getMemoryMaxEgoNodesClamped(),
                            cfg.getMemoryBlockCharCapClamped(),
                            cfg.getMemoryMinConfidenceClamped(),
                            cfg.getMemoryDecayBaseClamped(),
                            cfg.getMemoryGameTimeUnitClamped(),
                            cfg.getMemoryRetrievalTopKClamped());
            // W8d: wire the dense-retrieval context so the full retrieve path embeds the turn ONCE
            // (off-tick) and threads the shared vector into both fusion sites. The OWNER billing context
            // (never the prompter's) is already resolved above for the patron gate; reuse it. When dense
            // is off / unavailable / the embed degrades, both fusion sites are byte-identical to pre-W8.
            com.player2.playerengine.memory.retrieval.MemoryRetriever retriever =
                    new com.player2.playerengine.memory.retrieval.MemoryRetriever(store,
                            new com.player2.playerengine.memory.retrieval.MemoryRetriever.DenseContext(
                                    mod, ownerBilling));
            // Pass the self/owner anchor names so the knowledge-boundary discriminator
            // (specificSeedMatches) can fire — without these, the 6-arg overload nulls both
            // anchors and the hallucination-boundary verdict override is inert.
            String companionName = (character != null) ? character.name() : null;
            String ownerName = mod.getOwnerUsername();
            com.player2.playerengine.memory.retrieval.MemoryRetrievalResult result =
                    retriever.retrieve(turnText, ownerUuid, companionId, companionName, ownerName,
                            gameTime, thresholds, true);
            return result != null ? result.memoryBlock() : Optional.empty();
        } catch (Exception e) {
            LOGGER.warn("[Memory] retrieval failed; degrading to no memory block ({})",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Companion mood (WS2 + WS4): reads the optional {@code "mood"} object from the LLM reply, validates
     * it deterministically, updates + persists {@link com.player2.playerengine.player2api.mood.CompanionMood},
     * reports an invalid label to the MODEL ONLY (no player chat), and on a model-flagged +
     * code-confirmed meaningful transition mints one memory EVENT (patron-gated inside the ingest call).
     *
     * <p>Gated on {@code enableCompanionMood} (WS5): when off this is a complete no-op (no parse, no
     * write) so the per-turn behavior is byte-identical to pre-mood-feature. Never throws.
     */
    private void handleMoodDeclaration(JsonObject jsonResp) {
        try {
            if (!Player2ServerConfigHolder.get().isEnableCompanionMood()) {
                return;
            }
            AIPersistantData data = mod.getAIPersistantData();
            if (data == null) {
                return;
            }
            // Read the optional "mood" object directly (decision 1: absent = no change).
            JsonObject moodObj = (jsonResp.has("mood") && jsonResp.get("mood").isJsonObject())
                    ? jsonResp.getAsJsonObject("mood") : null;

            com.player2.playerengine.player2api.mood.CompanionMood prevMood = data.getCurrentMood();
            com.player2.playerengine.player2api.mood.MoodUpdate.MoodUpdateResult result =
                    com.player2.playerengine.player2api.mood.MoodUpdate.apply(moodObj, prevMood);

            // Invalid label → tell the MODEL only (mirrors the invalid-marker InfoMessage path); mood
            // stays unchanged; no player chat (mood is a soft state — do not spam the player).
            if (result.invalidLabel()) {
                addEventToQueue(new InfoMessage(
                        "Note: the mood label you declared is not valid and your mood was NOT changed. "
                        + "Valid moods are: neutral, happy, content, excited, curious, sad, anxious, "
                        + "angry, afraid, determined. Do not claim a mood you did not set."));
                return;
            }

            com.player2.playerengine.player2api.mood.CompanionMood newMood = result.mood();
            // Unchanged (absent/partial mood, same object) → nothing to persist or mint.
            if (newMood == prevMood) {
                return;
            }

            // Deterministic update + persist (mirrors updateMood → saveHistoryNow sequencing).
            data.updateMood(newMood);
            data.saveMoodNow();

            // WS4: model-flagged (memorable) AND code-confirmed meaningful transition → mint one EVENT.
            // ingestMoodEventDirectly runs MemoryGate.preflight internally (patron + enableGraphRagMemory
            // + budget); a complete no-op for non-patrons / memory off. The cause it writes is the
            // already-capped newMood.cause(), never the raw model JSON.
            if (com.player2.playerengine.player2api.mood.MoodMemoryRule.isMeaningfulTransition(
                    prevMood, newMood, result.memorable())) {
                com.player2.playerengine.memory.ingest.MemoryIngestionService.ingestMoodEventDirectly(
                        this.mod, prevMood, newMood);
            }
        } catch (Exception e) {
            LOGGER.warn("[Mood] declaration handling failed; mood unchanged ({})",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Mood (WS3): resolves the per-turn currentMood block for the user-tail, gated on
     * {@code enableCompanionMood}. When the flag is off this returns {@link Optional#empty()} so the
     * tail is byte-identical to pre-mood-feature. When on, the persisted mood's
     * {@link com.player2.playerengine.player2api.mood.CompanionMood#toPromptString()} (already bounded:
     * enum label + clamped intensity + capped cause) is injected at the tail only — never the static
     * system block. Never throws; any failure degrades to no mood block.
     */
    private Optional<String> resolveCurrentMoodBlock() {
        try {
            if (!Player2ServerConfigHolder.get().isEnableCompanionMood()) {
                return Optional.empty();
            }
            AIPersistantData data = mod.getAIPersistantData();
            if (data == null) {
                return Optional.empty();
            }
            com.player2.playerengine.player2api.mood.CompanionMood mood = data.getCurrentMood();
            if (mood == null) {
                return Optional.empty();
            }
            String block = mood.toPromptString();
            return (block == null || block.isBlank()) ? Optional.empty() : Optional.of(block);
        } catch (Exception e) {
            LOGGER.warn("[Mood] currentMood block resolution failed; degrading to no mood block ({})",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean isEventDuplicateOfLastMessage(Event evt) {
        boolean isDuplicate = eventQueue.peekLast() != null && eventQueue.peekLast().equals(evt);
        if (isDuplicate) {
            if (evt instanceof Event.UserMessage um && um.fromVoice()) {
                LOGGER.warn("STT/voice: duplicate user message dropped for companion={} preview=\"{}\"",
                        getName(), com.player2.playerengine.player2api.utils.SttLogging.messagePreview(um.message()));
            } else {
                LOGGER.warn("[EventQueueData]: evt={} was added twice!", evt.getConversationHistoryString());
            }
            return true;
        }
        return false;
    }

    private void addEventToQueue(Event event) {
        if (isEventDuplicateOfLastMessage(event)) {
            return; // skip
        }
        if (eventQueue.size() > MAX_EVENT_QUEUE_SIZE) {
            eventQueue.removeFirst();
        }
        LOGGER.info("queue for UUID={} name={} adding event={} ", getUUID(), getName(), event);
        eventQueue.add(event);
    }

    /**
     * Queue bounded, curated model context without waking the conversation dispatcher. Oversized or
     * blank input is rejected instead of truncated so arbitrary external output can never be smuggled
     * into a later prompt through this path.
     */
    public boolean deferInfo(Event.InfoMessage message) {
        return addDeferredInfo(deferredInfoQueue, message);
    }

    /**
     * Terminal operator-stop barrier. It does not wake the model: pending dispatchable events are
     * removed, any pre-stop response ticket becomes stale, and a bounded truthfulness note waits for
     * the next genuine conversation turn.
     */
    public boolean cancelPendingModelActionsForOperatorStop() {
        LLMCompleter.CancellationOutcome cancellation = invalidateProcessingTurnAndClearEvents();
        jobs.stop();
        boolean requestStillDraining = cancellation == LLMCompleter.CancellationOutcome.RETIREMENT_LIMIT_REACHED;
        repairs.reset();
        lintRepairOpen = false;
        clearTtsCooldown();
        pendingSegmentActions = List.of();
        pendingChunks = List.of();
        pendingInvalidMarkers = List.of();
        String modelNote = requestStillDraining
                ? "A player issued an immediate stop. Automation and queued actions were cancelled, but a stuck "
                        + "prior model request is still draining because the bounded worker safety limit was reached. "
                        + "New replies may be delayed; do not resume the prior action."
                : "A player issued an immediate stop. Automation and any queued or in-flight action were cancelled. "
                        + "Do not claim the prior action completed or resume it unless the owner explicitly asks.";
        deferInfo(new InfoMessage(modelNote));
        return requestStillDraining;
    }

    /** Clears pre-stop events before atomically releasing the dispatcher slot for a new owner request. */
    private synchronized LLMCompleter.CancellationOutcome invalidateProcessingTurnAndClearEvents() {
        conversationTurnGate.invalidate();
        eventQueue.clear();
        LLMCompleter completer = activeLlmCompleter;
        LLMCompleter.Submission submission = activeLlmSubmission;
        activeLlmCompleter = null;
        activeLlmSubmission = null;
        activeTurnTicket = NO_ACTIVE_TURN;
        isProcessing = false;
        if (completer != null && submission != null) {
            return completer.cancel(submission);
        }
        return LLMCompleter.CancellationOutcome.NOT_ACTIVE;
    }

    /** A stale callback cannot release a newer request because every dispatched turn has a unique ticket. */
    private synchronized void releaseProcessing(long turnTicket) {
        if (activeTurnTicket == turnTicket) {
            activeLlmCompleter = null;
            activeLlmSubmission = null;
            activeTurnTicket = NO_ACTIVE_TURN;
            isProcessing = false;
        }
    }

    /** Serializes the final ticket check, billing-bucket submission, and cancellable handle capture. */
    private synchronized boolean submitDecisionIfCurrent(
            long turnTicket,
            LLMCompleter completer,
            ConversationHistory history,
            Consumer<JsonObject> onResponse,
            Consumer<String> onError,
            String phase) {
        if (activeTurnTicket != turnTicket || !conversationTurnGate.accepts(turnTicket)) {
            LOGGER.info("Skipping stale {} model request after authenticated stop for bot={}",
                    phase, getName());
            releaseProcessing(turnTicket);
            return false;
        }
        LLMCompleter.Submission submission = completer.processToJson(
                mod.getPlayer2APIService(), history, onResponse, onError, true, AiTaskClass.DECISION);
        if (!submission.accepted()) {
            LOGGER.warn("Model request submission was rejected for bot={} phase={}", getName(), phase);
            releaseProcessing(turnTicket);
            return false;
        }
        activeLlmCompleter = completer;
        activeLlmSubmission = submission;
        modelRequests.incrementAndGet();
        return true;
    }

    /** Decision requests this companion has sent to the model since it was created. */
    public long getModelRequestCount() {
        return modelRequests.get();
    }

    /**
     * True for the error a decision turn reports when the model's reply was not JSON. Such an error
     * is retried with feedback to the model and never shown to the player.
     */
    static boolean isModelParseFailure(String errMsg) {
        return errMsg != null && errMsg.startsWith(
                com.player2.playerengine.player2api.utils.LlmJsonParseException.SENTINEL);
    }

    static boolean hasDispatchableEvents(Deque<Event> events) {
        return events != null && !events.isEmpty();
    }

    static boolean addDeferredInfo(Deque<Event.InfoMessage> target, Event.InfoMessage message) {
        if (target == null || message == null || message.message() == null
                || message.message().isBlank()
                || message.message().length() > MAX_DEFERRED_INFO_MESSAGE_LENGTH) {
            return false;
        }
        synchronized (target) {
            if (target.contains(message)) {
                return true;
            }
            while (target.size() >= MAX_DEFERRED_INFO_QUEUE_SIZE) {
                target.pollFirst();
            }
            target.addLast(message);
            return true;
        }
    }

    static int mergeDeferredInfoForProcessing(
            Deque<Event> dispatchableEvents,
            Deque<Event.InfoMessage> deferredInfo) {
        if (!hasDispatchableEvents(dispatchableEvents) || deferredInfo == null || deferredInfo.isEmpty()) {
            return 0;
        }
        List<Event.InfoMessage> pending = new ArrayList<>(MAX_DEFERRED_INFO_QUEUE_SIZE);
        synchronized (deferredInfo) {
            Event.InfoMessage next;
            while ((next = deferredInfo.pollFirst()) != null) {
                pending.add(next);
            }
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            dispatchableEvents.addFirst(pending.get(i));
        }
        return pending.size();
    }

    private Optional<String> getReminderStringFromLastEvent(Event lastEvent) {
        if (lastEvent instanceof Event.UserMessage) {
            return Optional.of((((Event.UserMessage) lastEvent).userName().equals(getMod().getOwnerUsername())
                    ? Prompts.reminderOnOwnerMsg
                    : Prompts.reminderOnOtherUSerMsg) + " " + Prompts.generalConversationReminder);
        }
        if (lastEvent instanceof Event.CharacterMessage) {
            // This plan (masterplan/peer-talk-restraint-plan.md) OWNS the CharacterMessage reminder text
            // (the single per-turn reminder slot for a peer head event). Do NOT overwrite this slot from
            // another track — extend Prompts.reminderOnAIMsg(int) instead. The streak read here reflects
            // the START-OF-TURN value: this method is called at process() (~:424) BEFORE handleLlmResponse
            // increments/resets consecutivePeerReplies for this turn, so the reminder count is correct.
            return Optional.of(Prompts.reminderOnAIMsg(getConsecutivePeerReplies())
                    + " " + Prompts.generalConversationReminder);
        }
        return Optional.of(Prompts.generalConversationReminder);
    }

    /**
     * A reply that did not parse, or carried a field the reply does not have, is sent back once or
     * twice with the reason; nothing in it runs. The player hears nothing. Caller holds the lock.
     */
    private void retryBadReply(String why) {
        consecutiveParseFailures++;
        if (consecutiveParseFailures <= MAX_PARSE_RETRY) {
            LOGGER.warn("[Reply] bot={} reply not used ({}); attempt {}/{}, asking for a valid reply",
                    getName(), why, consecutiveParseFailures, MAX_PARSE_RETRY);
            addEventToQueue(new InfoMessage("Your previous reply was not used: " + why + ". Resend ONLY one"
                    + " JSON object {\"say\": \"...\", \"program\": \"...\"}, with \"program\" only when you act,"
                    + " no other fields, no extra text and no markdown code fences."));
        } else {
            LOGGER.error("[Reply] bot={} reply still not usable after {} retries ({}); giving up this chain",
                    getName(), MAX_PARSE_RETRY, why);
            consecutiveParseFailures = 0;
        }
    }

    private void handleLlmResponse(
            JsonObject jsonResp,
            Event lastEvent,
            String relayInitiator,
            UUID initiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            ConversationHistory historyWithWrappedStatus,
            Event.UserMessage lastUserMsg,
            long turnTicket) {
        final boolean greetingResponse;
        final Reply.Parsed parsed = Reply.parse(jsonResp);
        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                LOGGER.info("Discarding stale model response after authenticated stop for bot={}", getName());
                releaseProcessing(turnTicket);
                return;
            }
            greetingResponse = this.isGreetingResponse;
            this.isGreetingResponse = false;
            if (!parsed.ok()) {
                // R10: a reply with `command` or `plan` is a format error, never a partial order.
                lastProgramVerdict = "format: " + parsed.error();
                DecisionCapture.record(this, lastEvent, historyWithWrappedStatus, jsonResp, null);
                retryBadReply(parsed.error());
                releaseProcessing(turnTicket);
                return;
            }
            this.consecutiveParseFailures = 0;
            // Mood is declared at most once per logical turn; the reply parsed, so this is that turn.
            handleMoodDeclaration(jsonResp);
        }
        Reply reply = parsed.reply();
        String say = reply.say();
        boolean isPeerTurn = lastEvent instanceof Event.CharacterMessage;
        String dispatched = null;
        if (reply.program() != null) {
            if (greetingResponse) {
                LOGGER.info("[Greeting] bot={} sent a program on the greeting turn; not run", getName());
                lastProgramVerdict = "ignored: greeting turn";
            } else if (isPeerTurn) {
                // A job answers to the player who asked; another companion's line is not a request.
                LOGGER.info("[Job] bot={} sent a program on a peer turn; not run", getName());
                lastProgramVerdict = "ignored: peer turn";
                deferInfo(new InfoMessage("Your program was not run: another companion's line is not a request."
                        + " Act only when a player asks."));
            } else if (conversationTurnGate.accepts(turnTicket)) {
                say = startProgram(reply.program(), say, lastUserMsg, initiator);
                dispatched = "started".equals(lastProgramVerdict) ? reply.program() : null;
            }
        }

        String previousAssistant = mod.getAIPersistantData().getLastAssistantContent().orElse("");
        if (dispatched == null && lastEvent instanceof Event.InfoMessage
                && ConversationHistory.isRepeatedSay(previousAssistant, say)) {
            LOGGER.info("[Reply] bot={} repeated its last line after an Info turn; not said again", getName());
            say = "";
        }
        if (greetingResponse && !say.contains("[bl:greeting]")) {
            say = "[bl:greeting] " + say;
        }
        // --- Bodylang TTS-timed gestures: deterministic marker parse (Workstream 1) ---
        // Strip inline [bl:<action>] markers from the say ONCE here (decision 2 — the single
        // choke-point) and build the ordered chunk + boundary lists. The CharacterMessage is then
        // constructed with the STRIPPED text, so chat, TTS and markSpeakingFor all operate on
        // stripped text with no second strip.
        MarkerParser.ParsedMessage markers = MarkerParser.parse(say);
        String strippedMessage = markers.strippedText();
        List<MarkerParser.SegmentBoundary> validBoundaries = new ArrayList<>();
        List<String> invalidTokens = new ArrayList<>();
        for (MarkerParser.SegmentBoundary b : markers.boundaries()) {
            if (b.valid()) {
                validBoundaries.add(b);
            } else {
                invalidTokens.add(b.rawToken());
            }
        }
        LOGGER.info("[AICommandBridge/processCharWithAPI]: Processed LLM response: say={} program={}",
                strippedMessage, lastProgramVerdict);
        DecisionCapture.record(this, lastEvent, historyWithWrappedStatus, jsonResp, dispatched);
        // Peer-talk restraint (masterplan/peer-talk-restraint-plan.md): the start-of-turn streak the
        // model saw was already baked into the reminder in process(), so mutating the counter now is
        // correct. A peer turn never starts a job, so substance there is words or a gesture.
        boolean substantiveReply = !strippedMessage.isEmpty() || dispatched != null || !validBoundaries.isEmpty();
        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                LOGGER.info("Discarding stale model side effects after authenticated stop for bot={}", getName());
                releaseProcessing(turnTicket);
                return;
            }
            // DESIGN.md §3 (truthfulness): an unknown marker is NOT silently dropped. Report it to the
            // MODEL here via an InfoMessage so the AI knows that gesture did not fire and cannot claim it
            // did. (The player-facing chat line is emitted in AgentSideEffects — Workstream 6.)
            if (!invalidTokens.isEmpty()) {
                LOGGER.warn("[Bodylang] bot={} emitted unknown gesture marker(s): {}", getName(), invalidTokens);
                addEventToQueue(new InfoMessage(String.format(
                        "Note: the gesture marker(s) %s are not valid and were NOT performed. "
                        + "Valid gestures are: greeting, nod_head, shake_head, victory. "
                        + "Do not claim you performed an invalid gesture.",
                        String.join(", ", invalidTokens))));
            }
            try {
                // A marker-only say ("[bl:greeting]") strips to empty text but must still dispatch so
                // its gesture fires via the TTS/segment path.
                if (!strippedMessage.isEmpty() || !validBoundaries.isEmpty()) {
                    this.pendingSegmentActions = List.copyOf(validBoundaries);
                    this.pendingChunks = List.copyOf(markers.chunks());
                    this.pendingInvalidMarkers = List.copyOf(invalidTokens);
                    mod.getAIPersistantData().addAssistantMessage(strippedMessage, mod.getPlayer2APIService());
                    onCharacterEvent.accept(new Event.CharacterMessage(strippedMessage, this, relayInitiator));
                    if (isPeerTurn && substantiveReply) {
                        this.consecutivePeerReplies++;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[AICommandBridge/processChatWithAPI/onLLMResponse]: ERROR RUNNING SIDE EFFECTS, errMsg={}",
                        e.getMessage());
            } finally {
                if (!isPeerTurn) {
                    this.consecutivePeerReplies = 0;
                } else if (!substantiveReply) {
                    LOGGER.debug("peer_talk_silence bot={} streak_before={}", getName(), this.consecutivePeerReplies);
                    this.consecutivePeerReplies = 0;
                }
                releaseProcessing(turnTicket);
            }
        }
    }

    /**
     * A reply's program: linted here, started as a job on the server thread when it is clean (§6.7).
     * A program that does not lint never touches the world. It gets one repair turn with every finding
     * (the lint repair); a second one that does not lint ends the attempt with a plain line.
     *
     * @return what the companion says with it: the reply's {@code say} when the job starts, nothing
     *         while a repair is pending, and a plain line when the attempt ends
     */
    private String startProgram(String source, String say, Event.UserMessage lastUserMsg, UUID initiator) {
        Linter.Result lint = jobs.lint(source);
        if (!lint.ok()) {
            lastProgramVerdict = "lint: " + lint.repairMessage();
            LOGGER.info("[Job] bot={} program does not lint: {}", getName(), lint.repairMessage());
            if (!lintRepairOpen) {
                lintRepairOpen = true;
                addEventToQueue(new InfoMessage("Your program was not run: it does not lint.\n"
                        + lint.repairMessage() + "\nYour program was:\n" + cut(source, MAX_PROGRAM_ECHO)
                        + "\nSend the corrected program, or no program and say what you can do instead."));
                return "";
            }
            lintRepairOpen = false;
            deferInfo(new InfoMessage(cut("Your corrected program still did not lint, so nothing ran: "
                    + lint.repairMessage(), MAX_DEFERRED_INFO_MESSAGE_LENGTH)));
            return CANNOT_WRITE_LINE;
        }
        lintRepairOpen = false;
        lastProgramVerdict = "started";
        String goal = lastUserMsg == null ? null : cut(lastUserMsg.message().strip(), MAX_GOAL);
        String fallbackGoal = say == null || say.isBlank() ? "the job" : cut(MarkerParser.parse(say).strippedText(), MAX_GOAL);
        MinecraftServer server = mod.getPlayer() == null ? null : mod.getPlayer().getServer();
        Runnable start = () -> {
            Job job = jobs.start(lint, goal, fallbackGoal, initiator);
            LOGGER.info("[Job] bot={} started job {} '{}' for {}", getName(), job.id(), job.goal(), initiator);
        };
        if (server != null && !server.isSameThread()) {
            server.execute(start);
        } else {
            start.run();
        }
        return say;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** What happened to the last reply's program: started, a lint or format finding, or ignored. */
    public String lastProgramVerdict() {
        return lastProgramVerdict;
    }

    /** Server tick: runs this companion's jobs. */
    public void tickJobs() {
        jobs.tick();
    }

    /** The {@code jobs()} list, for status and the smoke harness. */
    public List<String> jobs() {
        return jobs.jobs();
    }

    /**
     * A line the companion says without the model: a job's completion report (R5), a failure, a
     * question, an acknowledgement. It is spoken like a reply and kept in history, so the model knows
     * what was said. Server thread.
     */
    private void speakTemplate(String text, String originatingUser) {
        MinecraftServer server = mod.getPlayer() == null ? null : mod.getPlayer().getServer();
        if (server == null || text == null || text.isBlank()) {
            return;
        }
        pendingSegmentActions = List.of();
        pendingChunks = List.of(text);
        pendingInvalidMarkers = List.of();
        mod.getAIPersistantData().addAssistantMessage(text, mod.getPlayer2APIService());
        AgentSideEffects.onEntityMessage(server, new Event.CharacterMessage(text, this, originatingUser));
    }

    private String playerName(String uuid) {
        MinecraftServer server = mod.getPlayer() == null ? null : mod.getPlayer().getServer();
        if (server == null || uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(uuid));
            return p == null ? null : p.getGameProfile().getName();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The results loop: an ended job's findings, report and error come back to the model as the next
     * turn, so it can answer from what the world showed or carry the ask on. At most
     * {@link #MAX_FOLLOW_UPS} such turns per player line; past that the results wait, as context, for
     * the next real turn. Each turn is a model turn like any other, so the R17 caps charge it.
     */
    private void feedResults(Job job) {
        String results = "Results: " + JobResults.render(job);
        if (followUpsThisAsk < MAX_FOLLOW_UPS) {
            followUpsThisAsk++;
            LOGGER.info("[Job] bot={} results turn {}/{} for job {}", getName(), followUpsThisAsk, MAX_FOLLOW_UPS,
                    job.id());
            addEventToQueue(new InfoMessage(results + " Answer or carry on from these; say nothing more if the ask is"
                    + " done."));
        } else {
            LOGGER.info("[Job] bot={} results for job {} kept as context: follow-up limit reached", getName(), job.id());
            deferInfo(new InfoMessage(cut(results, MAX_DEFERRED_INFO_MESSAGE_LENGTH)));
        }
    }

    /** What the jobs report, turned into lines and model notes. */
    private final class JobReports implements ProgramJobs.Listener {
        @Override
        public void done(Job job, String report) {
            repairs.forget(job.goal());
            LOGGER.info("[Job] bot={} job {} done: {}", getName(), job.id(), report);
            if (!JobResults.queryOnly(job)) {
                speakTemplate(report, playerName(job.initiator()));
            }
            feedResults(job);
            if (mod.getModSettings().isEnableLookAtOwnerIdle()) {
                mod.runIdleUserTask(new LookAtOwnerTask());
            }
        }

        @Override
        public void needsRepair(Job job, ActionError error, String failedCall) {
            String what = (failedCall == null ? "the program" : "api." + failedCall) + " failed: "
                    + (error == null ? "unknown error" : error.toLine());
            LOGGER.info("[Job] bot={} job {} paused for repair: {}", getName(), job.id(), what);
            if (repairs.record(job.goal(), what) == RepairLimit.Decision.REPAIR) {
                addEventToQueue(new InfoMessage("Job \"" + job.goal() + "\" paused: " + what + ". So far: "
                        + job.report() + " " + JobResults.render(job) + " Send a program that finishes the job"
                        + " from where it stands, or no program and tell the player what went wrong."));
                return;
            }
            jobs.stop();
            String line = "I couldn't finish " + job.goal() + ": " + (error == null ? "it kept failing"
                    : error.message()) + ".";
            speakTemplate(cut(line, 200), playerName(job.initiator()));
            deferInfo(new InfoMessage(cut("The job \"" + job.goal() + "\" was given up after repeated failures ("
                    + what + "). Do not claim it finished.", MAX_DEFERRED_INFO_MESSAGE_LENGTH)));
        }

        @Override
        public void failed(Job job, ActionError error) {
            repairs.forget(job.goal());
            String why = error == null ? "a limit was reached" : error.message();
            LOGGER.info("[Job] bot={} job {} failed: {}", getName(), job.id(), error == null ? why : error.toLine());
            speakTemplate(cut("I had to stop " + job.goal() + ": " + why + ".", 200), playerName(job.initiator()));
            feedResults(job);
        }

        @Override
        public void asks(Job job, String question) {
            speakTemplate(cut(question, 200), playerName(job.initiator()));
        }

        @Override
        public void notice(UUID player, String text) {
            ConversationManager.noticeFromCompanion(AgentConversationData.this, player, text);
        }
    }

    private boolean isAuthenticatedOwner(Event.UserMessage um) {
        UUID sender = um == null ? null : um.authenticatedUserUuid();
        return sender != null && mod.getOwner() != null && sender.equals(mod.getOwner().getUUID());
    }

    public void onEvent(Event event) {
        if (event instanceof Event.UserMessage um) {
            peerLinesAnsweredSinceHuman.set(0);
            lintRepairOpen = false;
            followUpsThisAsk = 0;
            if (handleJobLine(um)) {
                return;
            }
        }
        addEventToQueue(event);
    }

    /**
     * The model-free job lines (§6.4, §6.6): a yes or no to a reconcile question from the job's
     * initiator, a bare continue, and "resume X". Each is answered here, with no model call; the model
     * is told on its next turn. Anything else goes to the model.
     */
    private boolean handleJobLine(Event.UserMessage um) {
        UUID speaker = um.authenticatedUserUuid();
        if (speaker == null || um.message() == null) {
            return false;
        }
        if (jobs.answer(um.message(), speaker)) {
            LOGGER.info("[Job] bot={} reconcile answered by {}: {}", getName(), um.userName(), um.message());
            return true;
        }
        ResumeIntent.Asked asked = ResumeIntent.parse(um.message());
        if (asked == null) {
            return false;
        }
        Job resumed = jobs.resume(asked, speaker);
        if (resumed == null) {
            return false;
        }
        LOGGER.info("[Job] bot={} {} resumed job {} '{}'", getName(), um.userName(), resumed.id(), resumed.goal());
        speakTemplate(cut("Picking up where I left off: " + resumed.goal() + ".", 200), um.userName());
        deferInfo(new InfoMessage(cut(um.userName() + " said \"" + um.message().strip() + "\", and the job \""
                + resumed.goal() + "\" resumed.", MAX_DEFERRED_INFO_MESSAGE_LENGTH)));
        return true;
    }

    public void onAICharacterMessage(Event.CharacterMessage msg) {
        boolean comingFromThisCharacter = msg.sendingCharacterData().getUUID().equals(getUUID());
        // is our character <=> dont add because we will already have added assistant
        // msg
        if (comingFromThisCharacter) {
            return;
        }
        Character self = getCharacter();
        int answered = peerLinesAnsweredSinceHuman.get();
        if (self != null && PeerTalkPolicy.wakes(msg.message(), self.name(), self.shortName(), answered,
                CompanionRules.get().peerReplies())) {
            peerLinesAnsweredSinceHuman.incrementAndGet();
            eventQueue.add(msg);
            return;
        }
        // Not addressed, or already answered enough peers since a human spoke: keep it as context
        // for the next turn without waking the model.
        LOGGER.info("peer line kept as context bot={} from={} answeredSinceHuman={}",
                getName(), msg.sendingCharacterData().getName(), answered);
        deferInfo(new Event.InfoMessage(peerContextLine(msg)));
    }

    static String peerContextLine(Event.CharacterMessage msg) {
        String line = msg.message() == null ? "" : msg.message().strip();
        if (line.isEmpty()) {
            return "";
        }
        int room = MAX_DEFERRED_INFO_MESSAGE_LENGTH - 64;
        if (line.length() > room) {
            line = line.substring(0, room) + "...";
        }
        return msg.sendingCharacterData().getName() + " said (no reply needed): " + line;
    }

    public void onGreeting() {
        synchronized (this) {
            greetingQueued = true;
        }
        addEventToQueue(mod.getAIPersistantData().getGreetingEvent());
    }

    public void onReturn(String ownerName) {
        AIPersistantData data = mod.getAIPersistantData();
        if (data.returnGreets()) {
            synchronized (this) {
                greetingQueued = true;
            }
        }
        addEventToQueue(data.getReturnEvent(ownerName));
    }

    public void onDeathRevival(String deathCause) {
        addEventToQueue(mod.getAIPersistantData().getDeathRevivalEvent(deathCause));
    }

    // Utils:
    public float getDistance(UUID target) {
        return StatusUtils.getDistanceToUUID(mod, target);
    }

    public UUID getUUID() {
        return mod.getPlayer().getUUID();
    }

    public PlayerEngineController getMod() {
        return mod;
    }

    public boolean isOwner(UUID playerToCheck) {
        return mod.isOwner(playerToCheck);
    }

    public LivingEntity getEntity() {
        return mod.getPlayer();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Character getCharacter() {
        return mod.getAIPersistantData().getCharacter();
    }

    public Player2APIService getPlayer2apiService() {
        return mod.getPlayer2APIService();
    }

    public String getName() {
        return getCharacter().shortName();
    }

    /** Transient peer-reply streak (consecutive non-silent replies to peer turns); read by the reminder builder. */
    int getConsecutivePeerReplies() {
        return consecutivePeerReplies;
    }

}
