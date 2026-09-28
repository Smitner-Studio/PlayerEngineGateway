package com.player2.playerengine.smoke;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.function.Supplier;

/**
 * The switch for {@code /playerengine smoke}. The harness moves fake players, forces chunks and
 * rebuilds terrain, so a production server must never expose it: the command exists only when the
 * JVM runs with {@code -Dplayerengine.smoke=true}.
 */
public final class SmokeGate {
    public static final String PROPERTY = "playerengine.smoke";

    private SmokeGate() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Adds the harness node under {@code root} only when {@code enabled}. The supplier is not called
     * otherwise, so a disabled server never registers the harness's tick hook or help entry either.
     */
    public static <S> LiteralArgumentBuilder<S> attach(LiteralArgumentBuilder<S> root, boolean enabled,
            Supplier<? extends ArgumentBuilder<S, ?>> harness) {
        if (enabled) {
            root.then(harness.get());
        }
        return root;
    }
}
