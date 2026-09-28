package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * {@code give_owner(item, n)}: hand n of an item to the owner. Done when the owner's inventory gained
 * them, or they lie on the ground at the owner's feet. A {@code give} line to another player stays
 * the {@code give} command.
 */
final class GiveOwnerPrimitive extends Base {
    /** How near the owner a dropped item still counts as handed over. */
    static final double AT_OWNER = 4.0;

    GiveOwnerPrimitive() {
        super("give_owner");
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        Player o = ctx.mod().getOwner();
        return o == null || o.level() != ctx.mod().getWorld() ? Calls.fail(FailureCode.NOT_FOUND, "my owner is not here")
                : null;
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) {
        String item = (String) args.get("item");
        Map<String, Integer> owner = world.ownerInventory();
        Vec3 at = world.ownerPosition();
        return Map.of("owner", owner == null ? 0 : Calls.count(owner, item),
                "ground", at == null ? 0 : Calls.count(world.groundItems(at, AT_OWNER), item),
                "carried", Calls.count(world.inventory(), item));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Player o = ctx.mod().getOwner();
        if (o == null) {
            ended.accept(TaskEnd.failed(Calls.fail(FailureCode.NOT_FOUND, "my owner is not here")));
            return;
        }
        Calls.runCommand(ctx, "give " + o.getName().getString() + " " + args.get("item") + " " + args.get("n"), ended);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String item = (String) args.get("item");
        int n = (Integer) args.get("n");
        Map<String, Integer> owner = world.ownerInventory();
        Vec3 at = world.ownerPosition();
        if (owner == null || at == null) {
            return Calls.fail(FailureCode.NOT_FOUND, "my owner is not here to take it");
        }
        int handed = Calls.count(owner, item) - ((Number) pre.get("owner")).intValue();
        int dropped = Math.max(0, Calls.count(world.groundItems(at, AT_OWNER), item) - ((Number) pre.get("ground")).intValue());
        if (handed + dropped >= n) {
            return null;
        }
        int carried = Calls.count(world.inventory(), item);
        FailureCode code = carried < n - handed - dropped ? FailureCode.MISSING_ITEM : FailureCode.UNREACHABLE;
        return Calls.fail(code, "my owner got " + Math.max(0, handed + dropped) + " of " + n + " " + Calls.human(item),
                "item", item, "needed", n, "given", Math.max(0, handed + dropped), "have", carried);
    }
}
