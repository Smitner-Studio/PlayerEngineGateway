package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.commands.AreaCommand;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.containeraccess.StorageLocator;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The world a companion's calls read, live. Every read here is non-loading: a chunk is read only
 * after {@code hasChunk} says it is there, as {@link WorldReader} does.
 */
public final class LiveWorld implements Primitive.World {
    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private final PlayerEngineController mod;
    private final Seam seam;
    private final ServerLevel level;
    private AreaScan.BlockLookup cells;

    LiveWorld(PlayerEngineController mod, Seam seam) {
        this.mod = mod;
        this.seam = seam;
        this.level = mod.getWorld();
    }

    /** The canonical id of an item: the registry id, without {@code minecraft:}. */
    public static String itemId(Item item) {
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
        return "minecraft".equals(rl.getNamespace()) ? rl.getPath() : rl.toString();
    }

    /** The registered item for a canonical id, or null. */
    public static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    static void add(Map<String, Integer> into, ItemStack s) {
        if (!s.isEmpty()) {
            into.merge(itemId(s.getItem()), s.getCount(), Integer::sum);
        }
    }

    private boolean loaded(int x, int z) {
        return level != null && level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    @Override
    public Vec3 position() {
        return mod.getPlayer().position();
    }

    @Override
    public AreaScan.Cell cell(int x, int y, int z) {
        if (cells == null) {
            cells = AreaCommand.lookup(mod, level, null);
        }
        return cells.cell(x, y, z);
    }

    @Override
    public String blockAt(int x, int y, int z) {
        return loaded(x, z) ? Queries.idOf(level.getBlockState(new BlockPos(x, y, z))) : null;
    }

    @Override
    public int light(int x, int y, int z) {
        return loaded(x, z) ? level.getMaxLocalRawBrightness(new BlockPos(x, y, z)) : -1;
    }

    @Override
    public Map<String, Integer> inventory() {
        Map<String, Integer> out = new TreeMap<>();
        LivingEntityInventory inv = mod.getInventory();
        for (ItemStack s : inv.main) {
            add(out, s);
        }
        for (ItemStack s : inv.offHand) {
            add(out, s);
        }
        for (ItemStack s : inv.armor) {
            add(out, s);
        }
        return out;
    }

    @Override
    public int freeSlots() {
        int free = 0;
        for (ItemStack s : mod.getInventory().main) {
            if (s.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    @Override
    public List<String> equipped() {
        List<String> out = new ArrayList<>();
        ItemStack hand = mod.getPlayer().getMainHandItem();
        if (!hand.isEmpty()) {
            out.add(itemId(hand.getItem()));
        }
        for (EquipmentSlot slot : WORN) {
            ItemStack s = mod.getPlayer().getItemBySlot(slot);
            if (!s.isEmpty()) {
                out.add(itemId(s.getItem()));
            }
        }
        return out;
    }

    private Player owner() {
        Player o = mod.getOwner();
        return o != null && o.isAlive() && !o.isRemoved() && o.level() == level ? o : null;
    }

    @Override
    public Vec3 ownerPosition() {
        Player o = owner();
        return o == null ? null : o.position();
    }

    @Override
    public AreaSpec.Facing ownerFacing() {
        Player o = owner();
        return o == null ? null : AreaSpec.Facing.fromYaw(o.getYRot());
    }

    @Override
    public Map<String, Integer> ownerInventory() {
        Player o = owner();
        if (o == null) {
            return null;
        }
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
            add(out, o.getInventory().getItem(i));
        }
        return out;
    }

    @Override
    public Map<String, Integer> groundItems(Vec3 centre, double radius) {
        Map<String, Integer> out = new TreeMap<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(centre, centre).inflate(radius),
                e -> e.isAlive() && e.position().distanceTo(centre) <= radius)) {
            add(out, e.getItem());
        }
        return out;
    }

    @Override
    public ContainerHandle.Contents container(AreaSpec.Pos p) throws Coercion.Failure {
        if (!loaded(p.x(), p.z())) {
            throw new Coercion.Failure(new ActionError(FailureCode.NOT_LOADED, "that container is in ground no one has "
                    + "loaded; go closer first", Map.of("pos", List.of(p.x(), p.y(), p.z()))));
        }
        ContainerResolver.Resolution r = ContainerResolver.resolve(level, new BlockPos(p.x(), p.y(), p.z()));
        if (!r.ok()) {
            FailureCode code = r.code() == StorageAccessCode.CONTAINER_UNREACHABLE ? FailureCode.NOT_LOADED
                    : r.code() == StorageAccessCode.CONTAINER_UNSUPPORTED ? FailureCode.DENIED : FailureCode.NO_CONTAINER;
            throw new Coercion.Failure(new ActionError(code, r.detail(), Map.of("pos", List.of(p.x(), p.y(), p.z()))));
        }
        BlockPos c = r.resolved().canonicalPos();
        BlockPos s = r.resolved().secondaryPos();
        ContainerHandle h = new ContainerHandle(new AreaSpec.Pos(c.getX(), c.getY(), c.getZ()),
                s == null ? null : new AreaSpec.Pos(s.getX(), s.getY(), s.getZ()), true);
        Container box = r.resolved().container();
        Map<String, Integer> items = new TreeMap<>();
        int free = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack st = box.getItem(i);
            if (st.isEmpty()) {
                free++;
            } else {
                add(items, st);
            }
        }
        return new ContainerHandle.Contents(h, items, free);
    }

    @Override
    public ContainerHandle nearestContainer(int radius) {
        return StorageLocator.findNearestContainer(mod, position(), radius).map(located -> {
            BlockPos b = located.canonicalPos();
            try {
                return container(new AreaSpec.Pos(b.getX(), b.getY(), b.getZ())).handle();
            } catch (Coercion.Failure f) {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public List<AreaSpec.Pos> containerBlocks(AreaSpec.Pos centre, int radius) {
        List<AreaSpec.Pos> out = new ArrayList<>();
        for (int cx = (centre.x() - radius) >> 4; cx <= (centre.x() + radius) >> 4; cx++) {
            for (int cz = (centre.z() - radius) >> 4; cz <= (centre.z() + radius) >> 4; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos b = be.getBlockPos();
                    if (!(be instanceof Container) || be.getBlockState().getBlock() instanceof EnderChestBlock
                            || Math.abs(b.getX() - centre.x()) > radius || Math.abs(b.getY() - centre.y()) > radius
                            || Math.abs(b.getZ() - centre.z()) > radius) {
                        continue;
                    }
                    out.add(new AreaSpec.Pos(b.getX(), b.getY(), b.getZ()));
                }
            }
        }
        return out;
    }

    @Override
    public boolean lineOfSight(ContainerHandle h) {
        Vec3 eyes = mod.getPlayer().getEyePosition();
        for (AreaSpec.Pos p : h.halves()) {
            BlockPos b = new BlockPos(p.x(), p.y(), p.z());
            BlockHitResult hit = level.clip(new ClipContext(eyes, Vec3.atCenterOf(b), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, mod.getPlayer()));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(b)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean known(ContainerHandle h) {
        return seam.isKnown(h);
    }

    @Override
    public boolean exposed(int x, int y, int z) {
        return SeamPerception.exposed(WorldReader.of(level), new BlockPos(x, y, z));
    }

    @Override
    public AreaSpec.Box lastArea() {
        return mod.getLastArea();
    }
}
