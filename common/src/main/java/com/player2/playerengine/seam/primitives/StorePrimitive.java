package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.ContainerHandle;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.LiveWorld;
import com.player2.playerengine.seam.SignatureTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code store(c?, items)}: put items from the inventory into a container; without {@code c}, the
 * nearest container in sight within 16 blocks. {@code "all_except_tools"} is everything carried but
 * tools, weapons and armor. Done when the container gained exactly what the inventory lost, and all
 * that was asked for.
 */
final class StorePrimitive extends TransferPrimitive {
    StorePrimitive() {
        super("store", "deposit_to_storage");
    }

    /** Tools, weapons and armor: what {@code all_except_tools} keeps. */
    static boolean isTool(String id) {
        Item item = LiveWorld.item(id);
        return item != null && new ItemStack(item).isDamageableItem();
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) throws Coercion.Failure {
        ContainerHandle c = (ContainerHandle) args.get("c");
        if (c == null) {
            c = world.nearestContainer(SignatureTable.MAX_CONTAINER_RADIUS);
            if (c == null) {
                throw new Coercion.Failure(Calls.fail(FailureCode.NO_CONTAINER, "there is no container I can see within "
                        + SignatureTable.MAX_CONTAINER_RADIUS + " blocks; name one"));
            }
        }
        ContainerHandle.Contents box = world.container(c.pos());
        Map<String, Integer> inv = world.inventory();
        Map<String, Integer> request = new LinkedHashMap<>();
        if ("all_except_tools".equals(args.get("items"))) {
            for (String id : inv.keySet()) {
                if (!isTool(id)) {
                    request.put(id, 0);
                }
            }
        } else {
            for (Map.Entry<String, Integer> e : request(args.get("items")).entrySet()) {
                int have = Calls.count(inv, e.getKey());
                if (e.getValue() > have) {
                    throw new Coercion.Failure(Calls.fail(FailureCode.MISSING_ITEM, "I carry " + have + " "
                                    + Calls.human(e.getKey()) + ", not " + e.getValue(), "item", e.getKey(),
                            "needed", e.getValue(), "have", have));
                }
                if (have > 0) {
                    request.put(e.getKey(), e.getValue());
                }
            }
        }
        if (request.isEmpty()) {
            throw new Coercion.Failure(Calls.fail(FailureCode.MISSING_ITEM, "I carry none of that to store",
                    "carrying", List.copyOf(inv.keySet())));
        }
        return pre(box, inv, request);
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        return check(pre, world, true);
    }
}
