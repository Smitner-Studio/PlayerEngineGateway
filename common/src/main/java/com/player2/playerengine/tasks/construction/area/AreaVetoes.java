package com.player2.playerengine.tasks.construction.area;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import net.minecraft.server.level.ServerLevel;

/**
 * Extra places an area command must refuse, registered by integrations. Empty by default: this fork
 * has no dependency that can tell, for example, whether a box lies on a ship's sub-level.
 */
public final class AreaVetoes {
    private static final List<BiFunction<ServerLevel, AreaSpec.Box, Optional<String>>> VETOES =
            new CopyOnWriteArrayList<>();

    private AreaVetoes() {
    }

    /** {@code veto} returns the refusal sentence, or empty when the box is fine. */
    public static void register(BiFunction<ServerLevel, AreaSpec.Box, Optional<String>> veto) {
        VETOES.add(veto);
    }

    public static Optional<String> check(ServerLevel level, AreaSpec.Box box) {
        for (var veto : VETOES) {
            Optional<String> why = veto.apply(level, box);
            if (why.isPresent()) {
                return why;
            }
        }
        return Optional.empty();
    }
}
