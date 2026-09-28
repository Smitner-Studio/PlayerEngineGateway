package com.player2.playerengine.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Lints a file of programs for the pack's replay checker, which has no linter of its own: every line
 * in is {@code {"key", "source", "profile"}}, every line out {@code {"key", "ok", "errors"}}, with the
 * published signature table and no registry (ids are checked by the seam at run time). One JVM for a
 * whole replay run, instead of one per reply.
 */
public final class LintFile {
    private LintFile() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LintFile <in.jsonl> <out.jsonl>");
        }
        ApiTable api = ApiTable.published();
        StringBuilder out = new StringBuilder();
        int n = 0;
        int ok = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject in = JsonParser.parseString(line).getAsJsonObject();
            Linter.Profile profile = Linter.Profile.valueOf(in.get("profile").getAsString().toUpperCase(Locale.ROOT));
            Linter.Result r = Linter.lint(in.get("source").getAsString(), profile, api, Linter.Ids.ANY,
                    Linter.Skills.NONE);
            JsonObject o = new JsonObject();
            o.add("key", in.get("key"));
            o.addProperty("ok", r.ok());
            JsonArray errors = new JsonArray();
            r.errors().forEach(e -> errors.add(e.toLine()));
            o.add("errors", errors);
            out.append(o).append('\n');
            n++;
            ok += r.ok() ? 1 : 0;
        }
        Files.writeString(Path.of(args[1]), out.toString(), StandardCharsets.UTF_8);
        System.out.println("program lint: " + ok + "/" + n + " programs lint clean");
    }
}
