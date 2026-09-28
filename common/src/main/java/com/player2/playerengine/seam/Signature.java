package com.player2.playerengine.seam;

import com.player2.playerengine.commands.base.PermissionClass;
import java.util.List;
import java.util.Objects;

/**
 * One entry of the action API (§5.3): its typed arguments, what it returns, the permission class it
 * runs under, and the world postcondition and idempotence the calls log and reconcile rely on.
 *
 * @param command      the registered command whose Task body this wraps, or null; when set, the
 *                     class comes from {@code PlayerEngineCommands.CLASSES}, never from here
 * @param bound        true when the seam already dispatches it; the rest are signature only
 */
public record Signature(String name, Kind kind, List<Arg> args, String returns, PermissionClass permissionClass,
        String command, boolean idempotent, String postcondition, boolean bound, String doc) {

    public enum Kind {
        /** Read-only; yields under the read budget and never loads a chunk (§6.2). */
        QUERY,
        /** Acts on the world; checked by its postcondition (§6.5). */
        PRIMITIVE
    }

    /** The types the model writes against (§5.2), by the names the reference uses. */
    public enum Type {
        POS("Pos"),
        BOX("Box"),
        FACING("Facing"),
        ITEM("ItemId"),
        BLOCK("BlockId"),
        /** {@code {item: n}}, {@code ["a","b"]} or {@code "a,b"}; an entry without a count means all of it. */
        ITEMS("ItemSet"),
        CONTAINER("Container"),
        /** An int clamped to 1..2304 (§5.4). */
        COUNT("Count"),
        INT("int"),
        TEXT("string"),
        QUERY_NAME("QueryName"),
        PREDICATE("Predicate"),
        ANY("any");

        public final String reference;

        Type(String reference) {
            this.reference = reference;
        }
    }

    /**
     * @param min the lower clamp for {@link Type#INT} and {@link Type#TEXT} length; ignored otherwise
     * @param max the upper clamp; 0 means none
     */
    public record Arg(String name, Type type, boolean required, int min, int max) {
        public static Arg of(String name, Type type) {
            return new Arg(name, type, true, 0, 0);
        }

        public static Arg bounded(String name, Type type, int min, int max) {
            return new Arg(name, type, true, min, max);
        }

        public static Arg optional(String name, Type type) {
            return new Arg(name, type, false, 0, 0);
        }
    }

    public Signature {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(permissionClass, "permissionClass of " + name);
        args = List.copyOf(args);
    }

    /** The TypeScript-style line the system prompt's API reference prints (§5.2). */
    public String reference() {
        StringBuilder sb = new StringBuilder("api.").append(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            Arg a = args.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a.name()).append(a.required() ? "" : "?").append(": ").append(a.type().reference);
            if (a.max() > 0) {
                sb.append(a.type() == Type.TEXT ? " /* <=" + a.max() + " chars */" : " /* " + a.min() + ".." + a.max() + " */");
            }
        }
        sb.append("): ").append(returns);
        return sb.toString();
    }
}
