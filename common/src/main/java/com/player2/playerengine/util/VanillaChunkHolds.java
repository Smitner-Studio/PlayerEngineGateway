package com.player2.playerengine.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * The Fabric fallback: {@link ChunkController}'s vanilla forced flags. Not persistent (vanilla keeps
 * no owner on a forced chunk, so a flag saved into the world would look foreign on the next start);
 * every hold is released at server stop, and a companion re-takes its hold when it next ticks.
 */
public final class VanillaChunkHolds implements ChunkHolds {
    private record Hold(ServerLevel level, List<ChunkPos> chunks) {
    }

    private final Map<UUID, Hold> holds = new HashMap<>();

    @Override
    public synchronized void holdExactly(ServerLevel level, UUID companion, UUID owner, List<ChunkPos> chunks) {
        Hold before = holds.get(companion);
        if (before != null && before.level() != level) {
            ChunkController.instance.releaseAll(companion);
            before = null;
        }
        // Every call, not only on entry: a chunk that was someone else's when the companion arrived
        // is claimed once they let it go. load() is idempotent.
        for (ChunkPos c : chunks) {
            ChunkController.instance.load(level, companion, c.x, c.z);
        }
        if (before != null) {
            for (ChunkPos c : before.chunks()) {
                if (!chunks.contains(c)) {
                    ChunkController.instance.unload(level, companion, c.x, c.z);
                }
            }
        }
        holds.put(companion, new Hold(level, List.copyOf(chunks)));
    }

    @Override
    public synchronized int releaseAll(UUID companion) {
        holds.remove(companion);
        return ChunkController.instance.releaseAll(companion);
    }

    @Override
    public synchronized Set<Long> held(ServerLevel level, UUID companion) {
        Hold h = holds.get(companion);
        Set<Long> out = new HashSet<>();
        if (h != null && h.level() == level) {
            for (ChunkPos c : h.chunks()) {
                if (level.getForcedChunks().contains(c.toLong())) {
                    out.add(c.toLong());
                }
            }
        }
        return out;
    }

    @Override
    public void serverStarted(MinecraftServer server) {
    }

    @Override
    public synchronized void serverStopping(MinecraftServer server) {
        holds.clear();
        ChunkController.instance.releaseEverything();
    }

    @Override
    public void tick(MinecraftServer server) {
    }
}
