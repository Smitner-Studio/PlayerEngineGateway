package com.player2.playerengine.containeraccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Turns a {@code BlockPos} into a live, canonically-indexed container view (Part C4.5, WS1).
 *
 * <p><b>Double-chest canonicalization (Decision 2):</b> chests resolve through
 * {@code ChestBlock.getContainer(chest, state, level, pos, true)} — {@code override=true}
 * because the bot accesses the block entity directly with no GUI, so the vanilla cat/blocked
 * check must not apply (intentional: the bot can read a "blocked" chest a player could not
 * open). A double chest is one 54-slot {@code CompoundContainer}; slot indices 0-26 are the
 * DoubleBlockCombiner FIRST half ({@code ChestType.RIGHT}), 27-53 the second half. The
 * canonical position is ALWAYS the FIRST half's BlockPos regardless of which half the caller
 * named — this is the C5 catalogue key and the rule lives only here.
 *
 * <p>Also hosts the shared storage-session constants reused by the WS3/WS4 session tasks
 * (part of the frozen C4.5 contract).
 */
public final class ContainerResolver {

    /**
     * FIXED pre-navigation travel cap (blocks, Euclidean) for every storage-session command.
     * Same value and rationale as the existing
     * {@code StoreInAnyContainerTask.DEPOSIT_TRAVEL_CAP_BLOCKS} precedent (3/5 * 12 chunks *
     * 16 blocks = 115.2 -> 115): the bot must never trek to a container a player at default
     * view distance could not see. Beyond this -> {@code container_too_far}, no navigation.
     */
    public static final double STORAGE_TRAVEL_CAP_BLOCKS = 115.0;

    /**
     * Navigation phase timeout for every storage-session task. Expiry ->
     * {@code container_unreachable: could not reach (x,y,z) within 45s}.
     */
    public static final long NAVIGATE_TIMEOUT_MS = 45_000L;

    private ContainerResolver() {
    }

    /**
     * Typed resolve result: either a {@link ResolvedContainer} or a
     * {@link StorageAccessCode} + human detail. Never throws to the caller.
     */
    public record Resolution(ResolvedContainer resolved, StorageAccessCode code, String detail) {

        public boolean ok() {
            return this.code == StorageAccessCode.OK;
        }

        public static Resolution success(ResolvedContainer resolved) {
            return new Resolution(resolved, StorageAccessCode.OK, "");
        }

        public static Resolution failure(StorageAccessCode code, String detail) {
            return new Resolution(null, code, detail);
        }
    }

    /**
     * Resolves the live container at {@code pos}.
     *
     * <p>The chunk guard is the sanctioned non-loading check
     * ({@code ServerChunkCache.hasChunk} — see the Issue C doc on
     * {@code SimpleChunkTracker.isChunkLoaded}); never {@code Level.getChunk}, which
     * sync-loads on the server thread. In practice the bot's arrival has already loaded the
     * chunk, so this guard is defensive only.
     */
    public static Resolution resolve(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        if (!level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
            return Resolution.failure(
                    StorageAccessCode.CONTAINER_UNREACHABLE,
                    "chunk not loaded at " + formatPos(pos));
        }

        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof EnderChestBlock) {
            return Resolution.failure(
                    StorageAccessCode.CONTAINER_UNSUPPORTED,
                    "ender chest at " + formatPos(pos) + " is per-player storage and cannot be accessed");
        }

        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return resolveChest(level, pos, state, chestBlock);
        }

        if (level.getBlockEntity(pos) instanceof Container container) {
            ContainerKind kind = ContainerKind.GENERIC;
            if (state.getBlock() instanceof BarrelBlock) {
                kind = ContainerKind.BARREL;
            } else if (state.getBlock() instanceof ShulkerBoxBlock) {
                kind = ContainerKind.SHULKER_BOX;
            }
            return Resolution.success(
                    new ResolvedContainer(container, kind, pos, null, container.getContainerSize()));
        }

        return Resolution.failure(
                StorageAccessCode.CONTAINER_MISSING,
                "block at " + formatPos(pos) + " is "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ", not a container");
    }

    private static Resolution resolveChest(ServerLevel level, BlockPos pos, BlockState state, ChestBlock chestBlock) {
        // override=true: direct BE access, no GUI — the cat/blocked check must not apply.
        Container container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
        if (container == null) {
            // getContainer may return null even with override=true (block entity missing/invalid).
            return Resolution.failure(
                    StorageAccessCode.CONTAINER_MISSING,
                    "chest block at " + formatPos(pos) + " has no live block entity");
        }

        boolean trapped = state.getBlock() instanceof TrappedChestBlock;
        ChestType type = state.getValue(ChestBlock.TYPE);
        BlockPos canonicalPos = pos;
        BlockPos secondaryPos = null;
        ContainerKind kind = trapped ? ContainerKind.TRAPPED_CHEST : ContainerKind.CHEST;

        if (container instanceof CompoundContainer && type != ChestType.SINGLE) {
            BlockPos[] halves = canonicalHalves(pos, state);
            canonicalPos = halves[0];
            secondaryPos = halves[1];
            kind = trapped ? ContainerKind.DOUBLE_TRAPPED_CHEST : ContainerKind.DOUBLE_CHEST;
        }

        return Resolution.success(
                new ResolvedContainer(container, kind, canonicalPos, secondaryPos, container.getContainerSize()));
    }

    /**
     * The canonical rule for a double chest named by either half (single source, Decision 2):
     * {@code [first, second]}, where the DoubleBlockCombiner FIRST half is {@code ChestType.RIGHT}
     * and always provides slots 0-26 of the CompoundContainer. {@code state} is a non-single
     * chest's state at {@code pos}.
     */
    public static BlockPos[] canonicalHalves(BlockPos pos, BlockState state) {
        BlockPos otherHalf = pos.relative(ChestBlock.getConnectedDirection(state));
        return state.getValue(ChestBlock.TYPE) == ChestType.RIGHT
                ? new BlockPos[] {pos, otherHalf}
                : new BlockPos[] {otherHalf, pos};
    }

    /**
     * Canonical {@code (x,y,z)} position rendering shared by error details, log records, and
     * the {@link ScanReportFormatter} header/receipt grammar (single source so the formats can
     * never drift apart).
     */
    public static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }
}
