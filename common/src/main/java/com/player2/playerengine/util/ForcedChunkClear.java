package com.player2.playerengine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Operator ruling of 2026-09-28: on the first gateway.8 start of a world, every vanilla forced
 * chunk ({@code setChunkForced}, {@code /forceload}) is un-forced once and logged. gateway.7 and
 * earlier held companion chunks as vanilla flags with no owner, and a crash could leave them
 * forced for good; nothing can tell those from an operator's, so all go. The world then records
 * the clear as done, and a chunk forced afterwards is never touched by it.
 */
public final class ForcedChunkClear {
    private static final Logger LOGGER = LogManager.getLogger("playerengine-chunks");
    public static final String DATA_FILE = "playerengine_forced_chunk_clear";

    /** Chunks the last start cleared; -1 when the world had already been cleared. */
    private static volatile int lastRunCleared = -1;

    private ForcedChunkClear() {
    }

    /** One dimension's vanilla forced chunks. */
    public interface ForcedLevel {
        String name();

        List<Long> forced();

        void unforce(long chunk);
    }

    /** Whether this world has had its clear. */
    public interface Marker {
        boolean done();

        void markDone();
    }

    /** Clears every level once per world. @return chunks cleared, or -1 when already done */
    public static int runOnce(List<ForcedLevel> levels, Marker marker, Consumer<String> log) {
        if (marker.done()) {
            log.accept("one-time forced-chunk clear already done for this world; nothing cleared");
            return -1;
        }
        int cleared = 0;
        for (ForcedLevel level : levels) {
            for (long chunk : new ArrayList<>(level.forced())) {
                level.unforce(chunk);
                cleared++;
                log.accept("one-time forced-chunk clear: un-forced " + level.name() + " chunk "
                        + ChunkPos.getX(chunk) + "," + ChunkPos.getZ(chunk));
            }
        }
        marker.markDone();
        log.accept("one-time forced-chunk clear: cleared " + cleared + " chunk(s); it will not run again");
        return cleared;
    }

    /** Server start: runs the clear on the world's levels, before anything forces a chunk. */
    public static void onServerStarted(MinecraftServer server) {
        List<ForcedLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.add(new ForcedLevel() {
                @Override
                public String name() {
                    return level.dimension().location().toString();
                }

                @Override
                public List<Long> forced() {
                    return new ArrayList<>(level.getForcedChunks());
                }

                @Override
                public void unforce(long chunk) {
                    level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
                }
            });
        }
        Done data = server.overworld().getDataStorage().computeIfAbsent(Done.factory(), DATA_FILE);
        lastRunCleared = runOnce(levels, new Marker() {
            @Override
            public boolean done() {
                return data.done;
            }

            @Override
            public void markDone() {
                data.done = true;
                data.setDirty();
            }
        }, LOGGER::info);
    }

    /** What this start's clear did, for the smoke harness: chunks cleared, or -1 when skipped. */
    public static int lastRunCleared() {
        return lastRunCleared;
    }

    static final class Done extends SavedData {
        boolean done;

        static SavedData.Factory<Done> factory() {
            return new SavedData.Factory<>(Done::new, Done::load, null);
        }

        static Done load(CompoundTag tag, HolderLookup.Provider registries) {
            Done d = new Done();
            d.done = tag.getBoolean("done");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("done", done);
            return tag;
        }
    }
}
