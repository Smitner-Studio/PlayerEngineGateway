package com.player2.playerengine.companion;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Player combat numbers for the companion. Player2NPC registers the companion with zombie
 * attributes (attack damage 3, armour 2, no attack speed), so under survival parity the base values
 * are pinned to a player's and each hit is scaled by the vanilla attack-cooldown curve
 * ({@code Player.attack}).
 */
public final class SurvivalCombat {
    public static final double PLAYER_ATTACK_DAMAGE = 1.0;
    public static final double PLAYER_ARMOR = 0.0;
    /** {@code Attributes.ATTACK_SPEED} default; weapons add negative modifiers to it. */
    public static final double PLAYER_ATTACK_SPEED = 4.0;

    /** Transient ATTACK_DAMAGE modifier that carries the cooldown scale for the one hit it wraps. */
    public static final ResourceLocation COOLDOWN_MODIFIER =
            ResourceLocation.fromNamespaceAndPath("playerengine", "survival_attack_cooldown");

    private SurvivalCombat() {}

    /**
     * Pins the zombie-derived base values to a player's under parity, and puts the entity type's
     * registered defaults back without it, so the switch is reversible for saved companions.
     */
    public static void applyBaseAttributes(AttributeMap attributes, boolean survivalParity, AttributeSupplier typeDefaults) {
        setBase(attributes, Attributes.ATTACK_DAMAGE, survivalParity ? PLAYER_ATTACK_DAMAGE : defaultOf(typeDefaults, Attributes.ATTACK_DAMAGE));
        setBase(attributes, Attributes.ARMOR, survivalParity ? PLAYER_ARMOR : defaultOf(typeDefaults, Attributes.ARMOR));
    }

    private static double defaultOf(AttributeSupplier typeDefaults, Holder<Attribute> attribute) {
        return typeDefaults != null && typeDefaults.hasAttribute(attribute)
                ? typeDefaults.getBaseValue(attribute)
                : Double.NaN;
    }

    private static void setBase(AttributeMap attributes, Holder<Attribute> attribute, double value) {
        if (Double.isNaN(value)) {
            return;
        }
        AttributeInstance instance = attributes.getInstance(attribute);
        if (instance != null && instance.getBaseValue() != value) {
            instance.setBaseValue(value);
        }
    }

    /** A player's attack speed holding {@code stack}: 4 plus the item's main-hand additive modifiers. */
    public static double attackSpeed(ItemStack stack) {
        double[] speed = {PLAYER_ATTACK_SPEED};
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        modifiers.forEach(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_SPEED) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                speed[0] += modifier.amount();
            }
        });
        return speed[0];
    }

    /** Ticks for a full charge ({@code Player.getCurrentItemAttackStrengthDelay}). */
    public static float attackDelayTicks(double attackSpeed) {
        return (float)(1.0 / attackSpeed * 20.0);
    }

    /** {@code Player.getAttackStrengthScale(0.5F)}, the value {@code Player.attack} uses. */
    public static float strengthScale(int ticksSinceAttack, float delayTicks) {
        return Mth.clamp(((float)ticksSinceAttack + 0.5F) / delayTicks, 0.0F, 1.0F);
    }

    /** Base-damage multiplier of a hit at {@code strengthScale}: 0.2 uncharged, 1.0 fully charged. */
    public static float damageMultiplier(float strengthScale) {
        return 0.2F + strengthScale * strengthScale * 0.8F;
    }
}
