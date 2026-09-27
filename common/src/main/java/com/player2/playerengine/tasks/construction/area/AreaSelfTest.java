package com.player2.playerengine.tasks.construction.area;

import java.util.HashMap;
import java.util.Map;

/** Area-command bounds, layout, pre-scan and verdict checks over a map-backed world. */
public final class AreaSelfTest {
    private static int checks;

    private static final int STONE_WOODEN = AreaScan.breakTicks(1.5F, 2.0F, true);
    private static final int STONE_STONE_PICK = AreaScan.breakTicks(1.5F, 4.0F, true);

    private AreaSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        breakTicksMatchTheDesignArithmetic();
        boundsHold();
        relativeLayout();
        grammar();
        timeDerivedSizeCap();
        liquidsRefuseIncludingWaterlogged();
        fallingBlocksFoldOrRefuse();
        playerBlocksAreLeftAndGuarded();
        toolSlotsAndFillShortfall();
        verdictNeedsTheWorldToBeDone();
        return checks;
    }

    // ---- fake world ----

    static final class FakeWorld implements AreaScan.BlockLookup {
        final Map<Long, AreaScan.Cell> cells = new HashMap<>();
        final AreaScan.Cell fallback;

        FakeWorld(AreaScan.Cell fallback) {
            this.fallback = fallback;
        }

        void set(int x, int y, int z, AreaScan.Cell c) {
            cells.put(key(x, y, z), c);
        }

        @Override
        public AreaScan.Cell cell(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), fallback);
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFL) << 42 | ((long) y & 0xFFFFFL) << 22 | ((long) z & 0x3FFFFFL);
        }
    }

    static AreaScan.Cell stone(int ticks) {
        return new AreaScan.Cell(true, false, null, false, false, false, false, false, false, ticks, false, false, "stone");
    }

    static final AreaScan.Cell AIR = new AreaScan.Cell(true, true, null, true, false, false, false, false, false, 0, false, false, "air");

    static AreaScan.Cell waterloggedSlab() {
        return new AreaScan.Cell(true, false, "water", false, false, false, false, false, false, 10, false, false, "stone_slab");
    }

    static AreaScan.Cell gravel() {
        return new AreaScan.Cell(true, false, null, false, true, false, false, false, false, 9, false, false, "gravel");
    }

    static AreaScan.Cell placed() {
        return new AreaScan.Cell(true, false, null, false, false, false, true, false, false, 20, false, false, "oak_planks");
    }

    static AreaSpec.Box box(int x, int y, int z, int dx, int dy, int dz) {
        return new AreaSpec.Box(x, y, z, x + dx - 1, y + dy - 1, z + dz - 1, AreaSpec.Facing.NORTH);
    }

    // ---- tests ----

    private static void breakTicksMatchTheDesignArithmetic() {
        require(STONE_WOODEN == 23, "stone, wooden pickaxe: 23 ticks, got " + STONE_WOODEN);
        require(STONE_STONE_PICK == 12, "stone, stone pickaxe: 12 ticks, got " + STONE_STONE_PICK);
        require(AreaScan.breakTicks(1.5F, 6.0F, true) == 8, "stone, iron pickaxe: 8 ticks");
        require(AreaScan.breakTicks(3.0F, 4.0F, true) == 23, "deepslate, stone pickaxe: 23 ticks");
        require(AreaScan.breakTicks(0.0F, 1.0F, true) == 1, "instant block: 1 tick");
    }

    private static void boundsHold() {
        AreaSpec.Pos me = new AreaSpec.Pos(0, 64, 0);
        require(AreaSpec.checkBounds(box(-8, 60, -8, 16, 8, 16), AreaSpec.MAX_EXCAVATE_CELLS, me, -64, 320) == null,
                "16x8x16 is allowed");
        require(AreaSpec.checkBounds(box(-8, 60, -8, 17, 8, 16), AreaSpec.MAX_EXCAVATE_CELLS, me, -64, 320) != null,
                "17x8x16 is over the cell cap");
        require(AreaSpec.checkBounds(box(-8, 60, -8, 33, 1, 2), AreaSpec.MAX_EXCAVATE_CELLS, me, -64, 320) != null,
                "33 wide is over the axis cap");
        require(AreaSpec.checkBounds(box(-2, 60, -2, 4, 9, 4), AreaSpec.MAX_EXCAVATE_CELLS, me, -64, 320) != null,
                "9 high is over the height cap");
        require(AreaSpec.checkBounds(box(100, 64, 0, 3, 3, 3), AreaSpec.MAX_EXCAVATE_CELLS, me, -64, 320) != null,
                "a box 100 blocks away is refused");
        require(AreaSpec.checkBounds(box(-1, -60, -1, 3, 3, 3), AreaSpec.MAX_EXCAVATE_CELLS,
                new AreaSpec.Pos(0, -58, 0), -64, 320) != null, "too close to the world's floor");
        require(AreaSpec.checkBounds(box(0, 60, 0, 9, 1, 9), AreaSpec.MAX_FILL_CELLS, me, -64, 320) == null
                && AreaSpec.checkBounds(box(0, 60, 0, 23, 1, 23), AreaSpec.MAX_FILL_CELLS, me, -64, 320) != null,
                "fill has its own 512 cap");
    }

    private static void relativeLayout() {
        AreaSpec.Anchors a = new AreaSpec.Anchors(new AreaSpec.Pos(0, 64, 0), AreaSpec.Facing.NORTH,
                new AreaSpec.Pos(10, 70, 10), AreaSpec.Facing.EAST, null);
        AreaSpec.Box north = resolve("9 4 9", a);
        require(north.equals(new AreaSpec.Box(-4, 64, -9, 4, 67, -1, AreaSpec.Facing.NORTH)),
                "north: 9 wide centred, 9 deep starting one ahead: " + north);
        AreaSpec.Box owner = resolve("3 2 5 anchor=owner", a);
        require(owner.equals(new AreaSpec.Box(11, 70, 9, 15, 71, 11, AreaSpec.Facing.EAST)),
                "owner facing east: 5 deep along +x, 3 wide across z: " + owner);
        AreaSpec.Box even = resolve("4 1 1 facing=south", a);
        require(even.equals(new AreaSpec.Box(-2, 64, 1, 1, 64, 1, AreaSpec.Facing.SOUTH)),
                "even width puts the extra column on the right (west when facing south): " + even);
        AreaSpec.Box centred = resolve("5 3 5 anchor=100,40,-20", a);
        require(centred.equals(new AreaSpec.Box(98, 40, -22, 102, 42, -18, AreaSpec.Facing.NORTH)),
                "coordinate anchor centres the box: " + centred);
        AreaSpec.Box corners = resolve("10 60 5 2 63 -3", a);
        require(corners.minX() == 2 && corners.maxX() == 10 && corners.minZ() == -3 && corners.maxZ() == 5,
                "corners are normalised");
        AreaSpec.Anchors withLast = new AreaSpec.Anchors(a.here(), a.hereFacing(), null, null, north);
        AreaSpec.Box ext = resolve("9 4 5 anchor=last facing=north", withLast);
        require(ext.minZ() == -14 && ext.maxZ() == -10 && ext.minX() == -4 && ext.maxX() == 4 && ext.minY() == 64,
                "anchor=last adjoins the last area on its facing side: " + ext);
        String[] err = new String[1];
        require(AreaSpec.resolve(AreaSpec.parse("3 3 3 anchor=owner", false, err),
                new AreaSpec.Anchors(a.here(), a.hereFacing(), null, null, null)).error() != null,
                "anchor=owner without an owner is refused");
        require(AreaSpec.Facing.fromYaw(0) == AreaSpec.Facing.SOUTH && AreaSpec.Facing.fromYaw(90) == AreaSpec.Facing.WEST
                && AreaSpec.Facing.fromYaw(-180) == AreaSpec.Facing.NORTH && AreaSpec.Facing.fromYaw(-90) == AreaSpec.Facing.EAST,
                "yaw to facing");
    }

    private static AreaSpec.Box resolve(String args, AreaSpec.Anchors a) {
        String[] err = new String[1];
        AreaSpec.Request r = AreaSpec.parse(args, false, err);
        require(r != null, "parses: " + args + " " + err[0]);
        AreaSpec.Resolved res = AreaSpec.resolve(r, a);
        require(res.box() != null, "resolves: " + args + " " + res.error());
        return res.box();
    }

    private static void grammar() {
        String[] err = new String[1];
        require(AreaSpec.parse("9 4", false, err) == null, "two numbers refused");
        require(AreaSpec.parse("9 4 9 depth=3", false, err) == null && err[0].contains("depth"), "unknown option named");
        require(AreaSpec.parse("9 1 9", true, err) == null, "fill needs a block first");
        AreaSpec.Request f = AreaSpec.parse("cobblestone 9 1 9 anchor=last", true, err);
        require(f != null && f.block().equals("cobblestone") && f.anchor().equals("last"), "fill grammar");
        require(AreaSpec.parse("1 2 3 4 5 6 facing=east", false, err) == null, "corners take no facing");
        AreaSpec.Request c = AreaSpec.parse("9 4 9 confirm=yes", false, err);
        require(c != null && c.confirm(), "confirm=yes parsed");
    }

    private static void timeDerivedSizeCap() {
        AreaSpec.Box big = box(0, 60, 0, 16, 8, 16);
        AreaScan.Result woodenBig = AreaScan.scan(AreaScan.Mode.EXCAVATE, big, new FakeWorld(stone(STONE_WOODEN)),
                30, 0, "", false);
        require(woodenBig.refused() && woodenBig.refusal().contains("about 428 blocks"),
                "16x8x16 of stone with a wooden pickaxe is refused with the size that fits: " + woodenBig.refusal());
        AreaScan.Result room = AreaScan.scan(AreaScan.Mode.EXCAVATE, box(0, 60, 0, 9, 4, 9),
                new FakeWorld(stone(STONE_STONE_PICK)), 30, 0, "", false);
        require(!room.refused() && room.targets().size() == 324
                && Math.abs(room.estimatedSeconds() - 324 * 1.7) < 0.01,
                "9x4x9 of stone with a stone pickaxe fits (~9 min): " + room.estimatedSeconds());
    }

    private static void liquidsRefuseIncludingWaterlogged() {
        AreaSpec.Box b = box(0, 60, 0, 3, 3, 3);
        FakeWorld w = new FakeWorld(stone(STONE_STONE_PICK));
        w.set(-1, 61, 1, waterloggedSlab()); // side shell
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, w, 30, 0, "", false).refusal().contains("water"),
                "waterlogged block beside the box refuses");
        FakeWorld up = new FakeWorld(stone(STONE_STONE_PICK));
        up.set(1, 64, 1, new AreaScan.Cell(true, false, "lava", false, false, false, false, false, false, 0, false, false, "lava"));
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, up, 30, 0, "", false).refusal().contains("lava"),
                "lava two above the ceiling refuses");
        FakeWorld unloaded = new FakeWorld(stone(STONE_STONE_PICK));
        unloaded.set(3, 60, 0, new AreaScan.Cell(false, false, null, false, false, false, false, false, false, 0, false, false, ""));
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, unloaded, 30, 0, "", false).refused(), "unloaded shell refuses");
    }

    private static void fallingBlocksFoldOrRefuse() {
        AreaSpec.Box b = box(0, 60, 0, 3, 3, 3);
        FakeWorld w = new FakeWorld(stone(STONE_STONE_PICK));
        for (int y = 63; y <= 65; y++) {
            w.set(1, y, 1, gravel());
        }
        AreaScan.Result r = AreaScan.scan(AreaScan.Mode.EXCAVATE, b, w, 30, 0, "", false);
        require(!r.refused() && r.folded() == 3 && r.targets().size() == 27 + 3, "3-high gravel is folded in");
        for (int y = 66; y <= 69; y++) {
            w.set(1, y, 1, gravel());
        }
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, w, 30, 0, "", false).refusal().contains("gravel"),
                "7-high gravel refuses");
    }

    private static void playerBlocksAreLeftAndGuarded() {
        AreaSpec.Box b = box(0, 60, 0, 5, 3, 5);
        FakeWorld w = new FakeWorld(stone(STONE_STONE_PICK));
        for (int i = 0; i < 9; i++) {
            w.set(i % 5, 60, i / 5, placed());
        }
        w.set(-1, 61, 2, placed()); // in the shell
        AreaScan.Result refused = AreaScan.scan(AreaScan.Mode.EXCAVATE, b, w, 30, 0, "", false);
        require(refused.refused() && refused.needsConfirm(), "9 player blocks need the owner's confirmation");
        AreaScan.Result ok = AreaScan.scan(AreaScan.Mode.EXCAVATE, b, w, 30, 0, "", true);
        require(!ok.refused() && ok.excluded() == 9 && ok.targets().size() == 75 - 9, "confirmed: dug around them");
        boolean placedTargeted = ok.targets().stream().anyMatch(p -> p[1] == 60 && p[0] == 0 && p[2] == 0);
        require(!placedTargeted, "a player block is never a target");
        require(ok.shellProtected().size() == 1 && ok.shellProtected().get(0)[0] == -1,
                "player block in the shell is guarded for the run");
    }

    private static void toolSlotsAndFillShortfall() {
        AreaSpec.Box b = box(0, 60, 0, 3, 2, 3);
        AreaScan.Cell needsPick = new AreaScan.Cell(true, false, null, false, false, false, false, false, false, 150, true, false, "stone");
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, new FakeWorld(needsPick), 30, 0, "", false)
                .refusal().contains("pickaxe"), "no pickaxe: refused with the fix");
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, new FakeWorld(stone(12)), 3, 0, "", false)
                .refusal().contains("full"), "fewer than 4 free slots refused");
        FakeWorld airy = new FakeWorld(AIR);
        AreaScan.Result fill = AreaScan.scan(AreaScan.Mode.FILL, box(0, 60, 0, 5, 1, 5), airy, 0, 20, "cobblestone", false);
        require(fill.refused() && fill.refusal().contains("5 more cobblestone"), "fill names the exact shortfall");
        require(!AreaScan.scan(AreaScan.Mode.FILL, box(0, 60, 0, 5, 1, 5), airy, 0, 25, "cobblestone", false).refused(),
                "enough blocks: fill accepted");
        require(AreaScan.scan(AreaScan.Mode.EXCAVATE, b, airy, 0, 0, "", false).targets().isEmpty(),
                "an already clear box has nothing to do, even with a full pack");
    }

    private static void verdictNeedsTheWorldToBeDone() {
        long now = 100_000;
        require(AreaScan.judge(false, false, 5, 100, now, now, 60_000, now + 60_000).verdict() == AreaScan.Verdict.FAIL,
                "builder idle with blocks left is a failure, not success");
        require(AreaScan.judge(false, false, 0, 100, now, now, 60_000, now + 60_000).verdict() == AreaScan.Verdict.SUCCESS,
                "remaining 0 is success");
        require(AreaScan.judge(true, true, 5, 100, now, now, 60_000, now + 60_000).reason().contains("stuck"),
                "paused builder fails as stuck");
        require(AreaScan.judge(true, false, 5, 100, now, now - 61_000, 60_000, now + 60_000).reason().contains("no progress"),
                "stall fails");
        require(AreaScan.judge(true, false, 5, 100, now, now, 60_000, now - 1).reason().contains("time"),
                "deadline fails");
        require(AreaScan.judge(true, false, 5, 100, now, now, 60_000, now + 1).verdict() == AreaScan.Verdict.CONTINUE,
                "otherwise keep going");
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("area self-test failed: " + what);
        }
        checks++;
    }
}
