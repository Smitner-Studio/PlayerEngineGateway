package com.player2.playerengine.memory.budget;

import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.memory.budget.MemoryGateDecision.SkipReason;
import com.player2.playerengine.memory.patron.MemoryPatronFallback;
import com.player2.playerengine.player2api.BudgetThresholdsResolver;
import com.player2.playerengine.player2api.JoulesCache;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import net.minecraft.server.MinecraftServer;

/**
 * The single, owner-scoped, FAIL-CLOSED gate at the Phase D memory-pipeline entry. Every memory
 * LLM call site (extraction, reflection, importance, layer-3 alias) MUST be downstream of a
 * {@link #preflight} that returned {@code allowed}; there is no other sanctioned path.
 *
 * <p><strong>Owner-scoped billing (closes plan Open item 1).</strong> The caller resolves the
 * COMPANION OWNER's billing context via
 * {@link Player2PayerResolution#resolve(com.player2.playerengine.PlayerEngineController, String, String)}
 * — {@code resolve(controller, ownerUsername, clientId)} — and passes it here as {@code ownerBilling}.
 * The gate evaluates the OWNER's patron tier and bills the OWNER, never the per-turn prompter. Under
 * {@code PROMPTER_PAYS} (default) with the owner offline and no stored owner token, the owner's
 * {@code billingKey()} resolves to {@code null} → {@link SkipReason#NO_BILLING} → fail-closed.
 * ({@code resolveForServer} is deliberately NOT used here — it bills any online player, not the owner.)
 *
 * <p><strong>Non-patron memory is the PRIMARY path (Phase D W9).</strong> Memory is available to
 * non-patron owners: the primary arm admits them and, when the owner's Joules cache is empty,
 * substitutes a {@linkplain JoulesCache#emptyNonPatron() zero-headroom non-patron snapshot} so the
 * downstream {@link JoulesCache#checkJoulesThreshold} sees a non-null snapshot and stays fail-CLOSED
 * (it {@linkplain JoulesCache#checkJoulesThreshold fails OPEN on a null snapshot},
 * {@code JoulesCache.java:144}). The window cap ({@code MemoryExtractionBudgetGate}) is the governing
 * spend lever, not the patron predicate. See {@code masterplan/phase-d-w8-embeddings-build-plan.md}
 * §B.2/§B.4.
 *
 * <p><strong>Preserved patron wall (manual-only fallback).</strong> The pre-W9 patron-walling
 * behavior — a {@code null} or non-patron owner snapshot returned {@link SkipReason#NOT_PATRON} with
 * zero outbound calls — is preserved as the patron arm of the live
 * {@code if (MemoryPatronFallback.MEMORY_PATRON_FALLBACK)} branch in {@link #preflight}. The patron
 * predicate ({@link #isConfirmedPatron}) is evaluated ONLY inside that arm; it is never reached on the
 * primary path. Flip {@link MemoryPatronFallback#MEMORY_PATRON_FALLBACK} to {@code true} + rebuild to
 * restore it (both arms are compiled). The predicate MUST NOT route through
 * {@link JoulesCache#checkJoulesThreshold} (which fails OPEN on a null snapshot).
 *
 * <p>The gate only PEEKS the cached owner snapshot ({@code JoulesCache.get(...)}); it never calls
 * {@code maybeRefresh} / a synchronous {@code /v1/joules} on the tick thread. Snapshot freshness is
 * the chat path's responsibility.
 */
public final class MemoryGate {

    private MemoryGate() {}

    /**
     * Fail-closed preflight, checks IN ORDER:
     * <ol>
     *   <li>{@code enableGraphRagMemory} off → {@link SkipReason#MEMORY_DISABLED}</li>
     *   <li>{@code dedicatedClientProxy} mode → {@link SkipReason#CLIENT_PROXY_UNSUPPORTED}
     *       (server has no outbound path; {@code ModelTierRouter.java:69-72})</li>
     *   <li>null owner billing / billingKey → {@link SkipReason#NO_BILLING}</li>
     *   <li>owner-snapshot PEEK; primary (W9) arm admits non-patrons and substitutes a
     *       {@linkplain JoulesCache#emptyNonPatron() zero-headroom non-patron snapshot} when the cache
     *       is null (so step 5 cannot fail open). Preserved patron arm (behind
     *       {@link MemoryPatronFallback#MEMORY_PATRON_FALLBACK}) walls non-patrons off →
     *       {@link SkipReason#NOT_PATRON}.</li>
     *   <li>A4 budget (call + Joules) → {@link SkipReason#BUDGET_HARD_SKIP}/{@link SkipReason#BUDGET_SOFT_SKIP}</li>
     *   <li>memory window cap peek → {@link SkipReason#EXTRACTION_CAP_SKIP}</li>
     *   <li>else {@code allowed}</li>
     * </ol>
     *
     * @param server       the server (used only for budget-store / threshold resolution)
     * @param ownerBilling the COMPANION OWNER's resolved billing context (never the prompter's)
     */
    public static MemoryGateDecision preflight(MinecraftServer server,
            Player2PayerResolution.ApiBillingContext ownerBilling) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();

        // (1) master switch — default true (W9). The primary arm admits non-patrons; only when
        // MEMORY_PATRON_FALLBACK is flipped does the additional patron requirement apply (step 3).
        if (!cfg.isEnableGraphRagMemory()) {
            return MemoryGateDecision.skip(SkipReason.MEMORY_DISABLED);
        }

        // (1a) dedicatedClientProxy: a server-side async memory job has no outbound path
        // (the client executes the call and there is no triggering client request to ride on).
        if (cfg.isDedicatedClientProxy()) {
            return MemoryGateDecision.skip(SkipReason.CLIENT_PROXY_UNSUPPORTED);
        }

        // (2) owner billing must be resolvable.
        if (ownerBilling == null || ownerBilling.billingKey() == null) {
            return MemoryGateDecision.skip(SkipReason.NO_BILLING);
        }
        String ownerBillingKey = ownerBilling.billingKey();

        // (3) owner-snapshot PEEK (read-only; no maybeRefresh on the tick).
        JoulesCache.JoulesSnapshot ownerSnapshot = JoulesCache.get(ownerBillingKey).orElse(null);
        if (MemoryPatronFallback.MEMORY_PATRON_FALLBACK) {
            // ---- PRESERVED PATRON ARM (compiled dead code until the toggle flips; §B.3) ----
            // Pre-W9 behavior: a null-snapshot or non-patron owner is walled off before the budget
            // check ever runs, which is ALSO what guaranteed ownerSnapshot != null at step (4).
            // MUST NOT route through JoulesCache.checkJoulesThreshold (fails OPEN on null).
            if (!isConfirmedPatron(ownerSnapshot)) {
                return MemoryGateDecision.skip(SkipReason.NOT_PATRON);
            }
            // ownerSnapshot guaranteed non-null here (patron => cached snapshot), invariant preserved.
        } else {
            // ---- PRIMARY (non-patron-admitting) ARM (W9, §B.2) ----
            // Admit non-patrons. Re-establish the load-bearing non-null-snapshot invariant WITHOUT a
            // patron wall: substitute a zero-headroom non-patron snapshot when the cache is empty so
            // step (4)'s checkJoulesThreshold sees a non-null snapshot and stays fail-CLOSED (it fails
            // OPEN on null, JoulesCache.java:144). The window cap (step 5) is the governing spend
            // lever for non-patrons; this substitute grants NO headroom (see plan §B.4/N7).
            if (ownerSnapshot == null) {
                ownerSnapshot = JoulesCache.emptyNonPatron();
            }
        }

        // (4) A4 budget — call-count peek + Joules peek, stricter of the two.
        // NOTE (load-bearing ordering): ownerSnapshot is guaranteed non-null here in BOTH arms of
        // step (3) — the patron arm returns before reaching here on a null snapshot, and the primary
        // arm substitutes emptyNonPatron() for a null snapshot — so checkJoulesThreshold's
        // fail-OPEN-on-null branch cannot bite. Do NOT reorder (3) below (4).
        BudgetThresholds thresholds = BudgetThresholdsResolver.resolve(server, ownerBilling);
        BudgetTracker.BudgetCheckResult callPeek = BudgetTracker.peek(ownerBillingKey, thresholds);
        BudgetTracker.BudgetCheckResult joulesPeek =
                JoulesCache.checkJoulesThreshold(ownerSnapshot, thresholds);
        BudgetTracker.BudgetCheckResult combined = BudgetTracker.stricter(callPeek, joulesPeek);
        if (combined == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
            return MemoryGateDecision.skip(SkipReason.BUDGET_HARD_SKIP);
        }
        if (combined == BudgetTracker.BudgetCheckResult.SOFT_LIMIT) {
            return MemoryGateDecision.skip(SkipReason.BUDGET_SOFT_SKIP);
        }

        // (5) memory-pipeline window cap — PEEK only (slot is recorded at fire time via reserveSlot).
        if (MemoryExtractionBudgetGate.peekCap(ownerBilling) == MemoryBudgetResult.CAP_REACHED) {
            return MemoryGateDecision.skip(SkipReason.EXTRACTION_CAP_SKIP);
        }

        return MemoryGateDecision.allow();
    }

    /**
     * Reserves one memory-pipeline slot at FIRE time (two-phase: {@link #preflight} peeked, this
     * records). Returns {@code false}
     * if the cap was reached between the peek and the record (a tight race), in which case the
     * caller must NOT fire.
     */
    public static boolean reserveSlot(Player2PayerResolution.ApiBillingContext ownerBilling) {
        return MemoryExtractionBudgetGate.reserveSlot(ownerBilling);
    }

    /**
     * Dedicated fail-closed patron predicate: {@code false} for a {@code null} snapshot or a
     * non-patron tier. MUST NOT be replaced by {@link JoulesCache#checkJoulesThreshold} (which
     * fails OPEN on null, silently enabling memory for non-patrons).
     *
     * <p><strong>Fallback-only (Phase D W9).</strong> This predicate is evaluated ONLY inside the
     * preserved patron arm of {@link #preflight} (behind
     * {@link MemoryPatronFallback#MEMORY_PATRON_FALLBACK}). The primary path never calls it. It is
     * retained (compiled) so the manual patron-restore fallback cannot bit-rot.
     */
    public static boolean isConfirmedPatron(JoulesCache.JoulesSnapshot snapshot) {
        return snapshot != null && snapshot.isPatron();
    }
}
