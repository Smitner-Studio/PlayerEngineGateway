package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.FailureCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code smelt(output, n)}: cook n of an output from what the companion carries (E7). The input is
 * chosen from the inventory through the recipe manager, so {@code smelt("iron_ingot", 8)} uses raw
 * iron or iron ore, whichever it holds most of. Naming the input instead ({@code smelt raw_iron 8},
 * the command's old form) is read as its output, with a note. Done when the output grew by n.
 */
final class SmeltPrimitive extends Base {
    SmeltPrimitive() {
        super("smelt");
    }

    /** {@code smelt <item> [count]}. */
    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        String[] t = argsText.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty() || argsText.contains(",") || argsText.contains("[")) {
            return null;
        }
        Object n = 1;
        String[] name = t;
        if (t.length >= 2 && t[t.length - 1].matches("-?\\d+")) {
            n = t[t.length - 1];
            name = Arrays.copyOf(t, t.length - 1);
        }
        return LineArgs.of(Map.of("output", String.join(" ", name), "n", n), Map.of());
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) throws Coercion.Failure {
        String asked = (String) args.get("output");
        int n = (Integer) args.get("n");
        Map<String, Integer> inv = world.inventory();
        String output = asked;
        String note = null;
        String input = bestInput(output, inv, world);
        if (input == null) {
            String cooked = world.smeltResult(asked);
            if (cooked != null) {
                // Named the input: smelt what was named, into what it makes.
                output = cooked;
                input = Calls.count(inv, asked) > 0 ? asked : null;
                note = "smelt takes the output; " + asked + " read as smelting it into " + cooked;
            }
        }
        if (input == null) {
            List<String> inputs = inputsFor(output, world);
            if (inputs.isEmpty()) {
                throw new Coercion.Failure(Calls.fail(FailureCode.BAD_ARGS, "nothing smelts into " + Calls.human(output),
                        "output", output));
            }
            throw new Coercion.Failure(Calls.fail(FailureCode.MISSING_ITEM, "I carry nothing that smelts into "
                    + Calls.human(output), "output", output, "inputs", inputs));
        }
        int carried = Calls.count(inv, input);
        if (carried < n) {
            throw new Coercion.Failure(Calls.fail(FailureCode.MISSING_ITEM, "I carry " + carried + " "
                    + Calls.human(input) + ", enough for " + carried + " " + Calls.human(output) + ", not " + n,
                    "input", input, "needed", n, "have", carried));
        }
        Map<String, Object> pre = new LinkedHashMap<>();
        pre.put("output", output);
        pre.put("input", input);
        pre.put("have", Calls.count(inv, output));
        if (note != null) {
            pre.put("note", note);
        }
        return pre;
    }

    /** The carried item that smelts into {@code output}, the one held most of; null when none. */
    static String bestInput(String output, Map<String, Integer> inv, World world) {
        String best = null;
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            if (e.getValue() > 0 && output.equals(world.smeltResult(e.getKey()))
                    && (best == null || e.getValue() > inv.get(best))) {
                best = e.getKey();
            }
        }
        return best;
    }

    /** Every item that smelts into {@code output}, for the missing_item message; at most 8. */
    static List<String> inputsFor(String output, World world) {
        List<String> out = new ArrayList<>();
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
            String id = "minecraft".equals(rl.getNamespace()) ? rl.getPath() : rl.toString();
            if (output.equals(world.smeltResult(id))) {
                out.add(id);
                if (out.size() == 8) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Object note = pre.get("note");
        Calls.runCommand(ctx, "smelt " + pre.get("input") + " " + args.get("n"), end -> ended.accept(
                note == null || end.error() != null ? end
                        : TaskEnd.finished(end.note() == null ? (String) note : end.note() + "; " + note)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String output = (String) pre.get("output");
        String input = (String) pre.get("input");
        int n = (Integer) args.get("n");
        int made = Calls.count(world.inventory(), output) - ((Number) pre.get("have")).intValue();
        if (made >= n) {
            return null;
        }
        int left = Calls.count(world.inventory(), input);
        return Calls.fail(left < n - Math.max(0, made) ? FailureCode.MISSING_ITEM : FailureCode.UNREACHABLE,
                "I smelted " + Math.max(0, made) + " of " + n + " " + Calls.human(output), "output", output,
                "input", input, "needed", n, "made", Math.max(0, made));
    }
}
