package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.ContainerHandle;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.MotionBounds;
import com.player2.playerengine.seam.Seam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What {@code store} and {@code withdraw} share. Both act on a whole container: a position names the
 * container it is part of, so a double chest named by either half is both halves (E8), and the
 * command runs against the canonical half. Both pass only when the container's delta and the
 * inventory's delta agree item by item and cover the request, so a deposit the world does not show
 * is never reported as done (E9).
 *
 * <p>The snapshot keeps the container, both sides' counts, and the request with each entry's count,
 * 0 meaning "all of it".
 */
abstract class TransferPrimitive extends Base {
    private final String command;

    TransferPrimitive(String name, String command) {
        super(name);
        this.command = command;
    }

    /** {@code <x> <y> <z> <items...>}, the storage commands' grammar; any other form stays with the command. */
    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        String[] t = argsText.trim().split("\\s+", 4);
        if (t.length < 3 || !t[0].matches("-?\\d+") || !t[1].matches("-?\\d+") || !t[2].matches("-?\\d+")) {
            return null;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("c", t[0] + " " + t[1] + " " + t[2]);
        if (t.length == 4) {
            raw.put("items", t[3]);
        }
        return LineArgs.of(raw, Map.of());
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        ContainerHandle c = (ContainerHandle) args.get("c");
        return c == null ? null : MotionBounds.check(ctx.region(), Calls.dimension(ctx), c.pos());
    }

    /** The request as {@code {item: count}}, 0 for all; {@code all_except_tools} is resolved by the caller. */
    @SuppressWarnings("unchecked")
    static Map<String, Integer> request(Object items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        ((Map<String, Integer>) items).forEach((k, v) -> out.put(k, v == null ? 0 : v));
        return out;
    }

    static Map<String, Object> pre(ContainerHandle.Contents box, Map<String, Integer> inventory,
            Map<String, Integer> request) {
        Map<String, Object> pre = new LinkedHashMap<>();
        pre.put("container", box.handle().toState());
        pre.put("before", Calls.copy(box.items()));
        pre.put("inventory", Calls.copy(inventory));
        pre.put("request", request);
        return pre;
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        ContainerHandle h;
        try {
            h = ContainerHandle.fromState(pre.get("container"));
        } catch (Coercion.Failure f) {
            ended.accept(TaskEnd.failed(f.error));
            return;
        }
        List<String> entries = new ArrayList<>();
        Calls.counts(pre, "request").forEach((item, n) -> entries.add(n == 0 ? item : item + " " + n));
        String line = command + " " + h.pos().x() + " " + h.pos().y() + " " + h.pos().z() + " "
                + String.join(", ", entries);
        Seam seam = ctx.mod().getCommandExecutor().seam();
        Calls.runCommand(ctx, line, end -> {
            if (end.error() == null) {
                seam.know(h);
            }
            ended.accept(end);
        });
    }

    /**
     * The shared check. {@code into} is the side that gains: the container for a store, the inventory
     * for a withdraw.
     */
    ActionError check(Map<String, Object> pre, World world, boolean intoContainer) {
        ContainerHandle.Contents now;
        try {
            now = world.container(ContainerHandle.fromState(pre.get("container")).pos());
        } catch (Coercion.Failure f) {
            return f.error;
        }
        Map<String, Integer> before = Calls.counts(pre, "before");
        Map<String, Integer> invBefore = Calls.counts(pre, "inventory");
        Map<String, Integer> inv = world.inventory();
        Map<String, Integer> source = intoContainer ? invBefore : before;
        Map<String, Object> moved = new LinkedHashMap<>();
        Map<String, Object> shortBy = new LinkedHashMap<>();
        int total = 0;
        boolean sourceLacked = false;
        for (Map.Entry<String, Integer> e : Calls.counts(pre, "request").entrySet()) {
            String item = e.getKey();
            int containerDelta = Calls.count(now.items(), item) - Calls.count(before, item);
            int inventoryDelta = Calls.count(inv, item) - Calls.count(invBefore, item);
            int gain = intoContainer ? containerDelta : inventoryDelta;
            if (containerDelta != -inventoryDelta) {
                return Calls.fail(FailureCode.UNREACHABLE, (intoContainer ? "my inventory lost " + -inventoryDelta
                                + " " + Calls.human(item) + " but the container gained " + containerDelta
                                : "the container lost " + -containerDelta + " " + Calls.human(item)
                                        + " but I gained " + inventoryDelta),
                        "item", item, "container_delta", containerDelta, "inventory_delta", inventoryDelta,
                        "container", now.handle().id());
            }
            int want = e.getValue() == 0 ? Calls.count(source, item) : e.getValue();
            if (gain > 0) {
                moved.put(item, gain);
                total += gain;
            }
            if (gain < want) {
                shortBy.put(item, want - gain);
            }
            if (Calls.count(source, item) < want || Calls.count(source, item) == 0) {
                sourceLacked = true;
            }
        }
        if (shortBy.isEmpty() && total > 0) {
            return null;
        }
        String where = "the container at " + now.handle().pos().x() + " " + now.handle().pos().y() + " "
                + now.handle().pos().z();
        FailureCode code;
        String why;
        if (sourceLacked && !intoContainer) {
            code = FailureCode.MISSING_ITEM;
            why = where + " does not hold what was asked for";
        } else if (sourceLacked) {
            code = FailureCode.MISSING_ITEM;
            why = "I do not carry what was asked for";
        } else if ((intoContainer ? now.freeSlots() : world.freeSlots()) == 0) {
            code = FailureCode.CONTAINER_FULL;
            why = intoContainer ? where + " is full" : "my inventory is full";
        } else {
            code = FailureCode.UNREACHABLE;
            why = total == 0 ? "nothing moved " + (intoContainer ? "into " : "out of ") + where
                    : "only part of it moved " + (intoContainer ? "into " : "out of ") + where;
        }
        return Calls.fail(code, why, "moved", moved, "short", shortBy, "container", now.handle().id());
    }
}
