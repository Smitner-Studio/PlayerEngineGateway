package com.player2.playerengine.player2api.gateway;

import java.util.concurrent.Callable;

/**
 * Which companion, and whose billing, the chat completion on this thread belongs to.
 *
 * <p>Set around a companion's Player2 call by {@code Player2APIService}; the call reaches
 * {@code HTTPUtils} synchronously on the same thread, where {@link GatewayRouter} picks the
 * character's endpoint profile. Calls made with no context (memory extraction, mod-intelligence
 * enrichment, auth) use the default profile. A plain ThreadLocal on purpose: an inheritable one
 * would leak a companion's context into pool threads spawned while it is set.
 */
public final class GatewayCallContext {
    public record Frame(String characterId, String billingKey) {
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private GatewayCallContext() {
    }

    /** Runs {@code call} with the given context and restores the previous one afterwards. */
    public static <T> T call(String characterId, String billingKey, Callable<T> call) throws Exception {
        Frame previous = CURRENT.get();
        CURRENT.set(new Frame(characterId, billingKey));
        try {
            return call.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The current frame, or {@code null} outside a companion call. */
    public static Frame current() {
        return CURRENT.get();
    }
}
