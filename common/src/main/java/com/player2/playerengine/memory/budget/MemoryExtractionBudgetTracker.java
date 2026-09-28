package com.player2.playerengine.memory.budget;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-billing-key windowed call ceiling for the ENTIRE memory pipeline (extraction + folded-in
 * importance + reflection + layer-3 alias), with its own window map so memory spend is independent
 * of and additive to the chat caps.
 *
 * <p>The hard guarantee from the cost model is the <em>call count</em>: at most
 * {@code callsPerWindow} memory {@code /chat/completions} per billing key per
 * {@code windowMinutes}. {@link #checkAndRecord} reserves a slot at fire time; {@link #peekCap}
 * and {@link #peek} are read-only and NEVER reserve (W5's reflection-trigger budget peek calls
 * these).
 */
public final class MemoryExtractionBudgetTracker {

    private static final Logger LOGGER = LogManager.getLogger(MemoryExtractionBudgetTracker.class);

    private static final class WindowState {
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile long windowStartMs = System.currentTimeMillis();
    }

    private static final ConcurrentHashMap<String, WindowState> WINDOWS = new ConcurrentHashMap<>();

    private MemoryExtractionBudgetTracker() {}

    /** Read-only window view (status/telemetry). Never reserves. */
    public static MemoryBudgetSnapshot peek(String billingKey, MemoryBudgetThresholds thresholds) {
        if (billingKey == null || thresholds.callsPerWindow() <= 0) {
            return new MemoryBudgetSnapshot(0, 0, 0);
        }
        WindowState ws = getOrCreate(billingKey);
        long windowMs = (long) thresholds.windowMinutes() * 60_000L;
        synchronized (ws) {
            resetIfExpired(ws, windowMs);
            return new MemoryBudgetSnapshot(
                    ws.callCount.get(), ws.windowStartMs, ws.windowStartMs + windowMs);
        }
    }

    /** Read-only cap check (does NOT record). W5's reflection-trigger peek calls this. */
    public static MemoryBudgetResult peekCap(String billingKey, MemoryBudgetThresholds thresholds) {
        if (billingKey == null || thresholds.callsPerWindow() <= 0) {
            return MemoryBudgetResult.OK;
        }
        long windowMs = (long) thresholds.windowMinutes() * 60_000L;
        WindowState ws = getOrCreate(billingKey);
        synchronized (ws) {
            resetIfExpired(ws, windowMs);
            if (ws.callCount.get() >= thresholds.callsPerWindow()) {
                return MemoryBudgetResult.CAP_REACHED;
            }
            return MemoryBudgetResult.OK;
        }
    }

    /** Reserves one memory-pipeline slot. Call only at fire time (after preflight passed). */
    public static MemoryBudgetResult checkAndRecord(String billingKey, MemoryBudgetThresholds thresholds) {
        if (billingKey == null || thresholds.callsPerWindow() <= 0) {
            return MemoryBudgetResult.OK;
        }
        long windowMs = (long) thresholds.windowMinutes() * 60_000L;
        WindowState ws = getOrCreate(billingKey);
        synchronized (ws) {
            resetIfExpired(ws, windowMs);
            int count = ws.callCount.incrementAndGet();
            if (count > thresholds.callsPerWindow()) {
                ws.callCount.decrementAndGet();
                LOGGER.debug("[memory] extraction_cap_skip billingKey={} count={}", billingKey, count - 1);
                return MemoryBudgetResult.CAP_REACHED;
            }
            return MemoryBudgetResult.OK;
        }
    }

    private static WindowState getOrCreate(String billingKey) {
        return WINDOWS.computeIfAbsent(billingKey, k -> new WindowState());
    }

    private static void resetIfExpired(WindowState ws, long windowMs) {
        long now = System.currentTimeMillis();
        if (now > ws.windowStartMs + windowMs) {
            ws.windowStartMs = now;
            ws.callCount.set(0);
        }
    }
}
