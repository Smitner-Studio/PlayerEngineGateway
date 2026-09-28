package com.player2.playerengine.forge;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.util.ChunkHolds;
import com.player2.playerengine.util.TicketChunkHolds;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;

/**
 * Companion chunk holds as NeoForge entity tickets (R9): one {@link TicketController}, tickets owned
 * by the companion's UUID, ticking, saved with each level's forced-chunk data and reinstated when the
 * level loads. Verified against the NeoForge 21.1.201 sources: {@code TicketController(id, callback)},
 * {@code forceChunk(level, UUID, x, z, add, ticking)}, {@code RegisterTicketControllersEvent} on the
 * mod bus, and the {@code LoadingValidationCallback} fired from {@code MinecraftServer.prepareLevels}
 * before entities load (so liveness is decided later, by the book's sweep).
 */
final class NeoForgeChunkTickets {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(PlayerEngine.MOD_ID, "companion_holds");
    private static final String TICKING_ENTITIES = "TickingEntities";

    private static TicketChunkHolds holds;
    static final TicketController CONTROLLER = new TicketController(ID, NeoForgeChunkTickets::onLoad);

    private NeoForgeChunkTickets() {
    }

    static void install(IEventBus modBus) {
        holds = new TicketChunkHolds(new TicketChunkHolds.Platform() {
            @Override
            public void force(ServerLevel level, UUID companion, int chunkX, int chunkZ, boolean add) {
                CONTROLLER.forceChunk(level, companion, chunkX, chunkZ, add, true);
            }

            @Override
            public Set<Long> persisted(ServerLevel level, UUID companion) {
                return persistedTickets(level, companion);
            }
        });
        ChunkHolds.install(holds);
        modBus.addListener(RegisterTicketControllersEvent.class, e -> e.register(CONTROLLER));
    }

    private static void onLoad(ServerLevel level, TicketHelper helper) {
        for (Map.Entry<UUID, TicketSet> e : helper.getEntityTickets().entrySet()) {
            Set<Long> chunks = new HashSet<>(e.getValue().ticking());
            chunks.addAll(e.getValue().nonTicking());
            holds.loaded(level, e.getKey(), chunks);
        }
    }

    /**
     * The level's saved ticket data, read through the same writer NeoForge saves it with, so the
     * answer is the store's own record and not this mod's bookkeeping.
     */
    static Set<Long> persistedTickets(ServerLevel level, UUID companion) {
        Set<Long> out = new HashSet<>();
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        if (data == null) {
            return out;
        }
        CompoundTag nbt = new CompoundTag();
        ForcedChunkManager.writeModForcedChunks(nbt, data.getBlockForcedChunks(), data.getEntityForcedChunks());
        ListTag controllers = nbt.getList("ModForced", Tag.TAG_COMPOUND);
        for (int i = 0; i < controllers.size(); i++) {
            CompoundTag c = controllers.getCompound(i);
            if (!ID.toString().equals(c.getString("Controller"))) {
                continue;
            }
            ListTag chunks = c.getList("ModForced", Tag.TAG_COMPOUND);
            for (int j = 0; j < chunks.size(); j++) {
                CompoundTag chunk = chunks.getCompound(j);
                for (Tag owner : chunk.getList(TICKING_ENTITIES, Tag.TAG_INT_ARRAY)) {
                    if (companion.equals(NbtUtils.loadUUID(owner))) {
                        out.add(chunk.getLong("Chunk"));
                    }
                }
            }
        }
        return out;
    }
}
