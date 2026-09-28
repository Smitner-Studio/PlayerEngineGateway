package com.player2.playerengine.smoke;

import com.mojang.authlib.GameProfile;
import dev.architectury.event.events.common.PlayerEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.players.PlayerList;

/**
 * The smoke harness's stand-in players: NeoForge {@code FakePlayer}s that the companion code treats
 * as online, without a client. Every reflective workaround the harness needs lives here.
 *
 * <p>A FakePlayer's {@code Connection} has no netty channel, and NeoForge's {@code hasChannel}
 * dereferences it. Two callers crash the server on that (both seen live, 2026-09-28):
 * <ul>
 *   <li>vanilla {@code MinecraftServer.synchronizeTime}, for every player in the PlayerList;</li>
 *   <li>AppleSkin's {@code SyncHandler.onLivingTickEvent}, for every ticking player entity.</li>
 * </ul>
 * So the player gets an {@link EmbeddedChannel} (Carpet's fake-player technique), and it is listed
 * in {@code ServerLevel.players} (the chat range check reads it) without being added as a ticking
 * entity. Any other mod that sends an optional payload on a player event can reintroduce the crash;
 * extend this class, not the scenarios.
 *
 * <p>FakePlayerFactory is NeoForge's, so it is reached reflectively and {@code common} stays
 * loader-neutral. Field names are mojmap, which is what NeoForge 1.21.1 runs.
 */
final class FakePlayers {
    private FakePlayers() {
    }

    /** The online fake player for {@code id}, created and listed on first use, moved to {@code at}. */
    static ServerPlayer online(ServerLevel level, UUID id, String name, BlockPos at) {
        ServerPlayer existing = level.getServer().getPlayerList().getPlayer(id);
        if (existing != null) {
            teleport(existing, at);
            return existing;
        }
        ServerPlayer p;
        try {
            p = (ServerPlayer) Class.forName("net.neoforged.neoforge.common.util.FakePlayerFactory")
                    .getMethod("get", ServerLevel.class, GameProfile.class)
                    .invoke(null, level, new GameProfile(id, name));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FakePlayerFactory unavailable: " + e, e);
        }
        giveChannel(p);
        listInLevel(level, p);
        listAsOnline(level.getServer(), p);
        teleport(p, at);
        return p;
    }

    /**
     * A returning owner: reads its saved player data (a real login does this in
     * {@code PlayerList.placeNewPlayer}), lists it, then fires the architectury join event that
     * Player2NPC re-summons companions from.
     */
    static ServerPlayer login(ServerLevel level, UUID id, String name, BlockPos at) {
        ServerPlayer p = online(level, id, name, at);
        level.getServer().getPlayerList().load(p);
        teleport(p, at);
        PlayerEvent.PLAYER_JOIN.invoker().join(p);
        return p;
    }

    static void teleport(ServerPlayer p, BlockPos to) {
        p.moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0f, 0f);
    }

    private static void giveChannel(ServerPlayer p) {
        Connection conn = (Connection) read(ServerCommonPacketListenerImpl.class, "connection", p.connection);
        if (read(Connection.class, "channel", conn) == null) {
            write(Connection.class, "channel", conn, new EmbeddedChannel());
        }
    }

    @SuppressWarnings("unchecked")
    private static void listInLevel(ServerLevel level, ServerPlayer p) {
        List<ServerPlayer> players = (List<ServerPlayer>) read(ServerLevel.class, "players", level);
        if (!players.contains(p)) {
            players.add(p);
        }
    }

    /**
     * Billing (Player2PayerResolution), owner lookup and chat fan-out read the PlayerList. NeoForge
     * patches {@code getPlayers()} to an unmodifiable view, so the backing fields are written. Being
     * listed also makes {@code saveAll} write the player's data at shutdown, which {@link #login}
     * reads back after a restart.
     */
    @SuppressWarnings("unchecked")
    private static void listAsOnline(MinecraftServer server, ServerPlayer p) {
        PlayerList list = server.getPlayerList();
        ((List<ServerPlayer>) read(PlayerList.class, "players", list)).add(p);
        ((Map<UUID, ServerPlayer>) read(PlayerList.class, "playersByUUID", list)).put(p.getUUID(), p);
    }

    private static Object read(Class<?> owner, String field, Object target) {
        try {
            return field(owner, field).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + field + " unavailable: " + e, e);
        }
    }

    private static void write(Class<?> owner, String field, Object target, Object value) {
        try {
            field(owner, field).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + field + " unavailable: " + e, e);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
