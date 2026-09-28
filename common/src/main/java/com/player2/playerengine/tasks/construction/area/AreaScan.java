package com.player2.playerengine.tasks.construction.area;

import com.player2.playerengine.seam.FailureCode;
import java.util.ArrayList;
import java.util.List;

/**
 * The pre-scan an area command runs before touching anything, and the verdict its task reaches each
 * check. Pure: the world is read through {@link BlockLookup}, so the self-test uses a map.
 */
public final class AreaScan {
    /** Per-step time cap at survival parity: the size limit of a single dig comes from this. */
    public static final double MAX_STEP_SECONDS = 20 * 60;
    /** Walking and repositioning on top of pure break time. */
    public static final double MOVE_OVERHEAD = 2.0;
    /** Vanilla post-break delay the companion also waits under survival parity. */
    public static final int POST_BREAK_TICKS = 5;
    /** Falling blocks folded into the dig above one column of the ceiling. */
    public static final int MAX_FOLDED_PER_COLUMN = 6;
    /** Player-placed or container cells a box may hold before it needs the owner's confirmation. */
    public static final int MAX_EXCLUDED_WITHOUT_CONFIRM = 8;
    /** Free inventory slots an excavation needs before it starts. */
    public static final int MIN_FREE_SLOTS = 4;

    public enum Mode { EXCAVATE, FILL }

    /** What the scan needs to know about one block. */
    public record Cell(
            boolean loaded,
            boolean air,
            /** Any fluid, waterlogged blocks included. */
            String liquid,
            boolean replaceable,
            boolean falling,
            boolean blockEntity,
            boolean playerPlaced,
            boolean unbreakable,
            boolean avoidBreak,
            /** Ticks to break with the best tool in the inventory, without the post-break delay. */
            int breakTicks,
            /** True when the block drops nothing without a tool the companion lacks. */
            boolean needsMissingTool,
            /** True when this is already the block a fill wants. */
            boolean isFillBlock,
            String name) {
    }

    @FunctionalInterface
    public interface BlockLookup {
        Cell cell(int x, int y, int z);
    }

    public record Result(
            String refusal,
            List<long[]> targets,
            List<long[]> checkCells,
            List<long[]> shellProtected,
            List<long[]> shellAirBefore,
            int excluded,
            int folded,
            double estimatedSeconds,
            /** The refusal clears once the owner confirms (the box holds players' blocks). */
            boolean needsConfirm,
            /** Why it refused, as the seam reports it; null when it did not. */
            FailureCode code) {
        public boolean refused() {
            return refusal != null;
        }

        static Result refuse(FailureCode code, String why) {
            return new Result(why, List.of(), List.of(), List.of(), List.of(), 0, 0, 0, false, code);
        }
    }

    private AreaScan() {
    }

    /**
     * @param freeSlots   empty inventory slots (excavation only)
     * @param fillHave    how many of the fill block the inventory holds (fill only)
     * @param confirmed   the owner confirmed a box that holds their blocks
     */
    public static Result scan(Mode mode, AreaSpec.Box b, BlockLookup world, int freeSlots, int fillHave,
                              String fillName, boolean confirmed) {
        List<long[]> targets = new ArrayList<>();
        List<long[]> checkCells = new ArrayList<>();
        List<long[]> shellProtected = new ArrayList<>();
        List<long[]> shellAirBefore = new ArrayList<>();
        int excluded = 0;
        long ticks = 0;
        String missingToolFor = null;

        // Box plus shell: one block on the sides and floor, two above the ceiling.
        for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
            for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) {
                for (int y = b.minY() - 1; y <= b.maxY() + 2; y++) {
                    Cell c = world.cell(x, y, z);
                    if (c == null || !c.loaded()) {
                        return Result.refuse(FailureCode.NOT_LOADED, "part of that ground is not loaded; go closer first");
                    }
                    boolean inBox = b.contains(x, y, z);
                    if (c.liquid() != null && (mode == Mode.EXCAVATE || inBox)) {
                        return Result.refuse(FailureCode.LIQUID, "there is " + c.liquid() + (inBox ? " in" : " right next to")
                                + " that space; digging it would flood it");
                    }
                    long[] p = {x, y, z};
                    if (!inBox) {
                        if (c.playerPlaced()) {
                            shellProtected.add(p);
                        } else if (c.air()) {
                            shellAirBefore.add(p);
                        }
                        continue;
                    }
                    boolean keep = c.playerPlaced() || c.blockEntity() || c.avoidBreak() || c.unbreakable();
                    if (mode == Mode.EXCAVATE) {
                        if (keep && !c.air()) {
                            excluded++;
                            continue;
                        }
                        checkCells.add(p);
                        if (!c.air()) {
                            targets.add(p);
                            ticks += c.breakTicks() + POST_BREAK_TICKS;
                            if (c.needsMissingTool() && missingToolFor == null) {
                                missingToolFor = c.name();
                            }
                        }
                    } else {
                        if (c.isFillBlock() || (!c.air() && !c.replaceable())) {
                            continue;
                        }
                        if (keep) {
                            excluded++;
                            continue;
                        }
                        checkCells.add(p);
                        targets.add(p);
                    }
                }
            }
        }

        int folded = 0;
        if (mode == Mode.EXCAVATE) {
            // Sand and gravel over the ceiling would pour into the room: dig them with it.
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    for (int k = 1; ; k++) {
                        Cell c = world.cell(x, b.maxY() + k, z);
                        if (c == null || !c.loaded() || !c.falling() || c.playerPlaced()) {
                            break;
                        }
                        if (k > MAX_FOLDED_PER_COLUMN) {
                            return Result.refuse(FailureCode.PROTECTED, "there is a deep pocket of loose " + c.name()
                                    + " overhead that would cave in");
                        }
                        long[] p = {x, b.maxY() + k, z};
                        targets.add(p);
                        checkCells.add(p);
                        ticks += c.breakTicks() + POST_BREAK_TICKS;
                        folded++;
                    }
                }
            }
        }

        if (excluded > MAX_EXCLUDED_WITHOUT_CONFIRM && !confirmed) {
            return new Result(excluded + " blocks in there were placed by players or hold items; I will leave "
                    + "them standing, but ask the owner to confirm first, then repeat the command with confirm=yes",
                    List.of(), List.of(), List.of(), List.of(), excluded, 0, 0, true, FailureCode.PROTECTED);
        }
        double seconds = ticks / 20.0 * MOVE_OVERHEAD;
        if (mode == Mode.EXCAVATE) {
            if (missingToolFor != null) {
                return Result.refuse(FailureCode.MISSING_ITEM, "it needs a pickaxe that can mine " + missingToolFor + "; get one first");
            }
            if (!targets.isEmpty() && freeSlots < MIN_FREE_SLOTS) {
                return Result.refuse(FailureCode.CONTAINER_FULL, "my pack is nearly full; empty it first");
            }
            if (seconds > MAX_STEP_SECONDS) {
                long fits = Math.max(1, (long) Math.floor(targets.size() * MAX_STEP_SECONDS / seconds));
                return Result.refuse(FailureCode.BUDGET, "that would take about " + Math.round(seconds / 60) + " minutes with my "
                        + "current tools; dig at most about " + fits + " blocks per step");
            }
        } else if (targets.size() > fillHave) {
            return Result.refuse(FailureCode.MISSING_ITEM, "that needs " + (targets.size() - fillHave) + " more " + fillName
                    + " than I carry; get them first");
        }
        return new Result(null, targets, checkCells, shellProtected, shellAirBefore, excluded, folded, seconds, false, null);
    }

    /** Ticks to break one block with a tool of {@code toolSpeed}, as the survival dig arithmetic has it. */
    public static int breakTicks(float hardness, float toolSpeed, boolean correctToolForDrops) {
        if (hardness < 0) {
            return Integer.MAX_VALUE / 4;
        }
        if (hardness == 0) {
            return 1;
        }
        float perTick = toolSpeed / hardness / (correctToolForDrops ? 30 : 100);
        return (int) Math.ceil(1.0 / perTick);
    }

    public enum Verdict { CONTINUE, SUCCESS, FAIL }

    /** @param code why it failed, as the seam reports it; null unless the verdict is FAIL */
    public record Judgement(Verdict verdict, String reason, FailureCode code) {
    }

    /**
     * The task's verdict each check. The world is the witness: success is {@code remaining == 0} and
     * nothing else, because the builder going idle also happens when it gives up.
     */
    public static Judgement judge(boolean builderActive, boolean builderPaused, int remaining, int total,
                                  long nowMillis, long lastProgressMillis, long stallMillis, long deadlineMillis) {
        if (remaining == 0) {
            return new Judgement(Verdict.SUCCESS, null, null);
        }
        String progress = " (" + (total - remaining) + " of " + total + " done)";
        if (!builderActive) {
            return new Judgement(Verdict.FAIL, "gave up with " + remaining + " blocks left" + progress, FailureCode.UNREACHABLE);
        }
        if (builderPaused) {
            return new Judgement(Verdict.FAIL, "stuck: can't reach part of it" + progress, FailureCode.UNREACHABLE);
        }
        if (nowMillis - lastProgressMillis > stallMillis) {
            return new Judgement(Verdict.FAIL, "no progress for a while" + progress, FailureCode.UNREACHABLE);
        }
        if (nowMillis > deadlineMillis) {
            return new Judgement(Verdict.FAIL, "ran out of time" + progress, FailureCode.TIMEOUT);
        }
        return new Judgement(Verdict.CONTINUE, null, null);
    }
}
