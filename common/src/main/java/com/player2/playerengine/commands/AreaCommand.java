package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.commands.base.GoalText;
import com.player2.playerengine.commands.base.RestOfLineArg;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.construction.area.AreaBuildTask;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import com.player2.playerengine.tasks.construction.area.AreaVetoes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Shared body of the area commands: parse the box, check where it is, scan it, then run an
 * {@link AreaBuildTask}. Every refusal is a plain sentence the model can repair a plan with.
 */
public abstract class AreaCommand extends Command {
    private static final Map<UUID, Refusal> CONFIRM_REFUSALS = new ConcurrentHashMap<>();

    private record Refusal(String corners, long atMillis) {
    }

    private final AreaScan.Mode mode;
    // Command instances are shared per executor; a newer run's callbacks replace an older one's, so
    // the older task's completion must not finish the newer command.
    private long runCounter;

    protected AreaCommand(String name, String description, AreaScan.Mode mode) throws CommandException {
        super(name, description, new RestOfLineArg("area"));
        this.mode = mode;
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        GoalText text = parser.get(GoalText.class);
        String[] err = new String[1];
        AreaSpec.Request req = AreaSpec.parse(text.text(), mode == AreaScan.Mode.FILL, err);
        if (req == null) {
            finishWithError(err[0]);
            return;
        }
        Block fillBlock = null;
        if (mode == AreaScan.Mode.FILL) {
            ResourceLocation id = ResourceLocation.tryParse(req.block().contains(":") ? req.block() : "minecraft:" + req.block());
            Optional<Block> b = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
            if (b.isEmpty() || b.get() == Blocks.AIR || b.get().asItem() == net.minecraft.world.item.Items.AIR) {
                finishWithError("'" + req.block() + "' is not a block I can place");
                return;
            }
            fillBlock = b.get();
        }
        ServerLevel level = mod.getWorld();
        AreaSpec.Resolved resolved = AreaSpec.resolve(req, anchors(mod));
        if (resolved.error() != null) {
            finishWithError(resolved.error());
            return;
        }
        AreaSpec.Box box = resolved.box();
        BlockPos me = mod.getPlayer().blockPosition();
        String bounds = AreaSpec.checkBounds(box,
                mode == AreaScan.Mode.EXCAVATE ? AreaSpec.MAX_EXCAVATE_CELLS : AreaSpec.MAX_FILL_CELLS,
                new AreaSpec.Pos(me.getX(), me.getY(), me.getZ()),
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (bounds == null) {
            bounds = placeRefusal(level, box);
        }
        if (bounds != null) {
            finishWithError(bounds);
            return;
        }
        UUID bot = mod.getPlayer().getUUID();
        Refusal prior = CONFIRM_REFUSALS.get(bot);
        boolean confirmed = req.confirm() && prior != null && prior.corners().equals(box.corners())
                && mod.getLastOwnerMessageMillis() > prior.atMillis();

        AreaScan.Result scan = AreaScan.scan(mode, box, lookup(mod, level, fillBlock), freeSlots(mod),
                fillBlock == null ? 0 : countOf(mod, fillBlock), fillBlock == null ? "" : name(fillBlock), confirmed);
        if (scan.refused()) {
            if (scan.needsConfirm()) {
                CONFIRM_REFUSALS.put(bot, new Refusal(box.corners(), System.currentTimeMillis()));
            }
            finishWithError(scan.refusal());
            return;
        }
        CONFIRM_REFUSALS.remove(bot);
        if (scan.targets().isEmpty()) {
            mod.setLastArea(box);
            finishWithNote(mode == AreaScan.Mode.EXCAVATE ? "that space is already clear" : "that is already filled");
            return;
        }
        AreaBuildTask task = new AreaBuildTask(mode, box, fillBlock, scan);
        long run = ++runCounter;
        mod.runUserTask(task, () -> {
            if (run != runCounter) {
                return;
            }
            AreaBuildTask.Outcome outcome = task.outcome();
            if (outcome == null) {
                // Stopped from outside (a stop, or another command replacing it).
                finish();
            } else if (outcome.success()) {
                mod.setLastArea(box);
                finishWithNote(outcome.message());
            } else {
                finishWithError(outcome.message());
            }
        });
    }

    private static AreaSpec.Anchors anchors(PlayerEngineController mod) {
        var me = mod.getPlayer();
        BlockPos here = me.blockPosition();
        AreaSpec.Pos owner = null;
        AreaSpec.Facing ownerFacing = null;
        Player o = mod.getOwner();
        if (o != null && o.level() == me.level()) {
            BlockPos op = o.blockPosition();
            owner = new AreaSpec.Pos(op.getX(), op.getY(), op.getZ());
            ownerFacing = AreaSpec.Facing.fromYaw(o.getYRot());
        }
        return new AreaSpec.Anchors(new AreaSpec.Pos(here.getX(), here.getY(), here.getZ()),
                AreaSpec.Facing.fromYaw(me.getYRot()), owner, ownerFacing, mod.getLastArea());
    }

    /** World border, spawn protection, dimension and registered vetoes; null when the place is allowed. */
    private static String placeRefusal(ServerLevel level, AreaSpec.Box box) {
        if (level.dimension() != Level.OVERWORLD && level.dimension() != Level.NETHER && level.dimension() != Level.END) {
            return "I only do earthworks in the overworld, the nether and the end";
        }
        var border = level.getWorldBorder();
        if (!border.isWithinBounds(new BlockPos(box.minX() - 2, box.minY(), box.minZ() - 2))
                || !border.isWithinBounds(new BlockPos(box.maxX() + 2, box.maxY(), box.maxZ() + 2))) {
            return "that is at the edge of the world border";
        }
        MinecraftServer server = level.getServer();
        int spawnRadius = server.getSpawnProtectionRadius();
        if (spawnRadius > 0 && level.dimension() == Level.OVERWORLD) {
            BlockPos spawn = level.getSharedSpawnPos();
            boolean clear = box.maxX() < spawn.getX() - spawnRadius || box.minX() > spawn.getX() + spawnRadius
                    || box.maxZ() < spawn.getZ() - spawnRadius || box.minZ() > spawn.getZ() + spawnRadius;
            if (!clear) {
                return "that is inside the protected spawn area";
            }
        }
        return AreaVetoes.check(level, box).orElse(null);
    }

    private static AreaScan.BlockLookup lookup(PlayerEngineController mod, ServerLevel level, Block fillBlock) {
        PlayerPlacedBlockStore store = mod.getBaritoneSettings().respectStructuresEnabled.get()
                ? PlayerPlacedBlockStore.get() : null;
        String dim = level.dimension().location().toString();
        List<Block> avoid = mod.getBaritoneSettings().blocksToAvoidBreaking.get();
        List<ItemStack> tools = mod.getBaritone().getEntityContext().inventory() == null
                ? List.of() : List.copyOf(mod.getBaritone().getEntityContext().inventory().main);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            m.set(x, y, z);
            if (!level.isLoaded(m)) {
                return new AreaScan.Cell(false, false, null, false, false, false, false, false, false, 0, false, false, "");
            }
            BlockState st = level.getBlockState(m);
            String liquid = liquidName(st);
            float hardness = st.getDestroySpeed(level, m);
            float bestSpeed = 1.0F;
            boolean correct = !st.requiresCorrectToolForDrops();
            for (ItemStack s : tools) {
                if (s.isEmpty()) {
                    continue;
                }
                boolean c = s.isCorrectToolForDrops(st);
                if (st.requiresCorrectToolForDrops() && !c) {
                    continue;
                }
                correct |= c;
                bestSpeed = Math.max(bestSpeed, s.getDestroySpeed(st));
            }
            return new AreaScan.Cell(
                    true,
                    st.isAir(),
                    liquid,
                    st.canBeReplaced(),
                    st.getBlock() instanceof FallingBlock,
                    st.hasBlockEntity(),
                    store != null && store.contains(dim, m),
                    hardness < 0,
                    avoid.contains(st.getBlock()),
                    AreaScan.breakTicks(hardness, bestSpeed, correct),
                    !correct,
                    fillBlock != null && st.is(fillBlock),
                    name(st.getBlock()));
        };
    }

    /**
     * The liquid a block holds, read from its fluid state so waterlogged blocks (slabs, stairs, kelp)
     * count as water: digging one out leaves a source block behind. Null when dry.
     */
    public static String liquidName(BlockState st) {
        FluidState fluid = st.getFluidState();
        return fluid.isEmpty() ? null : fluid.is(FluidTags.LAVA) ? "lava" : "water";
    }

    private static int freeSlots(PlayerEngineController mod) {
        var inv = mod.getBaritone().getEntityContext().inventory();
        if (inv == null) {
            return 0;
        }
        int free = 0;
        for (ItemStack s : inv.main) {
            if (s.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static int countOf(PlayerEngineController mod, Block block) {
        var inv = mod.getBaritone().getEntityContext().inventory();
        if (inv == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : inv.main) {
            if (!s.isEmpty() && s.getItem() == block.asItem()) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }
}
