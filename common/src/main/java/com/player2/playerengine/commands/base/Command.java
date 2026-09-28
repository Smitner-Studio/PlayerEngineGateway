package com.player2.playerengine.commands.base;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import java.util.function.Consumer;

public abstract class Command {
   protected final ArgParser parser;
   private final String name;
   private final String description;
   protected PlayerEngineController mod;
   private Runnable onFinish = null;
   private Consumer<CommandException> onError = null;
   private Consumer<String> onFinishWithNote = null;
   private boolean ended = false;

   public Command(String name, String description, ArgBase... args) {
      this.name = name;
      this.description = description;
      this.parser = new ArgParser(args);
   }

   /**
    * Legacy entry point: runs the command with only a finish callback (no error route). Commands
    * invoked this way that call {@link #finishWithError(String)} fall back to a normal finish.
    */
   public void run(PlayerEngineController mod, String line, Runnable onFinish) throws CommandException {
      this.run(mod, line, onFinish, null);
   }

   /**
    * Runs the command, wiring both the finish and the error callback. The {@code onError} consumer is
    * the executor's existing {@code getException} route, so a command that ends via
    * {@link #finishWithError(String)} reaches {@code CommandExecutionStopReason.Error} and the model
    * receives the "Command feedback: &lt;cmd&gt; FAILED. The error was &lt;msg&gt;." InfoMessage.
    */
   public void run(PlayerEngineController mod, String line, Runnable onFinish, Consumer<CommandException> onError)
         throws CommandException {
      this.run(mod, line, onFinish, onError, null);
   }

   /**
    * Runs the command, wiring the finish, error, and success-note callbacks. The {@code onFinishWithNote}
    * consumer is the executor's success-detail route, so a command that ends via
    * {@link #finishWithNote(String)} with a non-blank note reaches
    * {@code CommandExecutionStopReason.Finished} carrying that note and the model receives the
    * "Command feedback: &lt;cmd&gt; finished running, but: &lt;note&gt;. ..." InfoMessage. A blank/absent
    * note degrades to the plain finish route, byte-identical to a clean success.
    */
   public void run(
         PlayerEngineController mod,
         String line,
         Runnable onFinish,
         Consumer<CommandException> onError,
         Consumer<String> onFinishWithNote)
         throws CommandException {
      this.onFinish = onFinish;
      this.onError = onError;
      this.onFinishWithNote = onFinishWithNote;
      this.ended = false;
      this.mod = mod;
      this.parser.loadArgs(line, true);
      this.call(mod, this.parser);
   }

   protected void finish() {
      if (this.ended) {
         return;
      }
      this.ended = true;
      if (this.onFinish != null) {
         this.onFinish.run();
      }
   }

   /**
    * Ends the command via the ERROR path so the executor reports it as a failure with {@code message}.
    * If no error route was wired (legacy {@code run(..)} overload), it degrades to a normal finish so
    * existing callers never hang. Idempotent with {@link #finish()}: whichever fires first wins.
    */
   protected void finishWithError(String message) {
      if (this.ended) {
         return;
      }
      this.ended = true;
      if (this.onError != null) {
         this.onError.accept(new CommandException(message));
      } else if (this.onFinish != null) {
         this.onFinish.run();
      }
   }

   /**
    * Ends the command via the SUCCESS path, optionally carrying a factual success {@code note} that the
    * executor delivers to the model so a succeeded-but-degraded run no longer reads as a bland clean
    * success. A {@code null}/blank note is byte-identical to {@link #finish()} (plain clean success). If
    * a non-blank note is given but no note route was wired (legacy {@code run(..)} overload), it degrades
    * to a normal finish so existing callers never hang. Idempotent with {@link #finish()} and
    * {@link #finishWithError(String)}: whichever fires first wins.
    */
   protected void finishWithNote(String note) {
      if (this.ended) {
         return;
      }
      this.ended = true;
      if (note == null || note.isBlank()) {
         if (this.onFinish != null) {
            this.onFinish.run();
         }
         return;
      }
      if (this.onFinishWithNote != null) {
         this.onFinishWithNote.accept(note);
      } else if (this.onFinish != null) {
         this.onFinish.run();
      }
   }

   /**
    * Ends the command via the SUCCESS path carrying an informational RESULT {@code payload} (e.g.
    * {@code locate_storage} coordinates) that is NOT a degradation. Unlike {@link #finishWithNote(String)},
    * which the model sees framed as "finished running, but: …" (implying something went wrong), this
    * frames the payload as a neutral result while still delivering the standard "what next?" cue. A
    * {@code null}/blank payload degrades to a plain {@link #finish()}.
    */
   protected void finishWithInfo(String payload) {
      if (payload == null || payload.isBlank()) {
         this.finish();
         return;
      }
      this.finishWithNote(
            com.player2.playerengine.player2api.AgentConversationData.INFO_RESULT_NOTE_PREFIX + payload);
   }

   /**
    * True when running this command again after an interruption cannot do harm or double its work,
    * because it works towards a state of the world rather than counting actions. A plan resumed after
    * a restart re-runs such a step directly; any other step goes back to the model first.
    */
   public boolean isIdempotent() {
      return false;
   }

   public String getHelpRepresentation() {
      StringBuilder sb = new StringBuilder(this.name);

      for (ArgBase arg : this.parser.getArgs()) {
         sb.append(" ");
         sb.append(arg.getHelpRepresentation());
      }

      return sb.toString();
   }

   protected void log(Object message) {
      Debug.logMessage(message.toString());
   }

   protected void logError(Object message) {
      Debug.logError(message.toString());
   }

   protected abstract void call(PlayerEngineController var1, ArgParser var2) throws CommandException;

   public String getName() {
      return this.name;
   }

   public String getDescription() {
      return this.description;
   }
}
