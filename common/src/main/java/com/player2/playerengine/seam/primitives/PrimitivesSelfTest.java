package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.ContainerHandle;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Outcome;
import com.player2.playerengine.seam.Primitive;
import com.player2.playerengine.seam.Seam;
import com.player2.playerengine.seam.SeamTestWorld;
import com.player2.playerengine.seam.Signature;
import com.player2.playerengine.seam.SignatureTable;
import com.player2.playerengine.seam.WorldReader;
import com.player2.playerengine.seam.YesNo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The stage-2B primitives (§5.3). For each one: the postcondition passes on the world the real
 * outcome leaves, and fails, with the code the model repairs against, on a world where the Task said
 * Finished and nothing happened. The red witness is the same faked Finished through the same primitive
 * with its postcondition removed: it counts as success, so the postcondition is what catches it.
 */
public final class PrimitivesSelfTest {
    private static int checks;

    private PrimitivesSelfTest() {
    }

    /**
     * One primitive's case.
     *
     * @param setup  the world before the call
     * @param real   what the Task does to the world when it works
     * @param faked  the code a Finished with nothing done must fail with
     */
    record Case(String label, String name, Map<String, Object> raw, Consumer<SeamTestWorld> setup,
            Consumer<SeamTestWorld> real, FailureCode faked) {
    }

    public static int runAll() {
        checks = 0;
        List<Case> cases = new ArrayList<>();
        cases.addAll(motion());
        cases.addAll(talk());
        cases.addAll(world());
        cases.addAll(items());
        for (Case c : cases) {
            postconditionCatchesAFakedFinished(c);
        }
        everyPrimitiveIsCovered(cases);
        waitUntilRefusesWhatItCannotRecheck();
        confirmAnswersOnlyYesOrNo();
        mineCountsTheDropsNotTheOrder();
        noFalseDeposit();
        smeltChoosesItsInput();
        commandLineForms();
        return checks;
    }

    // --- world -------------------------------------------------------------------------------------

    private static void fillCells(SeamTestWorld w, String block, int... xz) {
        for (int i = 0; i < xz.length; i += 2) {
            w.set(xz[i], 64, xz[i + 1], block);
        }
    }

    private static List<Case> world() {
        Map<String, Object> floor = m("block", "Cobblestone", "box", "0 64 0 1 64 1");
        Consumer<SeamTestWorld> mineSetup = w -> {
            w.drops.put("iron_ore", List.of("iron_ore", "raw_iron"));
            w.carry("raw_iron", 2);
        };
        return List.of(
                new Case("fill", "fill", floor, w -> w.carry("cobblestone", 10),
                        w -> {
                            fillCells(w, "cobblestone", 0, 0, 0, 1, 1, 0, 1, 1);
                            w.carry("cobblestone", -4);
                        }, FailureCode.UNREACHABLE),
                new Case("fill, out of blocks", "fill", floor, w -> w.carry("cobblestone", 1),
                        w -> {
                            fillCells(w, "cobblestone", 0, 0, 0, 1, 1, 0, 1, 1);
                            w.carry("cobblestone", -1);
                        }, FailureCode.MISSING_ITEM),
                new Case("fill, around a player's block and stone", "fill", floor,
                        w -> {
                            w.carry("cobblestone", 10);
                            w.set(0, 64, 0, "oak_planks");
                            w.playerPlaced.add(new com.player2.playerengine.tasks.construction.area.AreaSpec.Pos(0, 64, 0));
                            w.set(1, 64, 1, "stone");
                        },
                        w -> fillCells(w, "cobblestone", 0, 1, 1, 0), FailureCode.UNREACHABLE),
                new Case("fill, water in the box", "fill", floor,
                        w -> {
                            w.carry("cobblestone", 10);
                            w.set(1, 64, 1, "water");
                        },
                        w -> fillCells(w, "cobblestone", 0, 0, 0, 1, 1, 0, 1, 1), FailureCode.LIQUID),
                new Case("place", "place", m("block", "oak planks", "p", "2 64 2"), w -> w.carry("oak_planks", 1),
                        w -> {
                            w.set(2, 64, 2, "oak_planks");
                            w.carry("oak_planks", -1);
                        }, FailureCode.UNREACHABLE),
                new Case("place, none carried", "place", m("block", "oak_planks", "p", "2 64 2"), w -> { },
                        w -> w.set(2, 64, 2, "oak_planks"), FailureCode.MISSING_ITEM),
                new Case("mine", "mine", m("block", "iron ore", "n", 3), mineSetup,
                        w -> w.carry("raw_iron", 3), FailureCode.NOT_FOUND),
                new Case("mine, silk touch", "mine", m("block", "iron_ore", "n", 2), mineSetup,
                        w -> w.carry("iron_ore", 2), FailureCode.NOT_FOUND),
                new Case("pickup_drops", "pickup_drops", m("radius", 8),
                        w -> {
                            w.position = new Vec3(0.5, 64, 0.5);
                            w.ground.add(new SeamTestWorld.Ground(new Vec3(3.5, 64, 0.5), "cobblestone", 5));
                        },
                        w -> {
                            w.ground.clear();
                            w.carry("cobblestone", 5);
                        }, FailureCode.UNREACHABLE),
                new Case("pickup_drops, inventory full", "pickup_drops", m("radius", 8),
                        w -> {
                            w.position = new Vec3(0.5, 64, 0.5);
                            w.freeSlots = 0;
                            w.ground.add(new SeamTestWorld.Ground(new Vec3(3.5, 64, 0.5), "cobblestone", 5));
                            w.ground.add(new SeamTestWorld.Ground(new Vec3(30.5, 64, 0.5), "dirt", 5));
                        },
                        w -> w.ground.remove(0), FailureCode.CONTAINER_FULL));
    }

    private static void mineCountsTheDropsNotTheOrder() {
        Primitive p = Seam.primitive("mine");
        Map<String, Object> args = coerce("mine", m("block", "iron_ore", "n", 3));
        SeamTestWorld w = new SeamTestWorld();
        w.drops.put("iron_ore", List.of("iron_ore", "raw_iron"));
        Map<String, Object> pre = snapshot(p, args, w);
        w.carry("raw_iron", 1);
        Outcome partial = Seam.verify(p, args, pre, Primitive.TaskEnd.finished("partial: mined 1 iron_ore"), w, List.of());
        require(!partial.ok() && partial.error().code() == FailureCode.UNREACHABLE,
                "a partial mine (1 of 3) is not a success, whatever the Task says: " + partial);
        SeamTestWorld glass = new SeamTestWorld();
        glass.drops.put("glass", List.of());
        try {
            p.snapshot(coerce("mine", m("block", "glass", "n", 1)), glass);
            require(false, "mining a block that yields nothing is refused before it starts");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.BAD_ARGS, "mining glass is bad_args: " + f.error);
        }
        Primitive.LineArgs line = p.fromLine("Iron Ore 8", null);
        require(line != null && line.raw().equals(m("block", "Iron Ore", "n", "8")), "mine's line form: " + line);
        require(p.fromLine("#minecraft:logs 3", null) == null, "a tag stays with the mine command");
        Coercion.Result ambiguous = Coercion.coerce(SignatureTable.get("mine"), p.fromLine("iron 3", null).raw(),
                Coercion.Ids.REGISTRIES);
        require(!ambiguous.ok() && ambiguous.error().code() == FailureCode.AMBIGUOUS,
                "mine iron is ambiguous and lists candidates (E6): " + ambiguous.error());
    }

    // --- items -------------------------------------------------------------------------------------

    private static final AreaSpec.Pos RIGHT = new AreaSpec.Pos(4, 64, 0);
    private static final AreaSpec.Pos LEFT = new AreaSpec.Pos(3, 64, 0);

    /** A double chest (canonical half at 4 64 0) with room, the companion beside it carrying stone and dirt. */
    private static ContainerHandle doubleChest(SeamTestWorld w, int free) {
        w.position = new Vec3(0.5, 64, 0.5);
        w.carry("cobblestone", 30).carry("dirt", 5).carry("diamond_pickaxe", 1);
        return w.chest(RIGHT, LEFT, free);
    }

    private static ContainerHandle chestOf(SeamTestWorld w) {
        return w.containers.keySet().iterator().next();
    }

    private static List<Case> items() {
        Consumer<SeamTestWorld> smelter = w -> {
            w.smelts.put("raw_iron", "iron_ingot");
            w.smelts.put("iron_ore", "iron_ingot");
            w.carry("raw_iron", 5).carry("iron_ore", 2);
        };
        return List.of(
                new Case("store into a double chest named by its other half", "store",
                        m("c", "3 64 0", "items", "cobblestone 20, dirt"), w -> doubleChest(w, 50),
                        w -> {
                            w.move(chestOf(w), "cobblestone", 20);
                            w.move(chestOf(w), "dirt", 5);
                        }, FailureCode.UNREACHABLE),
                new Case("store into a full chest", "store", m("c", "4 64 0", "items", "cobblestone 20"),
                        w -> doubleChest(w, 0), w -> w.move(chestOf(w), "cobblestone", 20), FailureCode.CONTAINER_FULL),
                new Case("store all_except_tools, nearest chest", "store", m("items", "all_except_tools"),
                        w -> doubleChest(w, 50),
                        w -> {
                            w.move(chestOf(w), "cobblestone", 30);
                            w.move(chestOf(w), "dirt", 5);
                        }, FailureCode.UNREACHABLE),
                new Case("withdraw", "withdraw", m("c", List.of(3, 64, 0), "items", m("iron_ingot", 4)),
                        w -> {
                            ContainerHandle h = doubleChest(w, 50);
                            w.containers.get(h).put("iron_ingot", 10);
                        },
                        w -> w.move(chestOf(w), "iron_ingot", -4), FailureCode.UNREACHABLE),
                new Case("withdraw all of an item", "withdraw", m("c", "4 64 0", "items", "iron_ingot"),
                        w -> {
                            ContainerHandle h = doubleChest(w, 50);
                            w.containers.get(h).put("iron_ingot", 10);
                            w.freeSlots = 0;
                        },
                        w -> w.move(chestOf(w), "iron_ingot", -10), FailureCode.CONTAINER_FULL),
                new Case("give_owner", "give_owner", m("item", "bread", "n", 3),
                        w -> {
                            w.owner = new Vec3(5.5, 64, 0.5);
                            w.carry("bread", 3);
                        },
                        w -> {
                            w.carry("bread", -3);
                            w.ownerInventory.merge("bread", 3, Integer::sum);
                        }, FailureCode.UNREACHABLE),
                new Case("give_owner, dropped at the owner's feet", "give_owner", m("item", "bread", "n", 3),
                        w -> w.owner = new Vec3(5.5, 64, 0.5),
                        w -> w.ground.add(new SeamTestWorld.Ground(new Vec3(6, 64, 1), "bread", 3)),
                        FailureCode.MISSING_ITEM),
                new Case("equip", "equip", m("item", "iron_sword"), w -> w.carry("iron_sword", 1),
                        w -> w.equipped.add("iron_sword"), FailureCode.UNREACHABLE),
                new Case("equip, none carried", "equip", m("item", "iron_helmet"), w -> { },
                        w -> w.equipped.add("iron_helmet"), FailureCode.MISSING_ITEM),
                new Case("get", "get", m("item", "torches", "n", 10), w -> w.carry("torch", 4),
                        w -> w.carry("torch", 6), FailureCode.MISSING_ITEM),
                new Case("craft", "craft", m("item", "oak_planks", "n", 4), w -> w.carry("oak_planks", 2),
                        w -> w.carry("oak_planks", 4), FailureCode.MISSING_ITEM),
                new Case("smelt by output", "smelt", m("output", "iron ingot", "n", 3), smelter,
                        w -> w.carry("raw_iron", -3).carry("iron_ingot", 3), FailureCode.UNREACHABLE),
                new Case("smelt naming the input", "smelt", m("output", "raw_iron", "n", 2), smelter,
                        w -> w.carry("raw_iron", -2).carry("iron_ingot", 2), FailureCode.UNREACHABLE));
    }

    /** E9: items that left the inventory but did not reach the container are not "deposited". */
    private static void noFalseDeposit() {
        Primitive p = Seam.primitive("store");
        Map<String, Object> args = coerce("store", m("c", "3 64 0", "items", "cobblestone 20"));
        SeamTestWorld w = new SeamTestWorld();
        ContainerHandle h = doubleChest(w, 50);
        Map<String, Object> pre = snapshot(p, args, w);
        require(pre.get("container").equals(h.toState()), "the snapshot holds the whole double chest (E8): " + pre);
        w.carry("cobblestone", -20);
        Outcome dropped = Seam.verify(p, args, pre, Primitive.TaskEnd.finished("deposited 20 cobblestone"), w, List.of());
        require(!dropped.ok() && dropped.error().code() == FailureCode.UNREACHABLE
                        && dropped.error().message().contains("container gained 0"),
                "a deposit whose items never reached the container fails (E9): " + dropped);
        SeamTestWorld half = new SeamTestWorld();
        ContainerHandle hh = doubleChest(half, 50);
        Map<String, Object> halfPre = snapshot(p, args, half);
        half.move(hh, "cobblestone", 12);
        Outcome partial = Seam.verify(p, args, halfPre, Primitive.TaskEnd.finished(null), half, List.of());
        require(!partial.ok() && ((Map<?, ?>) partial.error().state().get("short")).get("cobblestone").equals(8),
                "a partial deposit says what is short: " + partial);
        try {
            p.snapshot(coerce("store", m("c", "4 64 0", "items", "cobblestone 99")), doubleWorld());
            require(false, "storing more than it carries is refused before it moves");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.MISSING_ITEM, "storing 99 of 30 is missing_item: " + f.error);
        }
        try {
            p.snapshot(coerce("store", m("c", "9 64 9", "items", "dirt")), doubleWorld());
            require(false, "a position with no container is refused");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.NO_CONTAINER, "no container there: " + f.error);
        }
        SeamTestWorld far = doubleWorld();
        far.position = new Vec3(60, 64, 0);
        try {
            p.snapshot(coerce("store", m("items", "dirt")), far);
            require(false, "store without c and nothing in reach is refused");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.NO_CONTAINER, "no container within 16: " + f.error);
        }
        Primitive take = Seam.primitive("withdraw");
        Map<String, Object> ask = coerce("withdraw", m("c", "4 64 0", "items", "iron_ingot 4"));
        SeamTestWorld few = doubleWorld();
        few.containers.get(chestOf(few)).put("iron_ingot", 2);
        Map<String, Object> fewPre = snapshot(take, ask, few);
        few.move(chestOf(few), "iron_ingot", -2);
        Outcome shortTake = Seam.verify(take, ask, fewPre, Primitive.TaskEnd.finished(null), few, List.of());
        require(!shortTake.ok() && shortTake.error().code() == FailureCode.MISSING_ITEM,
                "withdrawing 4 from a chest holding 2 is missing_item: " + shortTake);
    }

    private static SeamTestWorld doubleWorld() {
        SeamTestWorld w = new SeamTestWorld();
        doubleChest(w, 50);
        return w;
    }

    /** E7: smelt picks its input from the inventory by the output asked for. */
    private static void smeltChoosesItsInput() {
        Primitive p = Seam.primitive("smelt");
        SeamTestWorld w = new SeamTestWorld();
        w.smelts.put("raw_iron", "iron_ingot");
        w.smelts.put("iron_ore", "iron_ingot");
        w.smelts.put("raw_gold", "gold_ingot");
        w.carry("raw_iron", 5).carry("iron_ore", 9).carry("raw_gold", 3);
        Map<String, Object> pre = snapshot(p, coerce("smelt", m("output", "iron_ingot", "n", 4)), w);
        require("iron_ore".equals(pre.get("input")) && "iron_ingot".equals(pre.get("output")),
                "smelt(iron_ingot) uses the input it holds most of: " + pre);
        Map<String, Object> named = snapshot(p, coerce("smelt", m("output", "raw_gold", "n", 2)), w);
        require("raw_gold".equals(named.get("input")) && "gold_ingot".equals(named.get("output"))
                        && String.valueOf(named.get("note")).contains("gold_ingot"),
                "naming the input smelts it into its output, with a note: " + named);
        SeamTestWorld empty = new SeamTestWorld();
        empty.smelts.put("raw_iron", "iron_ingot");
        try {
            p.snapshot(coerce("smelt", m("output", "iron_ingot", "n", 1)), empty);
            require(false, "smelting with nothing to smelt is refused");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.MISSING_ITEM
                            && ((List<?>) f.error.state().get("inputs")).contains("raw_iron"),
                    "missing_item lists what would smelt into it: " + f.error);
        }
        try {
            p.snapshot(coerce("smelt", m("output", "diamond", "n", 1)), empty);
            require(false, "an output nothing smelts into is refused");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.BAD_ARGS, "nothing smelts into diamond: " + f.error);
        }
        try {
            p.snapshot(coerce("smelt", m("output", "iron_ingot", "n", 7)), w.carry("iron_ore", -9));
            require(false, "smelting more than the input allows is refused");
        } catch (Coercion.Failure f) {
            require(f.error.code() == FailureCode.MISSING_ITEM, "5 raw iron cannot make 7: " + f.error);
        }
    }

    /** E6 on the command path: the storage and item lines' loose forms land on canonical arguments. */
    private static void commandLineForms() {
        Primitive store = Seam.primitiveFor("deposit_to_storage");
        Primitive.LineArgs dep = store.fromLine("3 64 0 Cobblestone 20,dirt", null);
        Coercion.Result c = Coercion.coerce(store.signature(), dep.raw(), Coercion.Ids.REGISTRIES);
        Map<String, Integer> want = new LinkedHashMap<>();
        want.put("cobblestone", 20);
        want.put("dirt", null);
        require(c.ok() && c.args().get("items").equals(want)
                        && c.args().get("c").equals(ContainerHandle.at(new AreaSpec.Pos(3, 64, 0))),
                "deposit_to_storage's line coerces to store(c, items): " + (c.ok() ? c.args() : c.error()));
        Coercion.Result none = Coercion.coerce(store.signature(), store.fromLine("3 64 0", null).raw(),
                Coercion.Ids.REGISTRIES);
        require(!none.ok() && none.error().code() == FailureCode.BAD_ARGS, "a deposit with no items is bad_args (E6)");
        require(store.fromLine("chest dirt", null) == null, "a line without coordinates stays with the command");
        Primitive give = Seam.primitiveFor("give");
        require(give.fromLine("bread 3", null).raw().equals(m("item", "bread", "n", "3")), "give <item> <n>");
        require(give.fromLine("Ellie diamond 3", null) == null, "a give to someone else stays the give command");
        Primitive equip = Seam.primitiveFor("equip");
        require(equip.fromLine("iron", null) == null && equip.fromLine("iron_sword", null) != null,
                "equip iron (a set) stays the command; equip iron_sword is the primitive");
        Primitive get = Seam.primitiveFor("get");
        require(get.fromLine("planks 4", null) == null && get.fromLine("log 20", null) == null,
                "get with a catalogue group stays the command");
        require(get.fromLine("Torches 5", null).raw().equals(m("item", "Torches", "n", 5)),
                "get <item> <n> with nothing carried is get(item, n)");
        Primitive smelt = Seam.primitiveFor("smelt");
        require(smelt.fromLine("raw_iron 32", null).raw().equals(m("output", "raw_iron", "n", "32")),
                "smelt's line is read as smelt(output, n); the snapshot turns an input into its output");
    }

    // --- talk --------------------------------------------------------------------------------------

    private static List<Case> talk() {
        return List.of(
                new Case("say", "say", m("text", "on my way"),
                        w -> w.spoken = new Seam.Spoken(List.of("hi"), 3),
                        w -> w.spoken = new Seam.Spoken(List.of("hi", "on my way"), 4), FailureCode.SUPERSEDED),
                new Case("confirm", "confirm", m("question", "dig here?"),
                        w -> w.confirmation = new Seam.Confirmation(1, "dig here?", null, null),
                        w -> w.confirmation = new Seam.Confirmation(1, "dig here?", Boolean.FALSE, "no"),
                        FailureCode.SUPERSEDED));
    }

    private static void confirmAnswersOnlyYesOrNo() {
        require(YesNo.parse("Yes!") == Boolean.TRUE && YesNo.parse(" go ahead ") == Boolean.TRUE
                        && YesNo.parse("nope.") == Boolean.FALSE && YesNo.parse("don't") == Boolean.FALSE,
                "yes and no phrases parse");
        require(YesNo.parse("yes but only the top") == null && YesNo.parse("maybe") == null && YesNo.parse("") == null,
                "anything else is not guessed");
        Seam seam = new Seam(null);
        require(!seam.reply("yes"), "a reply with no open question is not consumed");
        seam.ask("dig here?");
        require(seam.awaitingReply() && seam.reply("Yes") && seam.confirmation().answer() == Boolean.TRUE,
                "a yes answers the open question and is consumed");
        require(!seam.reply("no"), "a second reply does not re-answer it");
        seam.ask("fill it?");
        require(!seam.reply("only half of it") && seam.confirmation().answered()
                        && seam.confirmation().answer() == null && !seam.awaitingReply(),
                "another reply closes the question unanswered and goes on to the model");
        SeamTestWorld w = new SeamTestWorld();
        w.confirmation = seam.confirmation();
        Outcome other = Seam.verify(Seam.primitive("confirm"), coerce("confirm", m("question", "fill it?")), Map.of(),
                Primitive.TaskEnd.finished(null), w, List.of());
        require(!other.ok(), "confirm does not pass on a reply that was not yes or no: " + other);
        Seam.Confirmation asked = seam.ask("stay?");
        seam.closeConfirmation(asked.id());
        require(seam.confirmation() == null && !seam.awaitingReply(), "a stopped confirm drops its question");
    }

    // --- motion ------------------------------------------------------------------------------------

    private static final WorldReader AIR = new WorldReader() {
        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    };

    private static List<Case> motion() {
        Predicate<Object> fiveTorches = v -> ((Integer) v) >= 5;
        return List.of(
                new Case("follow_owner", "follow_owner", m("until_near_blocks", 3, "timeout_s", 30),
                        w -> w.owner = new Vec3(20, 64, 0),
                        w -> w.position = new Vec3(18, 64, 0), FailureCode.TIMEOUT),
                new Case("follow_owner, owner gone", "follow_owner", m("until_near_blocks", 3, "timeout_s", 30),
                        w -> w.owner = null,
                        w -> w.owner = new Vec3(1, 0, 0), FailureCode.NOT_FOUND),
                new Case("wait", "wait", m("ticks", 40),
                        w -> w.gameTime = 1000,
                        w -> w.gameTime = 1040, FailureCode.SUPERSEDED),
                new Case("wait_until", "wait_until",
                        m("query_name", "count", "args", m("item", "torch"), "predicate", fiveTorches, "timeout_s", 10),
                        w -> w.reader = AIR,
                        w -> w.carry("torch", 5), FailureCode.TIMEOUT),
                new Case("wait_until, positional args", "wait_until",
                        m("query_name", "count", "args", List.of("Torches"), "predicate", fiveTorches, "timeout_s", 10),
                        w -> w.reader = AIR,
                        w -> w.carry("torch", 7), FailureCode.TIMEOUT));
    }

    private static void waitUntilRefusesWhatItCannotRecheck() {
        Primitive p = Seam.primitive("wait_until");
        Map<String, Object> big = coerce("wait_until", m("query_name", "find_blocks",
                "args", m("block", "oak_log", "radius", 16, "max", 4), "predicate", (Predicate<Object>) v -> true,
                "timeout_s", 10));
        ActionError refused = p.admit(big, null);
        require(refused != null && refused.code() == FailureCode.BUDGET,
                "wait_until on a radius-16 find_blocks is refused up front: " + refused);
        Map<String, Object> noPredicate = coerce("wait_until", m("query_name", "count", "args", m("item", "torch"),
                "predicate", "x > 3", "timeout_s", 10));
        ActionError text = p.admit(noPredicate, null);
        require(text != null && text.code() == FailureCode.BAD_ARGS, "a predicate must be a function: " + text);
    }

    // --- the shared check --------------------------------------------------------------------------

    private static void postconditionCatchesAFakedFinished(Case c) {
        Primitive p = Seam.primitive(c.name());
        require(p != null, c.label() + " is dispatched");
        Map<String, Object> args = coerce(c.name(), c.raw());

        SeamTestWorld done = new SeamTestWorld();
        c.setup().accept(done);
        Map<String, Object> pre = snapshot(p, args, done);
        c.real().accept(done);
        Outcome real = Seam.verify(p, args, pre, Primitive.TaskEnd.finished(null), done, List.of());
        require(real.ok(), c.label() + ": the postcondition passes on the real outcome: " + real);
        require(p.reconcile(args, pre, done) == Primitive.Reconcile.DONE,
                c.label() + ": an interrupted call whose effect shows is done");

        SeamTestWorld idle = new SeamTestWorld();
        c.setup().accept(idle);
        Map<String, Object> idlePre = snapshot(p, args, idle);
        Outcome faked = Seam.verify(p, args, idlePre, Primitive.TaskEnd.finished(null), idle, List.of());
        require(!faked.ok() && faked.error().code() == c.faked(), c.label() + ": a faked Finished fails with "
                + c.faked().wire() + ": " + faked);
        Primitive.Reconcile r = p.reconcile(args, idlePre, idle);
        require(r == (p.idempotent() ? Primitive.Reconcile.RERUN : Primitive.Reconcile.ASK),
                c.label() + ": an interrupted call with no effect " + (p.idempotent() ? "re-runs" : "asks") + ": " + r);

        // Red witness: the same faked Finished with the postcondition removed is a success.
        Outcome unchecked = Seam.verify(withoutPostcondition(p), args, idlePre, Primitive.TaskEnd.finished(null), idle,
                List.of());
        require(unchecked.ok(), c.label() + ": without its postcondition a faked Finished passes (red witness)");
    }

    private static void everyPrimitiveIsCovered(List<Case> cases) {
        for (Signature s : SignatureTable.all()) {
            if (s.bound() && s.kind() == Signature.Kind.PRIMITIVE && !List.of("goto", "excavate").contains(s.name())) {
                require(cases.stream().anyMatch(c -> c.name().equals(s.name())),
                        s.name() + " has a postcondition case here");
            }
        }
    }

    static Map<String, Object> snapshot(Primitive p, Map<String, Object> args, SeamTestWorld w) {
        try {
            return p.snapshot(args, w);
        } catch (Coercion.Failure f) {
            throw new IllegalStateException("snapshot failed: " + f.error);
        }
    }

    static Map<String, Object> coerce(String name, Map<String, Object> raw) {
        Coercion.Result r = Coercion.coerce(SignatureTable.get(name), raw, Coercion.Ids.REGISTRIES);
        if (!r.ok()) {
            throw new IllegalStateException(name + " " + raw + " does not coerce: " + r.error());
        }
        return r.args();
    }

    static Primitive withoutPostcondition(Primitive p) {
        return new Primitive() {
            @Override
            public Signature signature() {
                return p.signature();
            }

            @Override
            public LineArgs fromLine(String argsText, Context ctx) {
                return p.fromLine(argsText, ctx);
            }

            @Override
            public ActionError admit(Map<String, Object> args, Context ctx) {
                return p.admit(args, ctx);
            }

            @Override
            public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre,
                    Context ctx, Consumer<TaskEnd> ended) {
                p.start(args, options, pre, ctx, ended);
            }

            @Override
            public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
                return null;
            }
        };
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("primitives self-test failed: " + message);
        }
    }
}
