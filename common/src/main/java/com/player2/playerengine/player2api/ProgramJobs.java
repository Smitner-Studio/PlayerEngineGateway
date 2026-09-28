package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.program.ApiTable;
import com.player2.playerengine.program.CallLog;
import com.player2.playerengine.program.Job;
import com.player2.playerengine.program.JobBoard;
import com.player2.playerengine.program.Linter;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.Coercion;
import com.player2.playerengine.seam.MotionBounds;
import com.player2.playerengine.seam.ProgramPort;
import com.player2.playerengine.seam.YesNo;
import com.player2.playerengine.tasks.construction.area.AreaSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One companion's programs as jobs (§6.6): lint and start a reply's program, run the job board on the
 * server tick through the seam, and report what the jobs do to the conversation. {@code job.json}
 * persists the board; it loads once the companion's owner is online (R19), and a plan.json from
 * gateway.6 to gateway.8 found beside it is discarded with a notice (§5.1).
 */
public final class ProgramJobs {
    private static final Logger LOGGER = LogManager.getLogger();
    /** Ada's language profile (R3); {@code restricted} is an eval arm only. */
    static final Linter.Profile PROFILE = Linter.Profile.FULL;
    static final String OLD_PLAN_NOTICE = "I dropped an old unfinished job from before the update; ask me again.";

    /** What the jobs tell the conversation. Called on the server thread. */
    interface Listener {
        /** The job verified every call and ended; {@code report} is the completion template (R5). */
        void done(Job job, String report);

        /** An uncaught error paused the job; it needs a repaired program (§5.2). */
        void needsRepair(Job job, ActionError error, String failedCall);

        /** The job ended on a cap. */
        void failed(Job job, ActionError error);

        /** Reconcile cannot tell whether a call happened and asks the initiator (§6.4). */
        void asks(Job job, String question);

        /** A line for one player: the restore notice (R19) or the old-plan notice. */
        void notice(UUID player, String text);
    }

    /** Linter ids backed by the game registries, as the seam coerces them at run time. */
    static final Linter.Ids REGISTRY_IDS = (type, id) -> {
        try {
            Coercion.id(id, "BlockId".equals(type), Coercion.Ids.REGISTRIES, new ArrayList<>());
            return null;
        } catch (Coercion.Failure f) {
            return f.error;
        }
    };

    private final PlayerEngineController mod;
    private final ProgramPort port;
    private final Listener listener;
    private final ApiTable api = ApiTable.published();
    private JobBoard board;
    private String seenJob;
    private Job.State seenState;
    private Job.Pause seenPause;

    ProgramJobs(PlayerEngineController mod, Listener listener) {
        this.mod = mod;
        this.port = new ProgramPort(mod);
        this.listener = listener;
    }

    /** Server tick: hands queued outcomes to the active job, runs it, and reports what changed. */
    void tick() {
        if (!ensureLoaded(false)) {
            return;
        }
        Job active = board.active();
        ProgramPort.Delivery d;
        while ((d = port.poll()) != null) {
            if (active != null) {
                active.deliver(d.seq(), d.outcome());
            }
        }
        board.tick();
        observe();
        mod.setJobStatusLine(board.promptTail());
    }

    /** Lints a reply's program (§6.7). Pure: any thread. */
    Linter.Result lint(String source) {
        return Linter.lint(source, PROFILE, api, REGISTRY_IDS, Linter.Skills.NONE);
    }

    /**
     * Starts a clean program as the new job, which shelves the one before it (R6). A job paused for
     * repair is replaced, not shelved: this program is its repair, and keeps its goal when
     * {@code goal} is null. Server thread.
     */
    Job start(Linter.Result lint, String goal, String fallbackGoal, UUID initiator) {
        ensureLoaded(true);
        Job old = board.active();
        boolean repair = old != null && old.state() == Job.State.PAUSED && old.pause() == Job.Pause.REPAIR;
        String g = goal != null ? goal : repair ? old.goal() : fallbackGoal;
        if (repair) {
            board.cancel();
        }
        Job job = Job.start(UUID.randomUUID().toString().substring(0, 8), g, initiator == null ? ""
                : initiator.toString(), lint, PROFILE, api);
        port.region(regionFor(initiator));
        board.give(job);
        see(job);
        mod.setJobStatusLine(board.promptTail());
        return job;
    }

    /**
     * The model-free job lane: a bare continue resumes the paused job; "resume X" the newest shelved
     * job whose goal has X's words, else the paused one. Null when nothing was resumed, so the line
     * goes on to the model.
     */
    Job resume(ResumeIntent.Asked asked, UUID speaker) {
        if (!ensureLoaded(true)) {
            return null;
        }
        Job active = board.active();
        if (active != null && active.pause() == Job.Pause.RESTART && mod.getOwner() == null) {
            // R19: a restored job waits for the companion's owner.
            return null;
        }
        Job resumed = null;
        if (asked.what() != null) {
            resumed = board.resume(asked.what());
        }
        if (resumed == null && board.continueActive()) {
            resumed = board.active();
        }
        if (resumed != null) {
            port.region(regionFor(speaker));
            see(resumed);
            mod.setJobStatusLine(board.promptTail());
        }
        return resumed;
    }

    /** The initiator's yes or no to a reconcile question; true when consumed. */
    boolean answer(String text, UUID speaker) {
        Job a = board == null ? null : board.active();
        if (a == null || a.pause() != Job.Pause.CONFIRM || speaker == null
                || !speaker.toString().equals(a.initiator())) {
            return false;
        }
        Boolean yes = YesNo.parse(text);
        if (yes == null) {
            return false;
        }
        a.answer(yes);
        see(a);
        return true;
    }

    /** A stop (R16): the active job ends; the shelf stays. */
    void stop() {
        if (board != null) {
            board.cancel();
            mod.setJobStatusLine(board.promptTail());
        }
    }

    /** The {@code jobs()} list: the active job, then the shelf newest first. */
    List<String> jobs() {
        return board == null ? List.of() : board.jobs();
    }

    // --- internals ------------------------------------------------------------------------------

    private boolean ensureLoaded(boolean force) {
        if (board != null) {
            return true;
        }
        if (!force && mod.getOwner() == null) {
            return false;
        }
        AIPersistantData data = mod.getAIPersistantData();
        if (data == null) {
            return false;
        }
        discardOldPlan(data.getPlanFileOrNull());
        board = JobBoard.load(data.getJobFileOrNull(), api, port);
        see(board.active());
        JobBoard.Notice n = board.restoreNotice();
        if (n != null) {
            LOGGER.info("[Job] bot restored a paused job '{}' for initiator {}", board.active().goal(), n.initiator());
            listener.notice(uuid(n.initiator()), n.text());
        }
        mod.setJobStatusLine(board.promptTail());
        return true;
    }

    /** §5.1: a plan from gateway.6 to gateway.8 is not translated; it is dropped once, with one notice. */
    private void discardOldPlan(Path plan) {
        if (plan == null || !Files.isRegularFile(plan)) {
            return;
        }
        UUID initiator = null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(plan, StandardCharsets.UTF_8)).getAsJsonObject();
            initiator = o.has("initiator") ? uuid(o.get("initiator").getAsString()) : null;
        } catch (Exception e) {
            LOGGER.warn("[Job] old plan {} does not parse: {}", plan, e.getMessage());
        }
        try {
            Files.deleteIfExists(plan);
        } catch (Exception e) {
            LOGGER.warn("[Job] could not delete old plan {}: {}", plan, e.getMessage());
            return;
        }
        LOGGER.info("[Job] discarded old plan {} (initiator {}); plans do not carry over to programs", plan, initiator);
        listener.notice(initiator != null ? initiator : ownerUuid(), OLD_PLAN_NOTICE);
    }

    private void observe() {
        Job a = board.active();
        if (a == null || (a.id().equals(seenJob) && a.state() == seenState && a.pause() == seenPause)) {
            return;
        }
        see(a);
        switch (a.state()) {
            case DONE -> {
                port.region(null);
                listener.done(a, a.report());
            }
            case FAILED -> {
                port.region(null);
                listener.failed(a, a.lastError());
            }
            case PAUSED -> {
                if (a.pause() == Job.Pause.REPAIR) {
                    listener.needsRepair(a, a.lastError(), failedCall(a));
                } else if (a.pause() == Job.Pause.CONFIRM) {
                    listener.asks(a, a.question());
                }
            }
            default -> {
            }
        }
    }

    private static String failedCall(Job j) {
        List<CallLog.Entry> entries = j.log().entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            CallLog.Entry e = entries.get(i);
            if (e.status() == CallLog.Status.DONE && !e.ok()) {
                return e.name();
            }
        }
        return null;
    }

    private void see(Job j) {
        seenJob = j == null ? null : j.id();
        seenState = j == null ? null : j.state();
        seenPause = j == null ? null : j.pause();
    }

    /** §4.2: within 48 blocks of the initiator at job start; the companion's own place when the initiator is not here. */
    private MotionBounds.Region regionFor(UUID initiator) {
        LivingEntity self = mod.getPlayer();
        if (self == null || self.getServer() == null) {
            return null;
        }
        LivingEntity anchor = self;
        if (initiator != null) {
            ServerPlayer p = self.getServer().getPlayerList().getPlayer(initiator);
            if (p != null && p.level() == self.level()) {
                anchor = p;
            }
        }
        return MotionBounds.Region.around(self.level().dimension().location().toString(),
                new AreaSpec.Pos(anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ()));
    }

    private UUID ownerUuid() {
        return mod.getOwner() == null ? null : mod.getOwner().getUUID();
    }

    private static UUID uuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
