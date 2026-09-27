package com.player2.playerengine.tasks.container;

import com.player2.playerengine.util.ItemTarget;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Moves items from the companion's inventory into a container, one inventory stack per call.
 * Items are conserved: the container gains exactly what the inventory loses.
 */
final class ContainerDeposit {
    private ContainerDeposit() {
    }

    /**
     * Moves up to {@code owed} items matching {@code target} out of one inventory stack of
     * {@code from} into {@code to}.
     *
     * @return how many items moved; 0 when nothing matching is held or nothing fits
     */
    static int moveOneStack(Container from, Container to, ItemTarget target, int owed) {
        if (owed <= 0) {
            return 0;
        }
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack held = from.getItem(i);
            if (held.isEmpty() || !target.matches(held.getItem())) {
                continue;
            }
            int offered = Math.min(owed, held.getCount());
            int fits = offered - insert(to, held.copyWithCount(offered), true).getCount();
            if (fits <= 0) {
                continue;
            }
            insert(to, held.copyWithCount(fits), false);
            ItemStack left = held.copy();
            left.shrink(fits);
            from.setItem(i, left.isEmpty() ? ItemStack.EMPTY : left);
            return fits;
        }
        return 0;
    }

    /**
     * Inserts {@code stack} into {@code inventory}: first onto matching stacks, then into empty slots.
     * A simulated insert never touches the inventory.
     *
     * @return the part of {@code stack} that did not fit; {@code stack} itself is not modified
     */
    static ItemStack insert(Container inventory, ItemStack stack, boolean simulate) {
        ItemStack rest = stack.copy();
        for (int i = 0; i < inventory.getContainerSize() && !rest.isEmpty(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(rest, slot)) {
                continue;
            }
            int room = Math.min(slot.getMaxStackSize(), inventory.getMaxStackSize()) - slot.getCount();
            int moved = Math.min(rest.getCount(), room);
            if (moved > 0) {
                if (!simulate) {
                    inventory.setItem(i, slot.copyWithCount(slot.getCount() + moved));
                }
                rest.shrink(moved);
            }
        }
        for (int i = 0; i < inventory.getContainerSize() && !rest.isEmpty(); i++) {
            if (!inventory.getItem(i).isEmpty() || !inventory.canPlaceItem(i, rest)) {
                continue;
            }
            int moved = Math.min(rest.getCount(), Math.min(rest.getMaxStackSize(), inventory.getMaxStackSize()));
            if (!simulate) {
                inventory.setItem(i, rest.copyWithCount(moved));
            }
            rest.shrink(moved);
        }
        return rest;
    }
}
