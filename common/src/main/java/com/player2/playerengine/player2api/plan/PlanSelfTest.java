package com.player2.playerengine.player2api.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.player2api.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Planner self-test: the real {@link PlanParser} reads canned model replies (the mock model), and a
 * {@link MockHost} stands in for the command executor, the event queue and the clock.
 */
public final class PlanSelfTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Set<String> KNOWN = Set.of("excavate", "fill", "goto", "get", "mine", "deposit",
            "pickup_drops", "idle", "stop", "bodylang", "give");
    private static final Function<String, String> RESOLVE = name -> {
        String n = "drop".equals(name) ? "give" : name;
        return KNOWN.contains(n) ? n : null;
    };
    private static final PlanCoordinator.Turn OWNER_TURN = new PlanCoordinator.Turn(true, true, OWNER);
    private static final PlanCoordinator.Turn FEEDBACK_TURN = new PlanCoordinator.Turn(false, true, OWNER);
    private static final PlanCoordinator.Turn STRANGER_TURN = new PlanCoordinator.Turn(true, false, null);

    private static int checks;

    private PlanSelfTest() {
    }

    public static void main(String[] args) {
        runAll();
        int area = com.player2.playerengine.tasks.construction.area.AreaSelfTest.runAll();
        System.out.println("plan self-test: " + checks + " planner checks and " + area + " area checks passed");
    }

    public static int runAll() {
        parserShapes();
        stepsRunInOrderWithoutModelCalls();
        failureAsksForRepairAndRepairRuns();
        repairsAreBounded();
        continueResumesPausedPlanWithoutTheField();
        continueAppliesOnlyToItsOwnTurn();
        stopClearsThePlan();
        malformedPlanLeavesTheReplyAlone();
        supersededStepReportedAsFinishedPausesThePlan();
        everyLineThatRunsACommandMovesTheSeq();
        nonOwnerCannotPlanOrResume();
        ownershipIsTheAuthenticatedUuid();
        ownerOnlyCommandsAreFoundInEverySemicolonPart();
        feedbackTurnInOwnerChainCanStartAPlan();
        staleGenerationFinishIsDropped();
        stepThatRunsTooLongIsStoppedAndRepaired();
        idlePlanExpires();
        budgetIsPerOwnerAcrossCompanions();
        companionsShareTheServerBudget();
        perPlanCallCap();
        restartPausesAndOnlyIdempotentStepsResumeDirectly();
        return checks;
    }

    // ---- mock model: replies as the loop would read them ----

    private static PlanParser.Result reply(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return PlanParser.parse(o.get("plan"), RESOLVE);
    }

    private static PlanParser.Result planOf(String... steps) {
        StringBuilder sb = new StringBuilder("{\"message\":\"on it\",\"command\":\"\",\"plan\":{\"goal\":\"job\",\"steps\":[");
        for (int i = 0; i < steps.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(steps[i]).append('"');
        }
        return reply(sb.append("]}}").toString());
    }

    static final class MockHost implements PlanCoordinator.Host {
        final List<String> dispatched = new ArrayList<>();
        final List<PlanCoordinator.StepListener> listeners = new ArrayList<>();
        final List<String> modelTurns = new ArrayList<>();
        final Set<String> idempotent;
        long seq;
        long now = 1_000_000L;
        int cancels;
        JsonObject persisted;
        String status = "";

        MockHost(Set<String> idempotent) {
            this.idempotent = idempotent;
        }

        MockHost() {
            this(Set.of("excavate", "fill", "goto"));
        }

        @Override
        public void dispatch(String line, PlanCoordinator.StepListener listener) {
            dispatched.add(line);
            listeners.add(listener);
            seq++;
            listener.accepted(seq);
        }

        void foreignDispatch() {
            seq++;
        }

        void finishLast() {
            last().stopped(PlanCoordinator.StopKind.FINISHED, null);
        }

        void failLast(String why) {
            last().stopped(PlanCoordinator.StopKind.ERROR, why);
        }

        PlanCoordinator.StepListener last() {
            return listeners.get(listeners.size() - 1);
        }

        @Override
        public long currentSeq() {
            return seq;
        }

        @Override
        public void enqueueModelTurn(String info) {
            modelTurns.add(info);
        }

        @Override
        public void cancelRunningTask() {
            cancels++;
        }

        @Override
        public void persist(JsonObject planOrNull) {
            persisted = planOrNull;
        }

        @Override
        public boolean isIdempotent(String line) {
            return idempotent.contains(line.split(" ")[0]);
        }

        @Override
        public void publishStatus(String line) {
            status = line;
        }

        @Override
        public long now() {
            return now;
        }
    }

    private static PlanCoordinator coordinator(MockHost host) {
        return new PlanCoordinator(host, new PlanBudget(PlanBudget.CALLS_PER_OWNER_PER_HOUR));
    }

    // ---- tests ----

    private static void parserShapes() {
        require(PlanParser.parse(null, RESOLVE) instanceof PlanParser.Absent, "missing field is absent");
        for (String empty : List.of("null", "{}", "[]", "\"\"")) {
            JsonElement e = JsonParser.parseString(empty);
            require(PlanParser.parse(e, RESOLVE) instanceof PlanParser.Absent, empty + " is absent");
        }
        require(reply("{\"plan\":\"resume\"}") instanceof PlanParser.Resume, "resume word");
        require(reply("{\"plan\":\"cancel\"}") instanceof PlanParser.Cancel, "cancel word");
        require(reply("{\"plan\":\"dance\"}") instanceof PlanParser.Invalid, "other words are invalid");
        PlanParser.Result ok = reply("{\"plan\":{\"goal\":\"room\",\"steps\":[\"@excavate 9 4 9\","
                + "{\"command\":\"drop dirt 5\",\"note\":\"n\"}]}}");
        require(ok instanceof PlanParser.NewPlan np && np.steps().equals(List.of("excavate 9 4 9", "drop dirt 5"))
                && np.goal().equals("room"), "prefix stripped, alias resolved, object step read: " + ok);
        PlanParser.Result bare = reply("{\"plan\":[\"goto 1 2 3\"]}");
        require(bare instanceof PlanParser.NewPlan np2 && np2.goal().equals("goto 1 2 3"),
                "a bare list is a plan whose goal is its first step");
        // One command per step: CommandExecutor would run every ';' part.
        require(planOf("goto 1 2 3; excavate 9 9 9") instanceof PlanParser.Invalid inv
                && inv.reason().contains("one command"), "';' injection refused");
        require(planOf("dig_room 9 9") instanceof PlanParser.Invalid inv2 && inv2.reason().contains("dig_room"),
                "unknown command refused by name");
        require(planOf("idle") instanceof PlanParser.Invalid, "idle is not a work step");
        require(planOf("stop") instanceof PlanParser.Invalid, "stop is not a work step");
        String[] nine = new String[9];
        java.util.Arrays.fill(nine, "goto 1 2 3");
        require(planOf(nine) instanceof PlanParser.Invalid, "more than 8 steps refused");
        require(reply("{\"plan\":{\"steps\":7}}") instanceof PlanParser.Invalid, "non-list steps refused");
        require(PlanParser.isResumePhrase("Keep going!") && PlanParser.isResumePhrase(" continue. "),
                "resume phrases");
        require(!PlanParser.isResumePhrase("continue to the village"), "resume is whole-message only");
    }

    private static void stepsRunInOrderWithoutModelCalls() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        require(c.onModelDecision(planOf("excavate 9 4 9", "goto 1 2 3", "pickup_drops"), "", OWNER_TURN),
                "a plan takes over dispatch");
        h.finishLast();
        h.finishLast();
        require(h.modelTurns.isEmpty(), "no model turn between steps: " + h.modelTurns);
        h.finishLast();
        require(h.dispatched.equals(List.of("excavate 9 4 9", "goto 1 2 3", "pickup_drops")),
                "steps dispatched in order: " + h.dispatched);
        require(h.modelTurns.size() == 1 && h.modelTurns.get(0).contains("finished"),
                "exactly one completion turn: " + h.modelTurns);
        require(!c.hasPlan() && h.persisted == null && h.status.isEmpty(), "finished plan is cleared");
    }

    private static void failureAsksForRepairAndRepairRuns() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("goto 1 2 3", "excavate 9 4 9", "pickup_drops"), "", OWNER_TURN);
        h.finishLast();
        h.failLast("needs a pickaxe");
        require(c.status() == CompanionPlan.Status.AWAITING_REPAIR, "failure awaits repair");
        require(h.modelTurns.size() == 1 && h.modelTurns.get(0).contains("needs a pickaxe")
                && h.modelTurns.get(0).contains("pickup_drops"), "repair prompt names reason and remainder");
        require(c.onModelDecision(planOf("get stone_pickaxe", "excavate 9 4 9", "pickup_drops"), "", FEEDBACK_TURN),
                "repair plan dispatches");
        h.finishLast();
        h.finishLast();
        h.finishLast();
        require(h.dispatched.equals(List.of("goto 1 2 3", "excavate 9 4 9", "get stone_pickaxe",
                "excavate 9 4 9", "pickup_drops")), "repaired remainder ran: " + h.dispatched);
        String done = h.modelTurns.get(h.modelTurns.size() - 1);
        require(done.contains("finished") && done.contains("goto 1 2 3 -> ok"),
                "completion keeps the pre-repair results: " + done);
    }

    private static void repairsAreBounded() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("mine iron_ore 3"), "", OWNER_TURN);
        h.failLast("no ore");
        c.onModelDecision(planOf("mine iron_ore 3"), "", FEEDBACK_TURN);
        h.failLast("no ore");
        c.onModelDecision(planOf("mine iron_ore 3"), "", FEEDBACK_TURN);
        h.failLast("no ore");
        long repairPrompts = h.modelTurns.stream().filter(t -> t.contains("revised steps")).count();
        require(repairPrompts == PlanCoordinator.MAX_REPAIRS, "exactly " + PlanCoordinator.MAX_REPAIRS
                + " repair prompts: " + h.modelTurns);
        require(!c.hasPlan() && h.modelTurns.get(h.modelTurns.size() - 1).contains("stopped at step"),
                "third failure ends the plan");
    }

    private static void continueResumesPausedPlanWithoutTheField() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("excavate 9 4 9", "pickup_drops"), "", OWNER_TURN);
        h.last().stopped(PlanCoordinator.StopKind.CANCELLED, null);
        require(c.status() == CompanionPlan.Status.PAUSED, "cancelled step pauses");
        c.onUserMessage("Carry on.", false);
        require(!c.onModelDecision(new PlanParser.Absent(), "", STRANGER_TURN), "a stranger's continue does nothing");
        c.onUserMessage("continue", true);
        require(c.onModelDecision(reply("{\"message\":\"Back to it.\",\"command\":\"\"}"), "", OWNER_TURN),
                "owner continue resumes without a plan field");
        require(h.dispatched.equals(List.of("excavate 9 4 9", "excavate 9 4 9")), "cursor step re-dispatched");
        h.last().stopped(PlanCoordinator.StopKind.CANCELLED, null);
        require(c.onModelDecision(reply("{\"plan\":\"resume\"}"), "", OWNER_TURN), "plan:resume resumes");
        h.last().stopped(PlanCoordinator.StopKind.CANCELLED, null);
        c.onUserMessage("continue", true);
        require(!c.onModelDecision(new PlanParser.Absent(), "goto 5 5 5", OWNER_TURN),
                "a model command wins over the phrase fallback");
    }

    /** A "continue" whose turn never reached a decision (a peer turn) must not resume a later one. */
    private static void continueAppliesOnlyToItsOwnTurn() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("excavate 9 4 9", "pickup_drops"), "", OWNER_TURN);
        h.last().stopped(PlanCoordinator.StopKind.CANCELLED, null);
        c.beginTurn();
        c.onUserMessage("continue", true);
        c.beginTurn();
        require(!c.onModelDecision(new PlanParser.Absent(), "", OWNER_TURN),
                "a continue from an earlier turn does not resume a later decision");
        require(c.status() == CompanionPlan.Status.PAUSED, "the plan stays paused");
        c.onUserMessage("continue", true);
        require(c.onModelDecision(new PlanParser.Absent(), "", OWNER_TURN), "a continue in this turn resumes");
    }

    private static void stopClearsThePlan() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("excavate 9 4 9", "pickup_drops"), "", OWNER_TURN);
        PlanCoordinator.StepListener first = h.last();
        c.cancel("stop");
        first.stopped(PlanCoordinator.StopKind.FINISHED, null);
        require(!c.hasPlan() && h.dispatched.size() == 1 && h.persisted == null, "stop clears; late finish ignored");
        c.onModelDecision(planOf("goto 1 2 3", "pickup_drops"), "", OWNER_TURN);
        c.onModelDecision(reply("{\"plan\":\"cancel\"}"), "", OWNER_TURN);
        require(!c.hasPlan(), "plan:cancel clears");
    }

    private static void malformedPlanLeavesTheReplyAlone() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        boolean took = c.onModelDecision(reply("{\"message\":\"sure\",\"command\":\"goto 1 2 3\",\"plan\":{\"steps\":7}}"),
                "goto 1 2 3", OWNER_TURN);
        require(!took && h.dispatched.isEmpty(), "the reply's own command still runs");
        require(h.modelTurns.size() == 1 && h.modelTurns.get(0).contains("not started"), "one refusal");
        c.onModelDecision(reply("{\"plan\":{\"steps\":7}}"), "", OWNER_TURN);
        c.onModelDecision(reply("{\"plan\":{\"steps\":7}}"), "", OWNER_TURN);
        require(h.modelTurns.size() == 2, "refusal feedback is bounded: " + h.modelTurns.size());
    }

    private static void supersededStepReportedAsFinishedPausesThePlan() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("excavate 9 4 9", "pickup_drops"), "", OWNER_TURN);
        PlanCoordinator.StepListener step1 = h.last();
        h.foreignDispatch(); // the owner's own command replaces the running task
        step1.stopped(PlanCoordinator.StopKind.FINISHED, null); // ACTIVE-REPLACE reports Finished
        CompanionPlan p = c.snapshotForTest();
        require(p.status() == CompanionPlan.Status.PAUSED && p.cursor() == 0, "superseded step pauses the plan");
        require(h.dispatched.size() == 1, "nothing dispatched after supersession: " + h.dispatched);
        require(p.results().get(0).equals("superseded"), "result recorded");
    }

    private static void everyLineThatRunsACommandMovesTheSeq() {
        require(CommandExecutor.countsAsDispatch("goto 1 2 3"), "a command moves the seq");
        require(!CommandExecutor.countsAsDispatch("bodylang nod_head"), "a gesture does not");
        require(!CommandExecutor.countsAsDispatch(" BodyLang greeting ; bodylang victory"), "gestures only do not");
        require(CommandExecutor.countsAsDispatch("bodylang nod_head; goto 1 2 3"),
                "a gesture in front of a command still replaces the running step");
        require(CommandExecutor.countsAsDispatch("bodylanguage_lesson"), "only the bodylang command is exempt");
    }

    private static void nonOwnerCannotPlanOrResume() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        require(!c.onModelDecision(planOf("excavate 9 4 9"), "", STRANGER_TURN), "stranger plan refused");
        require(h.dispatched.isEmpty() && !c.hasPlan(), "nothing dispatched for a stranger");
        require(h.modelTurns.size() == 1 && h.modelTurns.get(0).contains("Only your owner"), "refusal told");
        c.onModelDecision(planOf("goto 1 2 3", "pickup_drops"), "", OWNER_TURN);
        h.last().stopped(PlanCoordinator.StopKind.CANCELLED, null);
        require(!c.onModelDecision(reply("{\"plan\":\"resume\"}"), "", STRANGER_TURN), "stranger resume refused");
        require(!c.onModelDecision(reply("{\"plan\":\"cancel\"}"), "", STRANGER_TURN) && c.hasPlan(),
                "stranger cannot cancel either");
    }

    private static void ownershipIsTheAuthenticatedUuid() {
        require(OwnerGate.isOwner(new Event.UserMessage("dig a room", "Arran", false, OWNER), OWNER), "owner's UUID");
        require(!OwnerGate.isOwner(new Event.UserMessage("dig a room", "Arran"), OWNER),
                "the owner's name without an authenticated UUID is not the owner");
        require(!OwnerGate.isOwner(new Event.UserMessage("dig a room", "Arran", true, OTHER_OWNER), OWNER),
                "another player's UUID under the owner's name is not the owner");
        require(!OwnerGate.isOwner(new Event.UserMessage("dig a room", "Arran", false, OWNER), null),
                "no owner, no owner turns");
        PlanCoordinator.Turn spoofed = OwnerGate.turn(new Event.UserMessage("excavate 9 4 9", "Arran"), OWNER, true);
        require(spoofed.userTurn() && !spoofed.ownerTurn(), "an unauthenticated chat line never inherits the chain");
    }

    private static void ownerOnlyCommandsAreFoundInEverySemicolonPart() {
        Function<String, String> resolve = name -> KNOWN.contains(name) ? name : null;
        require("excavate".equals(OwnerGate.ownerOnlyCommandIn("goto 1 2 3; excavate 9 4 9", "@", resolve)),
                "an owner-only command after ';' is found");
        require("fill".equals(OwnerGate.ownerOnlyCommandIn("@fill dirt 3 1 3", "@", resolve)), "prefixed");
        require("excavate".equals(OwnerGate.ownerOnlyCommandIn("goto 1 2 3;@EXCAVATE 3 3 3", "@", resolve)),
                "prefix and case inside a later part");
        require(OwnerGate.ownerOnlyCommandIn("goto 1 2 3; mine stone 5", "@", resolve) == null, "ordinary line");
        require(OwnerGate.ownerOnlyCommandIn(null, "@", resolve) == null, "no command");
    }

    private static void feedbackTurnInOwnerChainCanStartAPlan() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        PlanCoordinator.Turn feedback = OwnerGate.turn(new Event.InfoMessage("goto finished"), OWNER, true);
        require(!feedback.userTurn() && feedback.ownerTurn() && OWNER.equals(feedback.initiator()),
                "a feedback turn in the owner's chain is the owner's: " + feedback);
        require(c.onModelDecision(planOf("excavate 9 4 9", "pickup_drops"), "", feedback)
                && h.dispatched.equals(List.of("excavate 9 4 9")), "a plan sent after a command finished starts");
        PlanCoordinator.Turn strangerChain = OwnerGate.turn(new Event.InfoMessage("goto finished"), OWNER, false);
        require(!strangerChain.ownerTurn() && strangerChain.initiator() == null, "a stranger's chain stays theirs");
    }

    private static void staleGenerationFinishIsDropped() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("goto 1 2 3", "pickup_drops"), "", OWNER_TURN);
        PlanCoordinator.StepListener planA = h.last();
        c.onModelDecision(planOf("excavate 9 4 9", "pickup_drops"), "", OWNER_TURN);
        planA.stopped(PlanCoordinator.StopKind.FINISHED, null);
        CompanionPlan b = c.snapshotForTest();
        require(b.cursor() == 0 && b.status() == CompanionPlan.Status.RUNNING
                && h.dispatched.equals(List.of("goto 1 2 3", "excavate 9 4 9")), "plan B unaffected by A's finish");
    }

    private static void stepThatRunsTooLongIsStoppedAndRepaired() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("excavate 9 4 9"), "", OWNER_TURN);
        PlanCoordinator.StepListener step = h.last();
        h.now += PlanCoordinator.STEP_TIMEOUT_MILLIS - 1000;
        c.tick();
        require(h.cancels == 0, "no cancel before the step timeout");
        h.now += 2000;
        c.tick();
        require(h.cancels == 1 && c.status() == CompanionPlan.Status.AWAITING_REPAIR, "timed-out step stopped");
        require(h.modelTurns.get(0).contains("took too long"), "reason reaches the model");
        step.stopped(PlanCoordinator.StopKind.FINISHED, null);
        require(c.status() == CompanionPlan.Status.AWAITING_REPAIR, "the cancel's own stop callback is dropped");
    }

    private static void idlePlanExpires() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("goto 1 2 3"), "", OWNER_TURN);
        h.last().stopped(PlanCoordinator.StopKind.CANCELLED, null);
        h.now += PlanCoordinator.IDLE_TIMEOUT_MILLIS - 1000;
        c.tick();
        require(c.hasPlan(), "still there before the idle hour");
        h.now += 2000;
        c.tick();
        require(!c.hasPlan() && h.persisted == null, "idle plan expired");
    }

    private static void budgetIsPerOwnerAcrossCompanions() {
        PlanBudget shared = new PlanBudget(2);
        MockHost h1 = new MockHost();
        MockHost h2 = new MockHost();
        PlanCoordinator ada = new PlanCoordinator(h1, shared);
        PlanCoordinator rivet = new PlanCoordinator(h2, shared);
        require(ada.onModelDecision(planOf("goto 1 2 3"), "", OWNER_TURN), "first plan charged");
        require(rivet.onModelDecision(planOf("goto 1 2 3"), "", OWNER_TURN), "second plan, other companion");
        PlanCoordinator third = new PlanCoordinator(new MockHost(), shared);
        require(!third.onModelDecision(planOf("goto 1 2 3"), "", OWNER_TURN), "owner's hour is spent");
        require(third.onModelDecision(planOf("goto 1 2 3"), "", new PlanCoordinator.Turn(true, true, OTHER_OWNER)),
                "another owner's allowance is separate");
    }

    /** The constructor the loop uses: two companions of one owner draw on one allowance. */
    private static void companionsShareTheServerBudget() {
        UUID owner = UUID.randomUUID(); // PlanBudget.SHARED is process-wide; no other check uses this owner
        PlanCoordinator.Turn turn = new PlanCoordinator.Turn(true, true, owner);
        PlanCoordinator ada = new PlanCoordinator(new MockHost());
        PlanCoordinator rivet = new PlanCoordinator(new MockHost());
        for (int i = 0; i < PlanBudget.CALLS_PER_OWNER_PER_HOUR; i++) {
            require((i % 2 == 0 ? ada : rivet).onModelDecision(planOf("goto 1 2 3"), "", turn),
                    "plan " + (i + 1) + " within the hour's allowance");
        }
        require(!new PlanCoordinator(new MockHost()).onModelDecision(planOf("goto 1 2 3"), "", turn),
                "a third companion cannot extend the owner's hour");
    }

    private static void perPlanCallCap() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("excavate 9 4 9"), "", OWNER_TURN); // 1 call
        for (int i = 2; i <= PlanCoordinator.MAX_CALLS_PER_PLAN; i++) {
            c.onModelDecision(new PlanParser.Absent(), "", OWNER_TURN); // owner chat while it runs
            require(c.hasPlan(), "plan survives call " + i);
        }
        c.onModelDecision(new PlanParser.Absent(), "", OWNER_TURN);
        require(!c.hasPlan() && h.modelTurns.get(h.modelTurns.size() - 1).contains("allowance"),
                "call " + (PlanCoordinator.MAX_CALLS_PER_PLAN + 1) + " drops the plan");
    }

    private static void restartPausesAndOnlyIdempotentStepsResumeDirectly() {
        MockHost h = new MockHost();
        PlanCoordinator c = coordinator(h);
        c.onModelDecision(planOf("goto 1 2 3", "mine iron_ore 3", "excavate 9 4 9"), "", OWNER_TURN);
        h.finishLast(); // now running step 2
        CompanionPlan loaded = CompanionPlan.fromJson(h.persisted);
        require(loaded != null && loaded.status() == CompanionPlan.Status.PAUSED && loaded.cursor() == 1
                && loaded.results().get(1).equals("unknown"), "saved running plan loads PAUSED, step unknown");

        MockHost h2 = new MockHost();
        PlanCoordinator after = coordinator(h2);
        after.restore(loaded);
        require(h2.dispatched.isEmpty(), "a restored plan never resumes on its own");
        after.onUserMessage("continue", true);
        require(!after.onModelDecision(new PlanParser.Absent(), "", OWNER_TURN)
                && after.status() == CompanionPlan.Status.AWAITING_REPAIR
                && h2.modelTurns.get(0).contains("restart"), "non-idempotent step goes through the model");

        CompanionPlan loaded2 = CompanionPlan.fromJson(h.persisted);
        MockHost h3 = new MockHost(Set.of("mine", "excavate"));
        PlanCoordinator after2 = coordinator(h3);
        after2.restore(loaded2);
        after2.onUserMessage("keep going", true);
        require(after2.onModelDecision(new PlanParser.Absent(), "", OWNER_TURN)
                && h3.dispatched.equals(List.of("mine iron_ore 3")), "idempotent step re-dispatched directly");
        require(CompanionPlan.fromJson(JsonParser.parseString("{\"version\":2}").getAsJsonObject()) == null,
                "unknown version is not loaded");
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("plan self-test failed: " + what);
        }
        checks++;
    }
}
