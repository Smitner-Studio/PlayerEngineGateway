package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.CommandCaller;
import com.player2.playerengine.companion.CompanionRules;
import com.player2.playerengine.player2api.BotBlacklistPolicy;
import com.player2.playerengine.player2api.UserBlacklistPolicy;
import com.player2.playerengine.player2api.AgentSideEffects.CommandExecutionStopReason;
import com.player2.playerengine.player2api.Event.InfoMessage;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.plan.OwnerGate;
import com.player2.playerengine.player2api.plan.PlanCoordinator;
import com.player2.playerengine.player2api.plan.PlanParser;
import com.player2.playerengine.player2api.plan.PlanStore;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.status.AgentStatus;
import com.player2.playerengine.player2api.status.StatusUtils;
import com.player2.playerengine.player2api.status.WorldStatus;
import com.player2.playerengine.player2api.utils.Utils;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.RagPromptBuilder;
import com.player2.playerengine.retrieval.RetrievalConfidenceThresholds;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RetrievalResult;
import com.player2.playerengine.retrieval.ToolRetriever;
import com.player2.playerengine.retrieval.learning.AliasLearnSuggestion;
import com.player2.playerengine.retrieval.learning.AliasLearningService;
import com.player2.playerengine.retrieval.learning.DeepCheckBudgetGate;
import com.player2.playerengine.retrieval.learning.DeepCheckRephraseService;
import com.player2.playerengine.retrieval.RagDeepSearchCommands;
import com.player2.playerengine.retrieval.learning.RagDeepCheckCoordinator;
import com.player2.playerengine.retrieval.learning.RagDeepCheckPipeline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

public class AgentConversationData {

    private static short MAX_EVENT_QUEUE_SIZE = 10;
    private static final int MAX_DEFERRED_INFO_QUEUE_SIZE = 4;
    private static final int MAX_DEFERRED_INFO_MESSAGE_LENGTH = 512;

    /**
     * Marker prefix for a {@code finishWithNote} note that carries an informational RESULT payload
     * (e.g. {@code locate_storage} coordinates) rather than a degradation. The command-finish prompt
     * strips it and frames the note as a neutral result instead of a "but:" degradation. Commands wrap
     * their payload with {@link com.player2.playerengine.commands.base.Command#finishWithInfo(String)}.
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
     * This turn answers a greeting and is forced to {@code bodylang greeting}. Never true for a batch
     * that carries an owner message: forcing that turn would drop the owner's order (a "continue"
     * after a restart), so it runs normally and greets through the {@code [bl:greeting]} marker.
     */
    private boolean isGreetingResponse = false;
    private boolean shouldIgnoreGreetingDance = true;

    private MessageBuffer playerEngineMsgBuffer = new MessageBuffer(10);

    /** Latest prompting username from the current batch (prompter-pays billing). */
    private String chainInitiatorUsername;

    // --- Phase B3: per-turn RAG prompt cache ---
    /** Most recent retrieval hits; reused when the goal text is short or the batch has no user message. */
    private List<RetrievalHit> cachedRetrievalHits = List.of();
    /** Hash of the last injected tool-id set; used to skip redundant system-prompt rewrites. */
    private int cachedPromptIdHash = 0;

    // --- Prefix-cache restructure: per-turn RAG command block relocated to the user tail ---
    /**
     * The RAG command block to inject into THIS turn's latest user message ({@code validCommands} key).
     * Null on session-stable branches (full-list / always-include / greeting), where the list stays in
     * the system message instead. Cleared per turn in {@link #resetB5TurnState()}.
     */
    private String pendingValidCommandsBlock = null;
    /**
     * Session-scoped cache of the last built RAG block string (NOT reset per turn). Reused by the
     * churn-gate skip and {@code applyRagPromptFromCache} so those turns can re-supply the same block to
     * the user tail without rebuilding (and without leaving the model with an empty command set).
     */
    private String cachedValidCommandsBlock = null;

    // --- Prefix-cache restructure: status locals captured once per turn for follow-up tail rebuilds ---
    /** worldStatus/agentStatus/gameDebugMessages/reminders captured at first-pass assembly (L290-295). */
    private String turnWorldStatus = "";
    private String turnAgentStatus = "";
    private String turnAltoClefDebugMsgs = "";
    private Optional<String> turnReminderString = Optional.empty();
    /**
     * The per-turn currentMood block (already-bounded {@code toPromptString()}), resolved once per turn
     * gated on {@code enableCompanionMood}, so deep-check / post-decision retry follow-up tail rebuilds
     * reuse the same value. {@link Optional#empty()} when the flag is off (tail byte-identical). Reset in
     * {@link #resetB5TurnState()}. TAIL ONLY — must never enter the static system block (prefix-cache).
     */
    private Optional<String> turnCurrentMood = Optional.empty();
    /** Suppress repeated warnings when the RAG index is not yet initialised for this bot. */
    private boolean ragFallbackWarnedOnce = false;
    /** True once live RAG may have replaced the full system prompt during this session. */
    private boolean liveRagManagedSystemPrompt = false;

    // --- Phase B5: per-turn RAG / deep-check state ---
    private List<RetrievalHit> activeRetrievalHits = List.of();
    private Set<String> activePromptToolIds = Set.of();
    private String lastRagGoalText = "";
    private Optional<AliasLearnSuggestion> pendingLearnSuggestion = Optional.empty();
    private int deepCheckAttemptsThisTurn = 0;
    private boolean postDecisionRetryAttempted = false;
    private String lastRagPromptSource = "first_pass";
    /** Audit trigger for alias learning: heuristic_weak, model_requested, post_decision_out_of_top_k. */
    private String lastDeepCheckTriggerReason = "";

    /**
     * Command name (normalized, no {@code @}) for which a "finished running" InfoMessage is already
     * queued or awaiting an LLM response. Prevents the same completion from re-triggering API rounds.
     */
    private String commandAwaitingFinishAck = null;
    /** Invalidates queued/in-flight model turns when an authenticated owner stop bypasses the model. */
    private final ConversationTurnGate conversationTurnGate = new ConversationTurnGate();
    /** Matching billing-bucket request, retained so owner stop can retire only this bot's HTTP worker. */
    private LLMCompleter activeLlmCompleter;
    private LLMCompleter.Submission activeLlmSubmission;
    /** Stops unchanged command-error feedback from opening an unbounded chain of billed decision turns. */
    private final RepeatedCommandFailureGuard repeatedCommandFailureGuard = new RepeatedCommandFailureGuard();

    /**
     * Consecutive LLM JSON parse failures since the last successful parse. Bounds the
     * "resend valid JSON" retry loop so a model that keeps emitting unparseable output does not
     * spin forever (DESIGN.md §3 reporting must not turn into an infinite re-prompt). Reset to 0 on
     * any successful response in {@link #handleLlmResponse}.
     */
    private int consecutiveParseFailures = 0;

    /** Max consecutive parse-failure re-prompts before giving up this chain and notifying the player only. */
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

    /** Long-horizon work: plan memory for this companion (see PlanCoordinator). */
    private final PlanCoordinator planCoordinator = new PlanCoordinator(new PlanHost());
    /** Plan step dispatches wait for the next server tick, outside any task-chain callback. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingPlanDispatch =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean planLoaded;
    /** Said instead of calling the model when a turn cap is reached ({@link TurnCaps}). */
    static final String TIRED_LINE = "I'm worn out. Give me a little while before the next thing.";
    private final java.util.concurrent.atomic.AtomicLong modelRequests = new java.util.concurrent.atomic.AtomicLong();
    /** The authenticated player (UUID, never name) whose chat started the chain in progress, or null. */
    private volatile UUID chainInitiator;

    public AgentConversationData(PlayerEngineController mod) {
        this.mod = mod;
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

    // 0 => should not process,
    // otherwise gives a number that increases based on higher priority
    // (for now it is #ns from last processing time)
    public long getTtsCooldownUntilNanos() {
        return ttsCooldownUntilNanos;
    }

    // --- Peer-talk restraint: deterministic fallback gate (Workstream 3) — NOT WIRED (future work) ---
    // STUB ONLY. The primary peer-talk mechanism is model-driven (the transient consecutivePeerReplies
    // counter + count-aware reminder, above/below). This block documents the planned deterministic
    // safety floor that is deliberately NOT implemented this run — no field, no config, no behavior here.
    //
    // When a future session is explicitly asked to enable the fallback (see
    // masterplan/peer-talk-restraint-plan.md Workstream 3), implement Option A (softest, recommended):
    //   - Add a transient `private long peerReplyCooldownUntilNanos = 0L;` mirroring ttsCooldownUntilNanos.
    //   - When the gate is enabled AND consecutivePeerReplies >= threshold, set the cooldown and have
    //     getPriority() return 0 (defer, NEVER discard) until it lifts — the peer message stays queued so
    //     the model still sees it (no model-unseen drop; preserves DESIGN.md §3 truthfulness).
    //   - Escalation rungs B (drop-after-expiry) and C (queue-injection block in onAICharacterMessage)
    //     are documented in the plan and carry a stronger truthfulness obligation; do not wire by default.
    // Config keys (define in Player2ServerRuntimeConfig / Player2ServerConfigHolder, ALL default OFF;
    // do NOT add them this run):
    //   - peerTalkRestraintHardGateEnabled = false   (boolean; master switch, off = model-driven only)
    //   - peerTalkRestraintGateThreshold   = 4       (clamp 2..20; streak at which the gate engages)
    //   - peerTalkRestraintCooldownSeconds = 8.0     (clamp 1.0..60.0; Option-A defer window)
    // Until then getPriority() below is unchanged (no peer-talk behavior).
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
     * As {@link #resetForClear()}; with {@code dropPlan} false the plan stays in memory and in
     * plan.json, so a server stop keeps it for the next start (where it loads PAUSED).
     */
    public void resetForClear(boolean dropPlan) {
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
        if (dropPlan) {
            planCoordinator.cancel("reset");
        }
        pendingPlanDispatch.clear();
        clearTtsCooldown();
        cachedRetrievalHits = List.of();
        cachedPromptIdHash = 0;
        cachedValidCommandsBlock = null;
        resetB5TurnState();
        commandAwaitingFinishAck = null;
        repeatedCommandFailureGuard.reset();
        consecutiveParseFailures = 0;
        consecutivePeerReplies = 0;
        peerLinesAnsweredSinceHuman.set(0);
    }

    private void resetB5TurnState() {
        activeRetrievalHits = List.of();
        activePromptToolIds = Set.of();
        pendingValidCommandsBlock = null;
        lastRagGoalText = "";
        pendingLearnSuggestion = Optional.empty();
        deepCheckAttemptsThisTurn = 0;
        postDecisionRetryAttempted = false;
        lastRagPromptSource = "first_pass";
        lastDeepCheckTriggerReason = "";
        turnCurrentMood = Optional.empty();
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
        // the independently dispatchable event last so reminder, RAG, payer and provenance selection
        // continue to describe the event that actually caused this request.
        mergeDeferredInfoForProcessing(eventQueue, deferredInfoQueue);

        Consumer<String> onErrMsg = errMsg -> {
            synchronized (this) {
                if (!conversationTurnGate.accepts(turnTicket)) {
                    LOGGER.info("Discarding stale model error after authenticated owner stop for bot={}", getName());
                    releaseProcessing(turnTicket);
                    return;
                }
                try {
                    // DESIGN.md §3: a model JSON parse failure is reflected to the model as an
                    // InfoMessage so it retries truthfully next round. The player hears nothing: a
                    // system-voiced line from the companion breaks character, and the retry usually
                    // lands. Giving up leaves the current task running. The raw payload is already
                    // logged in Utils.parseCleanedJson; do not surface it here.
                    if (isModelParseFailure(errMsg)) {
                        consecutiveParseFailures++;
                        if (consecutiveParseFailures <= MAX_PARSE_RETRY) {
                            LOGGER.warn("[AICommandBridge]: LLM reply failed to parse as JSON for bot={} "
                                    + "(attempt {}/{}); asking model to resend valid JSON.",
                                    getName(), consecutiveParseFailures, MAX_PARSE_RETRY);
                            addEventToQueue(new InfoMessage(
                                    "Your previous reply could not be read because it was not valid JSON. "
                                    + "Resend ONLY a single valid JSON object with the \"message\" and \"command\" fields, "
                                    + "no extra text, no markdown code fences."));
                        } else {
                            LOGGER.error("[AICommandBridge]: LLM reply still unparseable after {} retries for bot={}; "
                                    + "giving up this chain.", MAX_PARSE_RETRY, getName());
                            consecutiveParseFailures = 0;
                            // Give-up aborts before a model response can acknowledge this finish round.
                            commandAwaitingFinishAck = null;
                        }
                        return;
                    }
                    extOnErrMsg.accept(errMsg);
                } finally {
                    releaseProcessing(turnTicket);
                }
            }
        };

        this.lastProcessTime = System.nanoTime();
        resetB5TurnState();

        String lastUserInBatch = null;
        UUID lastUserUuid = null;
        boolean ownerInBatch = false;
        planCoordinator.beginTurn();
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserInBatch = um.userName();
                lastUserUuid = um.authenticatedUserUuid();
                if (isAuthenticatedOwner(um)) {
                    ownerInBatch = true;
                    mod.markOwnerMessage(System.currentTimeMillis());
                }
                planCoordinator.onUserMessage(um.message(), lastUserUuid != null);
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
            onCharacterEvent.accept(new Event.CharacterMessage(TIRED_LINE, null, this, relayInitiator));
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

        // --- Phase B3: capture last user message text before the queue is drained ---
        Event.UserMessage lastUserMsgForRag = null;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserMsgForRag = um;
            }
        }

        // prepare conversation history for LLM call
        Event lastEvent = mod.getAIPersistantData().dumpEventQueueToConversationHistoryAndReturnLastEvent(eventQueue,
                mod.getPlayer2APIService());
        Optional<String> reminderString = getReminderStringFromLastEvent(lastEvent);

        // --- Phase B3: update system prompt with RAG-retrieved commands ---
        maybeUpdateRagSystemPrompt(lastUserMsgForRag);

        // remove all invalid npcs:
        String defaultReminderString = " | REMEMBER TO OUTPUT ONLY VALID JSON OUTPUT";
        reminderString = reminderString.map(a -> a + defaultReminderString);
        reminderString = Optional.of(reminderString.orElse(defaultReminderString));

        String agentStatus = AgentStatus.fromMod(this.mod).toString();
        String worldStatus = WorldStatus.fromMod(this.mod).toString();
        String altoClefDebugMsgs = this.playerEngineMsgBuffer.dumpAndGetString();
        // Capture the per-turn status locals so mid-turn follow-ups (deep-check / post-decision retry)
        // can rebuild the wrapped copy with the relocated command block WITHOUT re-draining the debug
        // buffer (dumpAndGetString is draining) and without re-deriving world/agent/reminder status.
        this.turnWorldStatus = worldStatus;
        this.turnAgentStatus = agentStatus;
        this.turnAltoClefDebugMsgs = altoClefDebugMsgs;
        this.turnReminderString = reminderString;
        // Phase D (W5): zero-LLM memory retrieval, hard-gated by the OWNER's patron status (fail-closed
        // → Optional.empty(), request byte-identical to today). Injected at the user-tail only.
        Optional<String> memoryBlock = resolveMemoryBlock(lastUserMsgForRag);
        // Mood (WS3): inject the persisted current mood into the per-turn TAIL only, gated on
        // enableCompanionMood. Flag-off → Optional.empty() → tail byte-identical to pre-mood-feature.
        // toPromptString() is already bounded (enum label + clamped intensity + capped cause); it flows
        // through LogEgressGuard.cappedMessage at the API call site (whole-message backstop, same as
        // every other message — there is no per-field capForModel call). Stored on turnCurrentMood so
        // mid-turn follow-up rebuilds reuse the same value.
        this.turnCurrentMood = resolveCurrentMoodBlock();
        ConversationHistory historyWithWrappedStatus = mod.getAIPersistantData()
                .getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                        mod.getPlayer2APIService(), reminderString, Optional.ofNullable(pendingValidCommandsBlock),
                        memoryBlock, this.turnCurrentMood);

        // A stop can arrive while status/RAG assembly is in progress. Avoid dispatching an already-stale
        // request; the callback ticket remains the final race backstop if invalidation happens after here.
        if (!conversationTurnGate.accepts(turnTicket)) {
            LOGGER.info("Skipping stale model request after authenticated owner stop for bot={}", getName());
            releaseProcessing(turnTicket);
            return;
        }

        LOGGER.info("[AICommandBridge/processChatWithAPI]: Calling LLM: history={}",
                new Object[] { historyWithWrappedStatus.toString() });

        final Event.UserMessage ragUserMsg = lastUserMsgForRag;
        Consumer<JsonObject> onLLMResponse = jsonResp -> handleLlmResponse(
                jsonResp, lastEvent, relayInitiator, onCharacterEvent, onErrMsg, completer,
                historyWithWrappedStatus, ragUserMsg, false, false, turnTicket);
        submitDecisionIfCurrent(turnTicket, completer, historyWithWrappedStatus,
                onLLMResponse, onErrMsg, "initial", false);
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
        planCoordinator.cancel("owner stop");
        pendingPlanDispatch.clear();
        boolean requestStillDraining = cancellation == LLMCompleter.CancellationOutcome.RETIREMENT_LIMIT_REACHED;
        commandAwaitingFinishAck = null;
        repeatedCommandFailureGuard.reset();
        clearTtsCooldown();
        pendingSegmentActions = List.of();
        pendingChunks = List.of();
        pendingInvalidMarkers = List.of();
        String modelNote = requestStillDraining
                ? "The owner issued an immediate stop. Automation and queued actions were cancelled, but a stuck "
                        + "prior model request is still draining because the bounded worker safety limit was reached. "
                        + "New replies may be delayed; do not resume the prior action."
                : "The owner issued an immediate stop. Automation and any queued or in-flight action were cancelled. "
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
            String phase,
            boolean handoffFromActiveRequest) {
        if (activeTurnTicket != turnTicket || !conversationTurnGate.accepts(turnTicket)) {
            LOGGER.info("Skipping stale {} model request after authenticated owner stop for bot={}",
                    phase, getName());
            releaseProcessing(turnTicket);
            return false;
        }
        LLMCompleter.Submission submission = handoffFromActiveRequest
                ? completer.processToJsonAfter(activeLlmSubmission,
                        mod.getPlayer2APIService(), history, onResponse, onError, true, AiTaskClass.DECISION)
                : completer.processToJson(
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

    // --- Phase B3: per-turn RAG helpers ---

    /**
     * Updates the NPC system prompt with a RAG-retrieved command set for the given turn.
     *
     * <p>Skips retrieval on greeting turns and when the goal text is too short.
     * Reuses cached hits when the batch contains only InfoMessage events.
     * Falls back to the full command list when the RAG index is not initialised,
     * controlled by {@code ragFallbackToFullList}.
     */
    private void maybeUpdateRagSystemPrompt(Event.UserMessage lastUserMsgForRag) {
        Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();
        if (!config.isRagLiveEnabled()) {
            // A live disable must not retain the previous turn's reduced RAG block. Restore the
            // full/base prompt on this turn and discard session-scoped retrieval material so a later
            // re-enable starts from a clean retrieval state. This emits no log or feedback text.
            cachedRetrievalHits = List.of();
            cachedPromptIdHash = 0;
            cachedValidCommandsBlock = null;
            activeRetrievalHits = List.of();
            activePromptToolIds = Set.of();
            pendingValidCommandsBlock = null;
            lastRagGoalText = "";
            ragFallbackWarnedOnce = false;
            if (liveRagManagedSystemPrompt) {
                mod.getAIPersistantData().updateSystemPrompt();
                liveRagManagedSystemPrompt = false;
            }
            return;
        }
        liveRagManagedSystemPrompt = true;

        // Greeting turn: inject the always-include set only (no content-based retrieval yet).
        if (isGreetingResponse) {
            updateSystemPromptAlwaysIncludeOnly();
            LOGGER.debug("[RAG] skip retrieval: greeting turn for bot={}", getName());
            return;
        }

        // InfoMessage-only batch or autonomous step: reuse cached hits from the previous user turn.
        if (lastUserMsgForRag == null) {
            applyRagPromptFromCache(config);
            return;
        }

        // Short goal text: not enough signal for BM25 — reuse cached hits.
        String goalText = lastUserMsgForRag.message();
        if (!RagPromptBuilder.hasSubstantiveGoalText(goalText, config.getRagMinGoalCharsClamped())) {
            LOGGER.debug("[RAG] skip retrieval: short goal for bot={}", getName());
            applyRagPromptFromCache(config);
            return;
        }

        // Resolve owner-scoped retriever.
        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        ToolRetriever retriever = RagIndex.getForOwner(server, ownerUuid);

        if (retriever == null) {
            if (!ragFallbackWarnedOnce) {
                LOGGER.warn("[RAG] retriever null for bot={} owner={}; set ragLiveEnabled=false to silence. "
                        + "Falling back to full command list.", getName(), ownerUuid);
                ragFallbackWarnedOnce = true;
            }
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }

        lastRagGoalText = goalText;
        int topK = config.getRagTopKClamped();
        RetrievalConfidenceThresholds thresholds = RetrievalConfidenceThresholds.fromConfig(config);
        RetrievalResult firstPass = retriever.retrieveWithConfidence(goalText, topK, null, thresholds);

        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(
                mod, chainInitiatorUsername, mod.getPlayer2APIService().getClientId());
        RagDeepCheckCoordinator.RetryMergeResult merged = RagDeepCheckCoordinator.maybeImproveRetrieval(
                retriever,
                goalText,
                topK,
                null,
                thresholds,
                firstPass,
                mod.getPlayer2APIService(),
                mod.getCommandExecutor(),
                billing,
                config.isEnableDeepCheckRephrase());
        if (merged.deepCheckAttempted()) {
            deepCheckAttemptsThisTurn++;
            lastDeepCheckTriggerReason = "heuristic_weak";
        }
        lastRagPromptSource = merged.promptSource();
        pendingLearnSuggestion = merged.learnSuggestion();
        List<RetrievalHit> hits = merged.activeResult().hits();

        if (hits.isEmpty()) {
            LOGGER.debug("[RAG] empty retrieval for goal=\"{}\" bot={} source={}", goalText, getName(), lastRagPromptSource);
            activeRetrievalHits = hits;
            activePromptToolIds = Set.of();
            cachedRetrievalHits = hits;
            cachedPromptIdHash = 0;
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }

        activeRetrievalHits = hits;
        activePromptToolIds = toolIdsFromHits(hits);

        int newHash = RagPromptBuilder.toolIdSetHash(RagPromptBuilder.ALWAYS_INCLUDE_IDS, hits);
        if (newHash == cachedPromptIdHash && cachedValidCommandsBlock != null) {
            // Churn-gate hit: same tool set as last build AND we hold the cached block string. The block is
            // NOT rebuilt here, so reuse the cached block and route it to the user tail (the system message
            // stays the byte-stable base). Without this the model would get an empty command set on a
            // same-set turn.
            // Guard: cachedPromptIdHash starts at 0 and toolIdSetHash returns Set.hashCode(), which is 0 for
            // an empty set (and in principle could be 0 for a non-empty set). If that collided on the FIRST
            // build of a session, cachedValidCommandsBlock would still be null here and reusing it would
            // strand the model with an empty validCommands tail even though activePromptToolIds (set above)
            // holds real ids. Requiring a non-null cached block forces fall-through to the build path in
            // that window, so the block is actually built before being cached/routed.
            LOGGER.debug("[RAG] same tool set (hash={}), reusing cached block for bot={}", newHash, getName());
            pendingValidCommandsBlock = cachedValidCommandsBlock;
            mod.getAIPersistantData().updateSystemPromptStatic();
            return;
        }

        cachedRetrievalHits = hits;
        cachedPromptIdHash = newHash;

        String block = RagPromptBuilder.buildValidCommandsBlock(
                retriever.getRegistry(), hits, mod.getCommandExecutor(), RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        LOGGER.debug("[RAG] injecting {} hits into user-tail validCommands for goal=\"{}\" bot={} source={}",
                hits.size(), goalText, getName(), lastRagPromptSource);
        // Relocate the per-turn block to the user tail; keep message 0 byte-stable.
        cachedValidCommandsBlock = block;
        pendingValidCommandsBlock = block;
        mod.getAIPersistantData().updateSystemPromptStatic();
    }

    private static Set<String> toolIdsFromHits(List<RetrievalHit> hits) {
        Set<String> ids = new HashSet<>();
        for (RetrievalHit h : hits) {
            ids.add(h.toolId());
        }
        ids.addAll(RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        return ids;
    }

    private void handleLlmResponse(
            JsonObject jsonResp,
            Event lastEvent,
            String relayInitiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrMsg,
            LLMCompleter completer,
            ConversationHistory historyWithWrappedStatus,
            Event.UserMessage lastUserMsgForRag,
            boolean isFollowUpDecision,
            boolean isModelDeepSearchFollowUp,
            long turnTicket) {
        final boolean greetingResponse;
        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                LOGGER.info("Discarding stale model response after authenticated owner stop for bot={}", getName());
                releaseProcessing(turnTicket);
                return;
            }
            // A response reached us = the LLM reply parsed successfully; clear the parse-retry guard.
            this.consecutiveParseFailures = 0;
            greetingResponse = this.isGreetingResponse;
            this.isGreetingResponse = false;
            if (!isFollowUpDecision && !isModelDeepSearchFollowUp) {
                handleMoodDeclaration(jsonResp);
            }
        }
        String llmMessage = Utils.getStringJsonSafely(jsonResp, "message");
        String command = greetingResponse ? "bodylang greeting"
                : Utils.getStringJsonSafely(jsonResp, "command");
        com.google.gson.JsonElement liftedPlan = greetingResponse ? null
                : com.player2.playerengine.seam.CommandLines.liftPlan(command, mod.getCommandExecutor().getCommandPrefix());
        if (liftedPlan != null) {
            // E5: a plan sent as a command is read as the plan field; it is no command to run.
            LOGGER.info("[Plan] bot={} sent a plan as its command; read as the plan field", getName());
            command = null;
        }

        // --- Companion mood: declare-parse + deterministic update (WS2) + mood→memory trigger (WS4) ---
        // Gated on enableCompanionMood (WS5): flag-off → no parse, no mood write, no extra request bytes.
        // The "mood" object is read directly from jsonResp (no marker fragility); MoodUpdate.apply
        // validates the label against the fixed vocabulary code-side and caps the cause (data-egress).
        // First-pass ONLY: mood is declared at most once per logical turn. Deep-check and post-decision
        // retry responses (isModelDeepSearchFollowUp / isFollowUpDecision) carry the pre-first-pass
        // turnCurrentMood in their tail, so a mood field on a follow-up would be reacting to stale
        // context and could double-mint; ignore mood on those passes.
        String cmdId = resolveCommandId(command);
        if (isModelDeepSearchFollowUp && RagDeepSearchCommands.isMetaCommandId(cmdId)) {
            LOGGER.warn("[B5] model_deepsearch_loop_blocked bot={}", getName());
            command = "idle";
            cmdId = "idle";
        }

        if (!isFollowUpDecision && maybeModelRequestedDeepSearch(
                command, cmdId, lastUserMsgForRag, relayInitiator, onCharacterEvent, onErrMsg, completer,
                historyWithWrappedStatus, lastEvent, turnTicket)) {
            return;
        }

        if (!isFollowUpDecision && maybePostDecisionRetry(
                command, lastUserMsgForRag, relayInitiator, onCharacterEvent, onErrMsg, completer,
                historyWithWrappedStatus, lastEvent, turnTicket)) {
            return;
        }

        if (RagDeepSearchCommands.isMetaCommandId(cmdId)) {
            LOGGER.debug("[B5] model_deepsearch_skipped bot={} (not handled)", getName());
            command = "idle";
            // Keep cmdId in sync with the rewritten command (mirrors the isModelDeepSearchFollowUp
            // guard above). Otherwise the stale meta-command cmdId leaks into the peer-talk
            // substantiveReply predicate below and would inflate the streak on an effectively-idle turn.
            cmdId = "idle";
        }

        String previousAssistant = mod.getAIPersistantData().getLastAssistantContent().orElse("");
        boolean redundantAfterInfo = lastEvent instanceof Event.InfoMessage
                && ConversationHistory.isRedundantAssistantAfterInfo(previousAssistant, llmMessage);
        if (redundantAfterInfo) {
            LOGGER.info(
                    "[AICommandBridge/processCharWithAPI]: Suppressing duplicate assistant chat after Info (command feedback) round");
            llmMessage = "";
        }
        if (llmMessage == null) {
            llmMessage = "";
        }
        // --- Bodylang TTS-timed gestures: deterministic marker parse (Workstream 1) ---
        // Strip inline [bl:<action>] markers from the message ONCE here (decision 2 — the single
        // choke-point) and build the ordered chunk + boundary lists. The CharacterMessage is then
        // constructed with the STRIPPED text in its message field, so chat (AgentSideEffects:55), TTS
        // (:58), and markSpeakingFor (:63) all operate on stripped text with no second strip.
        MarkerParser.ParsedMessage parsed = MarkerParser.parse(llmMessage);
        String strippedMessage = parsed.strippedText();
        // Store the valid-only boundary list (segment_done / fallback timer index into this) and the
        // wire chunk list (read by onEntityMessage). Invalid markers are reported below, not stored.
        List<MarkerParser.SegmentBoundary> validBoundaries = new java.util.ArrayList<>();
        List<String> invalidTokens = new java.util.ArrayList<>();
        for (MarkerParser.SegmentBoundary b : parsed.boundaries()) {
            if (b.valid()) {
                validBoundaries.add(b);
            } else {
                invalidTokens.add(b.rawToken());
            }
        }
        LOGGER.info("[AICommandBridge/processCharWithAPI]: Processed LLM response: message={} command={}",
                strippedMessage, command);
        // --- Peer-talk restraint (masterplan/peer-talk-restraint-plan.md), Workstreams 1 & 4 ---
        // Compute the "substantive peer reply" predicate ONCE here, at the dispatch site. Both the
        // increment and the silence-credit derive from this single predicate (the same notion of
        // substance the relay/TTS guard at AgentSideEffects:69 and the command guard at :102 use). The
        // discriminators (strippedMessage / cmdId / validBoundaries) are already in scope. NOTE: the
        // start-of-turn streak the model saw was already baked into the reminder back in process()
        // (getReminderStringFromLastEvent) BEFORE this point, so mutating the counter now is correct.
        boolean isPeerTurn = lastEvent instanceof Event.CharacterMessage;
        if (!greetingResponse && !isPeerTurn && conversationTurnGate.accepts(turnTicket)) {
            command = applyPlanAndOwnership(jsonResp, lastEvent, command, cmdId, liftedPlan);
            cmdId = resolveCommandId(command);
        } else if (isPeerTurn && peerRefusedCommandIn(command) != null) {
            command = null;
            cmdId = null;
        }
        DecisionCapture.record(this, lastEvent, historyWithWrappedStatus, jsonResp, command);
        boolean substantiveReply = !strippedMessage.isEmpty()
                || (cmdId != null && !"idle".equals(cmdId))   // a real, non-idle command counts
                || !validBoundaries.isEmpty();                 // a valid gesture counts
        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                LOGGER.info("Discarding stale model side effects after authenticated owner stop for bot={}", getName());
                releaseProcessing(turnTicket);
                return;
            }
            this.pendingSegmentActions = List.copyOf(validBoundaries);
            this.pendingChunks = List.copyOf(parsed.chunks());
            this.pendingInvalidMarkers = List.copyOf(invalidTokens);
            // DESIGN.md §3 (truthfulness): an unknown marker is NOT silently dropped. Report it to the
            // MODEL here via an InfoMessage so the AI knows that gesture did not fire and cannot claim it
            // did. (The player-facing chat line is emitted in AgentSideEffects — Workstream 6.) Do NOT
            // call markSpeakingFor here (decision 4 — it stays at AgentSideEffects:63).
            if (!invalidTokens.isEmpty()) {
                LOGGER.warn("[Bodylang] bot={} emitted unknown gesture marker(s): {}", getName(), invalidTokens);
                addEventToQueue(new InfoMessage(String.format(
                        "Note: the gesture marker(s) %s are not valid and were NOT performed. "
                        + "Valid gestures are: greeting, nod_head, shake_head, victory. "
                        + "Do not claim you performed an invalid gesture.",
                        String.join(", ", invalidTokens))));
            }
            try {
                // Fire the CharacterMessage when there is stripped text, a command, OR at least one valid
                // gesture boundary — a marker-only message ("[bl:greeting]") strips to empty text but must
                // still dispatch so its gesture fires via the TTS/segment path.
                if (!strippedMessage.isEmpty() || command != null || !validBoundaries.isEmpty()) {
                    registerPendingLearnCandidate();
                    mod.getAIPersistantData().addAssistantMessage(strippedMessage, mod.getPlayer2APIService());
                    onCharacterEvent.accept(new Event.CharacterMessage(strippedMessage, command, this, relayInitiator));
                    // Substantive reply to a peer turn: grow the peer-reply streak. (The dispatch condition
                    // above is intentionally BROADER than substantiveReply — it lets marker-only/blank-command
                    // turns through — so the counter is gated on substantiveReply, not on dispatch.)
                    if (isPeerTurn && substantiveReply) {
                        this.consecutivePeerReplies++;
                    }
                } else {
                    LOGGER.warn(
                            "[AICommandBridge/processChatWithAPI/onLLMResponse]: Generated null llm message and command");
                }
            } catch (Exception e) {
                LOGGER.error("[AICommandBridge/processChatWithAPI/onLLMResponse]: ERROR RUNNING SIDE EFFECTS, errMsg={}",
                        e.getMessage());
            } finally {
                // Peer-talk counter reset/credit — evaluated after both dispatch branches.
                if (!isPeerTurn) {
                    this.consecutivePeerReplies = 0;
                } else if (!substantiveReply) {
                    LOGGER.debug("peer_talk_silence bot={} streak_before={}", getName(), this.consecutivePeerReplies);
                    this.consecutivePeerReplies = 0;
                }
                acknowledgeCommandFinishRoundIfComplete(lastEvent, command);
                releaseProcessing(turnTicket);
            }
        }
    }

    /**
     * Applies the reply's {@code plan} field and returns the command the reply may still dispatch
     * (null when the plan took over). Any player may command any companion (R1).
     *
     * @param liftedPlan a plan the reply sent as its command (E5), used when the plan field is empty
     */
    private String applyPlanAndOwnership(JsonObject jsonResp, Event lastEvent, String command, String cmdId,
            com.google.gson.JsonElement liftedPlan) {
        PlanCoordinator.Turn turn = OwnerGate.turn(lastEvent, chainInitiator);
        if ("stop".equals(cmdId)) {
            planCoordinator.cancel("stop command");
        }
        com.google.gson.JsonElement planField = jsonResp.get("plan");
        if (liftedPlan != null && (planField == null || planField.isJsonNull())) {
            planField = liftedPlan;
        }
        PlanParser.Result plan = PlanParser.parse(planField, this::registeredCommandId);
        boolean planTook = planCoordinator.onModelDecision(plan, command, turn);
        return planTook ? null : command;
    }

    private String peerRefusedCommandIn(String command) {
        return OwnerGate.peerRefusedCommandIn(command, mod.getCommandExecutor().getCommandPrefix(),
                this::registeredCommandId);
    }

    private UUID ownerUuid() {
        return mod.getOwner() == null ? null : mod.getOwner().getUUID();
    }

    /** The registered command id a step's first word names (aliases resolved), or null. */
    private String registeredCommandId(String name) {
        com.player2.playerengine.commands.base.Command c = mod.getCommandExecutor().getRegisteredCommand(name);
        return c == null ? null : c.getName();
    }

    private boolean isAuthenticatedOwner(Event.UserMessage um) {
        return OwnerGate.isOwner(um, ownerUuid());
    }

    /**
     * Who this companion's next command line runs for: the chain's authenticated initiator, an
     * operator when online with permission level 2 (the level the mod's own server commands need).
     * No initiator, or one offline, is {@link CommandCaller#UNPRIVILEGED}.
     */
    public CommandCaller commandCaller() {
        UUID who = chainInitiator;
        MinecraftServer server = mod.getPlayer() == null ? null : mod.getPlayer().getServer();
        if (who == null || server == null) {
            return CommandCaller.UNPRIVILEGED;
        }
        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(who);
        return new CommandCaller(who, player != null && player.hasPermissions(2));
    }

    /** Server tick: loads a saved plan once, runs queued step dispatches, then the plan clocks. */
    public void tickPlan() {
        if (!planLoaded && mod.getOwner() != null) {
            planLoaded = true;
            java.nio.file.Path file = mod.getAIPersistantData().getPlanFileOrNull();
            planCoordinator.restore(PlanStore.load(file));
        }
        Runnable r;
        while ((r = pendingPlanDispatch.poll()) != null) {
            r.run();
        }
        planCoordinator.tick();
    }

    private final class PlanHost implements PlanCoordinator.Host {
        @Override
        public void dispatch(String line, PlanCoordinator.StepListener listener) {
            long seqAtSchedule = mod.getCommandDispatchSeq();
            pendingPlanDispatch.add(() -> {
                if (mod.getCommandDispatchSeq() != seqAtSchedule) {
                    // Another command started between scheduling and this tick: it wins.
                    listener.stopped(PlanCoordinator.StopKind.CANCELLED, "another command started first");
                    return;
                }
                AgentSideEffects.onCommandListGenerated(mod, line, reason -> {
                    if (reason instanceof CommandExecutionStopReason.Finished f) {
                        String note = f.note();
                        if (note != null && note.startsWith(INFO_RESULT_NOTE_PREFIX)) {
                            note = note.substring(INFO_RESULT_NOTE_PREFIX.length());
                        }
                        listener.stopped(PlanCoordinator.StopKind.FINISHED, note);
                    } else if (reason instanceof CommandExecutionStopReason.Error e) {
                        listener.stopped(PlanCoordinator.StopKind.ERROR,
                                RepeatedCommandFailureGuard.boundedFailureReason(e.errMsg()));
                    } else {
                        listener.stopped(PlanCoordinator.StopKind.CANCELLED, null);
                    }
                }, listener::accepted, commandCaller());
            });
        }

        @Override
        public long currentSeq() {
            return mod.getCommandDispatchSeq();
        }

        @Override
        public void enqueueModelTurn(String info) {
            addEventToQueue(new InfoMessage(info));
        }

        @Override
        public void cancelRunningTask() {
            mod.cancelUserTask();
        }

        @Override
        public void persist(JsonObject planOrNull) {
            PlanStore.save(mod.getAIPersistantData().getPlanFileOrNull(), planOrNull);
        }

        @Override
        public boolean isIdempotent(String line) {
            String first = line.trim().split("\\s+")[0];
            com.player2.playerengine.commands.base.Command c = mod.getCommandExecutor().getRegisteredCommand(first);
            return c != null && c.isIdempotent();
        }

        @Override
        public void publishStatus(String line) {
            mod.setPlanStatusLine(line);
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    }

    private String resolveCommandId(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String withPrefix = mod.getCommandExecutor().isClientCommand(command)
                ? command
                : mod.getCommandExecutor().getCommandPrefix() + command;
        return AgentSideEffects.firstCommandId(withPrefix, mod.getCommandExecutor());
    }

    private boolean maybeModelRequestedDeepSearch(
            String command,
            String cmdId,
            Event.UserMessage lastUserMsgForRag,
            String relayInitiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrMsg,
            LLMCompleter completer,
            ConversationHistory historyWithWrappedStatus,
            Event lastEvent,
            long turnTicket) {
        Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();
        if (!config.isEnableDeepCheckRephrase() || !RagDeepSearchCommands.isMetaCommandId(cmdId)) {
            return false;
        }
        if (deepCheckAttemptsThisTurn >= config.getDeepCheckMaxAttemptsPerTurnClamped()) {
            LOGGER.debug("[B5] model_deepsearch_skipped reason=attempt_cap bot={}", getName());
            return false;
        }
        if (lastUserMsgForRag == null || lastRagGoalText.isBlank()) {
            LOGGER.debug("[B5] model_deepsearch_skipped reason=no_goal bot={}", getName());
            return false;
        }

        LOGGER.info("[B5] model_deepsearch requested bot={} goal=\"{}\"", getName(), lastRagGoalText);

        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(
                mod, chainInitiatorUsername, mod.getPlayer2APIService().getClientId());
        DeepCheckBudgetGate.SkipReason skip = DeepCheckBudgetGate.preflight(billing);
        if (skip != DeepCheckBudgetGate.SkipReason.NONE) {
            LOGGER.debug("[B5] model_deepsearch_skipped reason={} bot={}", skip.name().toLowerCase(), getName());
            return false;
        }

        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        ToolRetriever retriever = RagIndex.getForOwner(server, ownerUuid);
        if (retriever == null) {
            return false;
        }

        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                releaseProcessing(turnTicket);
                return true;
            }
            deepCheckAttemptsThisTurn++;
            if (config.isEnableDeepCheckMessage()) {
                addEventToQueue(new InfoMessage("Let me check the command list for a better match."));
            }
        }

        RetrievalConfidenceThresholds thresholds = RetrievalConfidenceThresholds.fromConfig(config);
        RetrievalResult firstPass = retriever.retrieveWithConfidence(
                lastRagGoalText, config.getRagTopKClamped(), null, thresholds);

        RagDeepCheckPipeline.ApplyResult applied = RagDeepCheckPipeline.apply(
                retriever,
                lastRagGoalText,
                config.getRagTopKClamped(),
                null,
                thresholds,
                firstPass,
                mod.getPlayer2APIService(),
                mod.getCommandExecutor(),
                billing,
                true,
                "model_requested");
        if (!applied.success()) {
            return false;
        }

        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                releaseProcessing(turnTicket);
                return true;
            }
            applyRetrievalToPrompt(retriever, applied);
            lastRagPromptSource = "model_deepsearch";
            lastDeepCheckTriggerReason = "model_requested";
            LOGGER.info("[B5] model_deepsearch_ok source={} bot={}", applied.promptSource(), getName());

            // Rebuild the wrapped copy so the newly-retrieved command set reaches the user tail (the prior
            // copy still carries the first-pass block). System message is not mutated mid-turn.
            ConversationHistory followUpHistory = rebuildWrappedStatusForFollowUp();
            Consumer<JsonObject> followUp = jsonResp -> handleLlmResponse(
                    jsonResp, lastEvent, relayInitiator, onCharacterEvent, onErrMsg, completer,
                    followUpHistory, lastUserMsgForRag, true, true, turnTicket);
            submitDecisionIfCurrent(turnTicket, completer, followUpHistory,
                    followUp, onErrMsg, "deep-search-follow-up", true);
            return true;
        }
    }

    private void applyRetrievalToPrompt(ToolRetriever retriever, RagDeepCheckPipeline.ApplyResult applied) {
        activeRetrievalHits = applied.activeResult().hits();
        activePromptToolIds = toolIdsFromHits(applied.activeResult().hits());
        pendingLearnSuggestion = applied.learnSuggestion();
        String block = RagPromptBuilder.buildValidCommandsBlock(
                retriever.getRegistry(),
                applied.activeResult().hits(),
                mod.getCommandExecutor(),
                RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        // Prefix-cache restructure: do NOT mutate message 0 mid-turn (it would re-bust the cache and
        // would not reach the already-built wrapped copy anyway). Stage the block; the follow-up call
        // sites rebuild the wrapped copy carrying this block in the user tail.
        cachedValidCommandsBlock = block;
        pendingValidCommandsBlock = block;
        cachedRetrievalHits = applied.activeResult().hits();
        cachedPromptIdHash = RagPromptBuilder.toolIdSetHash(
                RagPromptBuilder.ALWAYS_INCLUDE_IDS, applied.activeResult().hits());
    }

    /**
     * Rebuilds the wrapped user-tail copy for a mid-turn follow-up LLM call (deep-check / post-decision
     * retry) using the status locals captured at first-pass assembly plus the freshly-staged
     * {@link #pendingValidCommandsBlock}. Re-supplies world/agent status, reminders, and the (already
     * drained) debug messages so the follow-up turn keeps full status — the system message is left
     * untouched (byte-stable for the conversation).
     */
    private ConversationHistory rebuildWrappedStatusForFollowUp() {
        // Thread the per-turn currentMood (resolved once in process()) through the follow-up tail rebuild
        // so deep-check / post-decision retry turns carry the same mood block. Empty memory block here
        // matches the pre-mood follow-up behavior (memory is first-pass only); mood is tail-only.
        return mod.getAIPersistantData().getConversationHistoryWrappedWithStatus(
                turnWorldStatus, turnAgentStatus, turnAltoClefDebugMsgs,
                mod.getPlayer2APIService(), turnReminderString,
                Optional.ofNullable(pendingValidCommandsBlock),
                Optional.empty(), turnCurrentMood);
    }

    private void registerPendingLearnCandidate() {
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        if (ownerUuid == null || pendingLearnSuggestion.isEmpty()) {
            return;
        }
        String triggerReason = lastDeepCheckTriggerReason.isBlank()
                ? lastRagPromptSource
                : lastDeepCheckTriggerReason;
        pendingLearnSuggestion.ifPresent(s -> AliasLearningService.registerPendingFromSuggestion(
                ownerUuid,
                getUUID(),
                lastRagGoalText,
                s,
                triggerReason));
        pendingLearnSuggestion = Optional.empty();
    }

    private boolean maybePostDecisionRetry(
            String command,
            Event.UserMessage lastUserMsgForRag,
            String relayInitiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrMsg,
            LLMCompleter completer,
            ConversationHistory historyWithWrappedStatus,
            Event lastEvent,
            long turnTicket) {
        Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();
        if (!config.isEnableDeepCheckRephrase() || postDecisionRetryAttempted) {
            return false;
        }
        String cmdId = resolveCommandId(command);
        if (cmdId == null || cmdId.isBlank() || activePromptToolIds.contains(cmdId)) {
            return false;
        }
        if (deepCheckAttemptsThisTurn >= config.getDeepCheckMaxAttemptsPerTurnClamped()) {
            return false;
        }
        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(
                mod, chainInitiatorUsername, mod.getPlayer2APIService().getClientId());
        if (DeepCheckBudgetGate.preflight(billing) != DeepCheckBudgetGate.SkipReason.NONE) {
            LOGGER.debug("[B5] post_decision_retry_skipped cmd={}", cmdId);
            return false;
        }
        if (lastUserMsgForRag == null || lastRagGoalText.isBlank()) {
            return false;
        }

        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                releaseProcessing(turnTicket);
                return true;
            }
            postDecisionRetryAttempted = true;
        }
        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        ToolRetriever retriever = RagIndex.getForOwner(server, ownerUuid);
        if (retriever == null) {
            return false;
        }

        RetrievalConfidenceThresholds thresholds = RetrievalConfidenceThresholds.fromConfig(config);
        RetrievalResult firstPass = retriever.retrieveWithConfidence(
                lastRagGoalText, config.getRagTopKClamped(), null, thresholds);
        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                releaseProcessing(turnTicket);
                return true;
            }
            deepCheckAttemptsThisTurn++;
        }

        String enrichedGoal = lastRagGoalText + " (model chose command: " + cmdId + ")";
        RagDeepCheckPipeline.ApplyResult applied = RagDeepCheckPipeline.apply(
                retriever,
                enrichedGoal,
                config.getRagTopKClamped(),
                null,
                thresholds,
                firstPass,
                mod.getPlayer2APIService(),
                mod.getCommandExecutor(),
                billing,
                true,
                "post_decision_out_of_top_k");
        if (!applied.success()) {
            return false;
        }

        synchronized (this) {
            if (!conversationTurnGate.accepts(turnTicket)) {
                releaseProcessing(turnTicket);
                return true;
            }
            applyRetrievalToPrompt(retriever, applied);
            lastRagPromptSource = applied.promptSource();
            lastDeepCheckTriggerReason = "post_decision_out_of_top_k";

            // Rebuild the wrapped copy so the re-retrieved command set reaches the user tail. System message
            // is not mutated mid-turn.
            ConversationHistory retryHistory = rebuildWrappedStatusForFollowUp();
            Consumer<JsonObject> retryHandler = jsonResp -> handleLlmResponse(
                    jsonResp, lastEvent, relayInitiator, onCharacterEvent, onErrMsg, completer,
                    retryHistory, lastUserMsgForRag, true, false, turnTicket);
            submitDecisionIfCurrent(turnTicket, completer, retryHistory,
                    retryHandler, onErrMsg, "post-decision-retry", true);
            return true;
        }
    }

    private void applyRagPromptFromCache(Player2ServerRuntimeConfig config) {
        if (cachedRetrievalHits.isEmpty()) {
            // No prior retrieval this session; use full list or always-include only (session-stable,
            // stays in the system message; no user-tail block).
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }
        // Prior retrieval exists this session. Under the prefix-cache restructure the cached set is NO
        // LONGER in the system message, so re-supply it to the user tail and keep message 0 byte-stable.
        // Without this, InfoMessage-only / short-goal / autonomous-step turns would send the model an
        // empty command set (no system list AND no tail block).
        //
        // Defense-in-depth (mirrors the churn-gate guard at L474): cachedRetrievalHits being non-empty is
        // today paired with a cachedValidCommandsBlock write (build path L491/L499, applyRetrievalToPrompt),
        // but that pairing is an invariant no assert enforces. If a future edit populates cachedRetrievalHits
        // without also setting cachedValidCommandsBlock, routing a null block here would yield
        // Optional.ofNullable(null) -> an absent validCommands key and an empty command set this turn -- the
        // exact failure WS3 set out to prevent. Make the violation loud and degrade to the session-stable
        // path (list rendered in message 0) instead of silently sending zero commands.
        if (cachedValidCommandsBlock == null) {
            LOGGER.warn("[RAG] applyRagPromptFromCache: non-empty cachedRetrievalHits but cachedValidCommandsBlock "
                    + "is null for bot={} — falling back to system-message list", getName());
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }
        pendingValidCommandsBlock = cachedValidCommandsBlock;
        mod.getAIPersistantData().updateSystemPromptStatic();
    }

    private void updateSystemPromptAlwaysIncludeOnly() {
        ToolRetriever global = RagIndex.getGlobal();
        if (global == null) {
            mod.getAIPersistantData().updateSystemPrompt();
            return;
        }
        String block = RagPromptBuilder.buildValidCommandsBlock(
                global.getRegistry(), List.of(), mod.getCommandExecutor(), RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        if (block.isBlank()) {
            mod.getAIPersistantData().updateSystemPrompt();
            return;
        }
        mod.getAIPersistantData().updateSystemPromptWithBlock(block);
    }

    // --- end B3 ---

    public void onEvent(Event event) {
        if (event instanceof Event.UserMessage) {
            commandAwaitingFinishAck = null;
            repeatedCommandFailureGuard.reset();
            peerLinesAnsweredSinceHuman.set(0);
        }
        addEventToQueue(event);
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

    private static boolean isCommandFinishPromptMessage(String message) {
        return message != null
                && message.startsWith("Command feedback:")
                && message.contains("finished running");
    }

    private static String normalizeCommandNameForFinishAck(String commandName) {
        if (commandName == null) {
            return "";
        }
        String s = commandName.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        int space = s.indexOf(' ');
        return space > 0 ? s.substring(0, space) : s;
    }

    private static boolean isNonTaskCommandForFinishPrompt(String commandName) {
        String base = normalizeCommandNameForFinishAck(commandName);
        return base.contains("bodylang")
                || "idle".equals(base)
                || "place_sign".equals(base)
                || "read_signs".equals(base);
    }

    private boolean shouldEnqueueCommandFinishPrompt(String commandName) {
        if (isNonTaskCommandForFinishPrompt(commandName)) {
            LOGGER.info("Skipping command finish prompt for non-task cmd={}", commandName);
            return false;
        }
        String normalized = normalizeCommandNameForFinishAck(commandName);
        if (commandAwaitingFinishAck != null && commandAwaitingFinishAck.equals(normalized)) {
            // [DEBUG-INSTR:dead-callback-idle] log the held ack vs incoming key to confirm the Gap-2
            // dedup path (cobblestone vs stone_pickaxe) post-fix. Remove with the ledger once confirmed.
            LOGGER.info(
                    "Skipping duplicate command finish prompt for cmd={} (held ack={} normalized={})",
                    commandName, commandAwaitingFinishAck, normalized);
            return false;
        }
        return true;
    }

    private void enqueueCommandFinishPrompt(String commandName) {
        enqueueCommandFinishPrompt(commandName, null);
    }

    private void enqueueCommandFinishPrompt(String commandName, String note) {
        if (note == null || note.isBlank()) {
            // Clean success: byte-identical to the original single-InfoMessage prompt.
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished running. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                    commandName)));
        } else if (note.startsWith(INFO_RESULT_NOTE_PREFIX)) {
            // Succeeded with an informational RESULT payload (e.g. locate_storage coordinates) — NOT a
            // degradation. Frame it as a result, not a "but:", so the model neither treats the payload
            // as a problem nor loses the standard "what next?" cue (which the old enqueueInfo+finish()
            // path suppressed by leaving a non-empty event queue at finish time).
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished running. Result: %s. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                    commandName,
                    note.substring(INFO_RESULT_NOTE_PREFIX.length()))));
        } else {
            // Succeeded-but-degraded: same single InfoMessage, with a factual clause so the model can
            // truthfully report the degradation instead of claiming a clean success.
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished running, but: %s. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                    commandName,
                    note)));
        }
        commandAwaitingFinishAck = normalizeCommandNameForFinishAck(commandName);
    }

    private void acknowledgeCommandFinishRoundIfComplete(Event lastEvent, String command) {
        if (!(lastEvent instanceof InfoMessage info) || !isCommandFinishPromptMessage(info.message())) {
            return;
        }
        // The model has now RESPONDED to this command-finish-prompt round (with any command — terminal
        // or a follow-up like another `get`). The round is acknowledged, so the dedup guard has done its
        // job and must be cleared. Previously this only cleared on a terminal command (idle/empty), so a
        // normal follow-up command left the ack set; the NEXT command's successful finish then matched
        // the stale ack, was suppressed as a "duplicate", enqueued no event, and — with an empty queue
        // and no auto-tick — the model idled until an external UserMessage cleared the ack (dead-callback
        // idle). Clearing unconditionally here lets each successful command's finish enqueue its own
        // "what next?" prompt and reliably drive the model's next turn. This does NOT re-introduce genuine
        // duplicate prompts: the ack is SET at enqueue time and a true double-fire of onCommandFinish for
        // the SAME execution happens before the model responds (while the finish InfoMessage is still
        // queued / isProcessing is still true), so the duplicate is still suppressed; this clear only runs
        // after the model has actually answered the round, when no duplicate remains to guard against.
        LOGGER.info("Command-finish round complete (model responded with cmd={}); clearing finish-ack for cmd={}",
                command, commandAwaitingFinishAck);
        commandAwaitingFinishAck = null;
    }

    public void onCommandFinish(AgentSideEffects.CommandExecutionStopReason stopReason) {
        LOGGER.info("on command finish for cmd={}", stopReason.commandName());
        if (stopReason instanceof CommandExecutionStopReason.Finished) {
            repeatedCommandFailureGuard.reset();
            LOGGER.info("on command={} finish case", stopReason.commandName());
            if (shouldIgnoreGreetingDance && stopReason.commandName().contains("bodylang greeting")) {
                LOGGER.info("Skipping on command finish because should ignore greeting dance");
                // ignore first greeting command finish:
                shouldIgnoreGreetingDance = false;
                return;
            } else {
                shouldIgnoreGreetingDance = false;
            }
            if (!eventQueue.isEmpty()) {
                LOGGER.info("Skipping command finish prompt for cmd={} because event queue is not empty",
                        stopReason.commandName());
            } else if (shouldEnqueueCommandFinishPrompt(stopReason.commandName())) {
                LOGGER.info("Enqueueing command finish prompt for cmd={}", stopReason.commandName());
                String note = ((CommandExecutionStopReason.Finished) stopReason).note();
                enqueueCommandFinishPrompt(stopReason.commandName(), note);
            }
        } else if (stopReason instanceof CommandExecutionStopReason.Error error) {
            String boundedReason = RepeatedCommandFailureGuard.boundedFailureReason(error.errMsg());
            RepeatedCommandFailureGuard.Decision decision = repeatedCommandFailureGuard.record(
                    stopReason.commandName(), boundedReason);
            if (decision == RepeatedCommandFailureGuard.Decision.HALT_AUTOMATIC_RETRY) {
                String commandId = RepeatedCommandFailureGuard.commandId(stopReason.commandName());
                LOGGER.warn("Stopping repeated command-error feedback loop for bot={} commandId={}",
                        getName(), commandId);
                deferInfo(new InfoMessage(
                        "Automatic retrying stopped after the command '" + commandId
                                + "' failed twice with the same error. Do not claim it succeeded or retry it "
                                + "unless the player explicitly asks."));
                reportRepeatedCommandFailureStopped(commandId);
                commandAwaitingFinishAck = null;
                return;
            }
            LOGGER.info("adding cmd={} to queue because it errored", stopReason.commandName());
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s FAILED. The error was %s.",
                    RepeatedCommandFailureGuard.boundedCommandForModel(stopReason.commandName()),
                    boundedReason)));
        } else {
            repeatedCommandFailureGuard.reset();
            LOGGER.info("Skipping command stop for cmd={} because it was cancelled", stopReason.commandName());
        }
        // (if canceled dont modify queue)
    }

    private void reportRepeatedCommandFailureStopped(String commandId) {
        if (!(mod.getOwner() instanceof ServerPlayer owner) || owner.getServer() == null) {
            return;
        }
        AgentSideEffects.broadcastChatToPlayer(owner.getServer(),
                Component.translatable("message.playerengine.agent.repeated_command_stopped", getName(), commandId),
                owner);
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
