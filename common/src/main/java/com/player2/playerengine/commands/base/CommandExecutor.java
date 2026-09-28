package com.player2.playerengine.commands.base;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.companion.NoPvp;
import com.player2.playerengine.seam.Seam;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.helpers.FuzzySearchHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
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
   private final HashMap<String, PermissionClass> permissionClasses = new HashMap<>();
   private final PlayerEngineController mod;
   private final Seam seam;

   public CommandExecutor(PlayerEngineController mod) {
      this.mod = mod;
      this.seam = new Seam(mod);
   }

   /** The action seam every part of a command line runs through. */
   public Seam seam() {
      return this.seam;
   }

   /** The registered command a name or alias resolves to, or null. */
   public Command getRegisteredCommand(String name) {
      return name == null ? null : this.commandSheet.get(resolveName(name.toLowerCase(java.util.Locale.ROOT)));
   }

   /**
    * Registers {@code commands}, each under the class {@code classOf} gives its name. A command with
    * no class registers nothing: the whole call throws before any command is added, so a companion
    * never runs with a partial or unclassed command sheet.
    *
    * @throws UnclassedCommandException naming every command {@code classOf} has no class for
    */
   public void registerNewCommand(Function<String, PermissionClass> classOf, Command... commands) {
      List<String> unclassed = new ArrayList<>();
      for (Command command : commands) {
         if (classOf.apply(command.getName()) == null) {
            unclassed.add(command.getName());
         }
      }
      if (!unclassed.isEmpty()) {
         throw new UnclassedCommandException(unclassed);
      }
      for (Command command : commands) {
         if (this.commandSheet.containsKey(command.getName())) {
            Debug.logInternal("Command with name " + command.getName() + " already exists! Can't register that name twice.");
         } else {
            this.commandSheet.put(command.getName(), command);
            this.permissionClasses.put(command.getName(), classOf.apply(command.getName()));
         }
      }
   }

   /** The class a registered command was registered under, or null for an unregistered name. */
   public PermissionClass permissionClassOf(String name) {
      Command command = this.getRegisteredCommand(name);
      return command == null ? null : this.permissionClasses.get(command.getName());
   }

   /**
    * Why {@code caller} may not run {@code lineWithoutPrefix}, or null when every part may run. Every
    * {@code ;} part is checked, since every part runs. Unknown names are left to the caller's own
    * does-not-exist handling.
    */
   public String refusal(String lineWithoutPrefix, CommandCaller caller, Predicate<String> namesAPlayer) {
      for (String part : lineWithoutPrefix.split(";")) {
         String[] tokens = part.trim().split("\\s+");
         if (tokens.length == 0 || tokens[0].isEmpty()) {
            continue;
         }
         PermissionClass permissionClass = this.permissionClassOf(tokens[0]);
         if (permissionClass == null) {
            continue;
         }
         String name = this.getRegisteredCommand(tokens[0]).getName();
         String why = CommandPolicy.refusal(name, permissionClass,
               Arrays.asList(tokens).subList(1, tokens.length), caller, namesAPlayer);
         if (why != null) {
            return why;
         }
      }
      return null;
   }

   /**
    * Runs the settings' idle command ({@code idleCommand}) as its registered command, with no command
    * grammar: one command and its arguments, no prefix and no {@code ;} chaining (a leading {@code @},
    * which older settings files wrote, is tolerated). It runs for no player, under the same permission policy, and
    * is not an order: it moves no dispatch seq.
    */
   public void runIdle(String line) {
      String text = line == null ? "" : line.trim();
      if (text.startsWith("@")) {
         text = text.substring(1).trim();
      }
      if (text.isEmpty()) {
         return;
      }
      if (text.contains(";")) {
         Debug.logWarning("idleCommand \"" + line + "\" chains commands; only one runs: " + text.split(";")[0].trim());
         text = text.split(";")[0].trim();
      }
      Command command = this.get(text.split("\\s+")[0]);
      if (command == null) {
         Debug.logWarning("idleCommand names no registered command: " + text);
         return;
      }
      String refusal = this.refusal(text, CommandCaller.UNPRIVILEGED,
            name -> NoPvp.namesAPlayer(name, this.mod.getWorld() == null ? null : this.mod.getWorld().getServer()));
      if (refusal != null) {
         Debug.logWarning("idleCommand refused: " + refusal);
         return;
      }
      try {
         command.run(this.mod, text, () -> { }, e -> Debug.logWarning(e.getMessage()));
      } catch (CommandException e) {
         Debug.logWarning("idleCommand failed: " + e.getMessage());
      }
   }

   /**
    * Pure, static alias lookup: returns the resolved command name when {@code raw} is a known synonym,
    * the unchanged input otherwise. Lower-cases {@code raw} first, so every caller resolves a synonym
    * the same way. Needs no instance state.
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
