package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code equip(item)}: hold a weapon or wear a piece of armor. Done when the item is in the main hand
 * or an armor slot. The {@code equip} command's set words ({@code equip iron}) and item lists stay the
 * command.
 */
final class EquipPrimitive extends Base {
    private static final Set<String> SETS = Set.of("leather", "iron", "gold", "diamond", "netherite", "chainmail");

    EquipPrimitive() {
        super("equip");
    }

    @Override
    public LineArgs fromLine(String argsText, Context ctx) {
        String a = argsText.trim();
        if (a.isEmpty() || a.contains(" ") || a.contains(",") || a.contains("[")
                || SETS.contains(a.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return LineArgs.of(Map.of("item", a), Map.of());
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Calls.runCommand(ctx, "equip " + args.get("item"), ended);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String item = (String) args.get("item");
        if (world.equipped().contains(item)) {
            return null;
        }
        boolean carried = Calls.count(world.inventory(), item) > 0;
        return Calls.fail(carried ? FailureCode.UNREACHABLE : FailureCode.MISSING_ITEM, carried
                ? "I carry " + Calls.human(item) + " but could not put it on" : "I have no " + Calls.human(item),
                "item", item, "equipped", world.equipped());
    }
}
