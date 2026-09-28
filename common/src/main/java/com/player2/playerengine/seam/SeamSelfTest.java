package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineCommands;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * The seam core (stage 2B-core): the signature table, coercion, container handles, perception, the
 * query budget, motion bounds and the {@code goto}/{@code excavate} postconditions. Needs the
 * Minecraft bootstrap for registries and block states, so it runs inside {@code companionSelfTest}.
 */
public final class SeamSelfTest {
    private static int checks;

    private SeamSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        signatureTableIsTheSource();
        coercionTable();
        commandLineForms();
        doubleChestIsOneHandle();
        perceptionAtTheSeam();
        radius32QuerySpansTicksUnderTheBudget();
        unloadedChunksAreNotLoaded();
        oneQueryPerCompanionPerTick();
        motionBounds();
        gotoPostcondition();
        excavatePostcondition();
        return checks;
    }

    // --- signature table --------------------------------------------------------------------------

    private static void signatureTableIsTheSource() {
        for (Signature s : SignatureTable.all()) {
            if (s.command() != null) {
                require(s.permissionClass() == PlayerEngineCommands.CLASSES.get(s.command()),
                        s.name() + " takes its class from " + s.command() + "'s row");
            }
            if (s.kind() == Signature.Kind.QUERY) {
                require(s.permissionClass() == com.player2.playerengine.commands.base.PermissionClass.QUERY,
                        s.name() + " is a query and runs as QUERY");
            }
        }
        require(SignatureTable.all().size() == 29, "29 signatures, as §5.3 lists them: " + SignatureTable.all().size());
        for (String bound : List.of("goto", "excavate")) {
            Primitive p = Seam.primitiveFor(bound);
            require(p != null && p.signature().bound() && p.signature().command().equals(bound),
                    bound + " lines dispatch through the seam as the " + bound + " primitive");
        }
        require(Seam.primitiveFor("mine") != null && Seam.primitiveFor("scan_storage") == null,
                "mine lines run as the mine primitive; a command no primitive wraps runs as itself");
        String published = SignatureTable.published();
        require(published != null, SignatureTable.RESOURCE + " is published");
        require(SignatureTable.json().equals(published),
                SignatureTable.RESOURCE + " matches the table; run task seam-signatures");
        for (FailureCode c : FailureCode.values()) {
            require(published.contains("\"" + c.wire() + "\""), "the published table lists " + c.wire());
        }
        require(SignatureTable.get("goto").reference().equals("api.goto(p: Pos): void"),
                "reference line: " + SignatureTable.get("goto").reference());
    }

    // --- coercion ----------------------------------------------------------------------------------

    /** One row: a call's raw arguments, and the arguments, notes or failure code it must coerce to. */
    private record Row(String sig, Map<String, Object> raw, Map<String, Object> args, FailureCode code,
            String noteContains) {
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static Map<String, Integer> items(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return out;
    }

    private static void coercionTable() {
        AreaSpec.Pos p = new AreaSpec.Pos(10, 64, -3);
        AreaSpec.Box box = new AreaSpec.Box(0, 60, 0, 4, 62, 4, AreaSpec.Facing.NORTH);
        List<Row> rows = List.of(
                new Row("goto", m("p", "10 64 -3"), m("p", p), null, null),
                new Row("goto", m("p", "10,64,-3"), m("p", p), null, null),
                new Row("goto", m("p", List.of(10, 64, -3)), m("p", p), null, null),
                new Row("goto", m("p", m("x", 10.9, "y", 64, "z", -2.1)), m("p", new AreaSpec.Pos(10, 64, -3)), null, null),
                new Row("goto", m("p", "10 64"), null, FailureCode.BAD_ARGS, null),
                new Row("goto", m(), null, FailureCode.BAD_ARGS, null),
                new Row("goto", m("p", "1 2 3", "speed", "fast"), null, FailureCode.BAD_ARGS, null),
                new Row("count", m("item", "Iron Ingots"), m("item", "iron_ingot"), null, "read as iron_ingot"),
                new Row("count", m("item", "minecraft:iron_ingot"), m("item", "iron_ingot"), null, "read as iron_ingot"),
                new Row("count", m("item", "IRON_INGOT"), m("item", "iron_ingot"), null, null),
                new Row("count", m("item", "iron_ingot"), m("item", "iron_ingot"), null, null),
                new Row("count", m("item", "iron"), null, FailureCode.AMBIGUOUS, null),
                new Row("count", m("item", "unobtainium"), null, FailureCode.BAD_ARGS, null),
                new Row("get", m("item", "torches", "n", "5000"), m("item", "torch", "n", 2304), null, "lowered to 2304"),
                new Row("get", m("item", "torch", "n", 0), m("item", "torch", "n", 1), null, "raised to 1"),
                new Row("find_blocks", m("block", "Oak Log", "radius", 40, "max", "10"),
                        m("block", "oak_log", "radius", 32, "max", 10), null, "radius 40 lowered to 32"),
                new Row("withdraw", m("c", "1 2 3", "items", "iron_ingot,coal"),
                        m("c", ContainerHandle.at(new AreaSpec.Pos(1, 2, 3)), "items", items("iron_ingot", null, "coal", null)),
                        null, null),
                new Row("withdraw", m("c", "1 2 3", "items", "iron ingots 5, coal"),
                        m("c", ContainerHandle.at(new AreaSpec.Pos(1, 2, 3)), "items", items("iron_ingot", 5, "coal", null)),
                        null, "read as iron_ingot"),
                new Row("withdraw", m("c", List.of(1, 2, 3), "items", List.of("iron_ingot", "coal")),
                        m("c", ContainerHandle.at(new AreaSpec.Pos(1, 2, 3)), "items", items("iron_ingot", null, "coal", null)),
                        null, null),
                new Row("withdraw", m("c", "1 2 3", "items", m("iron_ingot", 3, "coal", 2)),
                        m("c", ContainerHandle.at(new AreaSpec.Pos(1, 2, 3)), "items", items("iron_ingot", 3, "coal", 2)),
                        null, null),
                new Row("withdraw", m("c", "1 2 3"), null, FailureCode.BAD_ARGS, null),
                new Row("store", m("items", "all_except_tools"), m("items", "all_except_tools"), null, null),
                new Row("excavate", m("box", "4 62 4 0 60 0"), m("box", box), null, null),
                new Row("excavate", m("box", List.of(0, 60, 0, 4, 62, 4)), m("box", box), null, null),
                new Row("excavate", m("box", m("min", List.of(0, 60, 0), "max", m("x", 4, "y", 62, "z", 4))), m("box", box),
                        null, null),
                new Row("say", m("text", "x".repeat(250)), m("text", "x".repeat(200)), null, "cut to 200"),
                new Row("wait_until", m("query_name", "goto", "args", List.of(), "predicate", "x", "timeout_s", 5), null,
                        FailureCode.BAD_ARGS, null));
        for (Row r : rows) {
            Coercion.Result got = Coercion.coerce(SignatureTable.get(r.sig()), r.raw(), Coercion.Ids.REGISTRIES);
            String label = r.sig() + " " + r.raw();
            if (r.code() != null) {
                require(!got.ok() && got.error().code() == r.code(), label + " fails with " + r.code().wire() + ": "
                        + (got.ok() ? got.args() : got.error()));
            } else {
                require(got.ok() && got.args().equals(r.args()), label + " coerces to " + r.args() + ": "
                        + (got.ok() ? got.args() : got.error()));
            }
            if (r.noteContains() != null) {
                require(got.notes().stream().anyMatch(n -> n.contains(r.noteContains())),
                        label + " notes '" + r.noteContains() + "': " + got.notes());
            }
        }
        Coercion.Result iron = Coercion.coerce(SignatureTable.get("count"), m("item", "iron"), Coercion.Ids.REGISTRIES);
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) iron.error().state().get("candidates");
        require(candidates.contains("iron_ingot") && candidates.contains("raw_iron"),
                "an ambiguous id lists its candidates rather than guessing: " + candidates);
    }

    private static void commandLineForms() {
        Coercion.Ids ids = Coercion.Ids.REGISTRIES;
        CommandLines.Normalised light = CommandLines.normalise("scan_storage", "1 64 3", ids);
        require(light.ok() && light.args().equals("1 64 3 light") && !light.notes().isEmpty(),
                "scan_storage without a mode is a light scan, said: " + light);
        CommandLines.Normalised targeted = CommandLines.normalise("scan_storage", "1 64 3 Iron Ingots 5", ids);
        require(targeted.ok() && targeted.args().equals("1 64 3 targeted iron_ingot 5"),
                "scan_storage with items is targeted: " + targeted);
        CommandLines.Normalised list = CommandLines.normalise("withdraw_from_storage", "1 64 3 iron_ingot 5,coal", ids);
        require(list.ok() && list.args().equals("1 64 3 iron_ingot 5, coal"), "a comma list without spaces: " + list);
        CommandLines.Normalised same = CommandLines.normalise("withdraw_from_storage", "1 64 3 iron_ingot 5, coal", ids);
        require(same.ok() && same.notes().isEmpty() && same.args().equals("1 64 3 iron_ingot 5, coal"),
                "a canonical line passes unchanged: " + same);
        CommandLines.Normalised ambiguous = CommandLines.normalise("deposit_to_storage", "1 64 3 iron", ids);
        require(!ambiguous.ok() && ambiguous.error().code() == FailureCode.AMBIGUOUS, "an ambiguous item: " + ambiguous);
        CommandLines.Normalised other = CommandLines.normalise("mine", "Iron Ores 3", ids);
        require(other.ok() && other.args().equals("Iron Ores 3") && other.notes().isEmpty(),
                "lines no rule covers pass unchanged");
    }

    // --- containers -------------------------------------------------------------------------------

    private static void doubleChestIsOneHandle() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos left = new BlockPos(0, 64, 0);
        BlockState leftState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos right = left.relative(ChestBlock.getConnectedDirection(leftState));
        world.put(left, leftState);
        world.put(right, leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        BlockPos single = new BlockPos(5, 64, 0);
        world.put(single, Blocks.CHEST.defaultBlockState());
        BlockPos orphan = new BlockPos(9, 64, 0);
        world.put(orphan, leftState);
        Predicate<BlockPos> isContainer = world::containsKey;
        try {
            ContainerHandle viaLeft = ContainerHandle.resolve(pos(left), b -> world.getOrDefault(b, Blocks.AIR.defaultBlockState()), isContainer);
            ContainerHandle viaRight = ContainerHandle.resolve(pos(right), b -> world.getOrDefault(b, Blocks.AIR.defaultBlockState()), isContainer);
            require(viaLeft.equals(viaRight) && viaLeft.id().equals(viaRight.id()),
                    "either half of a double chest is the same handle: " + viaLeft + " / " + viaRight);
            require(viaLeft.covers(pos(left)) && viaLeft.covers(pos(right)), "the handle covers both halves");
            require(viaLeft.pos().equals(pos(right)), "the RIGHT half is canonical, as ContainerResolver has it");
            ContainerHandle one = ContainerHandle.resolve(pos(single), b -> world.getOrDefault(b, Blocks.AIR.defaultBlockState()), isContainer);
            require(one.secondary() == null && one.pos().equals(pos(single)), "a single chest is itself");
            ContainerHandle half = ContainerHandle.resolve(pos(orphan), b -> world.getOrDefault(b, Blocks.AIR.defaultBlockState()), isContainer);
            require(half.secondary() == null, "a half whose partner is gone is a single chest");
        } catch (Coercion.Failure f) {
            require(false, "container resolve failed: " + f.error);
        }
        try {
            ContainerHandle.resolve(new AreaSpec.Pos(3, 64, 3), b -> Blocks.STONE.defaultBlockState(), b -> false);
            require(false, "stone is not a container");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.NO_CONTAINER, "a position with no container is no_container");
        }
    }

    private static AreaSpec.Pos pos(BlockPos b) {
        return new AreaSpec.Pos(b.getX(), b.getY(), b.getZ());
    }

    // --- queries ----------------------------------------------------------------------------------

    /** Stone below y 64, air from 64 up, chunks loaded where {@code loaded} says; set cells override. */
    private static final class TestWorld implements WorldReader {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        final Predicate<long[]> loaded;
        long reads;

        TestWorld(Predicate<long[]> loaded) {
            this.loaded = loaded;
        }

        TestWorld set(BlockPos p, BlockState s) {
            cells.put(p, s);
            return this;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return loaded.test(new long[] {chunkX, chunkZ});
        }

        @Override
        public BlockState state(int x, int y, int z) {
            reads++;
            BlockState s = cells.get(new BlockPos(x, y, z));
            return s != null ? s : y < 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    private static Outcome runToEnd(QueryQueue.Query q, WorldReader w, ReadBudget budget, int[] ticks) {
        QueryQueue queue = new QueryQueue();
        Outcome[] out = new Outcome[1];
        queue.submit(q, o -> out[0] = o);
        long tick = 0;
        while (out[0] == null && tick < 100_000) {
            queue.tick(w, budget, tick++);
        }
        ticks[0] = (int) tick;
        return out[0];
    }

    private static void perceptionAtTheSeam() {
        BlockPos buried = new BlockPos(3, 60, 0);
        BlockPos exposed = new BlockPos(6, 63, 0);
        BlockPos nearer = new BlockPos(1, 63, 1);
        TestWorld w = new TestWorld(c -> true)
                .set(buried, Blocks.DIAMOND_ORE.defaultBlockState())
                .set(exposed, Blocks.DIAMOND_ORE.defaultBlockState())
                .set(nearer, Blocks.DIAMOND_ORE.defaultBlockState());
        int[] ticks = new int[1];
        Outcome hidden = runToEnd(Queries.blockAt(pos(buried)), w, new ReadBudget(ReadBudget.PER_TICK), ticks);
        require(hidden.ok() && SeamPerception.HIDDEN.equals(hidden.value()), "an enclosed block reads as hidden: " + hidden);
        Outcome seen = runToEnd(Queries.blockAt(pos(exposed)), w, new ReadBudget(ReadBudget.PER_TICK), ticks);
        require(seen.ok() && "diamond_ore".equals(seen.value()), "an exposed block reads as itself: " + seen);
        Outcome found = runToEnd(Queries.findBlocks(new AreaSpec.Pos(0, 64, 0), "diamond_ore", 8, 64), w,
                new ReadBudget(ReadBudget.PER_TICK), ticks);
        require(found.ok() && found.value().equals(List.of(pos(nearer), pos(exposed))),
                "find_blocks lists exposed blocks only, nearest first: " + found);
        Outcome capped = runToEnd(Queries.findBlocks(new AreaSpec.Pos(0, 64, 0), "diamond_ore", 8, 1), w,
                new ReadBudget(ReadBudget.PER_TICK), ticks);
        require(capped.ok() && capped.value().equals(List.of(pos(nearer))), "max caps the list: " + capped);
        SeamPerception.Raycasts rays = new SeamPerception.Raycasts();
        require(SeamPerception.containerVisible(true, false, () -> false, rays), "a known container is visible");
        require(!SeamPerception.containerVisible(false, true, () -> false, rays), "an exposed one out of sight is not");
        require(!SeamPerception.containerVisible(false, false, () -> true, rays), "an enclosed one is not");
        for (int i = 0; i < SeamPerception.MAX_RAYCASTS; i++) {
            SeamPerception.containerVisible(false, true, () -> true, rays);
        }
        require(rays.left() == 0 && !SeamPerception.containerVisible(false, true, () -> true, rays),
                "a query spends at most " + SeamPerception.MAX_RAYCASTS + " raycasts");
    }

    private static void radius32QuerySpansTicksUnderTheBudget() {
        TestWorld w = new TestWorld(c -> true);
        for (int i = 0; i < 40; i++) {
            w.set(new BlockPos(-20 + i, 63, (i * 7) % 30 - 15), Blocks.IRON_ORE.defaultBlockState());
        }
        ReadBudget budget = new ReadBudget(ReadBudget.PER_TICK);
        int[] ticks = new int[1];
        Outcome o = runToEnd(Queries.findBlocks(new AreaSpec.Pos(0, 64, 0), "iron_ore", 32, 64), w, budget, ticks);
        require(o.ok() && ((List<?>) o.value()).size() == 40, "the radius-32 query finds every exposed ore: " + o);
        require(ticks[0] > 60, "a radius-32 query spans many ticks, took " + ticks[0]);
        require(budget.peak() <= ReadBudget.PER_TICK, "no tick reads more than " + ReadBudget.PER_TICK + ": "
                + budget.peak());
        require(w.reads <= (long) ticks[0] * ReadBudget.PER_TICK, "the world saw no reads the budget did not charge");

        // Red witness: the same query without the budget reads far past the cap in one tick.
        ReadBudget none = ReadBudget.unbounded();
        Outcome unbudgeted = runToEnd(Queries.findBlocks(new AreaSpec.Pos(0, 64, 0), "iron_ore", 32, 64),
                new TestWorld(c -> true), none, ticks);
        require(unbudgeted.ok() && ticks[0] == 1 && none.peak() > ReadBudget.PER_TICK,
                "without the budget one tick reads " + none.peak() + " (cap " + ReadBudget.PER_TICK + ")");
    }

    private static void unloadedChunksAreNotLoaded() {
        TestWorld nothing = new TestWorld(c -> false);
        int[] ticks = new int[1];
        Outcome at = runToEnd(Queries.blockAt(new AreaSpec.Pos(100, 64, 100)), nothing, new ReadBudget(ReadBudget.PER_TICK),
                ticks);
        require(!at.ok() && at.error().code() == FailureCode.NOT_LOADED && nothing.reads == 0,
                "block_at in an unloaded chunk is not_loaded and reads nothing: " + at);
        Outcome find = runToEnd(Queries.findBlocks(new AreaSpec.Pos(100, 64, 100), "stone", 4, 8), nothing,
                new ReadBudget(ReadBudget.PER_TICK), ticks);
        require(!find.ok() && find.error().code() == FailureCode.NOT_LOADED && nothing.reads == 0,
                "find_blocks over unloaded ground is not_loaded and reads nothing: " + find);
        // Half loaded: chunk x 0 only. The query covers what is loaded and says what it skipped.
        TestWorld half = new TestWorld(c -> c[0] == 0).set(new BlockPos(2, 63, 2), Blocks.GOLD_ORE.defaultBlockState())
                .set(new BlockPos(-3, 63, 2), Blocks.GOLD_ORE.defaultBlockState());
        Outcome partial = runToEnd(Queries.findBlocks(new AreaSpec.Pos(2, 64, 2), "gold_ore", 8, 8), half,
                new ReadBudget(ReadBudget.PER_TICK), ticks);
        require(partial.ok() && partial.value().equals(List.of(new AreaSpec.Pos(2, 63, 2))),
                "a block in an unloaded chunk is not read: " + partial);
    }

    private static void oneQueryPerCompanionPerTick() {
        TestWorld w = new TestWorld(c -> true);
        QueryQueue queue = new QueryQueue();
        List<Object> done = new ArrayList<>();
        queue.submit(Queries.blockAt(new AreaSpec.Pos(0, 64, 0)), o -> done.add("first"));
        queue.submit(Queries.blockAt(new AreaSpec.Pos(0, 63, 0)), o -> done.add("second"));
        ReadBudget budget = new ReadBudget(ReadBudget.PER_TICK);
        queue.tick(w, budget, 1);
        require(done.equals(List.of("first")) && queue.size() == 1, "one query per companion per tick: " + done);
        queue.tick(w, budget, 2);
        require(done.equals(List.of("first", "second")) && queue.size() == 0, "the next one the tick after");
        ReadBudget spent = new ReadBudget(ReadBudget.PER_TICK);
        spent.charge(3, ReadBudget.PER_TICK - 2);
        queue.submit(Queries.blockAt(new AreaSpec.Pos(0, 63, 0)), o -> done.add("third"));
        queue.tick(w, spent, 3);
        require(queue.size() == 1, "a query waits when the server-wide budget is spent this tick");
        queue.tick(w, spent, 4);
        require(queue.size() == 0 && done.contains("third"), "and runs on the next tick");
    }

    // --- motion bounds ----------------------------------------------------------------------------

    private static void motionBounds() {
        MotionBounds.Region region = MotionBounds.Region.around("minecraft:overworld", new AreaSpec.Pos(0, 64, 0));
        require(MotionBounds.check(region, "minecraft:overworld", new AreaSpec.Pos(30, 70, 30)) == null,
                "a target 43 blocks away is inside the region");
        ActionError far = MotionBounds.check(region, "minecraft:overworld", new AreaSpec.Pos(40, 64, 40));
        require(far != null && far.code() == FailureCode.OUT_OF_REGION, "a target 57 blocks away is out_of_region");
        require(MotionBounds.check(region, "minecraft:the_nether", new AreaSpec.Pos(0, 64, 0)) != null,
                "another dimension is out of the region");
        require(MotionBounds.check(null, "minecraft:overworld", new AreaSpec.Pos(9000, 64, 0)) == null,
                "outside a job there is no region");
    }

    // --- postconditions ---------------------------------------------------------------------------

    private static Primitive.World standingAt(Vec3 at, AreaScan.BlockLookup cells) {
        return new Primitive.World() {
            @Override
            public Vec3 position() {
                return at;
            }

            @Override
            public AreaScan.Cell cell(int x, int y, int z) {
                return cells.cell(x, y, z);
            }
        };
    }

    private static void gotoPostcondition() {
        Primitive go = Seam.primitiveFor("goto");
        Map<String, Object> args = Map.of("p", new AreaSpec.Pos(10, 64, 10));
        Outcome real = Seam.verify(go, args, Map.of(), Primitive.TaskEnd.finished(null),
                standingAt(new Vec3(10.5, 64, 10.5), (x, y, z) -> null), List.of());
        require(real.ok(), "goto passes when the companion stands at the target: " + real);
        Outcome faked = Seam.verify(go, args, Map.of(), Primitive.TaskEnd.finished(null),
                standingAt(new Vec3(0.5, 64, 0.5), (x, y, z) -> null), List.of());
        require(!faked.ok() && faked.error().code() == FailureCode.UNREACHABLE,
                "a Finished goto that left the companion 14 blocks short fails: " + faked);
        Outcome failed = Seam.verify(go, args, Map.of(), Primitive.TaskEnd.failed(ActionError.of(FailureCode.TIMEOUT, "slow")),
                standingAt(new Vec3(10.5, 64, 10.5), (x, y, z) -> null), List.of());
        require(!failed.ok() && failed.error().code() == FailureCode.TIMEOUT, "the Task's own failure stands");
        require(go.reconcile(args, Map.of(), standingAt(new Vec3(0.5, 64, 0.5), (x, y, z) -> null)) == Primitive.Reconcile.RERUN,
                "an interrupted goto short of its target re-runs");
    }

    private static AreaScan.Cell cell(boolean air, boolean playerPlaced, String liquid) {
        return new AreaScan.Cell(true, air, liquid, false, false, false, playerPlaced, false, false, 10, false, false,
                air ? "air" : "stone");
    }

    private static void excavatePostcondition() {
        Primitive dig = Seam.primitiveFor("excavate");
        AreaSpec.Box box = new AreaSpec.Box(0, 60, 0, 2, 61, 2, AreaSpec.Facing.NORTH);
        Map<String, Object> args = Map.of("box", box);
        BlockPos kept = new BlockPos(1, 60, 1);
        Set<BlockPos> stoneLeft = new HashSet<>(List.of(new BlockPos(2, 61, 2)));
        Vec3 here = new Vec3(0, 60, 0);
        AreaScan.BlockLookup cleared = (x, y, z) -> new BlockPos(x, y, z).equals(kept) ? cell(false, true, null)
                : cell(true, false, null);
        Outcome real = Seam.verify(dig, args, Map.of(), Primitive.TaskEnd.finished("dug"), standingAt(here, cleared), List.of());
        require(real.ok() && "dug".equals(real.value()),
                "excavate passes when every cell but a player's block is air: " + real);
        AreaScan.BlockLookup notDug = (x, y, z) -> stoneLeft.contains(new BlockPos(x, y, z)) ? cell(false, false, null)
                : cell(true, false, null);
        Outcome faked = Seam.verify(dig, args, Map.of(), Primitive.TaskEnd.finished("dug"), standingAt(here, notDug), List.of());
        require(!faked.ok() && faked.error().code() == FailureCode.UNREACHABLE
                        && Integer.valueOf(1).equals(faked.error().state().get("left")),
                "a Finished excavate with a cell still standing fails: " + faked);
        AreaScan.BlockLookup flooded = (x, y, z) -> y == 60 ? cell(false, false, "water") : cell(true, false, null);
        Outcome wet = Seam.verify(dig, args, Map.of(), Primitive.TaskEnd.finished(null), standingAt(here, flooded), List.of());
        require(!wet.ok() && wet.error().code() == FailureCode.LIQUID, "water in the box is liquid: " + wet);
        AreaScan.BlockLookup unloaded = (x, y, z) -> new AreaScan.Cell(false, false, null, false, false, false, false,
                false, false, 0, false, false, "");
        Outcome gone = Seam.verify(dig, args, Map.of(), Primitive.TaskEnd.finished(null), standingAt(here, unloaded), List.of());
        require(!gone.ok() && gone.error().code() == FailureCode.NOT_LOADED, "an unloaded box is not_loaded: " + gone);
        require(dig.reconcile(args, Map.of(), standingAt(here, cleared)) == Primitive.Reconcile.DONE
                        && dig.reconcile(args, Map.of(), standingAt(here, notDug)) == Primitive.Reconcile.RERUN,
                "an interrupted excavate is done when clear and re-runs otherwise");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("seam self-test failed: " + message);
        }
    }
}
