package com.player2.playerengine.util;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Where companions' 3x3 chunk holds live. NeoForge installs {@link TicketChunkHolds} (owner-tagged,
 * persistent tickets, R9); the default, used on Fabric, is {@link VanillaChunkHolds}: vanilla forced
 * flags claimed only when nobody else forced the chunk, held in memory and released at server stop.
 */
public interface ChunkHolds {
    /** Holds exactly {@code chunks} in {@code level} for {@code companion}; every other hold of it is released. */
    void holdExactly(ServerLevel level, UUID companion, UUID owner, List<ChunkPos> chunks);

    /** Releases every hold of {@code companion}, in every dimension. @return chunks released */
    int releaseAll(UUID companion);

    /** The chunks the platform's own store records as held by {@code companion} in {@code level}. */
    Set<Long> held(ServerLevel level, UUID companion);

    void serverStarted(MinecraftServer server);

    /** Before the world saves and players are disconnected. */
    void serverStopping(MinecraftServer server);

    /** Once every 200 server ticks. */
    void tick(MinecraftServer server);

    static ChunkHolds get() {
        return Active.holds;
    }

    static void install(ChunkHolds holds) {
        Active.holds = holds;
    }

    final class Active {
        private static volatile ChunkHolds holds = new VanillaChunkHolds();

        private Active() {
        }
    }
}
