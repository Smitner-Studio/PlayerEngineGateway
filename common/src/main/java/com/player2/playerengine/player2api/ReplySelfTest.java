package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.program.ApiReference;
import com.player2.playerengine.program.ApiTable;

/**
 * The stage-4 reply contract (C1): a reply carrying {@code command} or {@code plan} is a format error,
 * so nothing in it can run; the program lane's model-free lines; the repair limit; and a system
 * prompt that teaches programs and names no retired field.
 */
public final class ReplySelfTest {
    private static int checks;

    private ReplySelfTest() {
    }

    public static int runAll() {
        checks = 0;
        legacyFieldsAreFormatErrors();
        replyV2Parses();
        resumeLines();
        repairsAreBounded();
        systemPromptTeachesPrograms();
        return checks;
    }

    /** Red witness for R10: drop the legacy-field check in {@link Reply#parse} and this fails. */
    private static void legacyFieldsAreFormatErrors() {
        Reply.Parsed withCommand = Reply.parse(json("{\"say\":\"On it.\",\"command\":\"excavate 1 2 3 4 5 6\"}"));
        require(!withCommand.ok() && withCommand.reply() == null && withCommand.error().contains("\"command\""),
                "a reply with a command is a format error and carries nothing to run: " + withCommand);
        Reply.Parsed alongside = Reply.parse(json(
                "{\"say\":\"On it.\",\"program\":\"api.wait(20);\",\"command\":\"goto 1 2 3\"}"));
        require(!alongside.ok() && alongside.reply() == null,
                "a command beside a program still rejects the whole reply, so the program does not run either");
        require(!Reply.parse(json("{\"say\":\"\",\"command\":\"\"}")).ok(), "even an empty command field");
        Reply.Parsed withPlan = Reply.parse(json("{\"say\":\"ok\",\"plan\":{\"goal\":\"g\",\"steps\":[\"goto 1 2 3\"]}}"));
        require(!withPlan.ok() && withPlan.error().contains("\"plan\""), "a reply with a plan is a format error");
        require(!Reply.parse(json("{\"plan\":\"resume\"}")).ok(), "plan resume is not a reply either");
    }

    private static void replyV2Parses() {
        Reply.Parsed ok = Reply.parse(json("{\"say\":\"On it.\",\"program\":\"api.wait(20);\",\"mood\":{\"label\":\"happy\"}}"));
        require(ok.ok() && ok.reply().say().equals("On it.") && ok.reply().program().equals("api.wait(20);"),
                "say and program");
        Reply.Parsed chat = Reply.parse(json("{\"say\":\"Hi!\"}"));
        require(chat.ok() && chat.reply().program() == null, "a reply without a program is conversation");
        require(Reply.parse(json("{\"say\":\"x\",\"program\":\"  \"}")).reply().program() == null,
                "a blank program is no program");
        require(Reply.parse(json("{}")).ok() && Reply.parse(json("{}")).reply().say().isEmpty(), "silence");
        require(!Reply.parse(json("{\"say\":\"x\",\"program\":[\"api.wait(1);\"]}")).ok(), "a program is a string");
        require(!Reply.parse(json("{\"say\":{\"text\":\"x\"}}")).ok(), "say is a string");
        require(!Reply.parse(json("{\"program\":\"" + "x".repeat(Reply.MAX_PROGRAM_CHARS + 1) + "\"}")).ok(),
                "a program over 8 KB (§6.3)");
    }

    private static void resumeLines() {
        require(ResumeIntent.parse("continue") != null && ResumeIntent.parse("continue").what() == null,
                "bare continue");
        require(ResumeIntent.parse("Keep going!") != null && ResumeIntent.parse("Keep going!").what() == null,
                "keep going");
        require("the dig".equals(ResumeIntent.parse("resume the dig").what()), "resume X names X");
        require("digging the room".equals(ResumeIntent.parse("continue digging the room").what()),
                "continue X names X");
        require(ResumeIntent.parse("can you continue the story you were telling me about the nether") == null,
                "a long line is chat");
        require(ResumeIntent.parse("I will continue") == null, "a line that only mentions continuing is chat");
    }

    private static void repairsAreBounded() {
        RepairLimit limit = new RepairLimit();
        require(limit.record("dig", "api.excavate failed: liquid") == RepairLimit.Decision.REPAIR, "first failure");
        require(limit.record("dig", "api.excavate failed: liquid") == RepairLimit.Decision.GIVE_UP,
                "the same failure twice gives up");
        limit.forget("dig");
        for (int i = 0; i < RepairLimit.MAX_REPAIRS; i++) {
            require(limit.record("sort", "failure " + i) == RepairLimit.Decision.REPAIR, "repair " + i);
        }
        require(limit.record("sort", "another") == RepairLimit.Decision.GIVE_UP, "at most 8 repairs per job");
        require(limit.record("other", "x") == RepairLimit.Decision.REPAIR, "chains are per job");
    }

    private static void systemPromptTeachesPrograms() {
        String prompt = Prompts.fill(Prompts.template(), "Ada", "A foreman.", "Smitner", ApiReference.published());
        for (ApiTable.Sig s : ApiTable.published().all()) {
            require(prompt.contains("api." + s.name() + "("), "the prompt names api." + s.name());
        }
        require(prompt.contains("\"say\"") && prompt.contains("\"program\""), "the reply fields");
        for (String retired : new String[] {"\"command\"", "\"plan\"", "\"reason\"", "valid" + "Commands", "{{"}) {
            require(!prompt.contains(retired), "the prompt does not carry " + retired);
        }
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("reply self-test: " + message);
        }
    }
}
