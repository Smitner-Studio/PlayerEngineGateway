package com.player2.playerengine.seam;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.construction.area.AreaScan;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;

/**
 * One action the seam dispatches (§5.3): typed arguments and a permission class (its
 * {@link #signature()}), a region check, a Task body, a world postcondition, an idempotence flag and a
 * reconcile check. The Task saying Finished is never the success: the postcondition is (§6.5).
 *
 * <p>A call runs {@code admit}, then {@code snapshot}, then {@code start}, then {@code postcondition}
 * against the snapshot. The snapshot is what the calls log keeps as the pre-snapshot (§6.4), so it
 * holds plain values only (maps, lists, strings, numbers).
 */
public interface Primitive {
    Signature signature();

    /** Region, protection and motion checks before anything moves; null when admitted. */
    ActionError admit(Map<String, Object> args, Context ctx);

    /**
     * What the postcondition compares against, read before the Task starts: for a delta
     * postcondition, the counts it starts from. A failure here ends the call before anything moves.
     */
    default Map<String, Object> snapshot(Map<String, Object> args, World world) throws Coercion.Failure {
        return Map.of();
    }

    /** Starts the Task body. {@code ended} receives the Task's own verdict, exactly once. */
    void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended);

    /** Null when the world shows the call's effect, measured from {@code pre}, else why it does not. */
    ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world);

    /** Whether a call may be re-run after an interruption without doubling its effect. */
    default boolean idempotent() {
        return signature().idempotent();
    }

    /**
     * What a call interrupted by a restart did (§6.4): done when the postcondition already holds,
     * re-run when the call is idempotent, and otherwise a question for the initiator.
     */
    default Reconcile reconcile(Map<String, Object> args, Map<String, Object> pre, World world) {
        if (postcondition(args, pre, world) == null) {
            return Reconcile.DONE;
        }
        return idempotent() ? Reconcile.RERUN : Reconcile.ASK;
    }

    enum Reconcile { DONE, RERUN, ASK }

    /** The companion a call runs on, and the job region its motion is bound to (null outside a job). */
    record Context(PlayerEngineController mod, MotionBounds.Region region) {
    }

    /**
     * How the Task ended by its own account.
     *
     * @param error set when the Task itself failed or refused
     * @param note  a success detail for the model, or null
     * @param value what the call returns to a program, when it returns something ({@code confirm})
     */
    record TaskEnd(ActionError error, String note, Object value) {
        public static TaskEnd finished(String note) {
            return new TaskEnd(null, note, null);
        }

        public static TaskEnd returned(Object value) {
            return new TaskEnd(null, null, value);
        }

        public static TaskEnd failed(ActionError error) {
            return new TaskEnd(error, null, null);
        }
    }

    /**
     * What a postcondition, snapshot or query reads. The live form is {@link LiveWorld}; the
     * self-tests fake it. A method a fake does not implement throws rather than answering empty, so a
     * postcondition can never pass by reading a default.
     */
    interface World {
        Vec3 position();

        AreaScan.Cell cell(int x, int y, int z);

        /** The block id at a cell in canonical form, or null when its chunk is not loaded. */
        default String blockAt(int x, int y, int z) {
            throw unsupported("blockAt");
        }

        /** The raw light level at a cell, or -1 when its chunk is not loaded. */
        default int light(int x, int y, int z) {
            throw unsupported("light");
        }

        /** What the companion carries (main, off hand and armor), by canonical item id. */
        default Map<String, Integer> inventory() {
            throw unsupported("inventory");
        }

        /** Empty main-inventory slots. */
        default int freeSlots() {
            throw unsupported("freeSlots");
        }

        /** The ids in the main hand and the armor slots. */
        default List<String> equipped() {
            throw unsupported("equipped");
        }

        /** The owner's position, or null when the owner is offline or in another dimension. */
        default Vec3 ownerPosition() {
            throw unsupported("ownerPosition");
        }

        /** The way the owner faces, or null when the owner is not here. */
        default AreaSpec.Facing ownerFacing() {
            throw unsupported("ownerFacing");
        }

        /** The owner's inventory by canonical item id, or null when the owner is not here. */
        default Map<String, Integer> ownerInventory() {
            throw unsupported("ownerInventory");
        }

        /** Item entities on the ground within {@code radius} of {@code centre}, by canonical item id. */
        default Map<String, Integer> groundItems(Vec3 centre, double radius) {
            throw unsupported("groundItems");
        }

        /**
         * The whole container at {@code p}: a double chest named by either half is both halves, with
         * the contents of both (E8).
         *
         * @throws Coercion.Failure {@code no_container} or {@code not_loaded}
         */
        default ContainerHandle.Contents container(AreaSpec.Pos p) throws Coercion.Failure {
            throw unsupported("container");
        }

        /** The nearest container the companion could see or already knows, within {@code radius}; null when none. */
        default ContainerHandle nearestContainer(int radius) {
            throw unsupported("nearestContainer");
        }

        /**
         * Positions of item-holding block entities within {@code radius} of {@code centre}, from loaded
         * chunks only; a double chest may appear once per half.
         */
        default List<AreaSpec.Pos> containerBlocks(AreaSpec.Pos centre, int radius) {
            throw unsupported("containerBlocks");
        }

        /** One raycast from the companion's eyes: whether any half of {@code h} is in plain sight. */
        default boolean lineOfSight(ContainerHandle h) {
            throw unsupported("lineOfSight");
        }

        /** Opened by this companion (or in the owner's recorded storage), so {@code contents} may read it. */
        default boolean known(ContainerHandle h) {
            throw unsupported("known");
        }

        /** Whether any face of a block is exposed (§5.6); unloaded neighbours count as solid. */
        default boolean exposed(int x, int y, int z) {
            throw unsupported("exposed");
        }

        /** The last box an area primitive finished, or null. */
        default AreaSpec.Box lastArea() {
            throw unsupported("lastArea");
        }

        /**
         * The items mining {@code blockId} with the right tool can yield, by canonical id, the block's
         * own item included (silk touch); empty when it yields nothing.
         */
        default List<String> drops(String blockId) {
            throw unsupported("drops");
        }

        /** What smelting, blasting or smoking {@code itemId} makes, by canonical id; null when nothing. */
        default String smeltResult(String itemId) {
            throw unsupported("smeltResult");
        }

        /** The server's game time in ticks. */
        default long gameTime() {
            throw unsupported("gameTime");
        }

        /** The block reads a query makes; only for a re-check that fits one tick's read budget. */
        default WorldReader reader() {
            throw unsupported("reader");
        }

        /** The lines this companion has said through {@code say}, oldest first, and how many in all. */
        default Seam.Spoken spoken() {
            throw unsupported("spoken");
        }

        /** The last {@code confirm} this companion asked, and the owner's answer so far. */
        default Seam.Confirmation confirmation() {
            throw unsupported("confirmation");
        }

        private static UnsupportedOperationException unsupported(String what) {
            return new UnsupportedOperationException("this world does not read " + what);
        }
    }
}
