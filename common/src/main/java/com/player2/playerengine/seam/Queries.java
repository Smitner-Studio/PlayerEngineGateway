package com.player2.playerengine.seam;

import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block queries bound at the seam: {@code block_at} and {@code find_blocks}. Both read under the
 * {@link ReadBudget}, skip chunks that are not loaded rather than load them, and report only exposed
 * blocks (§5.6).
 */
public final class Queries {
    /** The most one cell can cost: its own read plus an exposure check. */
    static final int WORST_CELL = 1 + SeamPerception.EXPOSURE_READS;

    private Queries() {
    }

    /** A registered block by its canonical id ({@link Coercion#id}), or null. */
    public static Block block(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
    }

    static String idOf(BlockState s) {
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        return "minecraft".equals(rl.getNamespace()) ? rl.getPath() : rl.toString();
    }

    private static List<Integer> list(AreaSpec.Pos p) {
        return List.of(p.x(), p.y(), p.z());
    }

    /** {@code block_at(p)}: the block id, {@link SeamPerception#HIDDEN}, or {@code not_loaded}. */
    public static QueryQueue.Query blockAt(AreaSpec.Pos p) {
        return (world, budget, tick) -> {
            if (!world.isLoaded(p.x() >> 4, p.z() >> 4)) {
                return Outcome.failed(new ActionError(FailureCode.NOT_LOADED,
                        "that block is in ground no one has loaded; go closer first", Map.of("pos", list(p))), null);
            }
            if (budget.available(tick) < WORST_CELL) {
                return null;
            }
            budget.charge(tick, WORST_CELL);
            BlockState s = world.state(p.x(), p.y(), p.z());
            boolean seen = s.isAir() || SeamPerception.exposed(world, new BlockPos(p.x(), p.y(), p.z()));
            return Outcome.ok(seen ? idOf(s) : SeamPerception.HIDDEN, null);
        };
    }

    /** {@code find_blocks(block, radius, max)} around {@code origin}; nearest first. */
    public static QueryQueue.Query findBlocks(AreaSpec.Pos origin, String blockId, int radius, int max) {
        Block block = block(blockId);
        if (block == null) {
            return (w, b, t) -> Outcome.failed(ActionError.of(FailureCode.BAD_ARGS, "'" + blockId + "' is not a block id"),
                    null);
        }
        return new FindBlocks(origin, s -> s.is(block), radius, max);
    }

    /** Scans a cube in slices; resumable at any cell, so a tick's reads stop at the budget. */
    static final class FindBlocks implements QueryQueue.Query {
        private final AreaSpec.Pos origin;
        private final Predicate<BlockState> wanted;
        private final int radius;
        private final int max;
        private final int side;
        private final long cells;
        private final Map<Long, Boolean> loaded = new HashMap<>();
        private final List<BlockPos> found = new ArrayList<>();
        private long next;
        private long scanned;
        private long skippedUnloaded;

        FindBlocks(AreaSpec.Pos origin, Predicate<BlockState> wanted, int radius, int max) {
            this.origin = origin;
            this.wanted = wanted;
            this.radius = radius;
            this.max = max;
            this.side = 2 * radius + 1;
            this.cells = (long) side * side * side;
        }

        @Override
        public Outcome step(WorldReader world, ReadBudget budget, long tick) {
            int spent = 0;
            int available = budget.available(tick);
            while (next < cells && available - spent >= WORST_CELL) {
                long i = next++;
                int dy = (int) (i % side) - radius;
                int dz = (int) ((i / side) % side) - radius;
                int dx = (int) (i / ((long) side * side)) - radius;
                int x = origin.x() + dx;
                int y = origin.y() + dy;
                int z = origin.z() + dz;
                if (y < world.minY() || y >= world.maxY()) {
                    continue;
                }
                if (!loaded.computeIfAbsent(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL),
                        k -> world.isLoaded(x >> 4, z >> 4))) {
                    skippedUnloaded++;
                    continue;
                }
                spent++;
                scanned++;
                BlockState s = world.state(x, y, z);
                if (!wanted.test(s)) {
                    continue;
                }
                spent += SeamPerception.EXPOSURE_READS;
                BlockPos p = new BlockPos(x, y, z);
                if (SeamPerception.exposed(world, p)) {
                    found.add(p);
                }
            }
            budget.charge(tick, spent);
            if (next < cells) {
                return null;
            }
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("radius", radius);
            state.put("scanned", scanned);
            if (skippedUnloaded > 0) {
                state.put("skipped_unloaded", skippedUnloaded);
            }
            if (scanned == 0 && skippedUnloaded > 0) {
                return Outcome.failed(new ActionError(FailureCode.NOT_LOADED,
                        "none of that ground is loaded; go closer first", state), null);
            }
            BlockPos o = new BlockPos(origin.x(), origin.y(), origin.z());
            found.sort(Comparator.comparingDouble(p -> p.distSqr(o)));
            List<AreaSpec.Pos> out = new ArrayList<>();
            for (BlockPos p : found.subList(0, Math.min(max, found.size()))) {
                out.add(new AreaSpec.Pos(p.getX(), p.getY(), p.getZ()));
            }
            return Outcome.ok(out, null);
        }
    }
}
