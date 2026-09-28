package com.player2.playerengine.companion;

import com.player2.playerengine.automaton.api.entity.LivingEntityHungerManager;
import java.util.Properties;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Checks the survival mining and combat arithmetic against vanilla 1.21.1, the well-fed hunger
 * stand-in, and the operator rules parsing. Break times are the Minecraft Wiki "Breaking" table values for stone (hardness 1.5),
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
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stoneBreakTimesMatchVanilla();
        effectsScaleLikeVanilla();
        waterPenaltyNeedsAquaAffinityToLift();
        airbornePenalty();
        unbreakableAndInstantBlocks();
        rulesDefaultsAndParsing();
        progressChatLevels();
        hungerOffRegeneratesLikeAFullFoodBar();
        zombieBaseAttributesBecomeAPlayers();
        attackCooldownScalesDamageLikeAPlayer();
        peerRepliesParsing();
        digTimeScaleCoversTheParitySlowdown();
        mineNamesTheBlockThatDropsCobblestone();
        checks += com.player2.playerengine.tasks.container.ContainerDepositSelfTest.runAll();
        checks += com.player2.playerengine.player2api.PeerTalkPolicySelfTest.runAll();
        checks += com.player2.playerengine.player2api.OfflineOwnerChatSelfTest.runAll();
        checks += com.player2.playerengine.chains.GestureGuardSelfTest.runAll();
        checks += com.player2.playerengine.automaton.utils.player.FeetChunkSelfTest.runAll();
        checks += com.player2.playerengine.trackers.ChunkHoldSelfTest.runAll();
        checks += com.player2.playerengine.util.ChunkControllerSelfTest.runAll();
        checks += com.player2.playerengine.util.PerceptionSelfTest.runAll();
        checks += com.player2.playerengine.util.TicketBookSelfTest.runAll();
        checks += com.player2.playerengine.util.ForcedChunkClearSelfTest.runAll();
        checks += com.player2.playerengine.player2api.DecisionCaptureSelfTest.runAll();
        checks += com.player2.playerengine.player2api.CompanionAddressSelfTest.runAll();
        checks += com.player2.playerengine.player2api.TurnCapsSelfTest.runAll();
        checks += com.player2.playerengine.PermissionClassSelfTest.runAll();
        checks += com.player2.playerengine.seam.SeamSelfTest.runAll();
        checks += com.player2.playerengine.seam.QueriesSelfTest.runAll();
        checks += com.player2.playerengine.seam.primitives.PrimitivesSelfTest.runAll();
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
        require(empty.progressChat() == CompanionRules.ProgressChat.OFF, "progressChat defaults to off");
        require(empty.hungerOverride() == null, "hunger unset leaves the settings file in charge");
        require(!empty.captureDecisions(), "captureDecisions defaults off");
        require(rules("captureDecisions", "true").captureDecisions(), "captureDecisions=true turns capture on");
        require(rules("hunger", "false").hungerOverride() == Boolean.FALSE, "hunger=false overrides the settings file");
        require(rules("hunger", "true").hungerOverride() == Boolean.TRUE, "hunger=true overrides the settings file");
        require(rules("hunger", "maybe").hungerOverride() == null, "an unreadable hunger value is ignored");

        require(!rules("survivalParity", "false").survivalParity(), "survivalParity=false is honoured");
        require(!rules("survivalParity", " FALSE ").survivalParity(), "boolean parsing trims and ignores case");
        require(rules("survivalParity", "nope").survivalParity(), "an unreadable boolean keeps the default");
        require(rules("progressChat", "off").progressChat() == CompanionRules.ProgressChat.OFF, "progressChat=off");
        require(rules("progressChat", "ALL").progressChat() == CompanionRules.ProgressChat.ALL, "progressChat=ALL");
        require(rules("progressChat", "chatty").progressChat() == CompanionRules.ProgressChat.OFF,
                "an unknown progressChat keeps the default");
    }

    private static void peerRepliesParsing() {
        require(new CompanionRules(new Properties()).peerReplies() == 1, "peerReplies defaults to 1");
        require(rules("peerReplies", "0").peerReplies() == 0, "peerReplies=0 is honoured");
        require(rules("peerReplies", " 3 ").peerReplies() == 3, "peerReplies trims");
        require(rules("peerReplies", "-4").peerReplies() == 0, "a negative peerReplies clamps to 0");
        require(rules("peerReplies", "999").peerReplies() == CompanionRules.MAX_PEER_REPLIES, "peerReplies clamps high");
        require(rules("peerReplies", "lots").peerReplies() == 1, "an unreadable peerReplies keeps the default");
    }

    /**
     * Ticks per block under parity are t + the post-break delay; upstream credited one tick more and
     * had no delay, so t - 1. The budget scale must cover that ratio for unenchanted pickaxes from
     * wood to netherite on stone, iron ore and deepslate (gold, at speed 12, is the one exception).
     */
    private static void digTimeScaleCoversTheParitySlowdown() {
        require(new CompanionRules(new Properties()).digTimeScale() == CompanionRules.SURVIVAL_DIG_TIME_SCALE,
                "parity stretches dig budgets");
        require(rules("survivalParity", "false").digTimeScale() == 1.0, "upstream pace keeps upstream budgets");
        float[] pickaxes = {WOODEN_PICKAXE, 4.0F, IRON_PICKAXE, DIAMOND_PICKAXE, 9.0F};
        float[] hardness = {STONE, 3.0F, 3.0F * 1.5F};
        double worst = 0.0;
        for (float tool : pickaxes) {
            for (float h : hardness) {
                float speed = SurvivalDigSpeed.digSpeed(tool, 0, -1, -1, 1.0F, false, false, true);
                float delta = SurvivalDigSpeed.progressPerTick(h, speed, true);
                int parity = (int) Math.ceil(1.0F / delta);
                int upstream = Math.max(1, parity - 1);
                worst = Math.max(worst, (parity + SurvivalDigSpeed.DESTROY_DELAY_TICKS) / (double) upstream);
            }
        }
        require(worst <= CompanionRules.SURVIVAL_DIG_TIME_SCALE,
                "dig budget scale " + CompanionRules.SURVIVAL_DIG_TIME_SCALE + " covers the worst slowdown " + worst);
    }

    private static void progressChatLevels() {
        require(CompanionRules.ProgressChat.ALL.shows(false) && CompanionRules.ProgressChat.ALL.shows(true), "all shows everything");
        require(!CompanionRules.ProgressChat.MILESTONES.shows(false), "milestones hides step chatter such as 'breaking iron ore'");
        require(CompanionRules.ProgressChat.MILESTONES.shows(true), "milestones keeps outcomes and failures");
        require(!CompanionRules.ProgressChat.OFF.shows(false) && !CompanionRules.ProgressChat.OFF.shows(true), "off hides everything");
    }

    private static void hungerOffRegeneratesLikeAFullFoodBar() {
        LivingEntityHungerManager hunger = new LivingEntityHungerManager();
        hunger.add(-12, 0.0F);
        require(hunger.getFoodLevel() == 8, "setup: food bar drained to 8");

        int heals = 0;
        for (int tick = 1; tick <= 160; tick++) {
            boolean heal = hunger.tickWellFed(true, true);
            if (heal) {
                heals++;
            }
            if (tick == 79) {
                require(heals == 0, "no heal before 80 ticks");
            }
        }
        require(heals == 2, "one heal per 80 ticks, as vanilla's food >= 18 regeneration: got " + heals);
        require(hunger.getFoodLevel() == 20, "the food bar is pinned full");

        boolean healedWithoutRegen = false;
        for (int tick = 0; tick < 200; tick++) {
            healedWithoutRegen |= hunger.tickWellFed(false, true);
        }
        require(!healedWithoutRegen, "naturalRegeneration=false stops regeneration");
        for (int tick = 0; tick < 79; tick++) {
            hunger.tickWellFed(true, true);
        }
        require(!hunger.tickWellFed(true, false), "full health does not heal");
        require(!hunger.tickWellFed(true, true), "full health resets the regeneration timer");
    }

    private static void zombieBaseAttributesBecomeAPlayers() {
        AttributeSupplier zombie = Zombie.createAttributes().build();
        AttributeMap attributes = new AttributeMap(zombie);
        require(attributes.getBaseValue(Attributes.ATTACK_DAMAGE) == 3.0, "setup: zombie attack damage 3");
        require(attributes.getBaseValue(Attributes.ARMOR) == 2.0, "setup: zombie armour 2");

        SurvivalCombat.applyBaseAttributes(attributes, true, zombie);
        require(attributes.getBaseValue(Attributes.ATTACK_DAMAGE) == 1.0, "parity: player attack damage 1");
        require(attributes.getBaseValue(Attributes.ARMOR) == 0.0, "parity: player armour 0");

        SurvivalCombat.applyBaseAttributes(attributes, false, zombie);
        require(attributes.getBaseValue(Attributes.ATTACK_DAMAGE) == 3.0, "parity off restores the registered attack damage");
        require(attributes.getBaseValue(Attributes.ARMOR) == 2.0, "parity off restores the registered armour");
    }

    private static void attackCooldownScalesDamageLikeAPlayer() {
        require(SurvivalCombat.attackSpeed(ItemStack.EMPTY) == 4.0, "empty hand attacks at speed 4");
        double sword = SurvivalCombat.attackSpeed(new ItemStack(Items.DIAMOND_SWORD));
        require(Math.abs(sword - 1.6) < 1e-6, "a sword attacks at speed 1.6, got " + sword);
        float swordDelay = SurvivalCombat.attackDelayTicks(sword);
        require(Math.abs(swordDelay - 12.5F) < 1e-4F, "a sword recharges in 12.5 ticks, got " + swordDelay);
        require(SurvivalCombat.attackDelayTicks(4.0) == 5.0F, "an empty hand recharges in 5 ticks");

        require(Math.abs(SurvivalCombat.damageMultiplier(SurvivalCombat.strengthScale(12, swordDelay)) - 1.0F) < 1e-6F,
                "a charged sword hit deals full damage");
        float spam = SurvivalCombat.damageMultiplier(SurvivalCombat.strengthScale(0, swordDelay));
        require(Math.abs(spam - 0.20128F) < 1e-4F, "an uncharged hit deals about 20%, got " + spam);
        float half = SurvivalCombat.damageMultiplier(SurvivalCombat.strengthScale(5, swordDelay));
        require(Math.abs(half - (0.2F + 0.44F * 0.44F * 0.8F)) < 1e-4F, "a sword hit 5 ticks after the last deals 35%, got " + half);
    }

    private static CompanionRules rules(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return new CompanionRules(p);
    }

    private static void requireTicks(int expected, int actual, String what) {
        require(expected == actual, what + ": expected " + expected + " ticks, got " + actual);
    }

    private static void mineNamesTheBlockThatDropsCobblestone() {
        String hint = com.player2.playerengine.commands.MineCommand.dropSourceHint("minecraft:cobblestone",
                "no_target_block_in_range");
        require(hint.contains("mine stone"), "a failed cobblestone search must point the model at stone, got: " + hint);
        require(com.player2.playerengine.commands.MineCommand.dropSourceHint("cobbled_deepslate",
                "no_target_block_in_range").contains("mine deepslate"), "cobbled deepslate comes from deepslate");
        require(com.player2.playerengine.commands.MineCommand.dropSourceHint("cobblestone", "mine_timeout").isEmpty(),
                "the hint is only for an empty search");
        require(com.player2.playerengine.commands.MineCommand.dropSourceHint("iron_ore", "no_target_block_in_range")
                .isEmpty(), "a block that is its own source gets no hint");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
