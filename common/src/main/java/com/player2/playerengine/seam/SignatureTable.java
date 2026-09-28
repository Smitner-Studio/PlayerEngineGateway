package com.player2.playerengine.seam;

import static com.player2.playerengine.seam.Signature.Kind.PRIMITIVE;
import static com.player2.playerengine.seam.Signature.Kind.QUERY;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngineCommands;
import com.player2.playerengine.commands.base.PermissionClass;
import com.player2.playerengine.seam.Signature.Arg;
import com.player2.playerengine.seam.Signature.Type;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The action API's signatures: the single source for the system prompt's API reference (§5.2) and
 * the stage-3 linter (§6.7). {@link #json()} is published as {@link #RESOURCE}; run
 * {@code task seam-signatures} after editing a row, and the companion self-test fails until the
 * published file matches.
 *
 * <p>A row that wraps a registered command takes that command's permission class from
 * {@link PlayerEngineCommands#CLASSES}; only rows with no command declare their own.
 */
public final class SignatureTable {
    /** Where the published table lives, on the classpath and under {@code common/src/main/resources}. */
    public static final String RESOURCE = "playerengine/seam/signatures.json";
    public static final int VERSION = 1;

    public static final int MAX_FIND_RADIUS = 32;
    public static final int MAX_FIND_RESULTS = 64;
    public static final int MAX_CONTAINER_RADIUS = 16;
    public static final int MAX_PICKUP_RADIUS = 16;
    public static final int MAX_WAIT_TICKS = 1200;
    public static final int MAX_WAIT_SECONDS = 600;
    public static final int MAX_TEXT = 200;

    private static final Map<String, Signature> BY_NAME;

    static {
        Map<String, Signature> m = new LinkedHashMap<>();
        for (Signature s : build()) {
            if (m.put(s.name(), s) != null) {
                throw new IllegalStateException("two signatures named " + s.name());
            }
        }
        BY_NAME = Collections.unmodifiableMap(m);
    }

    private SignatureTable() {
    }

    public static List<Signature> all() {
        return List.copyOf(BY_NAME.values());
    }

    public static Signature get(String name) {
        return BY_NAME.get(name);
    }

    private static List<Signature> build() {
        List<Signature> t = new ArrayList<>();
        // Queries (§5.3): yield, budgeted, never load a chunk.
        t.add(query("inventory", List.of(), "{[item: ItemId]: int}", true, "what the companion carries"));
        t.add(query("count", List.of(Arg.of("item", Type.ITEM)), "int", true, "how many of item it carries"));
        t.add(query("position", List.of(), "Pos", true, "where the companion stands"));
        t.add(query("owner_pos", List.of(), "Pos", true, "where the owner stands"));
        t.add(query("owner_facing", List.of(), "Facing", true, "the way the owner faces"));
        t.add(query("find_blocks", List.of(Arg.of("block", Type.BLOCK),
                        Arg.bounded("radius", Type.INT, 1, MAX_FIND_RADIUS),
                        Arg.bounded("max", Type.INT, 1, MAX_FIND_RESULTS)),
                "Pos[]", true, "exposed blocks of that kind, nearest first"));
        t.add(query("containers", List.of(Arg.bounded("radius", Type.INT, 1, MAX_CONTAINER_RADIUS)),
                "Container[]", true, "containers in sight or already known; a double chest is one handle"));
        t.add(query("contents", List.of(Arg.of("c", Type.CONTAINER)), "{[item: ItemId]: int}", true,
                "a known container's contents; an unknown one is denied until opened"));
        t.add(query("last_area", List.of(), "Box | null", true, "the last box an area primitive finished"));
        t.add(query("light_at", List.of(Arg.of("p", Type.POS)), "int", true, "the light level at p"));
        t.add(query("block_at", List.of(Arg.of("p", Type.POS)), "BlockId | \"hidden\"", true,
                "the block at p, or hidden when no face of it is exposed"));

        // Motion.
        t.add(primitive("goto", "goto", null, List.of(Arg.of("p", Type.POS)), true,
                "within 2 blocks of p", true, "walk to p"));
        t.add(primitive("follow_owner", "follow", null, List.of(Arg.bounded("until_near_blocks", Type.INT, 1, 64),
                        Arg.bounded("timeout_s", Type.INT, 1, MAX_WAIT_SECONDS)), true,
                "within the range, or timeout", true, "follow the owner until near"));
        t.add(primitive("wait", null, PermissionClass.MOVE, List.of(Arg.bounded("ticks", Type.INT, 1, MAX_WAIT_TICKS)),
                true, "that many ticks have passed", true, "stand still for ticks"));
        t.add(primitive("wait_until", null, PermissionClass.MOVE, List.of(Arg.of("query_name", Type.QUERY_NAME),
                        Arg.of("args", Type.ANY), Arg.of("predicate", Type.PREDICATE),
                        Arg.bounded("timeout_s", Type.INT, 1, MAX_WAIT_SECONDS)), true,
                "the predicate holds; re-queries at most every 20 ticks", true, "wait for a query to satisfy predicate"));

        // World.
        t.add(primitive("excavate", "excavate", null, List.of(Arg.of("box", Type.BOX)), true,
                "every non-excluded cell is air", true, "dig out a box; leaves players' blocks and containers"));
        t.add(primitive("fill", "fill", null, List.of(Arg.of("block", Type.BLOCK), Arg.of("box", Type.BOX)), true,
                "every target cell is block", true, "fill a box with block"));
        t.add(primitive("place", null, PermissionClass.WORLD, List.of(Arg.of("block", Type.BLOCK), Arg.of("p", Type.POS)),
                true, "block_at(p) == block", true, "place one block"));
        t.add(primitive("mine", "mine", null, List.of(Arg.of("block", Type.BLOCK), Arg.of("n", Type.COUNT)), false,
                "the drop item's inventory delta >= the expected drops for n", true, "mine n exposed blocks"));
        t.add(primitive("pickup_drops", "pickup_drops", null,
                List.of(Arg.bounded("radius", Type.INT, 1, MAX_PICKUP_RADIUS)), true,
                "no item entities left in the radius", true, "pick up dropped items nearby"));

        // Items.
        t.add(primitive("get", "get", null, List.of(Arg.of("item", Type.ITEM), Arg.of("n", Type.COUNT)), true,
                "count(item) >= n", true, "gather or craft until it carries n"));
        t.add(primitive("craft", null, PermissionClass.ITEMS, List.of(Arg.of("item", Type.ITEM), Arg.of("n", Type.COUNT)),
                false, "output delta >= n", true, "craft n more; gathers missing ingredients"));
        t.add(primitive("smelt", "smelt", null, List.of(Arg.of("output", Type.ITEM), Arg.of("n", Type.COUNT)), false,
                "output delta >= n", true, "smelt n of output; the input comes from the inventory"));
        t.add(primitive("store", "deposit_to_storage", null, List.of(Arg.optional("c", Type.CONTAINER),
                        Arg.of("items", Type.ITEMS)), false,
                "container delta = inventory delta", true,
                "store items, or \"all_except_tools\"; without c, the nearest container in sight"));
        t.add(primitive("withdraw", "withdraw_from_storage", null, List.of(Arg.of("c", Type.CONTAINER),
                        Arg.of("items", Type.ITEMS)), false,
                "inventory delta = container delta", true, "take items out of a container"));
        t.add(primitive("give_owner", "give", null, List.of(Arg.of("item", Type.ITEM), Arg.of("n", Type.COUNT)), false,
                "the owner's inventory delta, or an item entity at the owner", true, "hand items to the owner"));
        t.add(primitive("equip", "equip", null, List.of(Arg.of("item", Type.ITEM)), true,
                "the item is held or worn", true, "hold or wear item"));

        // Talk.
        t.add(primitive("say", null, PermissionClass.SELF, List.of(Arg.bounded("text", Type.TEXT, 1, MAX_TEXT)), true,
                "the line is in the companion's spoken log; at most 1 per 10 s, a sooner one waits", true,
                "say a line"));
        t.add(primitive("confirm", null, PermissionClass.SELF, List.of(Arg.bounded("question", Type.TEXT, 1, MAX_TEXT)),
                true, "the owner answered yes or no", true, "ask the owner and wait for yes or no"));
        return t;
    }

    private static Signature query(String name, List<Arg> args, String returns, boolean bound, String doc) {
        return new Signature(name, QUERY, args, returns, PermissionClass.QUERY, null, true, "none", bound, doc);
    }

    private static Signature primitive(String name, String command, PermissionClass own, List<Arg> args,
            boolean idempotent, String postcondition, boolean bound, String doc) {
        PermissionClass c = command == null ? own : PlayerEngineCommands.CLASSES.get(command);
        if (c == null) {
            throw new IllegalStateException(name + " wraps " + command + ", which has no permission class");
        }
        return new Signature(name, PRIMITIVE, args, "void", c, command, idempotent, postcondition, bound, doc);
    }

    /** The published form. Key order is fixed so the file diffs cleanly. */
    public static String json() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("source", "com.player2.playerengine.seam.SignatureTable; regenerate with task seam-signatures");
        JsonArray codes = new JsonArray();
        for (FailureCode c : FailureCode.values()) {
            codes.add(c.wire());
        }
        root.add("failureCodes", codes);
        JsonArray sigs = new JsonArray();
        for (Signature s : all()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name());
            o.addProperty("kind", s.kind().name().toLowerCase(java.util.Locale.ROOT));
            o.addProperty("class", s.permissionClass().name());
            if (s.command() != null) {
                o.addProperty("command", s.command());
            }
            JsonArray args = new JsonArray();
            for (Arg a : s.args()) {
                JsonObject ao = new JsonObject();
                ao.addProperty("name", a.name());
                ao.addProperty("type", a.type().reference);
                ao.addProperty("required", a.required());
                if (a.max() > 0) {
                    ao.addProperty("min", a.min());
                    ao.addProperty("max", a.max());
                }
                args.add(ao);
            }
            o.add("args", args);
            o.addProperty("returns", s.returns());
            o.addProperty("idempotent", s.idempotent());
            o.addProperty("postcondition", s.postcondition());
            o.addProperty("bound", s.bound());
            o.addProperty("doc", s.doc());
            o.addProperty("reference", s.reference());
            sigs.add(o);
        }
        root.add("signatures", sigs);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    /** The published file on the classpath, line endings normalised, or null when it is missing. */
    public static String published() {
        try (InputStream in = SignatureTable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes {@link #json()} to the path in {@code args[0]}; {@code task seam-signatures} runs this. */
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        Files.write(out, json().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote=" + out + " signatures=" + all().size());
    }
}
