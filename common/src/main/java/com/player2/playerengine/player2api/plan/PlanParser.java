package com.player2.playerengine.player2api.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the optional {@code plan} field of a decision reply. The field never breaks the reply: any
 * shape it cannot use becomes {@link Invalid} with a reason the model can act on.
 */
public final class PlanParser {
    public static final int MAX_STEPS = 8;
    public static final int MAX_STEP_CHARS = 160;
    public static final int MAX_GOAL_CHARS = 120;

    /** Commands that are not work: they would either do nothing or end the plan they belong to. */
    static final Set<String> FORBIDDEN_STEP_IDS = Set.of("idle", "bodylang", "stop", "rag_deepsearch");

    private static final Set<String> RESUME_WORDS = Set.of("resume", "continue");
    private static final Set<String> CANCEL_WORDS = Set.of("cancel", "stop", "abandon");

    public sealed interface Result permits Absent, Resume, Cancel, NewPlan, Invalid {
    }

    public record Absent() implements Result {
    }

    public record Resume() implements Result {
    }

    public record Cancel() implements Result {
    }

    public record NewPlan(String goal, List<String> steps) implements Result {
    }

    public record Invalid(String reason) implements Result {
    }

    private PlanParser() {
    }

    /**
     * @param field     the reply's {@code plan} value, or null when the key is missing
     * @param resolveId maps a step's first token to its registered command id, or null when no
     *                  command of that name (or alias) is registered
     */
    public static Result parse(JsonElement field, Function<String, String> resolveId) {
        if (field == null || field.isJsonNull()) {
            return new Absent();
        }
        if (field.isJsonPrimitive()) {
            JsonPrimitive p = field.getAsJsonPrimitive();
            if (!p.isString()) {
                return new Invalid("plan must be an object with goal and steps");
            }
            String word = p.getAsString().trim().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) {
                return new Absent();
            }
            if (RESUME_WORDS.contains(word)) {
                return new Resume();
            }
            if (CANCEL_WORDS.contains(word)) {
                return new Cancel();
            }
            return new Invalid("plan as text may only be \"resume\" or \"cancel\"");
        }
        JsonArray stepsArray;
        String goal = null;
        if (field.isJsonArray()) {
            stepsArray = field.getAsJsonArray();
        } else {
            JsonObject obj = field.getAsJsonObject();
            if (obj.size() == 0) {
                return new Absent();
            }
            JsonElement g = obj.get("goal");
            if (g != null && g.isJsonPrimitive()) {
                goal = g.getAsString();
            }
            JsonElement s = obj.get("steps");
            if (s == null || s.isJsonNull()) {
                return new Invalid("plan has no steps");
            }
            if (!s.isJsonArray()) {
                return new Invalid("plan steps must be a list of command lines");
            }
            stepsArray = s.getAsJsonArray();
        }
        if (stepsArray.isEmpty()) {
            return field.isJsonArray() ? new Absent() : new Invalid("plan has no steps");
        }
        if (stepsArray.size() > MAX_STEPS) {
            return new Invalid("a plan has at most " + MAX_STEPS + " steps; merge or drop some");
        }
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < stepsArray.size(); i++) {
            String line = stepLine(stepsArray.get(i));
            String label = "step " + (i + 1);
            if (line == null || line.isBlank()) {
                return new Invalid(label + " is not a command line");
            }
            line = line.trim();
            while (line.startsWith("@")) {
                line = line.substring(1).trim();
            }
            // CommandExecutor splits a line on ';' and runs every part, so one step could smuggle in
            // several commands past the per-step checks below.
            if (line.indexOf(';') >= 0) {
                return new Invalid(label + " holds more than one command; use one command per step");
            }
            if (line.length() > MAX_STEP_CHARS) {
                return new Invalid(label + " is longer than " + MAX_STEP_CHARS + " characters");
            }
            int sp = line.indexOf(' ');
            String name = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT);
            String id = resolveId.apply(name);
            if (id == null) {
                return new Invalid(label + " uses '" + name + "', which is not one of your commands");
            }
            if (FORBIDDEN_STEP_IDS.contains(id)) {
                return new Invalid(label + " uses '" + id + "', which is not a work step");
            }
            steps.add(line);
        }
        return new NewPlan(clampGoal(goal, steps), List.copyOf(steps));
    }

    private static String stepLine(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return e.getAsString();
        }
        if (e.isJsonObject()) {
            JsonElement c = e.getAsJsonObject().get("command");
            if (c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isString()) {
                return c.getAsString();
            }
        }
        return null;
    }

    private static String clampGoal(String goal, List<String> steps) {
        String g = goal == null || goal.isBlank() ? steps.get(0) : goal.trim().replaceAll("\\s+", " ");
        return g.length() > MAX_GOAL_CHARS ? g.substring(0, MAX_GOAL_CHARS) : g;
    }

    /**
     * True when the whole message asks to carry on with paused work. Whole-message only, so
     * "continue to the village" is an instruction for the model, not a resume.
     */
    public static boolean isResumePhrase(String message) {
        if (message == null) {
            return false;
        }
        String m = message.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?,]+$", "").trim();
        return switch (m) {
            case "continue", "keep going", "carry on", "go on", "resume", "finish it", "keep digging",
                    "back to work", "continue please", "please continue" -> true;
            default -> false;
        };
    }
}
