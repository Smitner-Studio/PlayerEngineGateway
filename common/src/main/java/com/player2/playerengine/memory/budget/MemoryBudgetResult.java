package com.player2.playerengine.memory.budget;

/**
 * Outcome of a memory-pipeline windowed-cap check; the memory pipeline keeps its own
 * per-billing-key ceiling, independent of any chat cap.
 */
public enum MemoryBudgetResult {
    OK,
    CAP_REACHED
}
