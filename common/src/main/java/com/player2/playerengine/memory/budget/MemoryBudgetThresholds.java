package com.player2.playerengine.memory.budget;

import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;

/**
 * Per-billing-key memory-pipeline call ceiling. Independent of and additive to the chat caps —
 * this is the memory pipeline's OWN hard ceiling, the worst-case
 * spend lever from the Phase D cost model ({@code memoryCallsPerWindow} completions per owner
 * per {@code memoryWindowMinutes}).
 */
public record MemoryBudgetThresholds(int callsPerWindow, int windowMinutes) {

    public static MemoryBudgetThresholds fromConfig(Player2ServerRuntimeConfig config) {
        return new MemoryBudgetThresholds(
                config.getMemoryCallsPerWindowClamped(),
                config.getMemoryWindowMinutesClamped());
    }
}
