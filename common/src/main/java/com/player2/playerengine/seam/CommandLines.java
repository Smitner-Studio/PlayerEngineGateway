package com.player2.playerengine.seam;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tolerant forms of today's command lines (§5.4, E5, E6), until the stage-4 cutover retires the
 * command grammar: a plan sent as a command, {@code scan_storage} without a mode, and storage item
 * lists with loose ids or commas without spaces. Each change is reported as a note.
 */
public final class CommandLines {
    private static final Set<String> SCAN_MODES = Set.of("light", "deep", "targeted");
    private static final Set<String> STORAGE_ITEM_COMMANDS = Set.of("withdraw_from_storage", "deposit_to_storage");

    private CommandLines() {
    }

    /** A command line's argument text after coercion, with a note per change, or why it failed. */
    public record Normalised(String args, List<String> notes, ActionError error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * The plan a model put in the {@code command} field ({@code plan {"goal": ..., "steps": [...]}},
     * E5), as the {@code plan} field would carry it, or null when the command is not one.
     */
    public static JsonElement liftPlan(String command, String prefix) {
        if (command == null) {
            return null;
        }
        String c = command.trim();
        if (prefix != null && !prefix.isEmpty() && c.startsWith(prefix)) {
            c = c.substring(prefix.length()).trim();
        }
        if (!c.toLowerCase(Locale.ROOT).startsWith("plan")) {
            return null;
        }
        String rest = c.substring(4).trim();
        if (!rest.startsWith("{") && !rest.startsWith("[")) {
            return null;
        }
        try {
            JsonElement plan = JsonParser.parseString(rest);
            if (plan.isJsonArray()) {
                JsonObject wrapped = new JsonObject();
                wrapped.add("steps", plan);
                return wrapped;
            }
            return plan.isJsonObject() ? plan : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Coerces the argument text of command {@code name}; lines no rule covers pass unchanged. */
    public static Normalised normalise(String name, String args, Coercion.Ids ids) {
        String text = args == null ? "" : args.trim();
        List<String> notes = new ArrayList<>();
        if (!"scan_storage".equals(name) && !STORAGE_ITEM_COMMANDS.contains(name)) {
            return new Normalised(text, notes, null);
        }
        List<String> t = new ArrayList<>(Arrays.asList(text.isEmpty() ? new String[0] : text.split("\\s+")));
        if (t.size() < 3 || !Coercion.isInt(t.get(0)) || !Coercion.isInt(t.get(1)) || !Coercion.isInt(t.get(2))) {
            // Coordinates are required; the command's own usage error says so.
            return new Normalised(text, notes, null);
        }
        int itemsFrom = 3;
        if ("scan_storage".equals(name)) {
            if (t.size() == 3) {
                t.add("light");
                notes.add("scan_storage mode defaults to light");
            } else if (!SCAN_MODES.contains(t.get(3).toLowerCase(Locale.ROOT))) {
                t.add(3, "targeted");
                notes.add("scan_storage with items is a targeted scan");
            } else {
                t.set(3, t.get(3).toLowerCase(Locale.ROOT));
            }
            itemsFrom = 4;
        }
        if (t.size() > itemsFrom) {
            try {
                String items = items(String.join(" ", t.subList(itemsFrom, t.size())), ids, notes);
                t = new ArrayList<>(t.subList(0, itemsFrom));
                t.add(items);
            } catch (Coercion.Failure f) {
                return new Normalised(text, notes, f.error);
            }
        }
        return new Normalised(String.join(" ", t), notes, null);
    }

    /**
     * The storage item grammar ({@code item [count], ...}) with each id made canonical. Counts are
     * left for the command's own limits.
     */
    static String items(String tail, Coercion.Ids ids, List<String> notes) throws Coercion.Failure {
        List<String> out = new ArrayList<>();
        for (String entry : tail.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            String[] w = e.split("\\s+");
            String count = null;
            String name = e;
            if (w.length >= 2 && Coercion.isInt(w[w.length - 1])) {
                count = w[w.length - 1];
                name = String.join(" ", Arrays.copyOf(w, w.length - 1));
            }
            String id = Coercion.id(name, false, ids, notes);
            out.add(count == null ? id : id + " " + count);
        }
        return String.join(", ", out);
    }
}
