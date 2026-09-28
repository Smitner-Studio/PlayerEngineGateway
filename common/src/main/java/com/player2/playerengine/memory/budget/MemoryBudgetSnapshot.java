package com.player2.playerengine.memory.budget;

/**
 * Read-only view of a billing key's memory-window state. Used for status/telemetry; never branched
 * on for the hard cap (that is {@link MemoryExtractionBudgetTracker#peekCap}/{@code checkAndRecord}).
 */
public record MemoryBudgetSnapshot(int callCount, long windowStartMs, long windowEndMs) {}
