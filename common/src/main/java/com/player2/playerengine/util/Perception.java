package com.player2.playerengine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a survival player standing where the companion stands could perceive (design §5.6, F1).
 * Target choice and status lines go through here so a companion never mines toward, or reports,
 * ore it could only know about by reading the world's block data.
 */
public final class Perception {
    /** Status lines list hostiles within this many blocks, as §5.6 fixes it. */
    public static final int HOSTILE_RADIUS = 32;

    private Perception() {
    }

    /** The world's blocks, as a function, so the rules are testable over a map. */
    @FunctionalInterface
    public interface BlockLookup {
        BlockState at(BlockPos pos);
    }

    /**
     * A loaded level's blocks. A neighbour in an unloaded chunk reads as stone: loading it would
     * cost a synchronous chunk load, and an unknown face must not make a block count as seen.
     */
    public static BlockLookup of(Level level) {
        return pos -> level.isLoaded(pos) ? level.getBlockState(pos) : Blocks.STONE.defaultBlockState();
    }

    /** At least one face touches air, fluid, or a block that is not a full opaque cube. */
    public static boolean isExposed(BlockLookup world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (seesThrough(world.at(pos.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    static boolean seesThrough(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || !s.canOcclude()
                || !s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /**
     * The nearest exposed candidate that passes {@code valid}, by {@code distance}, or null.
     *
     * @param distance smaller is nearer; the scanner passes Baritone's generic heuristic
     */
    public static BlockPos nearestExposed(Iterable<BlockPos> candidates, BlockLookup world,
            Predicate<BlockPos> valid, Function<BlockPos, Double> distance) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos p : candidates) {
            if (!valid.test(p) || !isExposed(world, p)) {
                continue;
            }
            double d = distance.apply(p);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * One line per hostile the companion can see: kind, rough distance and compass direction, never
     * coordinates. {@code lineOfSight} decides what is seen; production passes
     * {@code LivingEntity::hasLineOfSight} from the companion.
     */
    public static <T> List<String> describeHostiles(Vec3 from, List<T> mobs, Function<T, String> kind,
            Function<T, Vec3> position, Predicate<T> lineOfSight) {
        List<String> seen = new ArrayList<>();
        for (T mob : mobs) {
            Vec3 at = position.apply(mob);
            double d = at.distanceTo(from);
            if (d >= HOSTILE_RADIUS || !lineOfSight.test(mob)) {
                continue;
            }
            seen.add(kind.apply(mob) + " " + roughDistance(d) + " " + compass(at.x - from.x, at.z - from.z));
        }
        return seen;
    }

    /** Rounded to 5 blocks, so a status line cannot be read back as a position. */
    static String roughDistance(double d) {
        if (d < 5) {
            return "within 5 blocks";
        }
        return "about " + Math.round(d / 5.0) * 5 + " blocks";
    }

    /** One of the eight compass points; north is -z and east is +x, as the F3 screen names them. */
    static String compass(double dx, double dz) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
            return "here";
        }
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int octant = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
        return names[octant];
    }
}
