package com.player2.playerengine.seam;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a query may read. {@link #isLoaded} must never load a chunk, and {@link #state} is called
 * only for positions in a chunk it reported loaded, so no query can cost a synchronous chunk load.
 */
public interface WorldReader {
    boolean isLoaded(int chunkX, int chunkZ);

    BlockState state(int x, int y, int z);

    /** The lowest buildable y. */
    int minY();

    /** One above the highest buildable y. */
    int maxY();

    static WorldReader of(ServerLevel level) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return new WorldReader() {
            @Override
            public boolean isLoaded(int chunkX, int chunkZ) {
                // hasChunk is the non-loading check (ContainerResolver's precedent); getChunk would load.
                return level.getChunkSource().hasChunk(chunkX, chunkZ);
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return level.getBlockState(m.set(x, y, z));
            }

            @Override
            public int minY() {
                return level.getMinBuildHeight();
            }

            @Override
            public int maxY() {
                return level.getMaxBuildHeight();
            }
        };
    }
}
