package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.AreaCommand;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The one door every action goes through (§3, §5). Until the stage-4 cutover the model's command
 * lines come through here too: a line a bound primitive takes runs as that primitive (coerced,
 * admitted, run, then checked by its postcondition), and any other line runs its registered command
 * after the tolerant-form coercions. The permission class check stays in front of this, in
 * {@code CommandExecutor}, over the same class table the signatures read.
 */
public final class Seam {
    private static final Map<String, Primitive> BY_COMMAND;

    static {
        Map<String, Primitive> m = new LinkedHashMap<>();
        for (Primitive p : List.of(new GotoPrimitive(), new ExcavatePrimitive())) {
            Signature s = p.signature();
            if (!s.bound() || s.command() == null) {
                throw new IllegalStateException(s.name() + " is dispatched but its signature is not bound to a command");
            }
            m.put(s.command(), p);
        }
        BY_COMMAND = Map.copyOf(m);
    }

    private final PlayerEngineController mod;
    private final Coercion.Ids ids;
    private final QueryQueue queries = new QueryQueue();

    public Seam(PlayerEngineController mod) {
        this(mod, Coercion.Ids.REGISTRIES);
    }

    Seam(PlayerEngineController mod, Coercion.Ids ids) {
        this.mod = mod;
        this.ids = ids;
    }

    /** The primitive a registered command's lines run as, or null. */
    public static Primitive primitiveFor(String commandName) {
        return BY_COMMAND.get(commandName);
    }

    /** This companion's query queue; {@link #tick()} serves it. */
    public QueryQueue queries() {
        return queries;
    }

    /** Server tick: serves one pending query as far as the server-wide read budget allows. */
    public void tick() {
        ServerLevel level = mod.getWorld();
        if (level == null || queries.size() == 0) {
            return;
        }
        queries.tick(WorldReader.of(level), ReadBudget.SERVER, level.getServer().getTickCount());
    }

    /**
     * Runs one {@code ;} part of a command line. The callbacks are the executor's: exactly one of
     * them fires, once, when the part ends.
     */
    public void run(Command command, String part, Runnable onFinish, Consumer<CommandException> onError,
            Consumer<String> onNote) throws CommandException {
        String name = command.getName();
        String trimmed = part.trim();
        int space = trimmed.indexOf(' ');
        String argsText = space < 0 ? "" : trimmed.substring(space + 1).trim();

        Primitive primitive = BY_COMMAND.get(name);
        if (primitive != null) {
            Primitive.Context ctx = new Primitive.Context(mod, null);
            Primitive.LineArgs line = primitive.fromLine(argsText, ctx);
            if (line != null) {
                runPrimitive(primitive, line, ctx, onFinish, onError, onNote);
                return;
            }
        }

        CommandLines.Normalised n = CommandLines.normalise(name, argsText, ids);
        if (!n.ok()) {
            onError.accept(new CommandException(withCoercions(n.error(), n.notes()).toLine()));
            return;
        }
        if (n.notes().isEmpty()) {
            command.run(mod, part, onFinish, onError, onNote);
            return;
        }
        String read = "read as: " + String.join("; ", n.notes());
        command.run(mod, name + (n.args().isEmpty() ? "" : " " + n.args()),
                () -> onNote.accept(read),
                e -> onError.accept(new CommandException(e.getMessage() + " (" + read + ")", e)),
                note -> onNote.accept(note == null || note.isBlank() ? read : note + "; " + read));
    }

    private void runPrimitive(Primitive p, Primitive.LineArgs line, Primitive.Context ctx, Runnable onFinish,
            Consumer<CommandException> onError, Consumer<String> onNote) {
        if (line.error() != null) {
            onError.accept(new CommandException(line.error().toLine()));
            return;
        }
        Coercion.Result coerced = Coercion.coerce(p.signature(), line.raw(), ids);
        List<String> notes = new ArrayList<>(coerced.notes());
        if (!coerced.ok()) {
            onError.accept(new CommandException(withCoercions(coerced.error(), notes).toLine()));
            return;
        }
        ActionError refused = p.admit(coerced.args(), ctx);
        if (refused != null) {
            onError.accept(new CommandException(withCoercions(refused, notes).toLine()));
            return;
        }
        long seq = mod.getCommandDispatchSeq();
        AtomicBoolean done = new AtomicBoolean();
        p.start(coerced.args(), line.options(), ctx, end -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (mod.getCommandDispatchSeq() != seq || mod.isStopping) {
                // A stop or a newer order replaced this call: it ends as the command path always
                // ended a replaced task, and the moved seq tells a plan it was superseded.
                onFinish.run();
                return;
            }
            Outcome outcome = verify(p, coerced.args(), end, world(mod), notes);
            if (!outcome.ok()) {
                onError.accept(new CommandException(withCoercions(outcome.error(), notes).toLine()));
                return;
            }
            String note = outcome.value() instanceof String s && !s.isBlank() ? s : null;
            if (!notes.isEmpty()) {
                String read = "read as: " + String.join("; ", notes);
                note = note == null ? read : note + "; " + read;
            }
            if (note == null) {
                onFinish.run();
            } else {
                onNote.accept(note);
            }
        });
    }

    /**
     * A call's outcome from its Task's verdict and the world: a Task that says Finished succeeds
     * only when the postcondition holds (§6.5).
     */
    static Outcome verify(Primitive p, Map<String, Object> args, Primitive.TaskEnd end, Primitive.World world,
            List<String> notes) {
        if (end.error() != null) {
            return Outcome.failed(end.error(), notes);
        }
        ActionError post = p.postcondition(args, world);
        return post == null ? Outcome.ok(end.note(), notes) : Outcome.failed(post, notes);
    }

    private static ActionError withCoercions(ActionError e, List<String> notes) {
        return notes.isEmpty() ? e : e.with("coerced", List.copyOf(notes));
    }

    /** The world a postcondition reads: the companion's position, and cells as the area scan sees them. */
    static Primitive.World world(PlayerEngineController mod) {
        ServerLevel level = mod.getWorld();
        AreaScan.BlockLookup cells = AreaCommand.lookup(mod, level, null);
        return new Primitive.World() {
            @Override
            public Vec3 position() {
                return mod.getPlayer().position();
            }

            @Override
            public AreaScan.Cell cell(int x, int y, int z) {
                return cells.cell(x, y, z);
            }
        };
    }
}
