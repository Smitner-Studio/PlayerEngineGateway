package com.player2.playerengine.player2api.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One companion's current job: an ordered list of command lines and how far it has got. Mutated only
 * under the owning {@link PlanCoordinator}'s monitor.
 */
public final class CompanionPlan {
    public enum Status {
        /** A step is dispatched, or about to be. */
        RUNNING,
        /** Interrupted (superseded, cancelled task, restart); resumes on request. */
        PAUSED,
        /** A step failed and the model was asked for revised steps. */
        AWAITING_REPAIR
    }

    final long generation;
    final String goal;
    final List<String> steps;
    final List<String> results;
    final UUID initiator;
    int cursor;
    Status status = Status.RUNNING;
    int repairsUsed;
    int callsCharged;
    long lastActivityMillis;
    /** Monotonic token of the running dispatch; 0 when no step is running. */
    long stepToken;
    long stepDispatchedMillis;
    /** Controller dispatch seq observed when the running step was accepted; -1 until then. */
    long stepSeq = -1;
    /** True after a restart until the cursor step is dispatched again. */
    boolean cursorOutcomeUnknown;
    /** Compact results of the plan this one repaired, kept for the completion report. */
    String priorResults = "";

    CompanionPlan(long generation, String goal, List<String> steps, UUID initiator, long now) {
        this.generation = generation;
        this.goal = goal;
        this.steps = List.copyOf(steps);
        this.results = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            this.results.add("");
        }
        this.initiator = initiator;
        this.lastActivityMillis = now;
    }

    public String goal() {
        return goal;
    }

    public List<String> steps() {
        return steps;
    }

    public int cursor() {
        return cursor;
    }

    public Status status() {
        return status;
    }

    public UUID initiator() {
        return initiator;
    }

    public List<String> results() {
        return List.copyOf(results);
    }

    boolean stepRunning() {
        return stepToken != 0;
    }

    String currentStep() {
        return cursor < steps.size() ? steps.get(cursor) : "";
    }

    String doneSummary() {
        StringBuilder sb = new StringBuilder(priorResults);
        for (int i = 0; i < cursor && i < steps.size(); i++) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(steps.get(i)).append(" -> ").append(results.get(i));
        }
        return sb.length() == 0 ? "nothing yet" : cap(sb.toString(), 400);
    }

    String remainingSummary(int from) {
        if (from >= steps.size()) {
            return "nothing";
        }
        return cap(String.join("; ", steps.subList(from, steps.size())), 400);
    }

    static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("generation", generation);
        o.addProperty("goal", goal);
        JsonArray s = new JsonArray();
        steps.forEach(s::add);
        o.add("steps", s);
        JsonArray r = new JsonArray();
        results.forEach(r::add);
        o.add("results", r);
        o.addProperty("initiator", initiator == null ? "" : initiator.toString());
        o.addProperty("cursor", cursor);
        o.addProperty("status", status.name());
        o.addProperty("repairsUsed", repairsUsed);
        o.addProperty("callsCharged", callsCharged);
        o.addProperty("lastActivityMillis", lastActivityMillis);
        o.addProperty("stepRunning", stepRunning());
        o.addProperty("priorResults", priorResults);
        return o;
    }

    /**
     * Rebuilds a saved plan as PAUSED. A step that was running when the plan was saved may or may not
     * have finished, so its outcome is marked unknown.
     *
     * @return the plan, or null when the JSON is not a usable plan
     */
    static CompanionPlan fromJson(JsonObject o) {
        try {
            if (o.get("version") == null || o.get("version").getAsInt() != 1) {
                return null;
            }
            List<String> steps = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("steps")) {
                steps.add(e.getAsString());
            }
            if (steps.isEmpty() || steps.size() > PlanParser.MAX_STEPS) {
                return null;
            }
            String init = o.get("initiator").getAsString();
            CompanionPlan p = new CompanionPlan(
                    o.get("generation").getAsLong(),
                    o.get("goal").getAsString(),
                    steps,
                    init.isEmpty() ? null : UUID.fromString(init),
                    o.get("lastActivityMillis").getAsLong());
            JsonArray r = o.getAsJsonArray("results");
            for (int i = 0; i < r.size() && i < steps.size(); i++) {
                p.results.set(i, r.get(i).getAsString());
            }
            p.cursor = Math.max(0, Math.min(o.get("cursor").getAsInt(), steps.size() - 1));
            p.repairsUsed = o.get("repairsUsed").getAsInt();
            p.callsCharged = o.get("callsCharged").getAsInt();
            p.priorResults = o.has("priorResults") ? o.get("priorResults").getAsString() : "";
            p.status = Status.PAUSED;
            if (o.get("stepRunning").getAsBoolean()) {
                p.cursorOutcomeUnknown = true;
                p.results.set(p.cursor, "unknown");
            }
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
