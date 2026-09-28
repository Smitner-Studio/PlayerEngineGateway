package com.player2.playerengine.structureprotection;

import net.minecraft.core.BlockPos;

/** The shared protection rule over a real store: what excavate, fill and mine all refuse to break. */
public final class PlayerPlacedProtectionSelfTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static int checks;

    private PlayerPlacedProtectionSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.forSelfTest();
        BlockPos plank = new BlockPos(100, 64, -40);
        BlockPos loose = new BlockPos(101, 64, -40);
        BlockPos farChunk = new BlockPos(-300, 64, 900);

        require(!PlayerPlacedBlockStore.protects(true, store, OVERWORLD, plank), "an empty store protects nothing");
        store.add(OVERWORLD, plank);
        store.add(OVERWORLD, farChunk);
        require(PlayerPlacedBlockStore.protects(true, store, OVERWORLD, plank), "a player-placed block is protected");
        require(PlayerPlacedBlockStore.protects(true, store, OVERWORLD, new BlockPos(100, 64, -40)),
                "protection is by position, not by the BlockPos instance");
        require(PlayerPlacedBlockStore.protects(true, store, OVERWORLD, farChunk),
                "a second chunk bucket is protected too");
        require(!PlayerPlacedBlockStore.protects(true, store, OVERWORLD, loose),
                "the natural block beside it stays minable");
        require(!PlayerPlacedBlockStore.protects(true, store, NETHER, plank),
                "the same coordinates in another dimension are not protected");
        require(!PlayerPlacedBlockStore.protects(false, store, OVERWORLD, plank),
                "respectStructuresEnabled=false turns protection off");
        require(!PlayerPlacedBlockStore.protects(true, null, OVERWORLD, plank),
                "no loaded store protects nothing");

        store.add(OVERWORLD, plank);
        require(store.size() == 2, "adding a tracked position twice does not double-count it");
        require(store.remove(OVERWORLD, plank), "a player's break purges the position");
        require(!PlayerPlacedBlockStore.protects(true, store, OVERWORLD, plank),
                "a block placed where a purged one stood is natural");
        require(!store.remove(OVERWORLD, plank), "a second purge finds nothing");
        return checks;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("player-placed protection self-test failed: " + message);
        }
    }
}
