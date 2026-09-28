package com.player2.playerengine.seam.primitives;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.seam.ActionError;
import com.player2.playerengine.seam.FailureCode;
import com.player2.playerengine.seam.Seam;
import com.player2.playerengine.tasks.base.Task;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/**
 * {@code say(text)}: one chat line from the companion. At most one line per {@link #MIN_GAP_TICKS}
 * (10 s): a line that comes sooner waits for its turn rather than being dropped. Done when the line
 * is in the companion's spoken log.
 */
final class SayPrimitive extends Base {
    static final int MIN_GAP_TICKS = 200;

    SayPrimitive() {
        super("say");
    }

    @Override
    public Map<String, Object> snapshot(Map<String, Object> args, World world) {
        return Map.of("spoken", world.spoken().total());
    }

    @Override
    public void start(Map<String, Object> args, Map<String, Object> options, Map<String, Object> pre, Context ctx,
            Consumer<TaskEnd> ended) {
        ctx.mod().runUserTask(new SayTask((String) args.get("text")), () -> ended.accept(TaskEnd.finished(null)));
    }

    @Override
    public ActionError postcondition(Map<String, Object> args, Map<String, Object> pre, World world) {
        Seam.Spoken now = world.spoken();
        long before = ((Number) pre.get("spoken")).longValue();
        List<String> recent = now.recent();
        if (now.total() > before && !recent.isEmpty() && recent.get(recent.size() - 1).equals(args.get("text"))) {
            return null;
        }
        return Calls.fail(FailureCode.SUPERSEDED, "I did not get to say it");
    }

    /** Sends one chat line as the companion, broadcast as its model replies are. */
    static void speak(PlayerEngineController mod, String text) {
        var server = mod.getWorld().getServer();
        AgentSideEffects.broadcastChatToAllPlayers(server, Component.translatable(
                "message.playerengine.chat.character_message", mod.getPlayer().getName().getString(), text));
    }

    /** Waits out the rate limit, then says the line once. */
    static final class SayTask extends Task {
        private final String text;
        private boolean said;

        SayTask(String text) {
            this.text = text;
        }

        @Override
        protected void onStart() {
        }

        @Override
        protected Task onTick() {
            if (said) {
                return null;
            }
            Seam seam = controller.getCommandExecutor().seam();
            long now = controller.getWorld().getGameTime();
            long last = seam.lastSayTick();
            if (last != Long.MIN_VALUE && now - last < MIN_GAP_TICKS) {
                setDebugState("waiting to speak");
                return null;
            }
            speak(controller, text);
            seam.said(text, now);
            said = true;
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        public boolean isFinished() {
            return said;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "Say";
        }
    }
}
