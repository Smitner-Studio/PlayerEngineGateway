package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.ContainerHandle;
import com.player2.playerengine.seam.FailureCode;
import java.util.Map;

/**
 * {@code withdraw(c, items)}: take items out of a container. Done when the inventory gained exactly
 * what the container lost, and all that was asked for (an entry without a count is all the container
 * holds of it).
 */
final class WithdrawPrimitive extends TransferPrimitive {
    WithdrawPrimitive() {
        super("withdraw", "withdraw_from_storage");
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) throws Coercion.Failure {
        if (!(args.get("items") instanceof Map<?, ?>)) {
            throw new Coercion.Failure(Calls.fail(FailureCode.BAD_ARGS, "withdraw takes named items, not "
                    + args.get("items")));
        }
        ContainerHandle.Contents box = world.container(((ContainerHandle) args.get("c")).pos());
        return pre(box, world.inventory(), request(args.get("items")));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        return check(pre, world, false);
    }
}
