package com.player2.playerengine.trackers;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.ChunkController;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public class ChunkLoadingTracker {
    private List<ChunkPos> chunks = new ArrayList<ChunkPos>();
    private int ticks = 20;
    private long playerLastSeen = -1;
    private LivingEntity entity;

    public ChunkLoadingTracker(PlayerEngineController controller) {
        this.entity = controller.getEntity();
    }

    public boolean tick() {
        ticks--;
        if(ticks > 0)
            return false;
        ticks = 20;

        List players = entity.level().getEntitiesOfClass(Player.class, entity.getBoundingBox().inflate(48, 48, 48));
        if(!players.isEmpty())
            playerLastSeen = System.currentTimeMillis();

//        if(playerLastSeen < 0){
//            return false;
//        }
//        //unload after 10 min
//        if(System.currentTimeMillis() > playerLastSeen + 600000){
//            ChunkController.instance.unload((ServerLevel) entity.level(), entity.getUUID(), entity.chunkPosition().x, entity.chunkPosition().z);
//            chunks.clear();
//            playerLastSeen = -1;
//            return false;
//        }
        List<ChunkPos> list = chunksToHold(entity.getX(), entity.getZ());

        for(ChunkPos chunk : list){
            if(!chunks.contains(chunk)){
                ChunkController.instance.load((ServerLevel) entity.level(), entity.getUUID(), chunk.x, chunk.z);
            }
            chunks.remove(chunk);
        }

        for(ChunkPos chunk : chunks){
            ChunkController.instance.unload((ServerLevel) entity.level(), entity.getUUID(), chunk.x, chunk.z);
        }

        this.chunks = list;
        return false;
    }

    /**
     * The companion's chunk and its eight neighbours. A forced chunk ticks entities only inside
     * itself, and this tracker runs from the companion's own tick: a step into a chunk it does not
     * hold would freeze it there for good, with nothing left to force that chunk.
     */
    static List<ChunkPos> chunksToHold(double blockX, double blockZ) {
        int cx = SectionPos.blockToSectionCoord(Mth.floor(blockX));
        int cz = SectionPos.blockToSectionCoord(Mth.floor(blockZ));
        List<ChunkPos> list = new ArrayList<ChunkPos>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                list.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        return list;
    }

    public void reset() {
        if(entity.level() instanceof ServerLevel){
            ChunkController.instance.unload((ServerLevel) entity.level(), entity.getUUID(), entity.chunkPosition().x, entity.chunkPosition().z);
            chunks.clear();
            playerLastSeen = 0;
        }
    }
}
