package com.player2.playerengine.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Outcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calls log (§6.4): {@code started(seq, call, coerced args, pre-snapshot)} before a call is
 * dispatched and {@code done(seq, outcome)} after. It is written ahead of the world: the job is saved
 * with the {@code started} entry before the call begins, which is what reconcile relies on.
 */
public final class CallLog {
    public enum Status {
        STARTED,
        DONE,
        /** Started, then settled at reconcile as not done; a later entry re-ran it. */
        ABANDONED
    }

    public static final class Entry {
        private final long seq;
        private final String name;
        private final boolean query;
        private final Map<String, Object> args;
        private final Map<String, Object> pre;
        private final List<String> coercions;
        /** The earlier entry this one re-runs out of band at reconcile, or -1. */
        private final long redoOf;
        private Status status = Status.STARTED;
        private Map<String, Object> post;
        private Object value;
        private ActionError error;
        /** Set when reconcile, not the call's own outcome, settled it. */
        private String reconciled;
        /** A done call whose effect a restart rolled back, re-run by a later entry. */
        private boolean rolledBack;

        Entry(long seq, String name, boolean query, Map<String, Object> args, Map<String, Object> pre,
                List<String> coercions, long redoOf) {
            this.seq = seq;
            this.name = name;
            this.query = query;
            this.args = args;
            this.pre = pre;
            this.coercions = List.copyOf(coercions);
            this.redoOf = redoOf;
        }

        public long seq() {
            return seq;
        }

        public String name() {
            return name;
        }

        public boolean query() {
            return query;
        }

        public Map<String, Object> args() {
            return args;
        }

        public Map<String, Object> pre() {
            return pre;
        }

        public Map<String, Object> post() {
            return post;
        }

        public Status status() {
            return status;
        }

        public boolean ok() {
            return status == Status.DONE && error == null;
        }

        public Object value() {
            return value;
        }

        public ActionError error() {
            return error;
        }

        public long redoOf() {
            return redoOf;
        }

        public boolean rolledBack() {
            return rolledBack;
        }

        public String reconciled() {
            return reconciled;
        }

        void done(Outcome o, Map<String, Object> post) {
            status = Status.DONE;
            value = o.value();
            error = o.error();
            this.post = post;
        }

        void settle(String how) {
            status = Status.DONE;
            reconciled = how;
        }

        void abandon() {
            status = Status.ABANDONED;
        }

        void rollBack() {
            rolledBack = true;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("seq", seq);
            o.addProperty("call", name);
            if (query) {
                o.addProperty("query", true);
            }
            o.add("args", Values.plain(args));
            o.add("pre", Values.plain(pre));
            if (!coercions.isEmpty()) {
                o.add("coercions", Values.plain(coercions));
            }
            if (redoOf >= 0) {
                o.addProperty("redoOf", redoOf);
            }
            o.addProperty("status", status.name());
            if (post != null) {
                o.add("post", Values.plain(post));
            }
            if (value != null) {
                o.add("value", Values.plain(value));
            }
            if (error != null) {
                o.add("error", errorJson(error));
            }
            if (reconciled != null) {
                o.addProperty("reconciled", reconciled);
            }
            if (rolledBack) {
                o.addProperty("rolledBack", true);
            }
            return o;
        }

        @SuppressWarnings("unchecked")
        static Entry fromJson(JsonObject o) {
            List<String> coercions = new ArrayList<>();
            if (o.has("coercions")) {
                o.getAsJsonArray("coercions").forEach(c -> coercions.add(c.getAsString()));
            }
            Entry e = new Entry(o.get("seq").getAsLong(), o.get("call").getAsString(), o.has("query"),
                    hostMap(o.get("args")), hostMap(o.get("pre")), coercions,
                    o.has("redoOf") ? o.get("redoOf").getAsLong() : -1);
            e.status = Status.valueOf(o.get("status").getAsString());
            e.post = o.has("post") ? hostMap(o.get("post")) : null;
            e.value = o.has("value") ? Values.toHost(Values.decodePlain(o.get("value"))) : null;
            e.error = o.has("error") ? errorFromJson(o.getAsJsonObject("error")) : null;
            e.reconciled = o.has("reconciled") ? o.get("reconciled").getAsString() : null;
            e.rolledBack = o.has("rolledBack");
            return e;
        }

        /** {@code api.name(args)} for the calls log and the reconcile question. */
        public String describe() {
            return "api." + name + "(" + Values.display(Values.fromHost(args)) + ")";
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    Entry started(long seq, String name, boolean query, Map<String, Object> args, Map<String, Object> pre,
            List<String> coercions, long redoOf) {
        Entry e = new Entry(seq, name, query, args, pre, coercions, redoOf);
        entries.add(e);
        return e;
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    Entry get(long seq) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).seq == seq) {
                return entries.get(i);
            }
        }
        return null;
    }

    JsonArray toJson() {
        JsonArray a = new JsonArray();
        entries.forEach(e -> a.add(e.toJson()));
        return a;
    }

    static CallLog fromJson(JsonArray a) {
        CallLog log = new CallLog();
        for (JsonElement e : a) {
            log.entries.add(Entry.fromJson(e.getAsJsonObject()));
        }
        return log;
    }

    static JsonObject errorJson(ActionError e) {
        JsonObject o = new JsonObject();
        o.addProperty("code", e.code().wire());
        o.addProperty("message", e.message());
        o.add("state", Values.plain(e.state()));
        return o;
    }

    static ActionError errorFromJson(JsonObject o) {
        FailureCode code = FailureCode.valueOf(o.get("code").getAsString().toUpperCase(java.util.Locale.ROOT));
        return new ActionError(code, o.get("message").getAsString(), hostMap(o.get("state")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hostMap(JsonElement j) {
        Object v = Values.toHost(Values.decodePlain(j));
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }
}
