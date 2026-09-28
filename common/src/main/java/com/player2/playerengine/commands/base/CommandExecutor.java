package com.player2.playerengine.commands.base;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.helpers.FuzzySearchHelper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CommandExecutor {
   /**
    * Deterministic command-name synonym table (single source of truth, shared with
    * {@link #resolveName(String)} and {@code AgentSideEffects.firstCommandId}). Maps a model-emitted
    * synonym to the real registered command name so it executes silently instead of looping on a
    * non-existent command. Keys are lower-cased; lookups lower-case the raw name first.
    *
    * <p>Seeded with {@code drop -> give}: the 2-token {@code give <item> <count>} form defaults
    * username=null -> owner, so a name-only swap drops the item at the owner's feet with no arg
    * rewrite. Adding a new synonym is a one-line entry here; only add a synonym whose target binds the
    * same arg shape (or whose target's first arg is an owner-defaulting username) — otherwise the alias
    * needs arg rewriting, not just a name swap.
    */
   private static final Map<String, String> COMMAND_ALIASES = Map.of(
         "drop", "give");

   private final HashMap<String, Command> commandSheet = new HashMap<>();
   private final PlayerEngineController mod;

   public CommandExecutor(PlayerEngineController mod) {
      this.mod = mod;
   }

   /** The registered command a name or alias resolves to, or null. */
   public Command getRegisteredCommand(String name) {
      return name == null ? null : this.commandSheet.get(resolveName(name.toLowerCase(java.util.Locale.ROOT)));
   }

   public void registerNewCommand(Command... commands) {
      for (Command command : commands) {
         if (this.commandSheet.containsKey(command.getName())) {
            Debug.logInternal("Command with name " + command.getName() + " already exists! Can't register that name twice.");
         } else {
            this.commandSheet.put(command.getName(), command);
         }
      }
   }

   public String getCommandPrefix() {
      return this.mod.getModSettings().getCommandPrefix();
   }

   public boolean isClientCommand(String line) {
      return line.startsWith(this.getCommandPrefix());
   }

   private void executeRecursive(
         Command[] commands,
         String[] parts,
         int index,
         String accumulatedNote,
         Runnable onFinish,
         Consumer<String> onFinishWithNote,
         Consumer<CommandException> getException) {
      if (index >= commands.length) {
         // Deliver the accumulated success note only once the whole chain has completed. A
         // degraded-but-successful part advances the chain exactly like a clean success (it must NOT
         // abort later parts — a partial gather is still a success), so its note is carried forward
         // and emitted here. A blank accumulated note is byte-identical to the old clean finish.
         if (accumulatedNote == null || accumulatedNote.isBlank()) {
            onFinish.run();
         } else {
            onFinishWithNote.accept(accumulatedNote);
         }
      } else {
         Command command = commands[index];
         String part = parts[index];

         try {
            if (command == null) {
               getException.accept(new CommandException("Invalid command:" + part));
               this.executeRecursive(commands, parts, index + 1, accumulatedNote, onFinish, onFinishWithNote, getException);
            } else {
               // Pass the executor's error route to the command so a command that ends via
               // finishWithError(..) reaches CommandExecutionStopReason.Error (the only supported way
               // to deliver custom FAILED feedback to the model) — that terminates the chain. By
               // contrast, both a clean finish() and a success-with-note advance to the next part; the
               // note (if any) is joined into accumulatedNote and delivered once the chain completes,
               // so a degraded-but-successful part never silently drops a later command.
               command.run(
                  this.mod,
                  part,
                  () -> this.executeRecursive(commands, parts, index + 1, accumulatedNote, onFinish, onFinishWithNote, getException),
                  getException,
                  (note) -> this.executeRecursive(
                        commands, parts, index + 1, joinNotes(accumulatedNote, note), onFinish, onFinishWithNote, getException));
            }
         } catch (CommandException var9) {
            getException.accept(new CommandException(var9.getMessage() + "\nUsage: " + command.getHelpRepresentation(), var9));
         }
      }
   }

   /** Joins two success notes with "; ", treating null/blank as empty, so notes across a chain combine. */
   private static String joinNotes(String existing, String next) {
      if (next == null || next.isBlank()) {
         return existing;
      }
      if (existing == null || existing.isBlank()) {
         return next;
      }
      return existing + "; " + next;
   }

   public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
      this.execute(line, () -> {}, onFinish, getException);
   }

   /**
    * Executes a client command line. {@code onAccepted} runs after all command parts parse
    * successfully and before the first command's {@code run(...)} starts (Phase B5 grounding).
    * Delegates with a safe default note route that ignores any success note and finishes cleanly,
    * so callers that do not opt into the note route keep byte-identical behavior.
    */
   public void execute(
         String line,
         Runnable onAccepted,
         Runnable onFinish,
         Consumer<CommandException> getException) {
      this.execute(line, onAccepted, onFinish, (note) -> onFinish.run(), getException);
   }

   /**
    * Executes a client command line, carrying the success-detail route. {@code onAccepted} runs after
    * all command parts parse successfully and before the first command's {@code run(...)} starts
    * (Phase B5 grounding). {@code onFinishWithNote} is the success-detail route: when one or more
    * commands in the chain end via {@link Command#finishWithNote(String)} with a non-blank note, the
    * chain still runs to completion (a success-with-note advances like a clean success) and the joined
    * note is delivered here once the chain finishes. A fully clean chain routes through {@code onFinish}.
    */
   public void execute(
         String line,
         Runnable onAccepted,
         Runnable onFinish,
         Consumer<String> onFinishWithNote,
         Consumer<CommandException> getException) {
      if (!this.isClientCommand(line)) {
         return;
      }
      line = line.substring(this.getCommandPrefix().length());
      // The chain's own idle fallback after a task finishes is not a new order: bumping for it
      // would make a plan's next step, scheduled in that same tick, look superseded.
      if (countsAsDispatch(line) && !this.mod.getUserTaskChain().isInstallingIdleCommand()) {
         this.mod.bumpCommandDispatchSeq();
      }
      String[] parts = line.split(";");
      Command[] commands = new Command[parts.length];

      try {
         for (int i = 0; i < parts.length; i++) {
            commands[i] = this.getCommand(parts[i]);
         }
      } catch (CommandException var7) {
         getException.accept(var7);
         return;
      }

      if (onAccepted != null) {
         onAccepted.run();
      }
      this.executeRecursive(commands, parts, 0, null, onFinish, onFinishWithNote, getException);
   }

   /**
    * Whether a line (prefix removed) moves the command dispatch seq. Gestures suspend and resume the
    * running task rather than replace it, so a line of gestures only does not count; any other part
    * does, since every part runs.
    */
   public static boolean countsAsDispatch(String lineWithoutPrefix) {
      for (String part : lineWithoutPrefix.split(";")) {
         String p = part.trim();
         if (!p.isEmpty() && !p.split("\\s+")[0].equalsIgnoreCase("bodylang")) {
            return true;
         }
      }
      return false;
   }

   public void execute(String line, Consumer<CommandException> getException) {
      this.execute(line, () -> {}, () -> {}, getException);
   }

   public void execute(String line) {
      this.execute(line, ex -> Debug.logWarning(ex.getMessage()));
   }

   public void executeWithPrefix(String line) {
      if (!line.startsWith(this.getCommandPrefix())) {
         line = this.getCommandPrefix() + line;
      }

      this.execute(line);
   }

   /**
    * Pure, static alias lookup: returns the resolved command name when {@code raw} is a known synonym,
    * the unchanged input otherwise. Lower-cases {@code raw} to match {@code firstCommandId}'s lowering,
    * so the executor and the RAG-learning layer ({@code AliasLearningService}) resolve identically and
    * a silently-aliased emission never records a phantom rejection. Needs no instance state.
    */
   public static String resolveName(String raw) {
      if (raw == null) {
         return null;
      }
      return COMMAND_ALIASES.getOrDefault(raw.toLowerCase(Locale.ROOT), raw);
   }

   private Command getCommand(String line) throws CommandException {
      line = line.trim();
      if (line.length() != 0) {
         String command = line;
         int firstSpace = line.indexOf(32);
         if (firstSpace != -1) {
            command = line.substring(0, firstSpace);
         }

         // Resolve a known synonym (e.g. drop -> give) before the does-not-exist check so an aliased
         // command runs silently; resolveName returns the input unchanged for a non-alias name.
         String target = resolveName(command);
         if (this.commandSheet.containsKey(target)) {
            return this.commandSheet.get(target);
         }

         // Not registered and not a (registered) alias: throw the typed UnknownCommandException so the
         // error route can discriminate this case. Enrich with a threshold-gated fuzzy suggestion built
         // from the registered command-name corpus; the bare message is kept when there is no close
         // match (no false positive).
         String suggestion = FuzzySearchHelper.getClosestMatchWithinThreshold(command, commandNames());
         String message = "Command " + command + " does not exist.";
         if (suggestion != null) {
            message += " Did you mean \"" + suggestion + "\"?";
         }
         throw new UnknownCommandException(message);
      } else {
         return null;
      }
   }

   /** Registered command names — the corpus for the unknown-command "did you mean" suggestion. */
   private List<String> commandNames() {
      return this.commandSheet.values().stream().map(Command::getName).collect(Collectors.toList());
   }

   public Collection<Command> allCommands() {
      return this.commandSheet.values();
   }

   public Command get(String name) {
      return this.commandSheet.getOrDefault(name, null);
   }
}
