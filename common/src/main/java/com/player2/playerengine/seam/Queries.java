package com.player2.playerengine.seam;

import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The queries bound at the seam (§5.3). The block queries ({@code block_at}, {@code find_blocks}) and
 * {@code containers} read under the {@link ReadBudget}, skip chunks that are not loaded rather than
 * load them, and report only what a player standing there could perceive (§5.6). The rest read the
 * companion, its owner or a known container, which costs no block reads, but still wait their turn
 * in the queue so a program yields on every query (§6.2).
 */
public final class Queries {
    /** The most one cell can cost: its own read plus an exposure check. */
    static final int WORST_CELL = 1 + SeamPerception.EXPOSURE_READS;

    private Queries() {
    }

    /**
     * The query for a bound query signature over coerced arguments.
     *
     * @param world what the query reads besides block states: the companion, its owner, containers
     */
    public static QueryQueue.Query build(Signature sig, Map<String, Object> args, Primitive.World world) {
        return switch (sig.name()) {
            case "inventory" -> now(() -> Outcome.ok(world.inventory(), null));
            case "count" -> now(() -> Outcome.ok(world.inventory().getOrDefault((String) args.get("item"), 0), null));
            case "position" -> now(() -> Outcome.ok(floor(world.position()), null));
            case "owner_pos" -> now(() -> {
                Vec3 o = world.ownerPosition();
                return o == null ? ownerAway() : Outcome.ok(floor(o), null);
            });
            case "owner_facing" -> now(() -> {
                AreaSpec.Facing f = world.ownerFacing();
                return f == null ? ownerAway() : Outcome.ok(f, null);
            });
            case "last_area" -> now(() -> Outcome.ok(world.lastArea(), null));
            case "light_at" -> lightAt((AreaSpec.Pos) args.get("p"), world);
            case "block_at" -> blockAt((AreaSpec.Pos) args.get("p"));
            case "find_blocks" -> findBlocks(floor(world.position()), (String) args.get("block"),
                    (Integer) args.get("radius"), (Integer) args.get("max"));
            case "containers" -> new Containers(world, floor(world.position()), (Integer) args.get("radius"));
            case "contents" -> contents((ContainerHandle) args.get("c"), world);
            default -> throw new IllegalArgumentException(sig.name() + " is not a bound query");
        };
    }

    /** A query that needs no block reads: it answers the first time it is served. */
    private static QueryQueue.Query now(Supplier<Outcome> answer) {
        return (w, b, t) -> answer.get();
    }

    private static Outcome ownerAway() {
        return Outcome.failed(ActionError.of(FailureCode.NOT_FOUND, "my owner is not here"), null);
    }

    static AreaSpec.Pos floor(Vec3 v) {
        return new AreaSpec.Pos((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
    }

    /** {@code light_at(p)}: one read, and {@code not_loaded} rather than a load. */
    static QueryQueue.Query lightAt(AreaSpec.Pos p, Primitive.World world) {
        return (w, budget, tick) -> {
            if (!w.isLoaded(p.x() >> 4, p.z() >> 4)) {
                return Outcome.failed(new ActionError(FailureCode.NOT_LOADED,
                        "that block is in ground no one has loaded; go closer first", Map.of("pos", list(p))), null);
            }
            if (budget.available(tick) < 1) {
                return null;
            }
            budget.charge(tick, 1);
            return Outcome.ok(world.light(p.x(), p.y(), p.z()), null);
        };
    }

    /**
     * {@code contents(c)}: what a known container holds (§5.6). A container the companion has not
     * opened is {@code denied}; {@code store} and {@code withdraw} open it.
     */
    static QueryQueue.Query contents(ContainerHandle c, Primitive.World world) {
        return (w, budget, tick) -> {
            if (budget.available(tick) < 1) {
                return null;
            }
            budget.charge(tick, 1);
            ContainerHandle.Contents seen;
            try {
                seen = world.container(c.pos());
            } catch (Coercion.Failure f) {
                return Outcome.failed(f.error, null);
            }
            if (!world.known(seen.handle())) {
                return Outcome.failed(new ActionError(FailureCode.DENIED, "I have not opened that container, so I "
                        + "don't know what is in it; storing or taking something opens it",
                        Map.of("container", seen.handle().id())), null);
            }
            return Outcome.ok(seen.items(), null);
        };
    }

    /**
     * {@code containers(radius)}: one handle per whole container, nearest first, for the containers the
     * companion knows or can see: exposed, and in line of sight within {@link SeamPerception#MAX_RAYCASTS}
     * raycasts. Each candidate costs the exposure reads of both halves plus one, and the scan resumes
     * next tick where the budget stopped it.
     */
    static final class Containers implements QueryQueue.Query {
        static final int COST = 1 + 2 * SeamPerception.EXPOSURE_READS;

        private final Primitive.World world;
        private final AreaSpec.Pos origin;
        private final int radius;
        private final SeamPerception.Raycasts raycasts = new SeamPerception.Raycasts();
        private final Map<String, ContainerHandle> seen = new LinkedHashMap<>();
        private final List<ContainerHandle> visible = new ArrayList<>();
        private List<AreaSpec.Pos> candidates;
        private int next;

        Containers(Primitive.World world, AreaSpec.Pos origin, int radius) {
            this.world = world;
            this.origin = origin;
            this.radius = radius;
        }

        @Override
        public Outcome step(WorldReader reader, ReadBudget budget, long tick) {
            if (candidates == null) {
                candidates = world.containerBlocks(origin, radius);
            }
            while (next < candidates.size()) {
                if (budget.available(tick) < COST) {
                    return null;
                }
                budget.charge(tick, COST);
                AreaSpec.Pos p = candidates.get(next++);
                ContainerHandle h;
                try {
                    h = world.container(p).handle();
                } catch (Coercion.Failure f) {
                    continue;
                }
                if (seen.putIfAbsent(h.id(), h) != null) {
                    continue;
                }
                boolean exposed = false;
                for (AreaSpec.Pos half : h.halves()) {
                    exposed |= world.exposed(half.x(), half.y(), half.z());
                }
                if (SeamPerception.containerVisible(world.known(h), exposed, () -> world.lineOfSight(h), raycasts)) {
                    visible.add(h);
                }
            }
            visible.sort(Comparator.comparingLong(h -> {
                long dx = h.pos().x() - origin.x();
                long dy = h.pos().y() - origin.y();
                long dz = h.pos().z() - origin.z();
                return dx * dx + dy * dy + dz * dz;
            }));
            return Outcome.ok(List.copyOf(visible), null);
        }
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
