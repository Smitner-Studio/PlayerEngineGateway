package com.player2.playerengine.seam;

import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * An opaque handle for one whole container (§5.2). A double chest is one handle whichever half named
 * it, keyed by the half {@link ContainerResolver#canonicalHalves} makes canonical, so a deposit
 * reported against "the chest" can never land on the half the model did not mean (E8).
 *
 * @param pos       the canonical position; for an unresolved handle, the position the caller gave
 * @param secondary the other half of a double chest, or null
 * @param resolved  false for a position the caller passed where a container was expected
 */
public record ContainerHandle(AreaSpec.Pos pos, AreaSpec.Pos secondary, boolean resolved) {

    /** A position given where a container was expected, not yet checked against the world. */
    public static ContainerHandle at(AreaSpec.Pos pos) {
        return new ContainerHandle(pos, null, false);
    }

    /** The id the model sees and passes back; stable for the life of the container. */
    public String id() {
        return "container@" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    public boolean covers(AreaSpec.Pos p) {
        return p.equals(pos) || p.equals(secondary);
    }

    /**
     * The whole container at {@code p}, or {@code no_container}.
     *
     * @param states      the world's block states; the caller has already checked the chunk is loaded
     * @param isContainer whether the block entity at a position holds items
     */
    public static ContainerHandle resolve(AreaSpec.Pos p, Function<BlockPos, BlockState> states,
            Predicate<BlockPos> isContainer) throws Coercion.Failure {
        BlockPos at = new BlockPos(p.x(), p.y(), p.z());
        BlockState state = states.apply(at);
        if (!isContainer.test(at)) {
            throw new Coercion.Failure(new ActionError(FailureCode.NO_CONTAINER,
                    "there is no container at " + p.x() + " " + p.y() + " " + p.z(),
                    java.util.Map.of("pos", java.util.List.of(p.x(), p.y(), p.z()))));
        }
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos[] halves = ContainerResolver.canonicalHalves(at, state);
            BlockPos other = halves[0].equals(at) ? halves[1] : halves[0];
            BlockState otherState = states.apply(other);
            // A half whose partner is gone (mid-break, or a world edit) is a single chest in effect.
            if (otherState.getBlock() instanceof ChestBlock
                    && otherState.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && otherState.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE)) {
                return new ContainerHandle(pos(halves[0]), pos(halves[1]), true);
            }
        }
        return new ContainerHandle(p, null, true);
    }

    private static AreaSpec.Pos pos(BlockPos b) {
        return new AreaSpec.Pos(b.getX(), b.getY(), b.getZ());
    }
}
