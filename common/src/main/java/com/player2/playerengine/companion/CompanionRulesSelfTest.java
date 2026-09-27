package com.player2.playerengine.companion;

import java.util.Properties;

/**
 * Checks the survival mining arithmetic against vanilla 1.21.1 break times and the operator rules
 * parsing. Break times are the Minecraft Wiki "Breaking" table values for stone (hardness 1.5),
 * which follow from {@code Player.getDigSpeed} and {@code BlockBehaviour.getDestroyProgress}.
 * Run with {@code ./gradlew :common:companionSelfTest}.
 */
public final class CompanionRulesSelfTest {
    private static final float STONE = 1.5F;
    private static final float HAND = 1.0F;
    private static final float WOODEN_PICKAXE = 2.0F;
    private static final float IRON_PICKAXE = 6.0F;
    private static final float DIAMOND_PICKAXE = 8.0F;

    private static int checks;

    private CompanionRulesSelfTest() {
    }

    public static void main(String[] args) {
        stoneBreakTimesMatchVanilla();
        effectsScaleLikeVanilla();
        waterPenaltyNeedsAquaAffinityToLift();
        airbornePenalty();
        unbreakableAndInstantBlocks();
        rulesDefaultsAndParsing();
        progressChatLevels();
        System.out.println("companion self-test: " + checks + " checks passed");
    }

    /** Ticks of held attack one block needs: progress starts at 0 and gains one delta per tick. */
    private static int ticksToBreak(float toolSpeed, int efficiency, boolean correctTool,
                                    boolean eyeInWater, boolean aquaAffinity, boolean onGround,
                                    int haste, int fatigue) {
        float speed = SurvivalDigSpeed.digSpeed(toolSpeed, efficiency, haste, fatigue, 1.0F,
                eyeInWater, aquaAffinity, onGround);
        float delta = SurvivalDigSpeed.progressPerTick(STONE, speed, correctTool);
        // Progress after n ticks is delta * n, as LivingEntityInteractionManager.continueMining
        // computes it (the vanilla client sums floats instead, which can land one tick later).
        int ticks = 0;
        while (delta * ticks < 1.0F) {
            ticks++;
            if (ticks >= 100_000) {
                throw new AssertionError("mining never finishes");
            }
        }
        return ticks;
    }

    private static int ticksOnGround(float toolSpeed, int efficiency, boolean correctTool) {
        return ticksToBreak(toolSpeed, efficiency, correctTool, false, false, true, -1, -1);
    }

    private static void stoneBreakTimesMatchVanilla() {
        // Wiki: hand 7.5 s, wooden 1.15 s, iron 0.4 s, diamond 0.3 s, diamond + Efficiency V 0.1 s.
        requireTicks(150, ticksOnGround(HAND, 0, false), "stone by hand");
        requireTicks(23, ticksOnGround(WOODEN_PICKAXE, 0, true), "stone with a wooden pickaxe");
        requireTicks(8, ticksOnGround(IRON_PICKAXE, 0, true), "stone with an iron pickaxe");
        requireTicks(6, ticksOnGround(DIAMOND_PICKAXE, 0, true), "stone with a diamond pickaxe");
        requireTicks(2, ticksOnGround(DIAMOND_PICKAXE, 5, true), "stone with diamond + Efficiency V");
        // Efficiency only applies when the tool is already faster than a hand on this block.
        requireTicks(150, ticksOnGround(HAND, 5, false), "Efficiency on a non-tool does nothing");
    }

    private static void effectsScaleLikeVanilla() {
        // Haste II: x1.4. Iron on stone 6 * 1.4 / 1.5 / 30 = 0.1867 per tick -> 6 ticks.
        requireTicks(6, ticksToBreak(IRON_PICKAXE, 0, true, false, false, true, 1, -1), "Haste II iron");
        // Mining Fatigue I: x0.3. 1.8 / 1.5 / 30 = 0.04 per tick -> 25 ticks.
        requireTicks(25, ticksToBreak(IRON_PICKAXE, 0, true, false, false, true, -1, 0), "Mining Fatigue I iron");
        require(SurvivalDigSpeed.digSpeed(IRON_PICKAXE, 0, -1, 1, 1.0F, false, false, true) == IRON_PICKAXE * 0.09F,
                "Mining Fatigue II multiplies by 0.09");
        require(SurvivalDigSpeed.digSpeed(IRON_PICKAXE, 0, -1, -1, 0.5F, false, false, true) == 3.0F,
                "block_break_speed attribute scales the result");
    }

    private static void waterPenaltyNeedsAquaAffinityToLift() {
        // Submerged mining speed 0.2: iron on stone 1.2 / 1.5 / 30 = 0.02667 per tick -> 38 ticks.
        requireTicks(38, ticksToBreak(IRON_PICKAXE, 0, true, true, false, true, -1, -1), "underwater without Aqua Affinity");
        requireTicks(8, ticksToBreak(IRON_PICKAXE, 0, true, true, true, true, -1, -1), "underwater with Aqua Affinity");
        requireTicks(8, ticksToBreak(IRON_PICKAXE, 0, true, false, true, true, -1, -1), "Aqua Affinity on land changes nothing");
    }

    private static void airbornePenalty() {
        // Airborne /5: 1.2 / 1.5 / 30 = 0.02667 per tick -> 38 ticks.
        requireTicks(38, ticksToBreak(IRON_PICKAXE, 0, true, false, false, false, -1, -1), "iron while airborne");
        requireTicks(188, ticksToBreak(IRON_PICKAXE, 0, true, true, false, false, -1, -1),
                "underwater and floating stacks both penalties (x0.04)");
    }

    private static void unbreakableAndInstantBlocks() {
        require(SurvivalDigSpeed.progressPerTick(-1.0F, 100.0F, true) == 0.0F, "bedrock never progresses");
        require(SurvivalDigSpeed.progressPerTick(0.0F, 1.0F, true) >= 1.0F, "zero-hardness blocks break instantly");
        require(SurvivalDigSpeed.progressPerTick(STONE, IRON_PICKAXE, false)
                        == IRON_PICKAXE / STONE / 100.0F,
                "a tool that cannot harvest the block mines at the 1/100 rate");
    }

    private static void rulesDefaultsAndParsing() {
        CompanionRules empty = new CompanionRules(new Properties());
        require(empty.survivalParity(), "survivalParity defaults on");
        require(empty.progressChat() == CompanionRules.ProgressChat.MILESTONES, "progressChat defaults to milestones");

        require(!rules("survivalParity", "false").survivalParity(), "survivalParity=false is honoured");
        require(!rules("survivalParity", " FALSE ").survivalParity(), "boolean parsing trims and ignores case");
        require(rules("survivalParity", "nope").survivalParity(), "an unreadable boolean keeps the default");
        require(rules("progressChat", "off").progressChat() == CompanionRules.ProgressChat.OFF, "progressChat=off");
        require(rules("progressChat", "ALL").progressChat() == CompanionRules.ProgressChat.ALL, "progressChat=ALL");
        require(rules("progressChat", "chatty").progressChat() == CompanionRules.ProgressChat.MILESTONES,
                "an unknown progressChat keeps the default");
    }

    private static void progressChatLevels() {
        require(CompanionRules.ProgressChat.ALL.shows(false) && CompanionRules.ProgressChat.ALL.shows(true), "all shows everything");
        require(!CompanionRules.ProgressChat.MILESTONES.shows(false), "milestones hides step chatter such as 'breaking iron ore'");
        require(CompanionRules.ProgressChat.MILESTONES.shows(true), "milestones keeps outcomes and failures");
        require(!CompanionRules.ProgressChat.OFF.shows(false) && !CompanionRules.ProgressChat.OFF.shows(true), "off hides everything");
    }

    private static CompanionRules rules(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return new CompanionRules(p);
    }

    private static void requireTicks(int expected, int actual, String what) {
        require(expected == actual, what + ": expected " + expected + " ticks, got " + actual);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
