package com.player2.playerengine.smoke;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.Settings;
import com.player2.playerengine.help.ArgNote;
import com.player2.playerengine.help.HelpEntry;
import com.player2.playerengine.help.HelpRegistry;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.player2api.Event;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.utils.CharacterUtils;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.util.ChunkHolds;
import com.player2.playerengine.util.TicketChunkHolds;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Live smoke scenarios for the companion planner, run on a dedicated test server by
 * {@code /playerengine smoke <scenario>} (registered only under {@link SmokeGate}).
 *
 * <p>The owner and a stranger are {@link FakePlayers}; the companion is summoned through Player2NPC's
 * own {@code CompanionManager}; chat enters through {@link ConversationManager#onUserChatMessage}, the
 * method the chat event calls. The model is a loopback mock ({@code PLAYERENGINE_GATEWAY_URL}) that
 * echoes the command or plan named after a {@code SMOKE-CMD:} / {@code SMOKE-PLAN:} marker, so every
 * reply still goes through the real response parser, plan coordinator, owner gate and command
 * executor. Only the chat packet decode is skipped.
 *
 * <p>Each scenario is a list of stages (act once, then poll the world until settled) and logs exactly
 * one {@code [smoke] <name> ok: ...} or {@code [smoke] <name> FAIL: ...} line. Every marker line sent
 * is logged as {@code [smoke] marker ...}, so the runner can check the mock answered it: a refusal
 * scenario must not pass because the model was never asked.
 */
public final class SmokeHarness {
    private static final Logger LOGGER = LogManager.getLogger("smoke");
    private static final UUID OWNER_ID = UUID.nameUUIDFromBytes("smoke-owner".getBytes(StandardCharsets.UTF_8));
    private static final UUID STRANGER_ID = UUID.nameUUIDFromBytes("smoke-stranger".getBytes(StandardCharsets.UTF_8));
    private static final String OWNER_NAME = "SmokeOwner";
    private static final String STRANGER_NAME = "SmokeStranger";
    private static final UUID NETHER_PLAYER_ID = UUID.nameUUIDFromBytes("smoke-nether".getBytes(StandardCharsets.UTF_8));
    private static final String NETHER_PLAYER_NAME = "SmokeNether";
    /** Player2NPC's game id; its join handler asks the gateway for this game's characters. */
    private static final String GAME_ID = "player2-ai-npc-minecraft";
    private static final int FLOOR_Y = 120;
    private static final int POLL_TICKS = 10;
    /** A refusal is decided on the first model turn; the loopback mock answers in milliseconds. */
    private static final int REFUSAL_WINDOW_SEC = 20;
    private static final int DIG_TIMEOUT_SEC = 240;
    /** What {@code chunk-hold} leaves for {@code chunk-hold-restart} to check, in the world folder. */
    private static final String CHUNK_HOLD_MARKER = "playerengine-smoke-chunk-hold.txt";

    private static boolean tickRegistered;
    private static Run active;
    /** The fake owner whose per-player Player2NPC tick the harness drives (a fake never ticks). */
    private static ServerPlayer tickedOwner;
    private static Character character;

    private SmokeHarness() {
    }

    /**
     * One stage: {@code enter} acts once; {@code poll} returns null to keep waiting, {@code "!why"} to
     * fail, or a note (possibly empty) to pass. At the deadline {@code onTimeout} decides, with the
     * same convention: a refusal window passes on timeout, a wait fails.
     */
    private record Stage(Runnable enter, Supplier<String> poll, int timeoutSec, Supplier<String> onTimeout) {
    }

    private static final class Run {
        final String name;
        final Deque<Stage> stages;
        final List<String> notes = new ArrayList<>();
        Stage stage;
        long deadlineTick;

        Run(String name, List<Stage> stages) {
            this.name = name;
            this.stages = new ArrayDeque<>(stages);
        }
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        if (!tickRegistered) {
            tickRegistered = true;
            TickEvent.SERVER_POST.register(SmokeHarness::tick);
            LifecycleEvent.SERVER_STOPPING.register(server -> FakePlayers.quitAll(server,
                    List.of(OWNER_ID, STRANGER_ID, NETHER_PLAYER_ID)));
        }
        HelpRegistry.register(new HelpEntry("playerengine", "smoke", "smoke <scenario>",
                "help.playerengine.smoke.short", "help.playerengine.smoke.long",
                List.of(new ArgNote("scenario", "help.playerengine.smoke.arg.scenario")), 2, null, "diagnostics"));
        return Commands.literal("smoke")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("scenario", StringArgumentType.word())
                        .executes(ctx -> {
                            start(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "scenario"));
                            return 1;
                        }));
    }

    private static void start(MinecraftServer server, String name) {
        if (active != null) {
            fail(name, "scenario " + active.name + " is still running");
            return;
        }
        List<Stage> stages;
        try {
            stages = scenario(server.overworld(), name);
        } catch (RuntimeException e) {
            LOGGER.error("[smoke] {} FAIL: {}", name, e.toString(), e);
            return;
        }
        if (stages == null) {
            return;
        }
        active = new Run(name, stages);
        advance(server);
    }

    private static List<Stage> scenario(ServerLevel level, String name) {
        return switch (name) {
            case "spawn" -> spawn(level);
            case "excavate" -> excavate(level);
            case "protected" -> protectedShell(level);
            case "stop" -> stopMidDig(level);
            case "plan" -> planWithInterruption(level, 5, Resume.CONTINUE);
            case "waterlogged" -> waterlogged(level);
            case "stranger" -> stranger(level);
            case "chunks" -> chunksSurvive(level);
            case "resume" -> planWithInterruption(level, 8, Resume.REATTACH);
            case "despawn" -> despawnReleases(level);
            case "chunk-hold" -> chunkHold(level);
            case "attack" -> attackAPlayer(level);
            case "far-owner" -> farOwner(level);
            case "caps" -> turnCaps(level);
            case "chunk-hold-restart" -> chunkHoldAfterRestart(level);
            case "goto" -> planWithInterruption(level, 6, Resume.NONE);
            case "restart" -> restart(level);
            default -> {
                fail(name, "unknown scenario");
                yield null;
            }
        };
    }

    // --- scenarios ------------------------------------------------------------------------------

    /** Setup: the fake owner summons the gateway's first character through Player2NPC. */
    private static List<Stage> spawn(ServerLevel level) {
        BlockPos site = arena(level, 0);
        ServerPlayer owner = FakePlayers.online(level, OWNER_ID, OWNER_NAME, site.offset(0, 1, -6));
        character = CharacterUtils.requestFirstCharacter(owner, GAME_ID);
        if (companion() == null) {
            invoke(companionManager(owner), "spawnCompanion", Character.class, character);
        }
        return List.of(waitFor(() -> companion() == null ? null
                : "companion " + companion().getName() + " (" + character.id() + ") at " + bot().blockPosition()
                        + " owner=" + mod().getOwner().getUUID(),
                60, "no companion registered for the fake owner"));
    }

    /** Checklist 1: a real excavate completes. */
    private static List<Stage> excavate(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 0);
        BlockPos a = site.offset(2, 1, -1);
        BlockPos b = site.offset(4, 2, 1);
        fillBox(level, a, b);
        place(site, 0, -6, 0.5, -2.5);
        int total = solid(level, a, b);
        return List.of(
                act(() -> say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: excavate " + corners(a, b))),
                waitFor(() -> solid(level, a, b) == 0 ? "cleared " + total + " cells" : null,
                        DIG_TIMEOUT_SEC, () -> "remaining " + solid(level, a, b) + "/" + total + ", " + botState()));
    }

    /**
     * Checklist 4: player-placed blocks on two faces of the box's shell, and two inside the box, are
     * never broken. The inside ones make the check able to fail: without protection they are plain
     * targets of the dig, so a run with the store ignored breaks them. They are planks, which break
     * by hand; stone would make that run refuse for want of a pickaxe instead.
     */
    private static List<Stage> protectedShell(ServerLevel level) {
        requireCompanion();
        if (!mod().getBaritoneSettings().respectStructuresEnabled.get() || PlayerPlacedBlockStore.get() == null) {
            throw new IllegalStateException("structure protection is off or has no store; the check would be vacuous");
        }
        BlockPos site = arena(level, 3);
        BlockPos a = site.offset(2, 1, -1);
        BlockPos b = site.offset(4, 2, 1);
        fillBox(level, a, b);
        // The north face stands between the companion and the box; the west face is a flank.
        List<BlockPos> guarded = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-1, 0, -1), new BlockPos(b.getX(), b.getY(), a.getZ() - 1))) {
            guarded.add(p.immutable());
        }
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-1, 0, 0), new BlockPos(a.getX() - 1, b.getY(), b.getZ()))) {
            guarded.add(p.immutable());
        }
        List<BlockPos> inside = List.of(a.immutable(), new BlockPos(a.getX() + 1, b.getY(), a.getZ() + 1));
        guarded.addAll(inside);
        String dim = level.dimension().location().toString();
        for (BlockPos p : guarded) {
            level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
            PlayerPlacedBlockStore.get().add(dim, p);
        }
        place(site, 0, -7, 0.5, -3.5);
        int total = solid(level, a, b) - inside.size();
        Supplier<Integer> left = () -> solid(level, a, b) - inside.size();
        Supplier<String> broken = () -> {
            for (BlockPos p : guarded) {
                if (!level.getBlockState(p).is(Blocks.OAK_PLANKS)) {
                    return "!player-placed block at " + p.toShortString() + " was broken";
                }
            }
            return null;
        };
        return List.of(
                act(() -> say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: excavate " + corners(a, b))),
                waitFor(() -> {
                    String hit = broken.get();
                    return hit != null ? hit : left.get() == 0 ? "cleared " + total + " cells" : null;
                }, DIG_TIMEOUT_SEC, () -> "remaining " + left.get() + "/" + total + ", " + botState()),
                window(broken, 3, () -> (guarded.size() - inside.size()) + " player-placed shell blocks and "
                        + inside.size() + " inside the box intact"),
                act(() -> guarded.forEach(p -> PlayerPlacedBlockStore.get().remove(dim, p))));
    }

    /** Checklist 3: an owner stop mid-dig restores the builder settings the dig changed. */
    private static List<Stage> stopMidDig(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 4);
        BlockPos a = site.offset(2, 1, -2);
        BlockPos b = site.offset(6, 3, 2);
        fillBox(level, a, b);
        place(site, 0, -6, 0.5, -3.5);
        int total = solid(level, a, b);
        BuilderSettings before = BuilderSettings.of(mod().getBaritoneSettings());
        int[] remainingAtStop = new int[1];
        return List.of(
                act(() -> say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: excavate " + corners(a, b))),
                waitFor(() -> {
                    BuilderSettings now = BuilderSettings.of(mod().getBaritoneSettings());
                    return solid(level, a, b) < total && !now.equals(before)
                            ? "mid-dig " + before.diff(now) : null;
                }, 120, () -> "the dig never changed the builder settings (remaining " + solid(level, a, b)
                        + "/" + total + "); the restore check would be vacuous"),
                act(() -> say(OWNER_ID, OWNER_NAME, "stop")),
                waitFor(() -> {
                    remainingAtStop[0] = solid(level, a, b);
                    return BuilderSettings.of(mod().getBaritoneSettings()).equals(before)
                            ? "stopped at " + remainingAtStop[0] + "/" + total + ", settings restored" : null;
                }, 20, () -> "settings not restored after stop: "
                        + before.diff(BuilderSettings.of(mod().getBaritoneSettings()))),
                window(() -> solid(level, a, b) < remainingAtStop[0] - 1
                                ? "!still digging after the stop: " + solid(level, a, b) + "/" + total : null,
                        6, () -> BuilderSettings.of(mod().getBaritoneSettings()).equals(before)
                                ? "idle after the stop" : "!settings changed again after the stop"));
    }

    /** What follows the paused plan in {@link #planWithInterruption}. */
    private enum Resume {
        /** Leave it paused on disk for the restart scenario. */
        NONE,
        /** The owner's "continue" finishes it. */
        CONTINUE,
        /**
         * The companion gets a fresh conversation, as after a restart or re-attach, with its return
         * event queued, and the owner's "continue" lands in that first batch. The first turn of a
         * fresh conversation used to be forced to a greeting, which dropped the "continue".
         */
        REATTACH
    }

    /**
     * Checklist 2 ({@code plan}), 7 ({@code goto}) and {@code resume}: a 2-step plan starts, a direct
     * {@code goto} from the model mid-step pauses it (the CommandExecutor dispatch bump), then
     * {@link Resume} decides what follows.
     */
    private static List<Stage> planWithInterruption(ServerLevel level, int slot, Resume resume) {
        requireCompanion();
        BlockPos site = arena(level, slot);
        BlockPos a1 = site.offset(2, 1, -4);
        BlockPos b1 = site.offset(4, 2, -2);
        BlockPos a2 = site.offset(2, 1, 2);
        BlockPos b2 = site.offset(4, 2, 4);
        fillBox(level, a1, b1);
        fillBox(level, a2, b2);
        place(site, 0, -7, -2.5, 0.5);
        int total1 = solid(level, a1, b1);
        BlockPos away = site.offset(-5, 1, 0);
        long[] seq = new long[1];
        List<Stage> stages = new ArrayList<>(List.of(
                act(() -> say(OWNER_ID, OWNER_NAME, "SMOKE-PLAN: excavate " + corners(a1, b1)
                        + " | excavate " + corners(a2, b2))),
                waitFor(() -> mod().getPlanStatusLine().contains("step 1/2 running") && solid(level, a1, b1) < total1
                                ? "step 1 digging" : null,
                        120, () -> "step 1 never started: plan='" + mod().getPlanStatusLine() + "' box "
                                + solid(level, a1, b1) + "/" + total1),
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: goto " + away.getX() + " " + away.getY() + " " + away.getZ());
                }),
                waitFor(() -> mod().getPlanStatusLine().contains("step 1/2 paused")
                                ? "goto paused it mid-step 1 (seq " + seq[0] + "->" + mod().getCommandDispatchSeq()
                                        + ", box " + solid(level, a1, b1) + "/" + total1 + " left)"
                                : null,
                        30, () -> "plan not paused by the goto: plan='" + mod().getPlanStatusLine() + "' seq "
                                + seq[0] + "->" + mod().getCommandDispatchSeq())));
        if (resume == Resume.CONTINUE) {
            stages.add(act(() -> say(OWNER_ID, OWNER_NAME, "continue")));
            stages.add(bothCleared(level, a1, b1, a2, b2, "continue resumed it; both boxes cleared"));
        } else if (resume == Resume.REATTACH) {
            stages.add(act(() -> {
                PlayerEngineController m = mod();
                ConversationManager.despwnCompanion(m.getPlayer().getUUID());
                ConversationManager.getOrCreateEventQueueData(m);
                ConversationManager.sendReturnMessage(m, character, OWNER_NAME);
                say(OWNER_ID, OWNER_NAME, "continue");
            }));
            stages.add(waitFor(() -> mod().getPlanStatusLine().contains("running")
                            ? "one continue resumed it in the fresh conversation's first turn" : null,
                    30, () -> "plan not resumed by the first continue after re-attach: plan='"
                            + mod().getPlanStatusLine() + "'"));
            stages.add(bothCleared(level, a1, b1, a2, b2, "both boxes cleared"));
        }
        return stages;
    }

    /**
     * The arena's chunks are forced by the harness before the companion arrives. They stay forced
     * when the companion walks off to another arena, comes back, and stops.
     */
    private static List<Stage> chunksSurvive(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 7);
        BlockPos away = arena(level, 10);
        List<ChunkPos> arenaChunks = arenaChunks(site);
        Supplier<String> released = () -> {
            for (ChunkPos c : arenaChunks) {
                if (!level.getForcedChunks().contains(c.toLong())) {
                    return "!arena chunk " + c.x + "," + c.z + " was un-forced by the companion";
                }
            }
            return null;
        };
        return List.of(
                act(() -> place(site, 0, -6, 0.5, 0.5)),
                window(released, 3, () -> "companion held the arena for 3 s"),
                act(() -> place(away, 0, -6, 0.5, 0.5)),
                window(released, 4, () -> arenaChunks.size() + " arena chunks still forced after it left"),
                act(() -> place(site, 0, -6, 0.5, 0.5)),
                window(released, 3, () -> ""),
                act(() -> say(OWNER_ID, OWNER_NAME, "stop")),
                window(released, 4, () -> "and after it came back and stopped"));
    }

    /**
     * The companion holds its whole 3x3 in the platform's store; when Player2NPC dismisses it, all 9
     * are released and the arena's own forced chunks are not. It is then summoned again for the
     * scenarios that follow.
     */
    private static List<Stage> despawnReleases(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 9);
        ServerPlayer owner = (ServerPlayer) mod().getOwner();
        List<ChunkPos> arenaChunks = arenaChunks(site);
        UUID id = bot().getUUID();
        return List.of(
                act(() -> place(site, 0, -6, 0.5, 0.5)),
                waitFor(() -> holdsAround(level, id, bot().chunkPosition()) ? "companion holds its 9 chunks" : null,
                        20, () -> "the companion never held its 3x3 around " + bot().chunkPosition() + ": held "
                                + ChunkHolds.get().held(level, id).size()),
                act(() -> invoke(companionManager(owner), "dismissCompanion", Character.class, character)),
                waitFor(() -> companion() == null ? "dismissed" : null, 20, "companion still registered after dismiss"),
                window(() -> {
                    int left = ChunkHolds.get().held(level, id).size();
                    if (left > 0) {
                        return "!" + left + " chunk(s) still held after the companion was dismissed";
                    }
                    for (ChunkPos c : arenaChunks) {
                        if (!level.getForcedChunks().contains(c.toLong())) {
                            return "!arena chunk " + c.x + "," + c.z + " un-forced by the dismissal";
                        }
                    }
                    return null;
                }, 3, () -> "all 9 released, arena kept"),
                act(() -> invoke(companionManager(owner), "spawnCompanion", Character.class, character)),
                waitFor(() -> companion() == null ? null : "summoned again", 60, "no companion after summoning it again"));
    }

    /**
     * R9, first boot. The hold is the platform's owner-tagged tickets: 9 around the companion; a
     * {@code /forceload}ed chunk (the arena's) survives the companion leaving it; the same chunk
     * coordinates held in the nether are a separate hold; and a hold with no recorded owner (a
     * deleted companion's) is left for {@code chunk-hold-restart} to see swept at the next start.
     */
    private static List<Stage> chunkHold(ServerLevel level) {
        requireCompanion();
        if (!(ChunkHolds.get() instanceof TicketChunkHolds)) {
            throw new IllegalStateException("chunk holds are not tickets on this platform: " + ChunkHolds.get());
        }
        BlockPos site = arena(level, 11);
        BlockPos away = arena(level, 12);
        List<ChunkPos> arenaChunks = arenaChunks(site);
        UUID id = bot().getUUID();
        ChunkPos[] first = new ChunkPos[1];
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        UUID netherGhost = UUID.nameUUIDFromBytes("smoke-nether-ghost".getBytes(StandardCharsets.UTF_8));
        UUID staleGhost = UUID.nameUUIDFromBytes("smoke-stale-ghost".getBytes(StandardCharsets.UTF_8));
        ChunkPos staleAt = new ChunkPos(site(level, 13));
        return List.of(
                act(() -> place(site, 0, -6, 0.5, 0.5)),
                waitFor(() -> {
                    first[0] = bot().chunkPosition();
                    return holdsAround(level, id, first[0]) ? "9 tickets around " + first[0] : null;
                }, 20, () -> "no 9-ticket hold around " + bot().chunkPosition() + ": held "
                        + ChunkHolds.get().held(level, id).size()),
                act(() -> place(away, 0, -6, 0.5, 0.5)),
                waitFor(() -> {
                    if (!holdsAround(level, id, bot().chunkPosition())) {
                        return null;
                    }
                    for (ChunkPos c : arenaChunks) {
                        if (!level.getForcedChunks().contains(c.toLong())) {
                            return "!forceloaded chunk " + c.x + "," + c.z + " un-forced when the companion left";
                        }
                    }
                    return "moved from " + first[0] + " to " + bot().chunkPosition() + ": hold is the new 3x3 only, "
                            + arenaChunks.size() + " forceloaded chunks kept";
                }, 20, () -> "hold did not follow the companion: held " + ChunkHolds.get().held(level, id).size()),
                act(() -> {
                    if (nether == null) {
                        throw new IllegalStateException("no nether level");
                    }
                    ChunkHolds.get().holdExactly(nether, netherGhost, OWNER_ID, around(bot().chunkPosition()));
                }),
                waitFor(() -> {
                    ChunkPos here = bot().chunkPosition();
                    if (!holdsAround(nether, netherGhost, here)) {
                        return "!the nether hold at " + here + " is " + ChunkHolds.get().held(nether, netherGhost).size()
                                + " tickets";
                    }
                    ChunkHolds.get().releaseAll(netherGhost);
                    int netherLeft = ChunkHolds.get().held(nether, netherGhost).size();
                    return netherLeft == 0 && holdsAround(level, id, here)
                            ? "nether hold at the same x,z released, overworld hold kept"
                            : "!after the nether release: nether " + netherLeft + ", overworld "
                                    + ChunkHolds.get().held(level, id).size();
                }, 5, "nether hold never checked"),
                act(() -> {
                    ChunkHolds.get().holdExactly(level, staleGhost, null, around(staleAt));
                    writeMarker(level, staleGhost + " " + staleAt.x + " " + staleAt.z);
                }),
                waitFor(() -> ChunkHolds.get().held(level, staleGhost).size() == 9
                                ? "ownerless hold " + staleGhost + " left for the restart" : null,
                        5, "the ownerless hold was never taken"));
    }

    /**
     * R9, second boot, before the owner logs in: the companion's hold from the first boot is back,
     * and the ownerless one {@code chunk-hold} left was released by the start sweep.
     */
    private static List<Stage> chunkHoldAfterRestart(ServerLevel level) {
        if (!(ChunkHolds.get() instanceof TicketChunkHolds tickets)) {
            throw new IllegalStateException("chunk holds are not tickets on this platform: " + ChunkHolds.get());
        }
        String[] marker = readMarker(level).trim().split(" ");
        UUID staleGhost = UUID.fromString(marker[0]);
        UUID[] mine = new UUID[1];
        return List.of(
                waitFor(() -> {
                    List<UUID> owned = new ArrayList<>();
                    tickets.holders().forEach((c, o) -> {
                        if (OWNER_ID.equals(o)) {
                            owned.add(c);
                        }
                    });
                    if (owned.size() != 1) {
                        return "!" + owned.size() + " companion hold(s) recorded for the owner after the restart";
                    }
                    mine[0] = owned.get(0);
                    int n = ChunkHolds.get().held(level, mine[0]).size();
                    if (n != 9) {
                        return "!the companion's hold is " + n + " tickets after the restart";
                    }
                    for (long c : ChunkHolds.get().held(level, mine[0])) {
                        if (!level.isPositionEntityTicking(new ChunkPos(c).getMiddleBlockPosition(FLOOR_Y))) {
                            return null;
                        }
                    }
                    return "companion " + mine[0] + " holds its 9 chunks again, entity-ticking, owner offline";
                }, 30, "the held chunks never became entity-ticking"),
                waitFor(() -> {
                    int n = ChunkHolds.get().held(level, staleGhost).size();
                    return n == 0 ? "ownerless hold released at start" : null;
                }, 15, () -> "ownerless hold " + staleGhost + " still " + ChunkHolds.get().held(level, staleGhost).size()
                        + " tickets after the start sweep"));
    }

    private static List<ChunkPos> around(ChunkPos c) {
        List<ChunkPos> list = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                list.add(new ChunkPos(c.x + dx, c.z + dz));
            }
        }
        return list;
    }

    /** Whether the platform's store holds exactly the 3x3 around {@code at} for {@code id}. */
    private static boolean holdsAround(ServerLevel level, UUID id, ChunkPos at) {
        java.util.Set<Long> want = new java.util.HashSet<>();
        for (ChunkPos c : around(at)) {
            want.add(c.toLong());
        }
        return ChunkHolds.get().held(level, id).equals(want);
    }

    private static void writeMarker(ServerLevel level, String text) {
        try {
            java.nio.file.Files.writeString(level.getServer().getWorldPath(LevelResource.ROOT).resolve(CHUNK_HOLD_MARKER), text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot write " + CHUNK_HOLD_MARKER + ": " + e, e);
        }
    }

    private static String readMarker(ServerLevel level) {
        try {
            return java.nio.file.Files.readString(level.getServer().getWorldPath(LevelResource.ROOT).resolve(CHUNK_HOLD_MARKER));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("no " + CHUNK_HOLD_MARKER + "; run chunk-hold on the first boot", e);
        }
    }

    /**
     * R2: the owner, then a second player, each order the companion to attack the other player; no
     * attack task starts. Then the companion's own hit path is driven directly at a player beside it,
     * and it does not swing. Fake players are invulnerable (NeoForge {@code FakePlayer}), so damage
     * cannot be the witness; the attack task and the swing are.
     */
    private static List<Stage> attackAPlayer(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 14);
        place(site, 0, -3, 0.5, 0.5);
        ServerPlayer stranger = FakePlayers.online(level, STRANGER_ID, STRANGER_NAME, site.offset(2, 1, 0));
        long[] seq = new long[1];
        Supplier<String> attacking = () -> {
            com.player2.playerengine.tasks.base.Task t = mod().getUserTaskChain().getCurrentTask();
            return t != null && t.getClass().getSimpleName().equals("AttackAndGetDropsTask") && !t.isFinished()
                    ? "!an attack task is running: " + t : null;
        };
        return List.of(
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: attack " + STRANGER_NAME);
                }),
                window(attacking, REFUSAL_WINDOW_SEC, () -> mod().getCommandDispatchSeq() > seq[0]
                        ? "owner's attack on " + STRANGER_NAME + " refused (seq " + seq[0] + "->" + mod().getCommandDispatchSeq() + ")"
                        : "!the owner's attack command never dispatched"),
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    say(STRANGER_ID, STRANGER_NAME, "SMOKE-CMD: attack " + OWNER_NAME);
                }),
                window(attacking, REFUSAL_WINDOW_SEC, () -> mod().getCommandDispatchSeq() > seq[0]
                        ? "stranger's attack on " + OWNER_NAME + " refused" : "!the stranger's attack command never dispatched"),
                waitFor(() -> {
                    bot().teleportTo(stranger.getX() - 1.0, stranger.getY(), stranger.getZ());
                    bot().swinging = false;
                    float before = stranger.getHealth();
                    mod().getControllerExtras().attack(stranger);
                    if (bot().swinging) {
                        return "!the companion swung at a player";
                    }
                    return stranger.getHealth() == before ? "the hit path did not swing at the player beside it" : "!damage dealt";
                }, 5, "the hit path was never driven"));
    }

    /**
     * R8 with unique names. The companion is 100 blocks from everyone. The owner's line naming it is
     * acted on; the owner's unnamed line is not; a stranger's bare name is not (no Ada within 64
     * blocks) and the stranger is told the unique name; a player in the nether naming it uniquely is
     * told it is too far; the stranger's line with the unique name is acted on.
     */
    private static List<Stage> farOwner(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 15);
        place(site, 0, -3, 0.5, 0.5);
        ServerPlayer owner = (ServerPlayer) mod().getOwner();
        FakePlayers.teleport(owner, site.offset(100, 1, 0));
        FakePlayers.online(level, STRANGER_ID, STRANGER_NAME, site.offset(-100, 1, 0));
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw new IllegalStateException("no nether level");
        }
        FakePlayers.online(nether, NETHER_PLAYER_ID, NETHER_PLAYER_NAME, new BlockPos(site.getX() >> 3, 70, 0));
        String unique = OWNER_NAME + "'s " + character.shortName();
        List<String> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
        ConversationManager.noticeTap = (who, text) -> notices.add(who + ": "
                + (text.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        ? t.getKey() : text.getString()));
        long[] seq = new long[1];
        String gotoA = "SMOKE-CMD: goto " + site.getX() + " " + (site.getY() + 1) + " " + (site.getZ() + 3);
        String gotoB = "SMOKE-CMD: goto " + site.getX() + " " + (site.getY() + 1) + " " + (site.getZ() - 3);
        Supplier<String> moved = () -> mod().getCommandDispatchSeq() != seq[0]
                ? "!a command was dispatched (seq " + seq[0] + "->" + mod().getCommandDispatchSeq() + ")" : null;
        return List.of(
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    say(OWNER_ID, OWNER_NAME, gotoA);
                }),
                waitFor(() -> mod().getCommandDispatchSeq() > seq[0] ? "owner naming it from 100 blocks: acted on" : null,
                        REFUSAL_WINDOW_SEC, "the owner's named ask from 100 blocks was dropped"),
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    sayRaw(OWNER_ID, OWNER_NAME, gotoB);
                }),
                window(moved, 8, () -> "owner's unnamed line from 100 blocks: ignored"),
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    notices.clear();
                    sayRaw(STRANGER_ID, STRANGER_NAME, character.shortName() + ", " + gotoB);
                }),
                window(moved, 8, () -> notices.contains(STRANGER_NAME + ": message.playerengine.call.which")
                        ? "stranger's bare name from 100 blocks: not reached, told the unique name"
                        : "!the stranger was not told which: " + notices),
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    notices.clear();
                    sayRaw(NETHER_PLAYER_ID, NETHER_PLAYER_NAME, unique + ", " + gotoB);
                }),
                window(moved, 8, () -> notices.contains(NETHER_PLAYER_NAME + ": message.playerengine.call.too_far")
                        ? "unique name from the nether: told too far"
                        : "!the nether player was not told too far: " + notices),
                act(() -> {
                    seq[0] = mod().getCommandDispatchSeq();
                    sayRaw(STRANGER_ID, STRANGER_NAME, unique + ", " + gotoB);
                    LOGGER.info("[smoke] marker {}", gotoB);
                }),
                waitFor(() -> mod().getCommandDispatchSeq() > seq[0]
                                ? "stranger naming " + unique + " from 100 blocks: acted on" : null,
                        REFUSAL_WINDOW_SEC, "the stranger's unique-name ask was dropped"),
                act(() -> {
                    ConversationManager.noticeTap = null;
                    FakePlayers.teleport(owner, site.offset(0, 1, -3));
                }));
    }

    /**
     * Turn caps, with the player cap lowered to 3 for the run: a second player's first three lines
     * each reach the model, the fourth makes no model call. The companion's request count is taken
     * where the request is submitted.
     */
    private static List<Stage> turnCaps(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 17);
        place(site, 0, -3, 0.5, 0.5);
        FakePlayers.online(level, STRANGER_ID, STRANGER_NAME, site.offset(-2, 1, -2));
        com.player2.playerengine.player2api.TurnCaps.SHARED.resetForSmoke(3,
                com.player2.playerengine.player2api.TurnCaps.PER_COMPANION_PER_HOUR);
        long[] base = new long[1];
        List<Stage> stages = new ArrayList<>();
        stages.add(act(() -> base[0] = companion().getModelRequestCount()));
        for (int i = 1; i <= 3; i++) {
            int turn = i;
            stages.add(act(() -> say(STRANGER_ID, STRANGER_NAME, "how are you, turn " + turn)));
            stages.add(waitFor(() -> companion().getModelRequestCount() >= base[0] + turn
                            ? (turn == 3 ? "turns 1-3 each called the model" : "") : null,
                    REFUSAL_WINDOW_SEC, () -> "turn " + turn + " never reached the model ("
                            + (companion().getModelRequestCount() - base[0]) + " calls)"));
        }
        stages.add(act(() -> say(STRANGER_ID, STRANGER_NAME, "how are you, turn 4")));
        stages.add(window(() -> companion().getModelRequestCount() > base[0] + 3
                        ? "!turn 4 over the cap called the model" : null,
                8, () -> "turn 4 over the cap made no model call"));
        stages.add(act(() -> com.player2.playerengine.player2api.TurnCaps.SHARED.resetForSmoke(
                com.player2.playerengine.player2api.TurnCaps.PER_PLAYER_PER_HOUR,
                com.player2.playerengine.player2api.TurnCaps.PER_COMPANION_PER_HOUR)));
        return stages;
    }

    /** Checklist 5: a waterlogged block in the shell makes the excavate refuse. */
    private static List<Stage> waterlogged(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 1);
        BlockPos a = site.offset(2, 1, -1);
        BlockPos b = site.offset(4, 2, 1);
        fillBox(level, a, b);
        level.setBlockAndUpdate(b.offset(1, -1, 0), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true));
        place(site, 0, -6, 0.5, -2.5);
        int total = solid(level, a, b);
        long seqBefore = mod().getCommandDispatchSeq();
        return List.of(
                act(() -> say(OWNER_ID, OWNER_NAME, "SMOKE-CMD: excavate " + corners(a, b))),
                window(() -> solid(level, a, b) < total ? "!box was dug: " + solid(level, a, b) + "/" + total : null,
                        REFUSAL_WINDOW_SEC,
                        () -> mod().getCommandDispatchSeq() > seqBefore && solid(level, a, b) == total
                                ? "refused: seq " + seqBefore + "->" + mod().getCommandDispatchSeq() + ", box intact " + total
                                : "!command never dispatched (seq " + mod().getCommandDispatchSeq() + ")"));
    }

    /**
     * Checklist 8 under R1 (no difference between owners and strangers): a second player's excavate
     * command, then their excavate plan, each clear a box. The command path and the plan path were
     * the two owner gates.
     */
    private static List<Stage> stranger(ServerLevel level) {
        requireCompanion();
        BlockPos site = arena(level, 2);
        BlockPos a1 = site.offset(2, 1, -4);
        BlockPos b1 = site.offset(4, 2, -2);
        BlockPos a2 = site.offset(2, 1, 2);
        BlockPos b2 = site.offset(4, 2, 4);
        fillBox(level, a1, b1);
        fillBox(level, a2, b2);
        place(site, 0, -7, -2.5, 0.5);
        FakePlayers.online(level, STRANGER_ID, STRANGER_NAME, site.offset(-1, 1, -5));
        int total1 = solid(level, a1, b1);
        int total2 = solid(level, a2, b2);
        return List.of(
                act(() -> say(STRANGER_ID, STRANGER_NAME, "SMOKE-CMD: excavate " + corners(a1, b1))),
                waitFor(() -> solid(level, a1, b1) == 0 ? "stranger's excavate command cleared " + total1 + " cells" : null,
                        DIG_TIMEOUT_SEC, () -> "stranger's command: box " + solid(level, a1, b1) + "/" + total1 + ", "
                                + botState()),
                act(() -> say(STRANGER_ID, STRANGER_NAME, "SMOKE-PLAN: excavate " + corners(a2, b2))),
                waitFor(() -> solid(level, a2, b2) == 0 && mod().getPlanStatusLine().isEmpty()
                                ? "stranger's excavate plan cleared " + total2 + " cells" : null,
                        DIG_TIMEOUT_SEC, () -> "stranger's plan: box " + solid(level, a2, b2) + "/" + total2 + " plan='"
                                + mod().getPlanStatusLine() + "', " + botState()));
    }

    /**
     * Checklist 6, on a second boot of the same world after {@code goto} left a plan paused: the owner
     * logs back in, Player2NPC re-summons the companion from its join handler, the plan loads PAUSED
     * from plan.json, and "continue" finishes it.
     */
    private static List<Stage> restart(ServerLevel level) {
        BlockPos site = site(level, 6);
        forceChunks(level, site);
        BlockPos a1 = site.offset(2, 1, -4);
        BlockPos b1 = site.offset(4, 2, -2);
        BlockPos a2 = site.offset(2, 1, 2);
        BlockPos b2 = site.offset(4, 2, 4);
        if (solid(level, a2, b2) == 0) {
            throw new IllegalStateException("step 2's box is already clear; run `goto` on the first boot");
        }
        ServerPlayer owner = FakePlayers.login(level, OWNER_ID, OWNER_NAME, site.offset(0, 1, -7));
        character = CharacterUtils.requestFirstCharacter(owner, GAME_ID);
        tickedOwner = owner;
        return List.of(
                waitFor(() -> companion() == null ? null
                                : "re-summoned " + companion().getName() + " on the owner's join",
                        90, "no companion after the owner's join"),
                // A companion saved into the world would load beside the re-summoned one, and the
                // harness could then watch the copy that never hears "continue".
                window(() -> companionCount() > 1 ? "!" + companionCount() + " companions for " + character.id()
                                + " after the owner's join" : null,
                        5, () -> "one " + character.id()),
                waitFor(() -> mod().getPlanStatusLine().contains("paused")
                                ? "plan loaded paused (" + mod().getPlanStatusLine() + ")" : null,
                        30, () -> "no paused plan after the restart: plan='" + mod().getPlanStatusLine() + "'"),
                act(() -> say(OWNER_ID, OWNER_NAME, "continue")),
                bothCleared(level, a1, b1, a2, b2, "continue finished it; both boxes cleared"));
    }

    // --- stages ---------------------------------------------------------------------------------

    private static Stage act(Runnable r) {
        return new Stage(r, () -> "", 1, () -> "");
    }

    private static Stage waitFor(Supplier<String> poll, int timeoutSec, Supplier<String> why) {
        return new Stage(() -> { }, poll, timeoutSec, () -> "!timeout after " + timeoutSec + " s: " + why.get());
    }

    private static Stage waitFor(Supplier<String> poll, int timeoutSec, String why) {
        return waitFor(poll, timeoutSec, () -> why);
    }

    /** Holds for {@code seconds}: {@code violation} fails it early, {@code verdict} decides at the end. */
    private static Stage window(Supplier<String> violation, int seconds, Supplier<String> verdict) {
        return new Stage(() -> { }, violation, seconds, verdict);
    }

    private static Stage bothCleared(ServerLevel level, BlockPos a1, BlockPos b1, BlockPos a2, BlockPos b2, String note) {
        return waitFor(() -> solid(level, a1, b1) == 0 && solid(level, a2, b2) == 0
                        && mod().getPlanStatusLine().isEmpty() ? note : null,
                DIG_TIMEOUT_SEC, () -> "boxes " + solid(level, a1, b1) + " and " + solid(level, a2, b2)
                        + " left, plan='" + mod().getPlanStatusLine() + "', " + botState());
    }

    private static void tick(MinecraftServer server) {
        if (tickedOwner != null) {
            try {
                invoke(companionManager(tickedOwner), "serverTick", null, null);
            } catch (RuntimeException e) {
                LOGGER.error("[smoke] Player2NPC tick for the fake owner failed; no longer driving it", e);
                tickedOwner = null;
            }
        }
        if (active == null || server.getTickCount() % POLL_TICKS != 0) {
            return;
        }
        Stage stage = active.stage;
        String verdict;
        try {
            verdict = stage.poll().get();
            if (verdict == null && server.getTickCount() >= active.deadlineTick) {
                verdict = stage.onTimeout().get();
            }
        } catch (RuntimeException e) {
            verdict = "!" + e;
        }
        if (verdict == null) {
            return;
        }
        if (verdict.startsWith("!")) {
            fail(active.name, verdict.substring(1));
            active = null;
            return;
        }
        if (!verdict.isEmpty()) {
            active.notes.add(verdict);
        }
        advance(server);
    }

    private static void advance(MinecraftServer server) {
        Stage next = active.stages.poll();
        if (next == null) {
            LOGGER.info("[smoke] {} ok: {}", active.name, String.join("; ", active.notes));
            active = null;
            return;
        }
        active.stage = next;
        active.deadlineTick = server.getTickCount() + 20L * next.timeoutSec();
        try {
            next.enter().run();
        } catch (RuntimeException e) {
            LOGGER.error("[smoke] {} FAIL: {}", active.name, e.toString(), e);
            active = null;
        }
    }

    private static void fail(String name, String why) {
        LOGGER.error("[smoke] {} FAIL: {}", name, why);
        // A failed scenario can leave a plan or a dig running; stop it so the next scenario
        // reports its own result instead of this one's leftovers.
        if (!"restart".equals(name) && companion() != null && !mod().getPlanStatusLine().isEmpty()) {
            say(OWNER_ID, OWNER_NAME, "stop");
        }
    }

    // --- companion ------------------------------------------------------------------------------

    private static AgentConversationData companion() {
        if (character == null) {
            return null;
        }
        for (AgentConversationData d : ConversationManager.getDataByOwner(OWNER_ID)) {
            if (character.id().equals(d.getCharacter().id())) {
                return d;
            }
        }
        return null;
    }

    private static long companionCount() {
        return ConversationManager.getDataByOwner(OWNER_ID).stream()
                .filter(d -> character.id().equals(d.getCharacter().id())).count();
    }

    private static void requireCompanion() {
        if (companion() == null) {
            throw new IllegalStateException("no companion; run `playerengine smoke spawn` first");
        }
        String leftover = mod().getPlanStatusLine();
        if (!leftover.isEmpty()) {
            throw new IllegalStateException("an earlier scenario left a plan behind: " + leftover);
        }
    }

    private static PlayerEngineController mod() {
        return companion().getMod();
    }

    private static LivingEntity bot() {
        return mod().getPlayer();
    }

    /** What the companion is doing, for a timeout's FAIL line. */
    private static String botState() {
        com.player2.playerengine.tasks.base.Task task = mod().getUserTaskChain().getCurrentTask();
        var builder = mod().getBaritone().getBuilderProcess();
        return "task=" + (task == null ? "none" : task + (task.isFinished() ? " (finished)" : ""))
                + " builder=" + (builder.isActive() ? builder.isPaused() ? "paused" : "active" : "idle")
                + " runner=" + (mod().getTaskRunner().isActive() ? "on" : "off")
                + " chain=" + (mod().getTaskRunner().getCurrentTaskChain() == null ? "none"
                        : mod().getTaskRunner().getCurrentTaskChain().getName())
                + " at " + bot().blockPosition().toShortString()
                // A companion in a chunk that does not tick entities is frozen, whatever its task says.
                + (((ServerLevel) bot().level()).isPositionEntityTicking(bot().blockPosition()) ? "" : " (chunk not ticking)");
    }

    /** A line as typed, with no name in front; a marker in it is not expected to reach the model. */
    private static void sayRaw(UUID id, String name, String text) {
        ConversationManager.onUserChatMessage(new Event.UserMessage(text, name, false, id));
    }

    private static void say(UUID id, String name, String text) {
        if (text.contains("SMOKE-")) {
            LOGGER.info("[smoke] marker {}", text);
        }
        // The pack runs call-by-name chat: an unaddressed line never reaches the companion.
        ConversationManager.onUserChatMessage(new Event.UserMessage(
                character.shortName() + ", " + text, name, false, id));
    }

    /** Moves the owner and the companion into place (offsets from the arena centre). */
    private static void place(BlockPos site, int ownerDx, int ownerDz, double botDx, double botDz) {
        FakePlayers.teleport((ServerPlayer) mod().getOwner(), site.offset(ownerDx, 1, ownerDz));
        bot().teleportTo(site.getX() + botDx, site.getY() + 1, site.getZ() + botDz);
    }

    /** Player2NPC's per-player manager, reached reflectively (it is another mod). */
    private static Object companionManager(ServerPlayer owner) {
        try {
            return Class.forName("com.goodbird.player2npc.companion.CompanionManager")
                    .getMethod("get", ServerPlayer.class).invoke(null, owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Player2NPC CompanionManager unavailable: " + e, e);
        }
    }

    private static void invoke(Object target, String method, Class<?> argType, Object arg) {
        try {
            if (argType == null) {
                target.getClass().getMethod(method).invoke(target);
            } else {
                target.getClass().getMethod(method, argType).invoke(target, arg);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("CompanionManager." + method + " failed: " + e, e);
        }
    }

    // --- world ----------------------------------------------------------------------------------

    private static BlockPos site(ServerLevel level, int slot) {
        BlockPos spawn = level.getSharedSpawnPos();
        return new BlockPos(spawn.getX() + 200 + slot * 24, FLOOR_Y, spawn.getZ());
    }

    /** The chunks the harness forces for an arena centred on {@code c}. */
    private static List<ChunkPos> arenaChunks(BlockPos c) {
        List<ChunkPos> list = new ArrayList<>();
        for (int cx = (c.getX() - 12) >> 4; cx <= (c.getX() + 12) >> 4; cx++) {
            for (int cz = (c.getZ() - 12) >> 4; cz <= (c.getZ() + 12) >> 4; cz++) {
                list.add(new ChunkPos(cx, cz));
            }
        }
        return list;
    }

    private static void forceChunks(ServerLevel level, BlockPos c) {
        for (ChunkPos p : arenaChunks(c)) {
            level.setChunkForced(p.x, p.z, true);
            level.getChunk(p.x, p.z);
        }
    }

    /** A 17x17 stone platform at FLOOR_Y with air above it, chunks loaded and forced. */
    private static BlockPos arena(ServerLevel level, int slot) {
        BlockPos c = site(level, slot);
        forceChunks(level, c);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlockAndUpdate(c.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 8; y++) {
                    level.setBlockAndUpdate(c.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        return c;
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

    /** The per-entity builder settings an area task changes and must restore on every exit. */
    private record BuilderSettings(boolean allowBreak, boolean allowPlace, boolean buildInLayers,
            boolean layerOrder, List<Item> throwaways) {
        static BuilderSettings of(Settings s) {
            return new BuilderSettings(s.allowBreak.get(), s.allowPlace.get(), s.buildInLayers.get(),
                    s.layerOrder.get(), List.copyOf(s.acceptableThrowawayItems.get()));
        }

        String diff(BuilderSettings o) {
            List<String> d = new ArrayList<>();
            if (allowBreak != o.allowBreak) {
                d.add("allowBreak " + allowBreak + "->" + o.allowBreak);
            }
            if (allowPlace != o.allowPlace) {
                d.add("allowPlace " + allowPlace + "->" + o.allowPlace);
            }
            if (buildInLayers != o.buildInLayers) {
                d.add("buildInLayers " + buildInLayers + "->" + o.buildInLayers);
            }
            if (layerOrder != o.layerOrder) {
                d.add("layerOrder " + layerOrder + "->" + o.layerOrder);
            }
            if (!throwaways.equals(o.throwaways)) {
                d.add("throwaways " + throwaways.size() + "->" + o.throwaways.size() + " items");
            }
            return d.isEmpty() ? "no change" : String.join(", ", d);
        }
    }
}
