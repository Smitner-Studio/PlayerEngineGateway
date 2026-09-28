package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;

/**
 * One action the seam dispatches (§5.3): typed arguments and a permission class (its
 * {@link #signature()}), a region check, a Task body, a world postcondition, an idempotence flag and a
 * reconcile check. The Task saying Finished is never the success: the postcondition is (§6.5).
 */
public interface Primitive {
    Signature signature();

    /**
     * The raw arguments a command line's argument text gives, before coercion.
     *
     * @return null when this line form is not one the primitive takes, so the registered command
     *         runs it as before (for example {@code goto} with a dimension)
     */
    LineArgs fromLine(String argsText, Context ctx);

    /** Region, protection and motion checks before anything moves; null when admitted. */
    ActionError admit(Map<String, Object> args, Context ctx);

    /** Starts the Task body. {@code ended} receives the Task's own verdict, exactly once. */
    void start(Map<String, Object> args, Map<String, Object> options, Context ctx, Consumer<TaskEnd> ended);

    /** Null when the world shows the call's effect, else why it does not. */
    ActionError postcondition(Map<String, Object> args, World world);

    /** Whether a call may be re-run after an interruption without doubling its effect. */
    default boolean idempotent() {
        return signature().idempotent();
    }

    /**
     * What a call interrupted by a restart did (§6.4): done when the postcondition already holds,
     * re-run when the call is idempotent, and otherwise a question for the initiator.
     */
    default Reconcile reconcile(Map<String, Object> args, World world) {
        if (postcondition(args, world) == null) {
            return Reconcile.DONE;
        }
        return idempotent() ? Reconcile.RERUN : Reconcile.ASK;
    }

    enum Reconcile { DONE, RERUN, ASK }

    /** The companion a call runs on, and the job region its motion is bound to (null outside a job). */
    record Context(PlayerEngineController mod, MotionBounds.Region region) {
    }

    /**
     * A command line's arguments: the raw values by signature name, the line-only options the
     * signature does not carry (such as {@code confirm=yes}), or why the line does not parse.
     */
    record LineArgs(Map<String, Object> raw, Map<String, Object> options, ActionError error) {
        public static LineArgs of(Map<String, Object> raw, Map<String, Object> options) {
            return new LineArgs(raw, options, null);
        }

        public static LineArgs failed(ActionError error) {
            return new LineArgs(Map.of(), Map.of(), error);
        }
    }

    /**
     * How the Task ended by its own account.
     *
     * @param error set when the Task itself failed or refused
     * @param note  a success detail for the model, or null
     */
    record TaskEnd(ActionError error, String note) {
        public static TaskEnd finished(String note) {
            return new TaskEnd(null, note);
        }

        public static TaskEnd failed(ActionError error) {
            return new TaskEnd(error, null);
        }
    }

    /** What a postcondition reads. */
    interface World {
        Vec3 position();

        AreaScan.Cell cell(int x, int y, int z);
    }
}
