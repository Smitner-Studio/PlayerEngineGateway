package com.player2.playerengine.seam;

import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;

/**
 * A world the seam self-tests set up by hand: blocks by position (air where unset), the companion's
 * inventory, its owner, items on the ground and containers. Mutable, so a test can take a snapshot,
 * change the world as the Task would (or would not), and check the postcondition.
 */
public final class SeamTestWorld implements Primitive.World {
    public Vec3 position = Vec3.ZERO;
    public final Map<AreaSpec.Pos, String> blocks = new HashMap<>();
    public final Set<AreaSpec.Pos> playerPlaced = new HashSet<>();
    public final Set<AreaSpec.Pos> hidden = new HashSet<>();
    public final Map<AreaSpec.Pos, Integer> lightLevels = new HashMap<>();
    public final Set<Integer> unloadedChunkX = new HashSet<>();
    public final Map<String, Integer> inventory = new TreeMap<>();
    public int freeSlots = 20;
    public final List<String> equipped = new ArrayList<>();
    public Vec3 owner;
    public AreaSpec.Facing ownerFacing = AreaSpec.Facing.NORTH;
    public final Map<String, Integer> ownerInventory = new TreeMap<>();
    public final List<Ground> ground = new ArrayList<>();
    /** Containers by handle; {@link #container} finds one by either half. */
    public final Map<ContainerHandle, Map<String, Integer>> containers = new HashMap<>();
    public final Map<ContainerHandle, Integer> containerFree = new HashMap<>();
    public final Set<ContainerHandle> known = new HashSet<>();
    public final Set<ContainerHandle> inSight = new HashSet<>();
    public AreaSpec.Box lastArea;
    public int raycasts;
    public long gameTime;
    public WorldReader reader;
    public Seam.Spoken spoken = new Seam.Spoken(List.of(), 0);
    public Seam.Confirmation confirmation;
    public final Map<String, List<String>> drops = new HashMap<>();
    /** What smelting an item makes, by input id. */
    public final Map<String, String> smelts = new HashMap<>();

    /** An item entity: where it lies, what and how many. */
    public record Ground(Vec3 at, String item, int count) {
    }

    public SeamTestWorld set(int x, int y, int z, String block) {
        blocks.put(new AreaSpec.Pos(x, y, z), block);
        return this;
    }

    public SeamTestWorld carry(String item, int n) {
        inventory.merge(item, n, Integer::sum);
        inventory.values().removeIf(v -> v <= 0);
        return this;
    }

    public ContainerHandle chest(AreaSpec.Pos pos, AreaSpec.Pos secondary, int free) {
        ContainerHandle h = new ContainerHandle(pos, secondary, true);
        containers.put(h, new TreeMap<>());
        containerFree.put(h, free);
        return h;
    }

    /** Moves n of item from the inventory into the container, as a deposit that worked would. */
    public void move(ContainerHandle h, String item, int n) {
        carry(item, -n);
        containers.get(h).merge(item, n, Integer::sum);
        containers.get(h).values().removeIf(v -> v <= 0);
    }

    private boolean loaded(int x) {
        return !unloadedChunkX.contains(x >> 4);
    }

    @Override
    public Vec3 position() {
        return position;
    }

    @Override
    public AreaScan.Cell cell(int x, int y, int z) {
        if (!loaded(x)) {
            return new AreaScan.Cell(false, false, null, false, false, false, false, false, false, 0, false, false, "");
        }
        String b = blocks.getOrDefault(new AreaSpec.Pos(x, y, z), "air");
        boolean air = b.equals("air");
        String liquid = b.equals("water") || b.equals("lava") ? b : null;
        return new AreaScan.Cell(true, air, liquid, air || liquid != null || b.equals("short_grass"), false, false,
                playerPlaced.contains(new AreaSpec.Pos(x, y, z)), false, false, 10, false, false, b);
    }

    @Override
    public String blockAt(int x, int y, int z) {
        return loaded(x) ? blocks.getOrDefault(new AreaSpec.Pos(x, y, z), "air") : null;
    }

    @Override
    public int light(int x, int y, int z) {
        return loaded(x) ? lightLevels.getOrDefault(new AreaSpec.Pos(x, y, z), 15) : -1;
    }

    @Override
    public Map<String, Integer> inventory() {
        return new TreeMap<>(inventory);
    }

    @Override
    public int freeSlots() {
        return freeSlots;
    }

    @Override
    public List<String> equipped() {
        return List.copyOf(equipped);
    }

    @Override
    public Vec3 ownerPosition() {
        return owner;
    }

    @Override
    public AreaSpec.Facing ownerFacing() {
        return owner == null ? null : ownerFacing;
    }

    @Override
    public Map<String, Integer> ownerInventory() {
        return owner == null ? null : new TreeMap<>(ownerInventory);
    }

    @Override
    public Map<String, Integer> groundItems(Vec3 centre, double radius) {
        Map<String, Integer> out = new TreeMap<>();
        for (Ground g : ground) {
            if (g.at().distanceTo(centre) <= radius) {
                out.merge(g.item(), g.count(), Integer::sum);
            }
        }
        return out;
    }

    @Override
    public ContainerHandle.Contents container(AreaSpec.Pos p) throws Coercion.Failure {
        if (!loaded(p.x())) {
            throw Coercion.Failure.of(FailureCode.NOT_LOADED, "not loaded");
        }
        for (Map.Entry<ContainerHandle, Map<String, Integer>> e : containers.entrySet()) {
            if (e.getKey().covers(p)) {
                return new ContainerHandle.Contents(e.getKey(), e.getValue(), containerFree.getOrDefault(e.getKey(), 0));
            }
        }
        throw Coercion.Failure.of(FailureCode.NO_CONTAINER, "there is no container at " + p);
    }

    @Override
    public ContainerHandle nearestContainer(int radius) {
        ContainerHandle best = null;
        double bestD = Double.MAX_VALUE;
        for (ContainerHandle h : containers.keySet()) {
            double d = position.distanceTo(new Vec3(h.pos().x() + 0.5, h.pos().y() + 0.5, h.pos().z() + 0.5));
            if (d <= radius && d < bestD) {
                best = h;
                bestD = d;
            }
        }
        return best;
    }

    @Override
    public List<AreaSpec.Pos> containerBlocks(AreaSpec.Pos centre, int radius) {
        List<AreaSpec.Pos> out = new ArrayList<>();
        for (ContainerHandle h : containers.keySet()) {
            for (AreaSpec.Pos p : h.halves()) {
                if (loaded(p.x()) && Math.abs(p.x() - centre.x()) <= radius && Math.abs(p.y() - centre.y()) <= radius
                        && Math.abs(p.z() - centre.z()) <= radius) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    @Override
    public boolean lineOfSight(ContainerHandle h) {
        raycasts++;
        return inSight.contains(h);
    }

    @Override
    public boolean known(ContainerHandle h) {
        return known.contains(h);
    }

    @Override
    public boolean exposed(int x, int y, int z) {
        return !hidden.contains(new AreaSpec.Pos(x, y, z));
    }

    @Override
    public AreaSpec.Box lastArea() {
        return lastArea;
    }

    @Override
    public List<String> drops(String blockId) {
        return drops.getOrDefault(blockId, List.of(blockId));
    }

    @Override
    public String smeltResult(String itemId) {
        return smelts.get(itemId);
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public WorldReader reader() {
        if (reader == null) {
            throw new UnsupportedOperationException("this test world has no block reader");
        }
        return reader;
    }

    @Override
    public Seam.Spoken spoken() {
        return spoken;
    }

    @Override
    public Seam.Confirmation confirmation() {
        return confirmation;
    }
}
