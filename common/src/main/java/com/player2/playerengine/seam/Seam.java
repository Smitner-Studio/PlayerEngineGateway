package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.seam.primitives.Primitives;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

/**
 * The one door every action goes through (§3, §5). Until the stage-4 cutover the model's command
 * lines come through here too: a line a bound primitive takes runs as that primitive (coerced,
 * admitted, run, then checked by its postcondition), and any other line runs its registered command
 * after the tolerant-form coercions. The permission class check stays in front of this, in
 * {@code CommandExecutor}, over the same class table the signatures read.
 *
 * <p>A program's calls come in through {@link #call}: a query joins the query queue, and a
 * primitive runs the same way a command line's does.
 */
public final class Seam {
    private static final Map<String, Primitive> BY_NAME;
    private static final Map<String, Primitive> BY_COMMAND;

    static {
        Map<String, Primitive> byName = new LinkedHashMap<>();
        Map<String, Primitive> byCommand = new LinkedHashMap<>();
        for (Primitive p : Primitives.all()) {
            Signature s = p.signature();
            if (s == null || !s.bound() || s.kind() != Signature.Kind.PRIMITIVE) {
                throw new IllegalStateException((s == null ? p.getClass().getSimpleName() : s.name())
                        + " is dispatched but its signature is not a bound primitive");
            }
            if (byName.put(s.name(), p) != null) {
                throw new IllegalStateException("two primitives named " + s.name());
            }
            if (s.command() != null) {
                byCommand.put(s.command(), p);
            }
        }
        BY_NAME = Collections.unmodifiableMap(byName);
        BY_COMMAND = Collections.unmodifiableMap(byCommand);
    }

    private final PlayerEngineController mod;
    private final Coercion.Ids ids;
    private final QueryQueue queries = new QueryQueue();
    /** Containers this companion has opened (§5.6): {@code contents} may read them. */
    private final Set<String> known = Collections.synchronizedSet(new HashSet<>());

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

    /** The bound primitive by its API name, or null. */
    public static Primitive primitive(String name) {
        return BY_NAME.get(name);
    }

    /** This companion's query queue; {@link #tick()} serves it. */
    public QueryQueue queries() {
        return queries;
    }

    /** Records that this companion opened {@code h}, so its contents are known. */
    public void know(ContainerHandle h) {
        for (AreaSpec.Pos p : h.halves()) {
            known.add(p.x() + "," + p.y() + "," + p.z());
        }
    }

    public boolean isKnown(ContainerHandle h) {
        for (AreaSpec.Pos p : h.halves()) {
            if (known.contains(p.x() + "," + p.y() + "," + p.z())) {
                return true;
            }
        }
        return false;
    }

    /** The world this companion's calls read. */
    public Primitive.World world() {
        return new LiveWorld(mod, this);
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
     * Runs one API call from a program (§6.1). {@code done} receives the outcome exactly once: a
     * query's value, or a primitive's verdict after its postcondition, or {@code superseded} when a
     * stop or a newer order replaced it.
     *
     * @param raw    the arguments by signature name, before coercion
     * @param region the job's region, or null outside a job
     */
    public void call(String name, Map<String, Object> raw, MotionBounds.Region region, Consumer<Outcome> done) {
        Signature sig = SignatureTable.get(name);
        if (sig == null) {
            done.accept(Outcome.failed(ActionError.of(FailureCode.BAD_ARGS, "there is no api." + name), null));
            return;
        }
        if (!sig.bound()) {
            done.accept(Outcome.failed(ActionError.of(FailureCode.DENIED, "api." + name + " is not available yet"), null));
            return;
        }
        Coercion.Result coerced = Coercion.coerce(sig, raw == null ? Map.of() : raw, ids);
        if (!coerced.ok()) {
            done.accept(Outcome.failed(withCoercions(coerced.error(), coerced.notes()), coerced.notes()));
            return;
        }
        if (sig.kind() == Signature.Kind.QUERY) {
            List<String> notes = coerced.notes();
            queries.submit(Queries.build(sig, coerced.args(), world()),
                    o -> done.accept(o.ok() ? Outcome.ok(o.value(), notes)
                            : Outcome.failed(withCoercions(o.error(), notes), notes)));
            return;
        }
        Primitive p = BY_NAME.get(name);
        execute(p, coerced, Map.of(), new Primitive.Context(mod, region), done);
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
                runLine(primitive, line, ctx, onFinish, onError, onNote);
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

    private void runLine(Primitive p, Primitive.LineArgs line, Primitive.Context ctx, Runnable onFinish,
            Consumer<CommandException> onError, Consumer<String> onNote) {
        if (line.error() != null) {
            onError.accept(new CommandException(line.error().toLine()));
            return;
        }
        Coercion.Result coerced = Coercion.coerce(p.signature(), line.raw(), ids);
        if (!coerced.ok()) {
            onError.accept(new CommandException(withCoercions(coerced.error(), coerced.notes()).toLine()));
            return;
        }
        execute(p, coerced, line.options(), ctx, outcome -> {
            if (!outcome.ok()) {
                if (outcome.error().code() == FailureCode.SUPERSEDED) {
                    // A stop or a newer order replaced this call: it ends as the command path always
                    // ended a replaced task, and the moved seq tells a plan it was superseded.
                    onFinish.run();
                } else {
                    onError.accept(new CommandException(withCoercions(outcome.error(), outcome.coercions()).toLine()));
                }
                return;
            }
            String note = outcome.value() instanceof String s && !s.isBlank() ? s : null;
            if (!outcome.coercions().isEmpty()) {
                String read = "read as: " + String.join("; ", outcome.coercions());
                note = note == null ? read : note + "; " + read;
            }
            if (note == null) {
                onFinish.run();
            } else {
                onNote.accept(note);
            }
        });
    }

    /** Admit, snapshot, start, then the postcondition: the one path every primitive call takes. */
    private void execute(Primitive p, Coercion.Result coerced, Map<String, Object> options, Primitive.Context ctx,
            Consumer<Outcome> done) {
        List<String> notes = new ArrayList<>(coerced.notes());
        ActionError refused = p.admit(coerced.args(), ctx);
        if (refused != null) {
            done.accept(Outcome.failed(refused, notes));
            return;
        }
        Map<String, Object> pre;
        try {
            pre = p.snapshot(coerced.args(), world());
        } catch (Coercion.Failure f) {
            done.accept(Outcome.failed(f.error, notes));
            return;
        }
        long seq = mod.getCommandDispatchSeq();
        AtomicBoolean ended = new AtomicBoolean();
        p.start(coerced.args(), options, pre, ctx, end -> {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            if (mod.getCommandDispatchSeq() != seq || mod.isStopping) {
                done.accept(Outcome.failed(ActionError.of(FailureCode.SUPERSEDED,
                        "a stop or a newer order replaced " + p.signature().name()), notes));
                return;
            }
            done.accept(verify(p, coerced.args(), pre, end, world(), notes));
        });
    }

    /**
     * A call's outcome from its Task's verdict and the world: a Task that says Finished succeeds
     * only when the postcondition holds (§6.5).
     */
    static Outcome verify(Primitive p, Map<String, Object> args, Map<String, Object> pre, Primitive.TaskEnd end,
            Primitive.World world, List<String> notes) {
        if (end.error() != null) {
            return Outcome.failed(end.error(), notes);
        }
        ActionError post = p.postcondition(args, pre, world);
        return post == null ? Outcome.ok(end.note(), notes) : Outcome.failed(post, notes);
    }

    private static ActionError withCoercions(ActionError e, List<String> notes) {
        return notes == null || notes.isEmpty() ? e : e.with("coerced", List.copyOf(notes));
    }
}
