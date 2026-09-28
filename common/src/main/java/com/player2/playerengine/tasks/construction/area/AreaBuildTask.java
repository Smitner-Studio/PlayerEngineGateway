package com.player2.playerengine.tasks.construction.area;

import com.player2.playerengine.automaton.api.Settings;
import com.player2.playerengine.automaton.api.process.IBuilderProcess;
import com.player2.playerengine.automaton.api.schematic.ISchematic;
import com.player2.playerengine.companion.CompanionRules;
import com.player2.playerengine.tasks.base.ITaskRequiresGrounded;
import com.player2.playerengine.tasks.base.Task;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Clears or fills a scanned box with the embedded Baritone builder, in survival. Only the scanned
 * target cells are in the schematic, so player-placed and container cells inside the box keep their
 * protection, and the protected shell around it is a hard no-break for the run. The world is the
 * witness: the task succeeds only when every checked cell reads as done.
 */
public class AreaBuildTask extends Task implements ITaskRequiresGrounded {
    private static final int CHECK_EVERY_TICKS = 20;
    private static final long STALL_SECONDS = 45;
    private static final List<Item> SCAFFOLD_ITEMS = List.of(
            Blocks.DIRT.asItem(), Blocks.COBBLESTONE.asItem(), Blocks.COBBLED_DEEPSLATE.asItem(),
            Blocks.NETHERRACK.asItem());

    /** @param code why it failed, as the seam reports it; null on success */
    public record Outcome(boolean success, String message, com.player2.playerengine.seam.FailureCode code) {
    }

    private final AreaScan.Mode mode;
    private final AreaSpec.Box box;
    private final Block fillBlock;
    private final LongSet targets = new LongOpenHashSet();
    private final long[] checkCells;
    private final LongSet shellProtected = new LongOpenHashSet();
    private final long[] shellAirBefore;
    private final int excluded;
    private final double estimatedSeconds;
    private final int schematicHeight;

    private long startedMillis;
    private long lastProgressMillis;
    private long deadlineMillis;
    private int lastRemaining = Integer.MAX_VALUE;
    private int ticks;
    private Outcome outcome;
    private SavedSettings saved;

    public AreaBuildTask(AreaScan.Mode mode, AreaSpec.Box box, Block fillBlock, AreaScan.Result scan) {
        this.mode = mode;
        this.box = box;
        this.fillBlock = fillBlock;
        int maxY = box.maxY();
        for (long[] p : scan.targets()) {
            targets.add(BlockPos.asLong((int) p[0], (int) p[1], (int) p[2]));
            maxY = Math.max(maxY, (int) p[1]);
        }
        this.schematicHeight = maxY - box.minY() + 1;
        this.checkCells = scan.checkCells().stream()
                .mapToLong(p -> BlockPos.asLong((int) p[0], (int) p[1], (int) p[2])).toArray();
        for (long[] p : scan.shellProtected()) {
            shellProtected.add(BlockPos.asLong((int) p[0], (int) p[1], (int) p[2]));
        }
        this.shellAirBefore = scan.shellAirBefore().stream()
                .mapToLong(p -> BlockPos.asLong((int) p[0], (int) p[1], (int) p[2])).toArray();
        this.excluded = scan.excluded();
        this.estimatedSeconds = scan.estimatedSeconds();
    }

    /** Null while running or when the task was stopped from outside. */
    public Outcome outcome() {
        return outcome;
    }

    public AreaSpec.Box box() {
        return box;
    }

    @Override
    protected void onStart() {
        long now = System.currentTimeMillis();
        if (startedMillis == 0) {
            // First start only: an interruption by another chain must not reset the clocks.
            startedMillis = now;
            lastProgressMillis = now;
            double budget = mode == AreaScan.Mode.EXCAVATE
                    ? estimatedSeconds * 1.5 + 60
                    : targets.size() * 1.5 + 60;
            deadlineMillis = now + (long) (budget * 1000);
        }
        Settings s = controller.getBaritoneSettings();
        saved = new SavedSettings(s);
        s.allowBreak.set(true);
        s.allowPlace.set(true);
        s.buildInLayers.set(true);
        s.layerOrder.set(mode == AreaScan.Mode.EXCAVATE); // top-down digs, bottom-up fills
        s.acceptableThrowawayItems.set(new java.util.ArrayList<>(SCAFFOLD_ITEMS));
        IBuilderProcess builder = controller.getBaritone().getBuilderProcess();
        builder.setHardNoBreak(shellProtected);
        builder.build(mode == AreaScan.Mode.EXCAVATE ? "excavate" : "fill", new TargetSchematic(),
                new BlockPos(box.minX(), box.minY(), box.minZ()));
    }

    @Override
    protected Task onTick() {
        if (outcome != null) {
            return null;
        }
        if (++ticks % CHECK_EVERY_TICKS != 0) {
            setDebugState((mode == AreaScan.Mode.EXCAVATE ? "Excavating " : "Filling ") + box.corners());
            return null;
        }
        int remaining = remaining();
        long now = System.currentTimeMillis();
        if (remaining < lastRemaining) {
            lastRemaining = remaining;
            lastProgressMillis = now;
        }
        if (mode == AreaScan.Mode.EXCAVATE && remaining > 0 && freeSlots() == 0) {
            end(false, com.player2.playerengine.seam.FailureCode.CONTAINER_FULL, "my pack is full (" + (checkCells.length - remaining) + " of " + checkCells.length
                    + " done); empty it and carry on");
            return null;
        }
        IBuilderProcess builder = controller.getBaritone().getBuilderProcess();
        long stall = (long) (STALL_SECONDS * CompanionRules.get().digTimeScale() * 1000);
        AreaScan.Judgement j = AreaScan.judge(builder.isActive(), builder.isPaused(), remaining,
                checkCells.length, now, lastProgressMillis, stall, deadlineMillis);
        if (j.verdict() == AreaScan.Verdict.SUCCESS) {
            end(true, null, successNote());
        } else if (j.verdict() == AreaScan.Verdict.FAIL) {
            end(false, j.code(), j.reason());
        }
        return null;
    }

    @Override
    public boolean isFinished() {
        return outcome != null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        IBuilderProcess builder = controller.getBaritone().getBuilderProcess();
        if (builder.isActive()) {
            builder.onLostControl();
        }
        builder.setHardNoBreak(null);
        if (saved != null) {
            saved.restore(controller.getBaritoneSettings());
            saved = null;
        }
    }

    private void end(boolean success, com.player2.playerengine.seam.FailureCode code, String message) {
        outcome = new Outcome(success, message, code);
        onStop(null);
    }

    private int remaining() {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (long p : checkCells) {
            m.set(p);
            BlockState st = controller.getWorld().getBlockState(m);
            boolean done = mode == AreaScan.Mode.EXCAVATE ? st.isAir() : st.is(fillBlock);
            if (!done) {
                n++;
            }
        }
        return n;
    }

    private int freeSlots() {
        var inv = controller.getBaritone().getEntityContext().inventory();
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

    private String successNote() {
        StringBuilder sb = new StringBuilder();
        if (excluded > 0) {
            sb.append("left ").append(excluded).append(" blocks that people placed or that hold items");
        }
        int scaffold = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (long p : shellAirBefore) {
            if (!controller.getWorld().getBlockState(m.set(p)).isAir()) {
                scaffold++;
            }
        }
        if (scaffold > 0) {
            sb.append(sb.length() > 0 ? "; " : "").append(scaffold).append(" scaffold blocks sit just outside it");
        }
        if (mode == AreaScan.Mode.EXCAVATE) {
            sb.append(sb.length() > 0 ? "; " : "").append("area now ").append(box.corners());
        }
        return sb.toString();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof AreaBuildTask t && t.mode == mode && t.box.equals(box);
    }

    @Override
    protected String toDebugString() {
        return (mode == AreaScan.Mode.EXCAVATE ? "Excavate " : "Fill ") + box.corners();
    }

    /** Exactly the scanned cells, so nothing else in or around the box is the build's target. */
    private final class TargetSchematic implements ISchematic {
        @Override
        public boolean inSchematic(int x, int y, int z, BlockState current) {
            return targets.contains(BlockPos.asLong(box.minX() + x, box.minY() + y, box.minZ() + z));
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            if (mode == AreaScan.Mode.EXCAVATE) {
                return Blocks.AIR.defaultBlockState();
            }
            if (current.is(fillBlock)) {
                return current;
            }
            if (!current.isAir() && !current.canBeReplaced()) {
                return current;
            }
            for (BlockState placeable : approxPlaceable) {
                if (placeable.is(fillBlock)) {
                    return placeable;
                }
            }
            return fillBlock.defaultBlockState();
        }

        @Override
        public int widthX() {
            return box.sizeX();
        }

        @Override
        public int heightY() {
            return schematicHeight;
        }

        @Override
        public int lengthZ() {
            return box.sizeZ();
        }
    }

    /** The per-entity settings the run changes, restored on every exit. */
    private static final class SavedSettings {
        final boolean allowBreak;
        final boolean allowPlace;
        final boolean buildInLayers;
        final boolean layerOrder;
        final List<Item> throwaways;

        SavedSettings(Settings s) {
            allowBreak = s.allowBreak.get();
            allowPlace = s.allowPlace.get();
            buildInLayers = s.buildInLayers.get();
            layerOrder = s.layerOrder.get();
            throwaways = new java.util.ArrayList<>(s.acceptableThrowawayItems.get());
        }

        void restore(Settings s) {
            s.allowBreak.set(allowBreak);
            s.allowPlace.set(allowPlace);
            s.buildInLayers.set(buildInLayers);
            s.layerOrder.set(layerOrder);
            s.acceptableThrowawayItems.set(throwaways);
        }
    }

    /** True when {@code stack} would place {@code block}. */
    static boolean places(ItemStack stack, Block block) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() == block;
    }
}
