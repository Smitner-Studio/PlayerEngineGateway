package com.player2.playerengine.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Companion holds as a platform's owner-tagged, persistent entity tickets, bookkept by
 * {@link TicketBook}. The platform supplies only the ticket call, the persisted truth, and the
 * tickets it loaded ({@link #loaded}).
 */
public final class TicketChunkHolds implements ChunkHolds {
    private static final Logger LOGGER = LogManager.getLogger("playerengine-chunks");
    /** The overworld data file that records which player each holding companion belongs to. */
    public static final String OWNERS_FILE = "playerengine_companion_holds";

    /** The platform's ticket API. */
    public interface Platform {
        /** Adds or removes {@code companion}'s entity-ticking ticket on one chunk. */
        void force(ServerLevel level, UUID companion, int chunkX, int chunkZ, boolean add);

        /** The chunks the level's saved ticket data records for {@code companion}. */
        Set<Long> persisted(ServerLevel level, UUID companion);
    }

    private final Platform platform;
    private final TicketBook book;
    private volatile MinecraftServer server;

    public TicketChunkHolds(Platform platform) {
        this.platform = platform;
        this.book = new TicketBook(new TicketBook.Store() {
            @Override
            public void add(Object dimension, UUID companion, long chunk) {
                force(dimension, companion, chunk, true);
            }

            @Override
            public void remove(Object dimension, UUID companion, long chunk) {
                force(dimension, companion, chunk, false);
            }
        }, new TicketBook.Owners() {
            @Override
            public UUID get(UUID companion) {
                Owners o = owners();
                return o == null ? null : o.map.get(companion);
            }

            @Override
            public void put(UUID companion, UUID owner) {
                Owners o = owners();
                if (o != null) {
                    o.map.put(companion, owner);
                    o.setDirty();
                }
            }

            @Override
            public void remove(UUID companion) {
                Owners o = owners();
                if (o != null && o.map.remove(companion) != null) {
                    o.setDirty();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void force(Object dimension, UUID companion, long chunk, boolean add) {
        MinecraftServer s = server;
        ServerLevel level = s == null ? null : s.getLevel((ResourceKey<Level>) dimension);
        if (level == null) {
            LOGGER.warn("No level {} to {} companion {}'s ticket on chunk {}", dimension, add ? "add" : "remove",
                    companion, new ChunkPos(chunk));
            return;
        }
        platform.force(level, companion, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), add);
    }

    private Owners owners() {
        MinecraftServer s = server;
        return s == null ? null : s.overworld().getDataStorage().computeIfAbsent(Owners.factory(), OWNERS_FILE);
    }

    /** The platform's load callback: the tickets the save held for {@code companion} in {@code level}. */
    public void loaded(ServerLevel level, UUID companion, Collection<Long> chunks) {
        server = level.getServer();
        book.loaded(level.dimension(), companion, chunks);
    }

    @Override
    public void holdExactly(ServerLevel level, UUID companion, UUID owner, List<ChunkPos> chunks) {
        server = level.getServer();
        List<Long> wanted = new ArrayList<>(chunks.size());
        for (ChunkPos c : chunks) {
            wanted.add(c.toLong());
        }
        book.holdExactly(level.dimension(), companion, owner, wanted);
    }

    @Override
    public int releaseAll(UUID companion) {
        return book.releaseAll(companion);
    }

    @Override
    public Set<Long> held(ServerLevel level, UUID companion) {
        return platform.persisted(level, companion);
    }

    /** The holds this book records, for the smoke harness: companion to its recorded owner. */
    public Map<UUID, UUID> holders() {
        Map<UUID, UUID> out = new HashMap<>();
        Owners o = owners();
        for (UUID c : book.companions()) {
            out.put(c, o == null ? null : o.map.get(c));
        }
        return out;
    }

    @Override
    public void serverStarted(MinecraftServer server) {
        this.server = server;
        sweep(server);
    }

    @Override
    public void serverStopping(MinecraftServer server) {
        book.stopping();
    }

    @Override
    public void tick(MinecraftServer server) {
        this.server = server;
        sweep(server);
    }

    private void sweep(MinecraftServer server) {
        for (UUID stale : book.sweep(owner -> server.getPlayerList().getPlayer(owner) != null)) {
            LOGGER.info("Released the chunk hold of companion {}: it has not come back", stale);
        }
    }

    /** companion UUID to owner UUID, saved with the overworld. */
    static final class Owners extends SavedData {
        final Map<UUID, UUID> map = new HashMap<>();

        static SavedData.Factory<Owners> factory() {
            return new SavedData.Factory<>(Owners::new, Owners::load, null);
        }

        static Owners load(CompoundTag tag, HolderLookup.Provider registries) {
            Owners o = new Owners();
            ListTag list = tag.getList("holds", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                if (e.hasUUID("companion") && e.hasUUID("owner")) {
                    o.map.put(e.getUUID("companion"), e.getUUID("owner"));
                }
            }
            return o;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (Map.Entry<UUID, UUID> e : map.entrySet()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("companion", e.getKey());
                t.putUUID("owner", e.getValue());
                list.add(t);
            }
            tag.put("holds", list);
            return tag;
        }
    }
}
