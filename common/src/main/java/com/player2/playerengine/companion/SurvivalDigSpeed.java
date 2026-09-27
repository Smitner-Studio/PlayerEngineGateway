package com.player2.playerengine.companion;

/**
 * Vanilla 1.21.1 survival mining arithmetic, free of entity state so it can be checked headless.
 *
 * <p>Mirrors {@code Player.getDigSpeed} and {@code BlockBehaviour.getDestroyProgress}. The companion
 * is a {@code LivingEntity} registered with zombie attributes, so it lacks the player-only
 * {@code MINING_EFFICIENCY} and {@code SUBMERGED_MINING_SPEED} attributes that Efficiency and Aqua
 * Affinity act through; their vanilla values are reproduced here from the enchantment levels.
 * NeoForge's {@code PlayerEvent.BreakSpeed} is player-only and is not fired.
 */
public final class SurvivalDigSpeed {
    /** Ticks {@code MultiPlayerGameMode} waits after a non-instant break before the next block starts. */
    public static final int DESTROY_DELAY_TICKS = 5;

    /** {@code Attributes.SUBMERGED_MINING_SPEED} default; Aqua Affinity raises it to 1.0. */
    static final float SUBMERGED_MINING_SPEED = 0.2F;

    private SurvivalDigSpeed() {}

    /**
     * @param toolSpeed        {@code ItemStack.getDestroySpeed(state)} of the held item (1.0 bare hand)
     * @param efficiency       Efficiency level on the held item
     * @param hasteAmplifier   Haste or Conduit Power amplifier, or -1 when neither applies
     * @param fatigueAmplifier Mining Fatigue amplifier, or -1 when absent
     * @param blockBreakSpeed  {@code Attributes.BLOCK_BREAK_SPEED} value (1.0 when the entity lacks it)
     * @param eyeInWater       eyes in water
     * @param aquaAffinity     Aqua Affinity on the head slot
     * @param onGround         standing on a block
     */
    public static float digSpeed(float toolSpeed, int efficiency, int hasteAmplifier, int fatigueAmplifier,
                                 float blockBreakSpeed, boolean eyeInWater, boolean aquaAffinity, boolean onGround) {
        float f = toolSpeed;
        if (f > 1.0F && efficiency > 0) {
            f += (float)(efficiency * efficiency + 1);
        }
        if (hasteAmplifier >= 0) {
            f *= 1.0F + (float)(hasteAmplifier + 1) * 0.2F;
        }
        if (fatigueAmplifier >= 0) {
            f *= switch (fatigueAmplifier) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        f *= blockBreakSpeed;
        if (eyeInWater && !aquaAffinity) {
            f *= SUBMERGED_MINING_SPEED;
        }
        if (!onGround) {
            f /= 5.0F;
        }
        return f;
    }

    /** Fraction of the block broken per tick; 0 for unbreakable blocks (hardness -1). */
    public static float progressPerTick(float hardness, float digSpeed, boolean correctToolForDrops) {
        if (hardness == -1.0F) {
            return 0.0F;
        }
        return digSpeed / hardness / (correctToolForDrops ? 30 : 100);
    }
}
