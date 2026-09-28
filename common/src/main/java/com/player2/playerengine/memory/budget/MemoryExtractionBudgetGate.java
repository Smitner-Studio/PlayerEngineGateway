package com.player2.playerengine.memory.budget;

import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;

/**
 * Thin wrapper over {@link MemoryExtractionBudgetTracker} for the memory-pipeline windowed cap.
 *
 * <p>Provides read-only {@code peek*} methods (W5's reflection-trigger budget peek — never
 * reserves) and {@link #reserveSlot} (records at fire time). The A4 / patron checks live in
 * {@link MemoryGate}; this gate is ONLY the memory-pipeline's own per-billing-key ceiling.
 */
public final class MemoryExtractionBudgetGate {

    private MemoryExtractionBudgetGate() {}

    /** Read-only: would the next memory call exceed the window cap? Never reserves. */
    public static MemoryBudgetResult peekCap(Player2PayerResolution.ApiBillingContext billing) {
        if (billing == null || billing.billingKey() == null) {
            return MemoryBudgetResult.CAP_REACHED;
        }
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        return MemoryExtractionBudgetTracker.peekCap(
                billing.billingKey(), MemoryBudgetThresholds.fromConfig(cfg));
    }

    /** Read-only window view (status/telemetry). Never reserves. */
    public static MemoryBudgetSnapshot peek(Player2PayerResolution.ApiBillingContext billing) {
        if (billing == null || billing.billingKey() == null) {
            return new MemoryBudgetSnapshot(0, 0, 0);
        }
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        return MemoryExtractionBudgetTracker.peek(
                billing.billingKey(), MemoryBudgetThresholds.fromConfig(cfg));
    }

    /** Reserves one memory-pipeline slot (call only at fire time, after {@code MemoryGate} preflight passed). */
    public static boolean reserveSlot(Player2PayerResolution.ApiBillingContext billing) {
        if (billing == null || billing.billingKey() == null) {
            return false;
        }
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        return MemoryExtractionBudgetTracker.checkAndRecord(
                billing.billingKey(), MemoryBudgetThresholds.fromConfig(cfg))
                == MemoryBudgetResult.OK;
    }
}
