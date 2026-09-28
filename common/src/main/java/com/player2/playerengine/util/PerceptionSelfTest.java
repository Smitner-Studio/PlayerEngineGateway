package com.player2.playerengine.util;

import com.player2.playerengine.player2api.status.StatusUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * F1: a companion targets and reports only what a player standing there could perceive. Runs over
 * a map-backed world (solid stone unless set), through the same functions the scanner and the
 * status lines call. Needs the Minecraft bootstrap, so it runs inside {@code companionSelfTest}.
 */
public final class PerceptionSelfTest {
    private static int checks;

    private PerceptionSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        buriedOreIsNeverACandidate();
        buriedOreIsNeverReported();
        hostilesOnlyInLineOfSight();
        compassAndDistanceAreRough();
        return checks;
    }

    /** Solid stone everywhere except the cells set. */
    private static final class MapWorld implements Perception.BlockLookup {
        final Map<BlockPos, BlockState> cells = new HashMap<>();

        MapWorld set(BlockPos p, BlockState s) {
            cells.put(p.immutable(), s);
            return this;
        }

        MapWorld air(BlockPos p) {
            return set(p, Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockState at(BlockPos pos) {
            return cells.getOrDefault(pos, Blocks.STONE.defaultBlockState());
        }
    }

    private static void buriedOreIsNeverACandidate() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos buried = new BlockPos(2, 64, 0);
        BlockPos exposed = new BlockPos(6, 64, 0);
        MapWorld world = new MapWorld()
                .set(buried, Blocks.DIAMOND_ORE.defaultBlockState())
                .set(exposed, Blocks.DIAMOND_ORE.defaultBlockState())
                .air(exposed.above());
        Predicate<BlockPos> isDiamond = p -> world.at(p).is(Blocks.DIAMOND_ORE);
        BlockPos picked = Perception.nearestExposed(List.of(buried, exposed), world, isDiamond,
                p -> p.distSqr(from) * 1.0);
        require(exposed.equals(picked), "the nearer enclosed diamond is skipped for the exposed one (picked " + picked + ")");
        require(Perception.nearestExposed(List.of(buried), world, isDiamond, p -> p.distSqr(from) * 1.0) == null,
                "an enclosed diamond alone gives no candidate");
        // A fluid, a non-full block and a see-through block each expose the face they touch.
        for (BlockState open : List.of(Blocks.WATER.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState(),
                Blocks.GLASS.defaultBlockState())) {
            MapWorld w = new MapWorld().set(buried, Blocks.DIAMOND_ORE.defaultBlockState()).set(buried.below(), open);
            require(Perception.isExposed(w, buried), "a face on " + open.getBlock() + " is exposed");
        }
    }

    private static void buriedOreIsNeverReported() {
        BlockPos centre = new BlockPos(0, 64, 0);
        MapWorld world = new MapWorld();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-1, 0, -1), centre.offset(1, 1, 1))) {
            world.air(p);
        }
        world.set(centre.offset(0, 0, 4), Blocks.DIAMOND_ORE.defaultBlockState());
        world.set(centre.offset(2, 0, 0), Blocks.IRON_ORE.defaultBlockState());
        Map<String, Integer> counts = StatusUtils.countExposedBlocks(world, centre, 5);
        require(!counts.containsKey("diamond_ore"), "an enclosed diamond is not in nearby blocks: " + counts);
        require(counts.getOrDefault("iron_ore", 0) == 1, "an iron ore on the air pocket's wall is: " + counts);
        require(!counts.containsKey("air"), "air is not listed");
    }

    private record Mob(String kind, Vec3 at) {
    }

    private static void hostilesOnlyInLineOfSight() {
        Vec3 eye = new Vec3(0.5, 65.6, 0.5);
        Mob behindWall = new Mob("zombie", new Vec3(12.5, 65, 0.5));
        Mob inTheOpen = new Mob("skeleton", new Vec3(-9.5, 65, 0.5));
        Mob tooFar = new Mob("creeper", new Vec3(-40.5, 65, 0.5));
        // The occluder: a wall filling the plane x = 5.
        Predicate<Mob> sight = m -> !(Math.min(eye.x, m.at().x) < 5 && Math.max(eye.x, m.at().x) > 5);
        List<String> seen = Perception.describeHostiles(eye, List.of(behindWall, inTheOpen, tooFar), Mob::kind,
                Mob::at, sight);
        require(seen.size() == 1 && seen.get(0).startsWith("skeleton"), "only the mob in sight is listed: " + seen);
        require(!seen.get(0).matches(".*-?\\d+\\.\\d+.*") && !seen.get(0).contains(" at "),
                "no coordinates in a hostile line: " + seen.get(0));
        require(seen.get(0).equals("skeleton about 10 blocks west"), "kind, rough distance, direction: " + seen.get(0));
    }

    private static void compassAndDistanceAreRough() {
        require(Perception.compass(0, -10).equals("north"), "-z is north");
        require(Perception.compass(10, 0).equals("east"), "+x is east");
        require(Perception.compass(7, 7).equals("south-east"), "+x+z is south-east");
        require(Perception.roughDistance(12.4).equals("about 10 blocks"), "12.4 reads as about 10");
        require(Perception.roughDistance(2).equals("within 5 blocks"), "close reads as within 5");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("perception self-test failed: " + message);
        }
    }
}
