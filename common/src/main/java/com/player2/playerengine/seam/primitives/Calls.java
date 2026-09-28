package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Primitive;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;

/** What the primitives share: running a registered command as a Task body, and reading snapshots. */
final class Calls {
    private Calls() {
    }

    /**
     * Runs the registered command {@code line} names as a primitive's Task body. The command's own
     * finish, note and error routes become the Task's verdict; its error text maps to a failure code
     * ({@link #codeOf}). The postcondition, not this verdict, decides success.
     */
    static void runCommand(Primitive.Context ctx, String line, Consumer<Primitive.TaskEnd> ended) {
        String name = line.trim().split("\\s+", 2)[0];
        Command command = ctx.mod().getCommandExecutor().get(name);
        if (command == null) {
            ended.accept(Primitive.TaskEnd.failed(ActionError.of(FailureCode.DENIED, name + " is not available")));
            return;
        }
        try {
            command.run(ctx.mod(), line,
                    () -> ended.accept(Primitive.TaskEnd.finished(null)),
                    e -> ended.accept(Primitive.TaskEnd.failed(error(e.getMessage()))),
                    note -> ended.accept(Primitive.TaskEnd.finished(note)));
        } catch (CommandException e) {
            ended.accept(Primitive.TaskEnd.failed(ActionError.of(FailureCode.BAD_ARGS, e.getMessage())));
        }
    }

    static ActionError error(String message) {
        String m = message == null ? "" : message.trim();
        return ActionError.of(codeOf(m), m);
    }

    /**
     * The failure code a command's error text means. A line that already starts with a code (the
     * seam's own, or a storage token) keeps it; otherwise the words decide, and a failure no rule
     * names is {@code unreachable}, the code the model repairs by trying another way.
     */
    static FailureCode codeOf(String message) {
        String m = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        for (FailureCode c : FailureCode.values()) {
            if (m.startsWith(c.wire() + ":")) {
                return c;
            }
        }
        if (m.startsWith("insufficient_items")) {
            return FailureCode.MISSING_ITEM;
        }
        if (m.startsWith("insufficient_space")) {
            return FailureCode.CONTAINER_FULL;
        }
        if (m.startsWith("container_missing") || m.startsWith("container_unsupported")) {
            return FailureCode.NO_CONTAINER;
        }
        if (m.startsWith("invalid_")) {
            return FailureCode.BAD_ARGS;
        }
        if (m.contains("not loaded")) {
            return FailureCode.NOT_LOADED;
        }
        if (m.contains("disabled")) {
            return FailureCode.DENIED;
        }
        if (m.contains("protected") || m.contains("spawn area")) {
            return FailureCode.PROTECTED;
        }
        if (m.contains("timed out") || m.contains("timeout")) {
            return FailureCode.TIMEOUT;
        }
        if (m.contains("no fuel") || m.contains("not enough") || m.contains("don't have") || m.contains("too few")
                || m.contains("couldn't get") || m.contains("could not acquire")) {
            return FailureCode.MISSING_ITEM;
        }
        if (m.contains("no recipe") || m.contains("isn't recognized") || m.contains("not a block")
                || m.contains("specify")) {
            return FailureCode.BAD_ARGS;
        }
        if (m.contains("no matching block") || m.contains("no more matching")) {
            return FailureCode.NOT_FOUND;
        }
        if (m.contains("full")) {
            return FailureCode.CONTAINER_FULL;
        }
        return FailureCode.UNREACHABLE;
    }

    static ActionError fail(FailureCode code, String message, Object... kv) {
        Map<String, Object> state = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            state.put((String) kv[i], kv[i + 1]);
        }
        return new ActionError(code, message, state);
    }

    /** The item counts a snapshot recorded under {@code key}. */
    @SuppressWarnings("unchecked")
    static Map<String, Integer> counts(Map<String, Object> pre, String key) {
        Object v = pre.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Integer>) m : Map.of();
    }

    static int count(Map<String, Integer> counts, String item) {
        Integer n = counts.get(item);
        return n == null ? 0 : n;
    }

    static Map<String, Integer> copy(Map<String, Integer> counts) {
        return new TreeMap<>(counts);
    }

    static List<Integer> list(AreaSpec.Pos p) {
        return List.of(p.x(), p.y(), p.z());
    }

    static List<Integer> list(Vec3 v) {
        return List.of((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
    }

    static Vec3 vec(Object list) {
        List<?> l = (List<?>) list;
        return new Vec3(((Number) l.get(0)).doubleValue() + 0.5, ((Number) l.get(1)).doubleValue(),
                ((Number) l.get(2)).doubleValue() + 0.5);
    }

    /**
     * The job region (§4.2) for a world-editing call that picks its own targets near the companion
     * ({@code mine}, {@code get}, {@code craft}): the companion must stand inside it. Null outside a
     * job.
     */
    static ActionError standingInRegion(Primitive.Context ctx) {
        if (ctx.region() == null || ctx.mod().getPlayer() == null) {
            return null;
        }
        net.minecraft.core.BlockPos b = ctx.mod().getPlayer().blockPosition();
        return com.player2.playerengine.seam.MotionBounds.check(ctx.region(), dimension(ctx),
                new com.player2.playerengine.tasks.construction.area.AreaSpec.Pos(b.getX(), b.getY(), b.getZ()));
    }

    static String dimension(Primitive.Context ctx) {
        return ctx.mod().getWorld().dimension().location().toString();
    }

    /** The item a block is placed from, by canonical id; the id itself when the block has none. */
    static String itemOf(String blockId) {
        net.minecraft.world.level.block.Block b = com.player2.playerengine.seam.Queries.block(blockId);
        return b == null || b.asItem() == net.minecraft.world.item.Items.AIR ? blockId
                : com.player2.playerengine.seam.LiveWorld.itemId(b.asItem());
    }

    static String human(String id) {
        return id.replace('_', ' ');
    }
}
