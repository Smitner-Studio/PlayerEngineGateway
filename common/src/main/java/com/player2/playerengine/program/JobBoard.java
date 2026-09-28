package com.player2.playerengine.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One companion's jobs (§6.6): the active job and the shelf. A new job shelves the one before it at
 * once (R6, no idle timer); only the active job is in the prompt tail, the shelf only in
 * {@link #jobs()}. The whole board is {@code job.json}, saved on every change.
 */
public final class JobBoard {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Set<String> FILLER = Set.of("resume", "continue", "carry", "keep", "go", "back", "on",
            "with", "to", "the", "a", "an", "my", "your", "our", "that", "this", "job", "task", "please", "again", "it",
            "doing", "work", "working");

    /** Who hears that a restored job was interrupted (R19): its initiator, if online. */
    public record Notice(String initiator, String text) {
    }

    private final Path file;
    private final ApiTable api;
    private final ActionPort port;
    private Job active;
    /** Oldest first. */
    private final List<Job> shelf = new ArrayList<>();

    public JobBoard(Path file, ApiTable api, ActionPort port) {
        this.file = file;
        this.api = api;
        this.port = port;
    }

    public Job active() {
        return active;
    }

    public List<Job> shelf() {
        return Collections.unmodifiableList(shelf);
    }

    /** A new job. The current one, running or paused, is shelved the moment this arrives (R6). */
    public void give(Job job) {
        setAside();
        active = attach(job);
        save();
    }

    private void setAside() {
        if (active != null && !active.ended()) {
            active.shelve();
            if (active.state() == Job.State.SHELVED) {
                shelf.add(active);
            }
        }
        active = null;
    }

    private Job attach(Job job) {
        job.bind(port);
        job.onChange(this::save);
        return job;
    }

    /** Bare "continue": the PAUSED active job only, never a shelved one (§6.6). */
    public boolean continueActive() {
        return active != null && active.state() == Job.State.PAUSED && active.resume();
    }

    /**
     * "Resume X": the newest shelved job whose goal has every word of X. Null when none matches
     * deterministically, so the caller falls back to the model with {@link #jobs()}.
     */
    public Job resume(String request) {
        Job match = match(request);
        if (match == null) {
            return null;
        }
        shelf.remove(match);
        setAside();
        active = match;
        match.resume();
        save();
        return match;
    }

    Job match(String request) {
        List<String> want = new ArrayList<>();
        for (String w : words(request)) {
            if (!FILLER.contains(w)) {
                want.add(w);
            }
        }
        if (want.isEmpty()) {
            return null;
        }
        for (int i = shelf.size() - 1; i >= 0; i--) {
            List<String> goal = words(shelf.get(i).goal());
            boolean all = true;
            for (String w : want) {
                boolean found = false;
                for (String g : goal) {
                    if (g.equals(w) || stem(g).equals(stem(w))) {
                        found = true;
                        break;
                    }
                }
                all &= found;
            }
            if (all) {
                return shelf.get(i);
            }
        }
        return null;
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    private static String stem(String w) {
        return w.length() > 3 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
    }

    public void cancel() {
        if (active != null) {
            active.cancel();
        }
    }

    public void tick() {
        if (active != null) {
            active.tick();
        }
    }

    /** The {@code jobs()} query: the active job, then the shelf newest first. */
    public List<String> jobs() {
        List<String> out = new ArrayList<>();
        if (active != null) {
            out.add(active.goal() + " | " + active.state().name().toLowerCase(Locale.ROOT));
        }
        for (int i = shelf.size() - 1; i >= 0; i--) {
            out.add(shelf.get(i).goal() + " | shelved");
        }
        return out;
    }

    /** The prompt tail's job line; empty with no live job. Shelved jobs never appear here. */
    public String promptTail() {
        return active == null || active.ended() ? "" : active.tailLine();
    }

    /** After a load: the notice for the restored job's initiator (R19), or null. */
    public Notice restoreNotice() {
        if (active == null || active.state() != Job.State.PAUSED || active.pause() != Job.Pause.RESTART) {
            return null;
        }
        return new Notice(active.initiator(), "Your job was interrupted by a restart: " + active.goal()
                + ". Say \"continue\" to pick it up.");
    }

    // --- persistence ------------------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", Job.VERSION);
        if (active != null && !active.ended()) {
            o.add("active", active.toJson());
        }
        JsonArray s = new JsonArray();
        shelf.forEach(j -> s.add(j.toJson()));
        o.add("shelf", s);
        return o;
    }

    private void save() {
        if (file != null) {
            boolean empty = (active == null || active.ended()) && shelf.isEmpty();
            JobStore.save(file, empty ? null : toJson());
        }
    }

    /** The board in {@code file}, restored PAUSED (§6.4); an empty board when there is none. */
    public static JobBoard load(Path file, ApiTable api, ActionPort port) {
        JobBoard b = new JobBoard(file, api, port);
        JsonObject o = JobStore.load(file);
        if (o == null) {
            return b;
        }
        try {
            if (o.has("active")) {
                b.active = b.attach(Job.fromJson(o.getAsJsonObject("active"), api));
            }
            for (JsonElement e : o.getAsJsonArray("shelf")) {
                b.shelf.add(b.attach(Job.fromJson(e.getAsJsonObject(), api)));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Job] could not restore {}: {}", file, e.getMessage());
            JobStore.quarantine(file);
            return new JobBoard(file, api, port);
        }
        return b;
    }
}
