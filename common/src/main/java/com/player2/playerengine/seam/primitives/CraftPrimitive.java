package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.LiveWorld;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccessImpl;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * {@code craft(item, n)}: craft n more of an item that has a crafting recipe. Its body is the
 * {@code get} command's crafting path, which gathers missing ingredients as it goes. Done when the
 * inventory gained at least n; not idempotent, so an interrupted craft asks rather than re-runs.
 */
final class CraftPrimitive extends Base {
    private static final RecipeAccessImpl RECIPES = new RecipeAccessImpl();

    CraftPrimitive() {
        super("craft");
    }

    @Override
    public ActionError admit(Map<String, Object> args, Context ctx) {
        String id = (String) args.get("item");
        Item item = LiveWorld.item(id);
        ServerLevel level = ctx.mod().getWorld();
        if (item == null || RECIPES.selectRecipeForResult(level.getRecipeManager(), item, level.registryAccess()).isEmpty()) {
            return Calls.fail(FailureCode.BAD_ARGS, "nothing is crafted into " + Calls.human(id)
                    + "; get gathers it, smelt cooks it", "item", id);
        }
        return Calls.standingInRegion(ctx);
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) {
        return Map.of("have", Calls.count(world.inventory(), (String) args.get("item")));
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        Calls.runCommand(ctx, "get " + args.get("item") + " " + args.get("n"), ended);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        String item = (String) args.get("item");
        int n = (Integer) args.get("n");
        int made = Calls.count(world.inventory(), item) - ((Number) pre.get("have")).intValue();
        return made >= n ? null : Calls.fail(FailureCode.MISSING_ITEM, "I crafted " + Math.max(0, made) + " of " + n
                + " " + Calls.human(item), "item", item, "needed", n, "made", Math.max(0, made));
    }
}
