package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.companion.CompanionRules;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.DestroyBlockTask;
import com.player2.playerengine.tasks.movement.PickupDroppedItemTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Generic agentic block-mining step (WS4). Resolves the target block's required tool tier
 * (deterministically, WS2 {@link ToolRequirementResolver}), ensures a sufficient tool is held
 * (WS3 {@link ToolAcquisitionTask}), then breaks the nearest matching block(s) within radius and
 * collects their drops — detecting and reporting the vanilla "wrong tool = no drops" degradation to
 * BOTH the player chat and the model command-completion feedback (DESIGN.md §3).
 *
 * <p><b>Design invariants (HARD — violating any is a defect):</b>
 * <ul>
 *   <li>Tool resolution is 100% deterministic — no model calls, no {@code AiTaskClass}/deepsearch.
 *       Tier comes only from {@link MiningRequirement}/{@link StorageHelper} via
 *       {@link ToolRequirementResolver}.</li>
 *   <li>Never calls {@code isCorrectToolForDrops}, {@code Tier.getLevel()}, {@code Tier.getTag()}, or
 *       {@code TierSortingRegistry} — that divergence is confined to {@code MiningRequirement}.</li>
 *   <li>{@code mine_block} NEVER auto-stores: the only outcome is materials in inventory. It never
 *       deposits, reserves, releases, frees, or clears the MINED PRODUCT. The only reservation calls
 *       (inside {@link ToolAcquisitionTask}) are for the pickaxe TOOL draw.</li>
 *   <li>No-drops-on-wrong-tool is OBSERVED, never fabricated. Clean success only when
 *       {@code mineDegradation == CLEAN} and {@code minedNoDrops == 0}.</li>
 *   <li>Common-module only; byte-identical across branches.</li>
 * </ul>
 */
public final class MineBlockTask extends Task {

    private enum Phase {
        RESOLVING,
        ACQUIRING_TOOL,
        FINDING_BLOCK,
        BREAKING,
        COLLECTING,
        SETTLING,
        DONE,
        PARTIAL,
        FAILED
    }

    /**
     * Coarse terminal classification for the standalone {@code mine} command surface. The agentic
     * {@code mine_block} step does NOT read this — it routes via the run-state mine slot. The
     * command callback reads {@link #outcome()} to produce its single dual-audience terminal report.
     */
    public enum OutcomeKind {
        CLEAN_SUCCESS,
        PARTIAL_NO_DROPS,
        PARTIAL_TIMEOUT,
        PARTIAL_RANGE_EXHAUSTED,
        PARTIAL_DROPS_LOST,
        FAILED
    }

    /**
     * Why a non-no-drops partial terminated, so {@link #finishPartial} can pick the truthful
     * {@link OutcomeKind} (and reason token) for BOTH audiences (DESIGN.md §3). The no-drops branch
     * ({@code minedNoDrops > 0}) ALWAYS wins as {@link OutcomeKind#PARTIAL_NO_DROPS} regardless of the
     * cause passed; this enum only resolves the else-branch.
     * <ul>
     *   <li>{@link #TIMEOUT} — genuine overall timeout; more blocks may remain, retrying can continue.</li>
     *   <li>{@link #RANGE_EXHAUSTED} — no more matching blocks within range; the bot must relocate.</li>
     *   <li>{@link #DROPS_UNREACHABLE} — one or more correct-tool breaks produced a drop that could not be
     *       reached within the pickup budget (fell into water/void). The ore WAS destroyed, so this is still
     *       a partial SUCCESS with a visible degradation — never a failure.</li>
     * </ul>
     */
    private enum PartialCause {
        TIMEOUT,
        RANGE_EXHAUSTED,
        DROPS_UNREACHABLE
    }

    /**
     * Single typed terminal outcome (null until a terminal method runs), mirroring
     * {@code SmeltDeferredTask.Outcome}. {@code reasonToken} is the raw machine token the task emitted
     * (the command humanizes it for both audiences); it is null on clean success.
     */
    public record Outcome(OutcomeKind kind, int mined, int noDrops, int lostDrops, String blockId,
            String reasonToken) {}

    private final MineBlockParams params;
    private final AgenticRunRegistry.AgenticRunState runState;
    private final MaterialReservationService ledger;

    /** Recorded once, on the terminal path; null while the task is still running or was interrupted. */
    private Outcome outcome;

    private Phase phase = Phase.RESOLVING;
    private boolean finished;

    /** Resolved target blocks (a single registry block, or all members of a block tag). */
    private final Set<Block> targetBlocks = new LinkedHashSet<>();
    /** Resolved minimum tool requirement for the target. */
    private MiningRequirement requirement = MiningRequirement.HAND;

    /**
     * Wall-clock budget for chasing a single break's drop before abandoning it as unreachable. Long
     * enough for slow land pathing to a normal drop, short enough to give up on a water/void-trapped
     * drop well before the 120s overall timeout. NOTE: this BOUNDS the unreachable-drop chase — it does
     * not PREVENT a short bounded path toward water during the budget window (the pickup child may walk
     * the bot a little way before the budget trips). A MineBlockParams knob does not exist for this, so a
     * constant is used deliberately.
     */
    private static final long PICKUP_BUDGET_MS = 10_000L;

    private long startMs;
    private long settleStartMs = -1L;
    /** Dedicated per-break pickup-chase timer (NOT shared with settleStartMs). -1L when not chasing. */
    private long pickupStartMs = -1L;

    /**
     * Ground-truth pickup baseline, captured the instant {@link #pickupChild} is created.
     * {@link PickupDroppedItemTask} extends {@code AbstractDoToClosestObjectTask} — it is an INFINITE
     * task that NEVER flips {@code isFinished()}/{@code stopped()} on its own; when no target drop
     * remains it just wanders. So collection CANNOT be detected from the child finishing — it is
     * detected from world/inventory STATE instead: a positive delta of {@link #pickupItem}'s inventory
     * count versus {@link #pickupPreCount} means the item entered the inventory (including by the
     * vanilla proximity auto-pickup that fires while the child is still pathing). {@link #pickupTarget}
     * is the tracked drop entity, used both to corroborate the success predicate (it must be removed,
     * i.e. picked up/merged) and at budget-expiry to distinguish a destroyed drop (void/fire/lava) from
     * one that is still physically present but unreachable. {@link #pickupExpected} is this break's drop
     * stack size: success requires the inventory delta to reach it (so an unrelated same-type item
     * trickling in cannot be mistaken for THIS drop). All null/-1 when not chasing.
     */
    private Item pickupItem;
    private int pickupPreCount = -1;
    private int pickupExpected = -1;
    private ItemEntity pickupTarget;

    private int minedTotal;
    /** Blocks broken with an insufficient tool (vanilla suppressed their drops). Never fabricated. */
    private int minedNoDrops;
    /** Correct-tool breaks whose drop could not be reached within {@link #PICKUP_BUDGET_MS}. */
    private int lostDrops;

    /** Position currently being broken. */
    private BlockPos activePos;
    private DestroyBlockTask breakChild;
    private PickupDroppedItemTask pickupChild;

    /**
     * Per-iteration flag: the held tool did NOT meet the block's requirement at break time, so this
     * break yields no drops. Set in BREAKING, consumed and cleared in COLLECTING.
     */
    private boolean usedWrongTool;

    private ToolAcquisitionTask acquireChild;

    public MineBlockTask(
            MineBlockParams params,
            AgenticRunRegistry.AgenticRunState runState,
            MaterialReservationService ledger) {
        this.params = params;
        this.runState = runState;
        this.ledger = ledger;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /** Single typed terminal outcome for the standalone {@code mine} command; null until terminal. */
    public Outcome outcome() {
        return outcome;
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
        this.phase = Phase.RESOLVING;
        setDebugState(phase.name());
        report("mining " + describeTarget(), false);
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }

        // Overall timeout: partial-success if any block was mined, else a hard failure. The budget
        // was sized for the upstream dig speed, so it stretches with the survival-parity dig time.
        double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
        double budgetSec = params.timeoutSeconds() * CompanionRules.get().digTimeScale();
        if (elapsedSec >= budgetSec && phase != Phase.RESOLVING) {
            if (minedTotal > 0) {
                finishPartial("timeout after partial mine", PartialCause.TIMEOUT);
            } else {
                terminateFailed("mine_timeout");
            }
            return null;
        }

        switch (phase) {
            case RESOLVING:
                return resolve();
            case ACQUIRING_TOOL:
                return acquireTool();
            case FINDING_BLOCK:
                return findBlock();
            case BREAKING:
                return breakBlock();
            case COLLECTING:
                return collect();
            case SETTLING:
                return settle();
            case DONE:
            case PARTIAL:
            case FAILED:
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------------------------- RESOLVING

    private Task resolve() {
        if (!resolveTargetBlocks()) {
            terminateFailed("unknown_block_id");
            return null;
        }
        // Deterministic tier resolution: the strictest requirement across the resolved target set, so
        // a tag of mixed-tier blocks demands a tool able to mine the hardest member for drops.
        this.requirement = MiningRequirement.HAND;
        for (Block block : targetBlocks) {
            MiningRequirement req = ToolRequirementResolver.requirementFor(block);
            if (req.ordinal() > this.requirement.ordinal()) {
                this.requirement = req;
            }
        }
        this.phase = ToolRequirementResolver.isHandOnly(requirement)
                ? Phase.FINDING_BLOCK
                : Phase.ACQUIRING_TOOL;
        setDebugState(phase.name() + " req=" + requirement);
        return null;
    }

    /**
     * Resolves {@link MineBlockParams#blockId()} into {@link #targetBlocks}: a registry id
     * (e.g. {@code minecraft:stone}) yields a single block; a tag token (e.g. {@code mineable/pickaxe}
     * or {@code #minecraft:logs}) yields every block in that tag. Returns {@code false} when nothing
     * resolves (caller terminates with {@code unknown_block_id}).
     */
    private boolean resolveTargetBlocks() {
        targetBlocks.clear();
        String raw = params.blockId();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String token = raw.trim();
        boolean tagSyntax = token.startsWith("#");
        if (tagSyntax) {
            token = token.substring(1);
        }
        // A "mineable/*" style path (no namespace, contains a slash) is unambiguously a tag token.
        boolean looksLikeTag = tagSyntax || (!token.contains(":") && token.contains("/"));
        String normalised = token.contains(":") ? token : "minecraft:" + token;
        ResourceLocation parsed = ResourceLocation.tryParse(normalised);
        if (parsed == null) {
            return false;
        }

        if (looksLikeTag) {
            return resolveTag(parsed);
        }

        // Try a direct registry block id first (the common case).
        Optional<Block> direct = BuiltInRegistries.BLOCK.getOptional(parsed);
        if (direct.isPresent()) {
            targetBlocks.add(direct.get());
            return true;
        }
        // Fall back to interpreting it as a tag (e.g. "logs", "base_stone_overworld").
        return resolveTag(parsed);
    }

    /** Collects every block belonging to the given tag id into {@link #targetBlocks}. */
    private boolean resolveTag(ResourceLocation tagId) {
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
        for (Block block : BuiltInRegistries.BLOCK) {
            // Defensive: a datapacked/modded block missing a resource key or holder must NOT crash the
            // whole mine (DESIGN: degradation is visible, never a crash). Skip any such block rather
            // than throwing — getResourceKey/getHolder are Optional precisely for this case.
            boolean inTag = BuiltInRegistries.BLOCK.getResourceKey(block)
                    .flatMap(BuiltInRegistries.BLOCK::getHolder)
                    .map(holder -> holder.is(tag))
                    .orElse(false);
            if (inTag) {
                targetBlocks.add(block);
            }
        }
        return !targetBlocks.isEmpty();
    }

    // ----------------------------------------------------------------------------- ACQUIRING_TOOL

    private Task acquireTool() {
        // Already holds a sufficient tool — skip acquisition entirely.
        if (ToolRequirementResolver.isHeldInventoryOnly(this.controller, requirement)) {
            acquireChild = null;
            this.phase = Phase.FINDING_BLOCK;
            setDebugState(phase.name());
            return null;
        }
        if (acquireChild != null) {
            if (!acquireChild.isFinished() && !acquireChild.stopped()) {
                return acquireChild; // pipeline still working
            }
            // Child terminal — re-check. ToolAcquisitionTask reports FAILED as stopped()==true while
            // isFinished()==false (forced-stop path, mirroring GatherLooseItemsTask).
            boolean acquired = ToolRequirementResolver.isHeldInventoryOnly(this.controller, requirement);
            acquireChild = null;
            if (!acquired) {
                terminateFailed("could_not_acquire_tool:" + tierWord());
                return null;
            }
            this.phase = Phase.FINDING_BLOCK;
            setDebugState(phase.name());
            return null;
        }
        acquireChild = new ToolAcquisitionTask(requirement, params, runState, ledger);
        report("getting a " + tierWord() + " pickaxe before mining...", false);
        setDebugState(phase.name());
        return acquireChild;
    }

    // ------------------------------------------------------------------------------ FINDING_BLOCK

    private Task findBlock() {
        if (minedTotal >= params.maxBlocks()) {
            // Reset the settle timer before SETTLING: COLLECTING's drop-wait shares settleStartMs and
            // may have left it armed, which would otherwise make SETTLING's 0.5s pause fire immediately
            // off a stale timestamp.
            this.settleStartMs = -1L;
            this.phase = Phase.SETTLING;
            setDebugState(phase.name());
            return null;
        }
        Optional<BlockPos> nearest = getClosestBlock();
        if (nearest.isEmpty()) {
            // None within radius. If we already mined some this run, that is a partial success;
            // otherwise a hard "nothing to mine" failure.
            if (minedTotal > 0) {
                finishPartial("no more target blocks in range", PartialCause.RANGE_EXHAUSTED);
            } else {
                terminateFailed("no_target_block_in_range");
            }
            return null;
        }
        this.activePos = nearest.get();
        this.phase = Phase.BREAKING;
        setDebugState(phase.name() + "@" + activePos.toShortString());
        return null;
    }

    /**
     * Nearest reachable, breakable target block within radius. Copies the
     * {@code MineOrCollectTask.getClosestBlock} predicate (unreachable filter + {@link WorldHelper#canBreak})
     * rather than extending {@code MineAndCollectTask}.
     */
    private Optional<BlockPos> getClosestBlock() {
        Vec3 origin = this.controller.getPlayer().position();
        Block[] blocks = targetBlocks.toArray(Block[]::new);
        Optional<BlockPos> found = this.controller.getBlockScanner().getNearestBlock(
                origin,
                check -> !this.controller.getBlockScanner().isUnreachable(check)
                        && WorldHelper.canBreak(this.controller, check),
                blocks);
        // Enforce the configured radius (BlockScanner returns the nearest known, which may be farther).
        double radiusSq = params.radius() * params.radius();
        if (found.isPresent()) {
            BlockPos p = found.get();
            double dx = p.getX() + 0.5 - origin.x;
            double dy = p.getY() + 0.5 - origin.y;
            double dz = p.getZ() + 0.5 - origin.z;
            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                return Optional.empty();
            }
        }
        return found;
    }

    // ----------------------------------------------------------------------------------- BREAKING

    private Task breakBlock() {
        if (activePos == null) {
            this.phase = Phase.FINDING_BLOCK;
            setDebugState(phase.name());
            return null;
        }
        if (breakChild == null) {
            // Snapshot tool sufficiency for THIS block BEFORE breaking (no-drops predicate). The
            // inventory-tier check is the authoritative predicate (DESIGN deterministic-over-baritone);
            // false => vanilla suppresses drops for this break.
            //
            // WS5 reconciliation (Option A — verified, no code change): WS1 gated the ACTUAL drop in
            // LivingEntityInteractionManager.tryBreakBlock on canHarvest(blockState, MAIN-HAND). This
            // inventory-tier prediction must not diverge from that main-hand reality, or a suppressed drop
            // could be silently counted as a successful mine. They agree by construction here: the break
            // runs through DestroyBlockTask -> BuilderProcess, and BuilderProcess (line 462) calls
            // MovementHelper.switchToBestToolFor before breaking, which equips the highest-speed hotbar
            // item — for a tool-requiring block that is the pickaxe whenever one is in the hotbar. So when
            // miningRequirementMetInventory==true the correct tool is equipped to main hand (canHarvest
            // true, drop occurs, usedWrongTool=false ✓), and when it is false no pickaxe exists to equip
            // (canHarvest false, no drop, usedWrongTool=true ✓). KNOWN NARROW GAP: getBestSlot scans only
            // the hotbar (slots 0-8); a pickaxe held in inventory but NOT the hotbar reads
            // miningRequirementMetInventory==true yet is never equipped, so WS1 suppresses the drop while
            // this predicts a drop. This is a pre-existing MineBlockTask hotbar-placement limitation,
            // independent of and not worsened by WS1; closing it (hotbar-aware tier check) is out of scope.
            this.usedWrongTool =
                    !StorageHelper.miningRequirementMetInventory(this.controller, requirement);
            breakChild = DestroyBlockTask.sparingPlayerPlaced(activePos);
            report("breaking " + blockNameAt(activePos), false);
            setDebugState(phase.name() + "@" + activePos.toShortString());
            return breakChild;
        }
        if (!breakChild.isFinished() && !breakChild.stopped()) {
            return breakChild; // still breaking
        }
        if (breakChild.refused()) {
            // Nothing broke: the target is player-placed, and canBreak keeps it out of the next search.
            breakChild = null;
            activePos = null;
            this.phase = Phase.FINDING_BLOCK;
            setDebugState(phase.name());
            return null;
        }
        // Block broken (now air) — move to collection. Count-at-BREAK: minedTotal is the single source
        // of truth for "blocks broken" and is incremented here exactly once per break, so a broken-but-
        // uncollected block (drop fell into water/void) is never miscounted as 0. The collect() paths no
        // longer increment minedTotal (that would double-count).
        breakChild = null;
        this.phase = Phase.COLLECTING;
        this.settleStartMs = -1L;
        this.pickupStartMs = -1L;
        minedTotal++;
        updateProgress();
        setDebugState(phase.name());
        return null;
    }

    // --------------------------------------------------------------------------------- COLLECTING

    private Task collect() {
        // No-drops detection (wrong tool): vanilla already produced no drop — we OBSERVE and report it,
        // we never fabricate items. Count it and skip the pickup loop for this block.
        if (usedWrongTool) {
            minedNoDrops++; // minedTotal already counted at break.
            usedWrongTool = false;
            clearPickupState();
            activePos = null;
            report("broke " + describeTarget() + " but my " + tierWord()
                    + " tool isn't strong enough — got no drops.", true);
            updateProgress();
            this.phase = Phase.FINDING_BLOCK;
            setDebugState(phase.name());
            return null;
        }

        // Correct tool: prefer the nearby drop (copy the MineOrCollectTask prefer-drop loop).
        Vec3 origin = this.controller.getPlayer().position();
        if (pickupChild == null) {
            // Prefer the nearest ItemEntity dropped near this break (any item — a wrong-tool break is
            // handled above, so a correct-tool break's drop is whatever the loot table produced).
            double pickupRadius = Math.min(8.0, params.radius());
            Optional<ItemEntity> nearby = this.controller.getEntityTracker()
                    .getItemDropsWithin(origin, pickupRadius, e -> !e.isRemoved())
                    .stream()
                    .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(origin)));
            if (nearby.isEmpty()) {
                // Nothing left to pick up for this break — settle briefly, then continue mining.
                if (settleStartMs < 0L) {
                    settleStartMs = System.currentTimeMillis();
                }
                double settled = (System.currentTimeMillis() - settleStartMs) / 1000.0;
                if (settled < 0.5) {
                    setDebugState(phase.name() + ":waiting-for-drop");
                    return null;
                }
                // minedTotal already counted at break.
                activePos = null;
                updateProgress();
                this.phase = Phase.FINDING_BLOCK;
                setDebugState(phase.name());
                return null;
            }
            ItemEntity target = nearby.get();
            Item item = target.getItem().getItem();
            int count = Math.max(1, target.getItem().getCount());
            // Capture the ground-truth pickup baseline BEFORE the child runs. Because the pickup child is
            // infinite and never self-finishes, collection is detected below from the inventory delta of
            // this exact item — NOT from the child stopping. pickupTarget corroborates void/destroyed vs.
            // still-present-but-unreachable at budget expiry.
            pickupItem = item;
            pickupPreCount = this.controller.getItemStorage().getItemCount(item);
            pickupExpected = count;
            pickupTarget = target;
            pickupChild = new PickupDroppedItemTask(new ItemTarget(item, count), true);
            report("collecting " + ItemHelper.stripItemName(item) + " drop", false);
            setDebugState(phase.name() + ":pickup");
            return pickupChild;
        }

        // PRIMARY success detector — ground truth from world/inventory STATE (the player-cited evidence).
        // PickupDroppedItemTask is infinite and never self-finishes, so we MUST detect collection from
        // state, not from the child stopping. This runs every tick: the instant THIS break's drop enters
        // the inventory (by walking onto it OR by vanilla proximity auto-pickup while the child still
        // paths), we stop the infinite child, count NOTHING as lost, and advance immediately — no 10s
        // stall, no false "lost the drop" report.
        //
        // Robust predicate (avoids crediting an UNRELATED same-type item that trickled in concurrently
        // — e.g. a previous session's stray raw_iron proximity-picked while pathing). We require EITHER:
        //   (a) the tracked drop entity is actually removed (definitive: it was picked up / merged), OR
        //   (b) the inventory delta has reached this break's full expected stack size.
        // Either alone is conclusive that THIS drop landed; (b) backstops the case where the tracked
        // entity merged into another stack (its reference still "removed" but only flips after the merge).
        if (pickupItem != null) {
            int delta = this.controller.getItemStorage().getItemCount(pickupItem) - pickupPreCount;
            boolean targetGone = pickupTarget != null && pickupTarget.isRemoved();
            if (delta > 0 && (targetGone || delta >= pickupExpected)) {
                clearPickupState();
                activePos = null;
                settleStartMs = -1L; // stale-timer guard for the next break.
                updateProgress();
                this.phase = Phase.FINDING_BLOCK;
                setDebugState(phase.name());
                return null;
            }
        }

        if (!pickupChild.isFinished() && !pickupChild.stopped()) {
            // Bound the chase: PickupDroppedItemTask's internal wander never flips isFinished()/stopped()
            // for an unreachable (water/void-trapped) drop, so without this budget the pickup child would
            // run until the overall timeout. Start the per-break timer lazily, then ABANDON once it
            // exceeds PICKUP_BUDGET_MS. minedTotal is already credited at break, so abandoning here loses
            // only the drop, not the mine count. Clearing pickupChild + switching to FINDING_BLOCK
            // atomically prevents collect() re-entry for this position (no re-trigger loop).
            if (pickupStartMs < 0L) {
                pickupStartMs = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - pickupStartMs > PICKUP_BUDGET_MS) {
                // Budget expired. The PRIMARY check above ran every tick and did NOT credit this drop, so
                // the bot genuinely never collected it — this is a TRUE loss (not the old false positive).
                // Whether the tracked entity is gone (destroyed by void/fire/lava, despawned, or picked up
                // by another entity) or still physically present (floating on water, unreachable by land
                // path), the bot did not get it, so it is lost either way.
                boolean gone = pickupTarget != null && pickupTarget.isRemoved();
                if (!pickupChild.stopped()) {
                    pickupChild.stop(this);
                }
                clearPickupState();
                activePos = null;
                settleStartMs = -1L; // stale-timer guard for the next break.
                lostDrops++;
                // Best-effort per-block progress line (report() is throttle- and terminal-gated; the
                // TERMINAL dual-audience report is the truthfulness carrier, not this line).
                report("broke " + describeTarget() + " but the drop is unreachable ("
                        + (gone ? "gone — destroyed or taken by another entity" : "fell into water/void")
                        + ") — lost the drop, moving on.", true);
                updateProgress();
                this.phase = Phase.FINDING_BLOCK;
                setDebugState(phase.name());
                return null;
            }
            return pickupChild; // still collecting, within budget
        }
        // Defensive fallback: the infinite child should never self-terminate (it wanders forever), but if
        // it ever does, the PRIMARY state check above already ran every tick and did NOT credit this
        // drop — so the bot did NOT collect it. We must NOT advance as a clean success (that would report
        // CLEAN_SUCCESS while a drop was genuinely missed — a DESIGN.md §3 truthfulness bug). Treat it as
        // a true loss, mirroring the budget-expiry path: count it and emit the same best-effort line.
        boolean gone = pickupTarget != null && pickupTarget.isRemoved();
        clearPickupState();
        activePos = null;
        settleStartMs = -1L; // stale-timer guard for the next break.
        lostDrops++;
        report("broke " + describeTarget() + " but the drop is unreachable ("
                + (gone ? "gone — destroyed or taken by another entity" : "fell into water/void")
                + ") — lost the drop, moving on.", true);
        updateProgress();
        this.phase = Phase.FINDING_BLOCK;
        setDebugState(phase.name());
        return null;
    }

    /** Clears all per-break pickup chase state (child + ground-truth baseline + chase timer). */
    private void clearPickupState() {
        pickupChild = null;
        pickupItem = null;
        pickupPreCount = -1;
        pickupExpected = -1;
        pickupTarget = null;
        pickupStartMs = -1L;
    }

    // ----------------------------------------------------------------------------------- SETTLING

    private Task settle() {
        if (settleStartMs < 0L) {
            settleStartMs = System.currentTimeMillis();
        }
        double settled = (System.currentTimeMillis() - settleStartMs) / 1000.0;
        if (settled < 0.5) {
            return null;
        }
        if (minedNoDrops > 0) {
            // Reached only when minedTotal >= maxBlocks with some no-drops breaks: the no-drops branch
            // in finishPartial wins, so the passed cause is unused here.
            finishPartial("mined with insufficient tool", PartialCause.RANGE_EXHAUSTED);
        } else if (lostDrops > 0) {
            // Hit the block quota but lost one or more drops — a truthful partial, not CLEAN_SUCCESS.
            finishPartial("drops unreachable", PartialCause.DROPS_UNREACHABLE);
        } else {
            finishDone();
        }
        return null;
    }

    // ----------------------------------------------------------------------------------- terminal

    private void finishDone() {
        this.phase = Phase.DONE;
        this.finished = true;
        this.outcome = new Outcome(OutcomeKind.CLEAN_SUCCESS, minedTotal, 0, 0, targetLabel(), null);
        recordProgress();
        // Clean success: no degradation set, factual count only (AgenticDegradationSummary mine arm
        // reads mined= for the clean factual clause). minedNoDrops==0 guaranteed on this path.
        setDebugState(Phase.DONE.name());
        this.controller.log("[Agentic] mine_block: done (mined=" + minedTotal + " block=" + targetLabel() + ")");
        report("mined " + minedTotal + " " + describeTarget(), true);
    }

    private void finishPartial(String message, PartialCause cause) {
        this.phase = Phase.PARTIAL;
        this.finished = true; // PARTIAL maps to a SUCCEEDED step with a visible degradation note.
        if (minedNoDrops > 0) {
            // No-drops degradation always wins regardless of why we stopped iterating.
            this.outcome = new Outcome(OutcomeKind.PARTIAL_NO_DROPS, minedTotal, minedNoDrops, lostDrops,
                    targetLabel(), "incorrect_tool_no_drops");
        } else if (cause == PartialCause.DROPS_UNREACHABLE || lostDrops > 0) {
            // Ore WAS broken but one or more drops could not be reached (water/void) — a truthful partial
            // SUCCESS, never a failure and never CLEAN. Wins over range/timeout so the model is told the
            // drops were lost, not merely that the bot ran out of blocks or time.
            this.outcome = new Outcome(OutcomeKind.PARTIAL_DROPS_LOST, minedTotal, 0, lostDrops,
                    targetLabel(), "drops_unreachable");
        } else if (cause == PartialCause.RANGE_EXHAUSTED) {
            // No more matching blocks within range — NOT a timeout; the bot must relocate to mine more.
            this.outcome = new Outcome(OutcomeKind.PARTIAL_RANGE_EXHAUSTED, minedTotal, 0, 0,
                    targetLabel(), "no_more_blocks_in_range");
        } else {
            // Genuine overall timeout — more blocks may remain; retrying can continue.
            this.outcome = new Outcome(OutcomeKind.PARTIAL_TIMEOUT, minedTotal, 0, 0,
                    targetLabel(), "timeout");
        }
        recordProgress();
        if (runState != null) {
            // Every partial is a visible degradation the MODEL must see (DESIGN.md §3); the agentic
            // summary reads ONLY the DegradationLevel signal, never the factual *Progress string. The
            // no-drops case wins regardless of why iteration stopped; otherwise the cause picks the
            // truthful token (timeout vs relocate-to-mine-more). AgenticDegradationSummary.mineClause()
            // humanizes each token into an actionable model clause.
            if (minedNoDrops > 0) {
                runState.setMineDegraded(DegradationLevel.PARTIAL, "incorrect_tool_no_drops");
            } else if (cause == PartialCause.DROPS_UNREACHABLE || lostDrops > 0) {
                runState.setMineDegraded(DegradationLevel.PARTIAL, "drops_unreachable");
            } else if (cause == PartialCause.RANGE_EXHAUSTED) {
                runState.setMineDegraded(DegradationLevel.PARTIAL, "no_more_blocks_in_range");
            } else {
                runState.setMineDegraded(DegradationLevel.PARTIAL, "timeout");
            }
        }
        setDebugState(Phase.PARTIAL.name());
        this.controller.log("[Agentic] mine_block: partial: " + message
                + " (mined=" + minedTotal + " noDrops=" + minedNoDrops + " lostDrops=" + lostDrops
                + " block=" + targetLabel() + ")");
        if (minedNoDrops > 0) {
            report("mined " + minedTotal + " " + describeTarget() + " but " + minedNoDrops
                    + " gave no drops (tool too weak).", true);
        } else if (cause == PartialCause.DROPS_UNREACHABLE || lostDrops > 0) {
            report("mined " + minedTotal + " " + describeTarget() + ", lost " + lostDrops
                    + " drop(s) (unreachable).", true);
        } else {
            report("mined " + minedTotal + " " + describeTarget() + " (" + message + ").", true);
        }
    }

    private void terminateFailed(String reason) {
        this.phase = Phase.FAILED;
        this.finished = false; // forced-stop path: stopped()==true && isFinished()==false => FAILED step.
        this.outcome = new Outcome(OutcomeKind.FAILED, minedTotal, minedNoDrops, lostDrops,
                targetLabel(), reason);
        // Hard-failure channel (B1): write the reason to mineProgress so the executor's
        // progressForKind("mine_block") surfaces the SPECIFIC reason to the model (DESIGN.md §3).
        if (runState != null) {
            runState.setMineProgress(reason);
        }
        stopChildren();
        setDebugState("FAILED:" + reason);
        this.controller.log("[Agentic] mine_block: " + reason + " (block=" + targetLabel() + ")");
        report("couldn't mine " + describeTarget() + ": " + reason, true);
        if (!this.stopped()) {
            this.stop(this);
        }
    }

    private void stopChildren() {
        if (breakChild != null && !breakChild.stopped()) {
            breakChild.stop(this);
        }
        if (pickupChild != null && !pickupChild.stopped()) {
            pickupChild.stop(this);
        }
        if (acquireChild != null && !acquireChild.stopped()) {
            acquireChild.stop(this);
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Mirror GatherLooseItemsTask: only treat as a fresh interruption if not already terminal, so
        // terminateFailed's self-stop does not recurse and overwrite the real reason.
        if (!finished && !isTerminalPhase()) {
            this.phase = Phase.FAILED;
            if (runState != null) {
                runState.setMineProgress("interrupted");
            }
            setDebugState("FAILED:interrupted");
        }
    }

    private boolean isTerminalPhase() {
        return phase == Phase.DONE || phase == Phase.PARTIAL || phase == Phase.FAILED;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof MineBlockTask task
                && java.util.Objects.equals(task.params, this.params);
    }

    @Override
    protected String toDebugString() {
        return "MineBlock[" + targetLabel() + "]";
    }

    // ----------------------------------------------------------------------------------- helpers

    /** Writes the always-rewritten factual progress token (mined / noDrops / block) for the model. */
    private void recordProgress() {
        if (runState != null) {
            runState.setMineProgress(
                    "mined=" + minedTotal + " noDrops=" + minedNoDrops + " lostDrops=" + lostDrops
                            + " block=" + targetLabel());
        }
    }

    private void updateProgress() {
        recordProgress();
        setDebugState(phase.name() + " mined=" + minedTotal);
    }

    private String tierWord() {
        return requirement.name().toLowerCase(Locale.ROOT);
    }

    private String describeTarget() {
        return targetLabel();
    }

    private String targetLabel() {
        String raw = params.blockId();
        return raw != null ? raw : "block";
    }

    private String blockNameAt(BlockPos pos) {
        try {
            Block block = this.controller.getWorld().getBlockState(pos).getBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id != null ? id.getPath() : describeTarget();
        } catch (Exception e) {
            return describeTarget();
        }
    }

    /** Player-facing progress note via the controller seam; suppressed once the run is terminal. */
    private void report(String message, boolean milestone) {
        if (runState != null && runState.isTerminal()) {
            return;
        }
        this.controller.reportAgenticProgress(message, milestone);
    }
}
