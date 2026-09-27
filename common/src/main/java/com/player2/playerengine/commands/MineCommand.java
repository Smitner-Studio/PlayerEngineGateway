package com.player2.playerengine.commands;

import net.minecraft.network.chat.Component;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.agentic.MineBlockParams;
import com.player2.playerengine.tasks.agentic.MineBlockTask;
import com.player2.playerengine.tasks.agentic.MineBlockTask.Outcome;
import com.player2.playerengine.tasks.agentic.MineBlockTask.OutcomeKind;

/**
 * Standalone {@code mine <block> [count]} command — the generic block-mining trigger surface (R2).
 *
 * <p>Mirrors {@link SmeltCommand}/{@link SmithCommand}: it wraps the shared agentic
 * {@link MineBlockTask} as a tracked user step. Because no agentic run is in flight when the user
 * issues {@code mine} directly, the task is constructed with a {@code null} run-state and
 * {@code null} reservation ledger — every run-state/ledger access in {@link MineBlockTask} is
 * null-guarded, so the standalone path never NPEs. The task resolves the required pickaxe tier
 * deterministically, acquires a tool if it lacks one, breaks the nearest matching block(s), and
 * collects the drops; it NEVER auto-stores the result.
 *
 * <p><b>Dual-audience reporting (DESIGN.md §3).</b> Every terminal outcome reaches BOTH the player
 * (a concise chat line via {@code reportAgenticProgress(msg, true)}) AND the model (via
 * {@code finishWithInfo}/{@code finishWithNote}/{@code finishWithError}). On every terminal path the
 * player line fires FIRST, then the model-facing finish (canonical order). The single
 * {@link Outcome} the task records is the shared source for both surfaces. Failure/degradation
 * tokens emitted by the task are humanized by {@link #readable(String)} so no raw snake_case/colon
 * machine token ever leaks to either audience.
 */
public class MineCommand extends Command {

    public MineCommand() throws CommandException {
        super(
                "mine",
                "mine <block> [count]. Mine and collect up to [count] of the nearest matching blocks"
                        + " of the named type (ores, stone, deepslate, obsidian, gravel, etc.). The"
                        + " companion deterministically picks and acquires the right pickaxe tier if it"
                        + " lacks one, breaks the block(s), and collects the drops. Count is a number of"
                        + " BLOCKS to break (a block may drop multiple items or, with too-weak a tool,"
                        + " none) and defaults to 1. Name the block as it stands in the world, not the"
                        + " item it drops: stone drops cobblestone and deepslate drops cobbled deepslate,"
                        + " so for cobblestone mine stone. Examples: `mine iron_ore 8`, `mine stone 32`,"
                        + " `mine minecraft:deepslate`.",
                new Arg<>(String.class, "block"),
                new Arg<>(Integer.class, "count", 1, 1));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        String blockId;
        int requested;
        try {
            blockId = parser.get(String.class);
            requested = parser.get(Integer.class);
        } catch (CommandException e) {
            // Missing/invalid arg (a bare `mine` throws "Not enough arguments supplied"): player line
            // BEFORE the model-facing finish so both audiences see the friendly message rather than a
            // raw executor error (DESIGN.md §3 / AGENTS.md dual-audience requirement).
            mod.reportAgenticProgress(e.getMessage(), true);
            this.finishWithError(e.getMessage());
            return;
        }

        if (blockId == null || blockId.isBlank()) {
            // Player sees the localized form; model keeps a fixed English token (cardinal rule).
            mod.reportAgenticProgress(Component.translatable("message.playerengine.mine.specify_block"), true);
            this.finishWithError("Specify a block to mine, e.g. `mine iron_ore 8`.");
            return;
        }

        String block = blockId.trim();
        int count = Math.max(1, Math.min(256, requested));

        // radius 32, timeout 120s, tool-acquire container budget 8, climb budget 6 — mirrors the
        // agentic mine_block defaults; numeric fields are clamped inside MineBlockParams' contract.
        MineBlockParams params = new MineBlockParams(block, count, 32.0, 120.0, 8, 6);
        final MineBlockTask task = new MineBlockTask(params, null, null);
        mod.runUserTaskTracked(
                "mine:" + block + "x" + count,
                "mine_block",
                task,
                RollbackPolicy.NONE,
                () -> onMineComplete(mod, task, count));
    }

    /**
     * Tracked-step completion gate: maps the task's single {@link Outcome} to the dual-audience
     * report. The player line fires FIRST (milestone, bypasses the throttle), then the model-facing
     * finish: clean success -> {@code finishWithInfo}; partial (no-drops / timeout) ->
     * {@code finishWithNote}; hard failure -> {@code finishWithError} with a humanized reason.
     */
    private void onMineComplete(PlayerEngineController mod, MineBlockTask task, int count) {
        Outcome o = task.outcome();
        if (o == null) {
            // Interrupted / superseded before a terminal outcome was recorded — a lifecycle stop is
            // not a mine failure (mirrors SmeltCommand.onSmeltComplete's null branch).
            this.finish();
            return;
        }
        Component playerLine = playerLine(o, count);
        mod.reportAgenticProgress(playerLine, true);
        switch (o.kind()) {
            case CLEAN_SUCCESS ->
                    this.finishWithInfo("mined " + o.mined() + " " + o.blockId());
            case PARTIAL_NO_DROPS ->
                    this.finishWithNote("partial: mined " + o.mined() + " " + o.blockId() + "; "
                            + o.noDrops() + " gave no drops (tool too weak)");
            case PARTIAL_TIMEOUT ->
                    this.finishWithNote("partial: mined " + o.mined() + " of " + count + " "
                            + o.blockId() + "; timed out before finishing (more may remain — "
                            + "retrying can continue)");
            case PARTIAL_RANGE_EXHAUSTED ->
                    this.finishWithNote("partial: mined " + o.mined() + " "
                            + o.blockId() + "; no more " + o.blockId() + " within range "
                            + "(relocate to mine more)");
            case PARTIAL_DROPS_LOST ->
                    this.finishWithNote("partial: mined " + o.mined() + " " + o.blockId() + "; "
                            + o.lostDrops() + " drop(s) fell into water/void and could not be collected");
            case FAILED ->
                    this.finishWithError("could not mine " + o.blockId() + ": "
                            + readable(o.reasonToken()) + dropSourceHint(o.blockId(), o.reasonToken()));
        }
    }

    /**
     * Model-facing hint when the model named a drop instead of the block that yields it: natural
     * stone drops cobblestone, so a search for cobblestone blocks finds only what someone placed.
     * Empty unless the search came up empty for such a drop.
     */
    public static String dropSourceHint(String blockId, String reasonToken) {
        if (blockId == null || !"no_target_block_in_range".equals(reasonToken)) {
            return "";
        }
        String id = blockId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        String source = switch (id) {
            case "cobblestone" -> "stone";
            case "cobbled_deepslate" -> "deepslate";
            default -> null;
        };
        return source == null ? ""
                : ". " + id.replace('_', ' ') + " is what " + source + " drops; mine " + source + " to get it";
    }

    /** Concise, human-readable player chat line for each terminal outcome. */
    private static Component playerLine(Outcome o, int count) {
        Component block = display(o.blockId());
        return switch (o.kind()) {
            case CLEAN_SUCCESS -> Component.translatable(
                    "message.playerengine.mine.success", o.mined(), block);
            case PARTIAL_NO_DROPS -> Component.translatable(
                    "message.playerengine.mine.partial_no_drops", o.mined(), block, o.noDrops());
            case PARTIAL_TIMEOUT -> Component.translatable(
                    "message.playerengine.mine.partial_timeout", o.mined(), count, block);
            case PARTIAL_RANGE_EXHAUSTED -> Component.translatable(
                    "message.playerengine.mine.partial_range_exhausted", o.mined(), block);
            case PARTIAL_DROPS_LOST -> Component.translatable(
                    "message.playerengine.mine.partial_drops_lost", o.mined(), block, o.lostDrops());
            case FAILED -> Component.translatable(
                    "message.playerengine.mine.failed", block, readable(o.reasonToken()));
        };
    }

    /** Strips the namespace and replaces underscores with spaces for a human-readable block name. */
    private static Component display(String id) {
        if (id == null || id.isBlank()) {
            return Component.translatable("message.playerengine.mine.block_fallback");
        }
        String s = id.trim();
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) {
            s = s.substring(colon + 1);
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        return Component.literal(s.replace('_', ' '));
    }

    /**
     * Humanizes EVERY failure/degradation token {@link MineBlockTask} can emit so no raw
     * snake_case/colon machine token leaks to the player chat or the model finish (DESIGN.md §3).
     * Tokens enumerated from {@code MineBlockTask.terminateFailed}/{@code finishPartial}/{@code onStop}:
     * {@code unknown_block_id}, {@code no_target_block_in_range}, {@code mine_timeout},
     * {@code timeout}, {@code no_more_blocks_in_range}, {@code incorrect_tool_no_drops},
     * {@code drops_unreachable}, {@code interrupted}, and the compound
     * {@code could_not_acquire_tool:<tier>}. Any unrecognized token falls back to stripping
     * underscores and colons rather than surfacing a raw machine token.
     */
    private static String readable(String token) {
        if (token == null || token.isBlank()) {
            return "unknown reason";
        }
        String t = token.trim();
        if (t.startsWith("could_not_acquire_tool:")) {
            String tier = t.substring("could_not_acquire_tool:".length());
            return "couldn't get a " + tier + " pickaxe";
        }
        return switch (t) {
            case "unknown_block_id" -> "that block name isn't recognized";
            case "no_target_block_in_range" -> "no matching block within range";
            case "mine_timeout", "timeout" -> "timed out";
            case "no_more_blocks_in_range" -> "no more matching blocks within range";
            case "incorrect_tool_no_drops" -> "tool too weak";
            case "drops_unreachable" -> "drops fell into water or void";
            case "interrupted" -> "interrupted";
            default -> t.replace('_', ' ').replace(':', ' ');
        };
    }
}
