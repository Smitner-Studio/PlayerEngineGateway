package com.player2.playerengine.smoke;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.player2api.Event;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * SPIKE: op-only live smoke scenarios for the companion planner. Simulated owner and stranger are
 * NeoForge FakePlayers (reached by reflection so common stays loader-neutral); the companion is
 * summoned through Player2NPC's own CompanionManager; chat enters through
 * {@link ConversationManager#onUserChatMessage}, the method the chat event calls. The model is a
 * loopback mock (PLAYERENGINE_GATEWAY_URL) that echoes the command or plan named after a
 * {@code SMOKE-CMD:} / {@code SMOKE-PLAN:} marker, so every reply still goes through the real
 * response parser, plan coordinator and owner gate. Each scenario logs one
 * {@code [smoke] <name> ok|FAILED: ...} line.
 */
public final class SmokeHarness {
    private static final Logger LOGGER = LogManager.getLogger("smoke");
    private static final UUID OWNER_ID = UUID.nameUUIDFromBytes("smoke-owner".getBytes(StandardCharsets.UTF_8));
    private static final UUID STRANGER_ID = UUID.nameUUIDFromBytes("smoke-stranger".getBytes(StandardCharsets.UTF_8));
    private static final String OWNER_NAME = "SmokeOwner";
    private static final String STRANGER_NAME = "SmokeStranger";
    private static final int FLOOR_Y = 120;

    private static final List<Probe> PROBES = new ArrayList<>();
    private static boolean tickRegistered;

    private SmokeHarness() {
    }

    /** A condition polled once a second until it settles or times out. */
    private record Probe(String name, long deadlineTick, Supplier<String> check, Supplier<String> onTimeout) {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        if (!tickRegistered) {
            tickRegistered = true;
            TickEvent.SERVER_POST.register(SmokeHarness::tick);
        }
        return Commands.literal("smoke")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("scenario", StringArgumentType.word())
                        .executes(ctx -> {
                            String scenario = StringArgumentType.getString(ctx, "scenario");
                            try {
                                start(ctx.getSource().getServer(), scenario);
                            } catch (Exception e) {
                                LOGGER.error("[smoke] {} FAILED: {}", scenario, e.toString(), e);
                            }
                            return 1;
                        }));
    }

    private static void start(MinecraftServer server, String scenario) throws Exception {
        ServerLevel level = server.overworld();
        switch (scenario) {
            case "spawn" -> spawn(server, level);
            case "excavate" -> excavate(server, level, 0, false);
            case "waterlogged" -> excavate(server, level, 1, true);
            case "stranger" -> stranger(server, level, 2);
            default -> LOGGER.error("[smoke] {} FAILED: unknown scenario", scenario);
        }
    }

    // --- scenarios ------------------------------------------------------------------------------

    private static void spawn(MinecraftServer server, ServerLevel level) throws Exception {
        BlockPos site = site(level, 0);
        arena(level, site);
        ServerPlayer owner = fakePlayer(level, OWNER_ID, OWNER_NAME, site.offset(0, 1, -5));
        if (companion() == null) {
            Object manager = Class.forName("com.goodbird.player2npc.companion.CompanionManager")
                    .getMethod("get", ServerPlayer.class).invoke(null, owner);
            Character ada = new Character("foreman-ada", "Foreman Ada", "Ada", "Foreman Ada here.",
                    "You are Foreman Ada.", "", new String[0]);
            manager.getClass().getMethod("spawnCompanion", Character.class).invoke(manager, ada);
        }
        long deadline = server.getTickCount() + 20L * 60;
        PROBES.add(new Probe("spawn", deadline,
                () -> companion() == null ? null
                        : "companion " + companion().getName() + " at " + companion().getMod().getPlayer().blockPosition()
                                + " owner=" + companion().getMod().getOwner().getUUID(),
                () -> "no companion registered for the fake owner within 60 s"));
    }

    /** Checklist 1 (clean box clears) and 5 (a waterlogged shell cell refuses the box). */
    private static void excavate(MinecraftServer server, ServerLevel level, int slot, boolean waterlogged) {
        String name = waterlogged ? "waterlogged" : "excavate";
        AgentConversationData data = requireCompanion(name);
        if (data == null) {
            return;
        }
        BlockPos site = site(level, slot);
        arena(level, site);
        BlockPos a = site.offset(2, 1, -1);
        BlockPos b = site.offset(4, 2, 1);
        fillBox(level, a, b);
        if (waterlogged) {
            level.setBlockAndUpdate(b.offset(1, -1, 0), Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED, true));
        }
        PlayerEngineController mod = data.getMod();
        teleport(level, (ServerPlayer) mod.getOwner(), site.offset(0, 1, -4));
        mod.getPlayer().teleportTo(site.getX() + 0.5, site.getY() + 1, site.getZ() - 2.5);
        int total = solid(level, a, b);
        long seqBefore = mod.getCommandDispatchSeq();
        say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: excavate " + corners(a, b));
        long deadline = server.getTickCount() + 20L * (waterlogged ? 30 : 240);
        if (!waterlogged) {
            PROBES.add(new Probe(name, deadline,
                    () -> solid(level, a, b) == 0 ? "cleared " + total + " cells; plan=" + mod.getPlanStatusLine() : null,
                    () -> "remaining " + solid(level, a, b) + "/" + total + " seq " + seqBefore + "->"
                            + mod.getCommandDispatchSeq()));
        } else {
            // Refusal: the command ran (seq moved) and the box is untouched when the window closes.
            PROBES.add(new Probe(name, deadline,
                    () -> solid(level, a, b) < total ? "!box was dug: " + solid(level, a, b) + "/" + total : null,
                    () -> mod.getCommandDispatchSeq() > seqBefore && solid(level, a, b) == total
                            ? "=refused: seq " + seqBefore + "->" + mod.getCommandDispatchSeq() + ", box intact " + total
                            : "command never dispatched (seq " + mod.getCommandDispatchSeq() + ")"));
        }
    }

    /** Checklist 8: a second player's dig plan is declined by the owner gate. */
    private static void stranger(MinecraftServer server, ServerLevel level, int slot) {
        AgentConversationData data = requireCompanion("stranger");
        if (data == null) {
            return;
        }
        BlockPos site = site(level, slot);
        arena(level, site);
        BlockPos a = site.offset(2, 1, -1);
        BlockPos b = site.offset(4, 2, 1);
        fillBox(level, a, b);
        PlayerEngineController mod = data.getMod();
        mod.getPlayer().teleportTo(site.getX() + 0.5, site.getY() + 1, site.getZ() - 2.5);
        teleport(level, (ServerPlayer) mod.getOwner(), site.offset(0, 1, -4));
        fakePlayer(level, STRANGER_ID, STRANGER_NAME, site.offset(-1, 1, -4));
        int total = solid(level, a, b);
        long seqBefore = mod.getCommandDispatchSeq();
        say(STRANGER_ID, STRANGER_NAME, "SMOKE-PLAN: excavate " + corners(a, b));
        long deadline = server.getTickCount() + 20L * 30;
        PROBES.add(new Probe("stranger", deadline,
                () -> mod.getCommandDispatchSeq() != seqBefore ? "!stranger's plan dispatched a command" : null,
                () -> solid(level, a, b) == total && mod.getPlanStatusLine().isEmpty()
                        ? "=declined: no dispatch (seq " + seqBefore + "), no plan, box intact " + total
                        : "box " + solid(level, a, b) + "/" + total + " plan='" + mod.getPlanStatusLine() + "'"));
    }

    // --- polling --------------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (PROBES.isEmpty() || server.getTickCount() % 20 != 0) {
            return;
        }
        for (Probe p : new ArrayList<>(PROBES)) {
            String verdict;
            try {
                verdict = p.check().get();
            } catch (Exception e) {
                verdict = "!" + e;
            }
            if (verdict == null && server.getTickCount() >= p.deadlineTick()) {
                String t = p.onTimeout().get();
                // A timeout verdict starting with '=' is the expected outcome (a refusal window).
                verdict = t.startsWith("=") ? t.substring(1) : "!timeout: " + t;
            }
            if (verdict == null) {
                continue;
            }
            PROBES.remove(p);
            if (verdict.startsWith("!")) {
                LOGGER.error("[smoke] {} FAILED: {}", p.name(), verdict.substring(1));
            } else {
                LOGGER.info("[smoke] {} ok: {}", p.name(), verdict);
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static AgentConversationData companion() {
        Collection<AgentConversationData> all = ConversationManager.getDataByOwner(OWNER_ID);
        return all.isEmpty() ? null : all.iterator().next();
    }

    private static AgentConversationData requireCompanion(String scenario) {
        AgentConversationData data = companion();
        if (data == null) {
            LOGGER.error("[smoke] {} FAILED: no companion; run `playerengine smoke spawn` first", scenario);
        }
        return data;
    }

    private static void say(UUID id, String name, String text) {
        // The pack runs call-by-name chat: an unaddressed line never reaches the companion.
        ConversationManager.onUserChatMessage(new Event.UserMessage("Ada, " + text, name, false, id));
    }

    private static BlockPos site(ServerLevel level, int slot) {
        BlockPos spawn = level.getSharedSpawnPos();
        return new BlockPos(spawn.getX() + 200 + slot * 24, FLOOR_Y, spawn.getZ());
    }

    /** A 17x17 stone platform at FLOOR_Y with air above it, chunks loaded and forced. */
    private static void arena(ServerLevel level, BlockPos c) {
        for (int cx = (c.getX() - 12) >> 4; cx <= (c.getX() + 12) >> 4; cx++) {
            for (int cz = (c.getZ() - 12) >> 4; cz <= (c.getZ() + 12) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlockAndUpdate(c.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 8; y++) {
                    level.setBlockAndUpdate(c.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void fillBox(ServerLevel level, BlockPos a, BlockPos b) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            level.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
        }
    }

    private static int solid(ServerLevel level, BlockPos a, BlockPos b) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (!level.getBlockState(p).isAir()) {
                n++;
            }
        }
        return n;
    }

    private static String corners(BlockPos a, BlockPos b) {
        return a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private static void teleport(ServerLevel level, ServerPlayer p, BlockPos to) {
        p.moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0f, 0f);
    }

    /**
     * NeoForge FakePlayer (FakePlayerFactory is NeoForge's, so reached reflectively from common),
     * listed as online and in the level without being a ticking entity.
     */
    private static ServerPlayer fakePlayer(ServerLevel level, UUID id, String name, BlockPos at) {
        ServerPlayer existing = (ServerPlayer) level.getPlayerByUUID(id);
        if (existing != null) {
            teleport(level, existing, at);
            return existing;
        }
        try {
            ServerPlayer p = (ServerPlayer) Class.forName("net.neoforged.neoforge.common.util.FakePlayerFactory")
                    .getMethod("get", ServerLevel.class, GameProfile.class)
                    .invoke(null, level, new GameProfile(id, name));
            teleport(level, p, at);
            giveChannel(p);
            listInLevel(level, p);
            listAsOnline(level.getServer(), p);
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FakePlayerFactory unavailable: " + e, e);
        }
    }

    /**
     * FakePlayerNetHandler's Connection has no netty channel, and NeoForge's hasChannel (reached
     * from vanilla time sync and mods' payload sends) dereferences it. An EmbeddedChannel absorbs
     * writes (Carpet's fake-player technique).
     */
    private static void giveChannel(ServerPlayer p) {
        try {
            var cf = net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
            cf.setAccessible(true);
            var conn = cf.get(p.connection);
            var f = net.minecraft.network.Connection.class.getDeclaredField("channel");
            f.setAccessible(true);
            if (f.get(conn) == null) {
                f.set(conn, new io.netty.channel.embedded.EmbeddedChannel());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Connection.channel unavailable: " + e, e);
        }
    }

    /**
     * The chat range check reads {@code level.players()}. Adding the FakePlayer as an entity
     * (addNewPlayer) makes it tick, and mods that send payloads on player tick (AppleSkin) crash on
     * its channel-less connection, so only the level's player list is written.
     */
    @SuppressWarnings("unchecked")
    private static void listInLevel(ServerLevel level, ServerPlayer p) {
        try {
            var f = ServerLevel.class.getDeclaredField("players");
            f.setAccessible(true);
            ((List<ServerPlayer>) f.get(level)).add(p);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ServerLevel.players unavailable: " + e, e);
        }
    }

    /**
     * Billing (Player2PayerResolution) and chat fan-out read the PlayerList, which a FakePlayer is
     * never in. Both backing fields are reached reflectively (mojmap names at runtime on
     * NeoForge 1.21.1).
     */
    @SuppressWarnings("unchecked")
    private static void listAsOnline(MinecraftServer server, ServerPlayer p) {
        var list = server.getPlayerList();
        if (list.getPlayer(p.getUUID()) != null) {
            return;
        }
        try {
            // NeoForge patches getPlayers() to an unmodifiable view, so write the backing list.
            var players = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            players.setAccessible(true);
            ((List<ServerPlayer>) players.get(list)).add(p);
            var f = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
            f.setAccessible(true);
            ((java.util.Map<UUID, ServerPlayer>) f.get(list)).put(p.getUUID(), p);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PlayerList.playersByUUID unavailable: " + e, e);
        }
    }

    @SuppressWarnings("unused")
    private static void markPlayerPlaced(ServerLevel level, BlockPos pos) {
        PlayerPlacedBlockStore.get().add(level.dimension().location().toString(), pos);
    }
}
