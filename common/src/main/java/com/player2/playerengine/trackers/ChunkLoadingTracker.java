package com.player2.playerengine.trackers;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.ChunkHolds;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the companion's 3x3 chunk hold on it, once a second, through {@link ChunkHolds}. The hold
 * follows the companion across dimensions and is released only by {@link #reset()} (despawn and
 * dismissal) or the platform's stale sweep; it persists across restarts where the platform has
 * tickets (R9).
 */
public class ChunkLoadingTracker {
    private int ticks = 20;
    private final PlayerEngineController controller;
    private final LivingEntity entity;

    public ChunkLoadingTracker(PlayerEngineController controller) {
        this.controller = controller;
        this.entity = controller.getEntity();
    }

    public boolean tick() {
        ticks--;
        if(ticks > 0)
            return false;
        ticks = 20;
        Player owner = controller.getOwner();
        ChunkHolds.get().holdExactly((ServerLevel) entity.level(), entity.getUUID(),
                owner == null ? null : owner.getUUID(), chunksToHold(entity.getX(), entity.getZ()));
        return false;
    }

    /**
     * The companion's chunk and its eight neighbours. A forced chunk ticks entities only inside
     * itself, and this tracker runs from the companion's own tick: a step into a chunk it does not
     * hold would freeze it there for good, with nothing left to force that chunk.
     */
    static List<ChunkPos> chunksToHold(double blockX, double blockZ) {
        int cx = SectionPos.blockToSectionCoord(Mth.floor(blockX));
        int cz = SectionPos.blockToSectionCoord(Mth.floor(blockZ));
        List<ChunkPos> list = new ArrayList<ChunkPos>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                list.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        return list;
    }

    /** Releases the whole hold, in whatever level it was taken. */
    public void reset() {
        ChunkHolds.get().releaseAll(entity.getUUID());
        ticks = 20;
    }
}
