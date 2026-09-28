package com.player2.playerengine.program;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code api.*} names the linter and interpreter know, read from the seam's published signature
 * table. It is read from the JSON, not from {@code SignatureTable}, because that class resolves
 * permission classes through the command registry and so needs Minecraft; this package does not.
 */
public final class ApiTable {
    /** {@code SignatureTable.RESOURCE}; the companion self-test keeps that file equal to the table. */
    public static final String RESOURCE = "playerengine/seam/signatures.json";

    /** One argument. {@code type} is the reference name ({@code Pos}, {@code ItemId}, {@code int}, …). */
    public record Arg(String name, String type, boolean required, int min, int max) {
    }

    public record Sig(String name, boolean query, List<Arg> args, boolean idempotent) {
        public Sig {
            args = List.copyOf(args);
        }

        int requiredArgs() {
            int n = 0;
            for (Arg a : args) {
                if (a.required()) {
                    n++;
                }
            }
            return n;
        }
    }

    private static volatile ApiTable published;

    private final Map<String, Sig> byName;

    public ApiTable(List<Sig> sigs) {
        Map<String, Sig> m = new LinkedHashMap<>();
        sigs.forEach(s -> m.put(s.name(), s));
        this.byName = Collections.unmodifiableMap(m);
    }

    public Sig get(String name) {
        return byName.get(name);
    }

    public java.util.Collection<Sig> all() {
        return byName.values();
    }

    /** The table on the classpath. */
    public static ApiTable published() {
        ApiTable t = published;
        if (t == null) {
            try (InputStream in = ApiTable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath");
                }
                t = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + RESOURCE, e);
            }
            published = t;
        }
        return t;
    }

    static ApiTable parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Sig> sigs = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("signatures")) {
            JsonObject o = e.getAsJsonObject();
            List<Arg> args = new ArrayList<>();
            for (JsonElement ae : o.getAsJsonArray("args")) {
                JsonObject a = ae.getAsJsonObject();
                args.add(new Arg(a.get("name").getAsString(), a.get("type").getAsString(),
                        a.get("required").getAsBoolean(), a.has("min") ? a.get("min").getAsInt() : 0,
                        a.has("max") ? a.get("max").getAsInt() : 0));
            }
            sigs.add(new Sig(o.get("name").getAsString(), "query".equals(o.get("kind").getAsString()), args,
                    o.get("idempotent").getAsBoolean()));
        }
        return new ApiTable(sigs);
    }
}
