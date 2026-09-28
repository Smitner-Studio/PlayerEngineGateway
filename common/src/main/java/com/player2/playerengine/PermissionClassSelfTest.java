package com.player2.playerengine;

import com.player2.playerengine.commands.BuildStructureCommand;
import com.player2.playerengine.commands.GamerCommand;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandCaller;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.commands.base.CommandPolicy;
import com.player2.playerengine.commands.base.PermissionClass;
import com.player2.playerengine.commands.base.UnclassedCommandException;
import com.player2.playerengine.companion.NoPvp;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.retrieval.ToolDocument;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Holds the permission classes to the registrations that run at startup: every registered command,
 * disabled command and seed tool document has a class, a command without one fails registration,
 * and the one policy (R1) with the PvP refusal (R2) decides every line. Needs a bootstrapped
 * registry; run from {@code CompanionRulesSelfTest}.
 */
public final class PermissionClassSelfTest {
    /** The registrations the stage 2A plan counted in {@code PlayerEngineCommands}. */
    private static final int REGISTERED_COMMANDS = 46;
    private static final String ONLINE_PLAYER = "Steve";
    private static final Predicate<String> NAMES_A_PLAYER =
            name -> NoPvp.namesAPlayer(name, null) || name.equalsIgnoreCase(ONLINE_PLAYER);
    private static final CommandCaller PLAYER = new CommandCaller(UUID.randomUUID(), false);
    private static final CommandCaller OPERATOR = new CommandCaller(UUID.randomUUID(), true);

    private static int checks;

    private PermissionClassSelfTest() {
    }

    public static int runAll() {
        checks = 0;
        try {
            CommandExecutor executor = startupExecutor();
            everyRegistrationHasItsClass(executor);
            everySeedAndDisabledCommandHasAClass(executor);
            anUnclassedCommandFailsRegistration();
            onePolicyForEveryPlayer(executor);
            adminIsOperatorOnly(executor);
            noCommandTargetsAPlayer(executor);
        } catch (Exception e) {
            throw new AssertionError("permission class self-test: " + e, e);
        }
        System.out.println("permission classes: " + checks + " checks over " + REGISTERED_COMMANDS + " registrations");
        return checks;
    }

    /** The executor exactly as {@code PlayerEngineCommands.init} fills it. */
    private static CommandExecutor startupExecutor() throws Exception {
        CommandExecutor executor = new CommandExecutor(null);
        executor.registerNewCommand(PlayerEngineCommands.CLASSES::get, PlayerEngineCommands.commands());
        return executor;
    }

    private static void everyRegistrationHasItsClass(CommandExecutor executor) {
        require(executor.allCommands().size() == REGISTERED_COMMANDS,
                "registered " + executor.allCommands().size() + " commands, expected " + REGISTERED_COMMANDS);
        for (Command command : executor.allCommands()) {
            PermissionClass declared = PlayerEngineCommands.CLASSES.get(command.getName());
            require(declared != null && executor.permissionClassOf(command.getName()) == declared,
                    command.getName() + " registered under " + executor.permissionClassOf(command.getName()));
            require(!PlayerEngineCommands.DISABLED.contains(command.getName()),
                    command.getName() + " is registered and listed as disabled");
        }
        require(executor.permissionClassOf("drop") == PermissionClass.ITEMS, "an alias takes its target's class");
        require(executor.permissionClassOf("no_such_command") == null, "an unknown name has no class");
    }

    private static void everySeedAndDisabledCommandHasAClass(CommandExecutor executor) throws Exception {
        Set<String> known = new HashSet<>();
        for (Command command : executor.allCommands()) {
            known.add(command.getName());
        }
        for (ToolDocument doc : SeedToolMetadata.all()) {
            require(PlayerEngineCommands.CLASSES.containsKey(doc.id()), "seed document " + doc.id() + " has no class");
            known.add(doc.id());
        }
        for (Command disabled : new Command[] {new BuildStructureCommand(), new GamerCommand()}) {
            require(PlayerEngineCommands.DISABLED.contains(disabled.getName()),
                    disabled.getName() + " is not listed as disabled");
            require(PlayerEngineCommands.CLASSES.containsKey(disabled.getName()),
                    "disabled " + disabled.getName() + " has no class");
            require(executor.getRegisteredCommand(disabled.getName()) == null, disabled.getName() + " is registered");
        }
        known.addAll(PlayerEngineCommands.DISABLED);
        for (String id : PlayerEngineCommands.CLASSES.keySet()) {
            require(known.contains(id), "class row " + id + " names no command, seed document or disabled command");
        }
    }

    /** Red witness for "registration fails without a class": nothing from a failed call registers. */
    private static void anUnclassedCommandFailsRegistration() throws Exception {
        CommandExecutor executor = new CommandExecutor(null);
        Command unclassed = new Command("unclassed_probe", "A command with no permission class.") {
            @Override
            protected void call(PlayerEngineController mod, ArgParser parser) {
                this.finish();
            }
        };
        try {
            executor.registerNewCommand(PlayerEngineCommands.CLASSES::get,
                    new com.player2.playerengine.commands.GotoCommand(), unclassed);
            throw new AssertionError("a command without a class registered");
        } catch (UnclassedCommandException expected) {
            require(expected.commandNames().equals(java.util.List.of("unclassed_probe")),
                    "the failure names " + expected.commandNames());
        }
        require(executor.allCommands().isEmpty(), "a failed registration left commands behind");
    }

    private static void onePolicyForEveryPlayer(CommandExecutor executor) {
        for (Command command : executor.allCommands()) {
            PermissionClass permissionClass = executor.permissionClassOf(command.getName());
            if (permissionClass == PermissionClass.ADMIN) {
                continue;
            }
            for (CommandCaller caller : new CommandCaller[] {PLAYER, OPERATOR, CommandCaller.UNPRIVILEGED}) {
                require(executor.refusal(command.getName(), caller, NAMES_A_PLAYER) == null,
                        command.getName() + " (" + permissionClass + ") refused for " + caller);
            }
        }
        require(executor.refusal("goto 1 2 3; excavate 9 4 9; fill dirt 3 1 3", PLAYER, NAMES_A_PLAYER) == null,
                "area commands are open to every player (R1)");
        require(executor.refusal("follow " + ONLINE_PLAYER, PLAYER, NAMES_A_PLAYER) == null,
                "following a player is not an attack");
        require(executor.refusal("give " + ONLINE_PLAYER + " diamond 1", PLAYER, NAMES_A_PLAYER) == null,
                "giving to a player is not an attack");
    }

    private static void adminIsOperatorOnly(CommandExecutor executor) {
        int admin = 0;
        for (Command command : executor.allCommands()) {
            if (executor.permissionClassOf(command.getName()) != PermissionClass.ADMIN) {
                continue;
            }
            admin++;
            for (CommandCaller caller : new CommandCaller[] {PLAYER, CommandCaller.UNPRIVILEGED}) {
                String refusal = executor.refusal(command.getName() + " on", caller, NAMES_A_PLAYER);
                require(refusal != null && refusal.startsWith(CommandPolicy.DENIED + ":"),
                        command.getName() + " ran for a non-operator: " + refusal);
            }
            require(executor.refusal(command.getName() + " on", OPERATOR, NAMES_A_PLAYER) == null,
                    command.getName() + " refused for an operator");
        }
        require(admin == 3, admin + " ADMIN commands, expected reload_settings, resetmemory and chatclef");
        require(executor.refusal("goto 1 2 3; resetmemory", PLAYER, NAMES_A_PLAYER) != null,
                "an ADMIN part later in the line is still refused");
    }

    private static void noCommandTargetsAPlayer(CommandExecutor executor) {
        String[] lines = {
            "attack " + ONLINE_PLAYER, "attack " + ONLINE_PLAYER.toLowerCase() + " 2", "attack \"" + ONLINE_PLAYER + "\"",
            "attack player 1", "attack minecraft:player", "goto 1 2 3; attack " + ONLINE_PLAYER,
        };
        for (String line : lines) {
            for (CommandCaller caller : new CommandCaller[] {PLAYER, OPERATOR}) {
                String refusal = executor.refusal(line, caller, NAMES_A_PLAYER);
                require((CommandPolicy.DENIED + ": " + NoPvp.REFUSAL).equals(refusal),
                        "\"" + line + "\" for " + caller + " gave " + refusal);
            }
        }
        require(executor.refusal("attack zombie 5", PLAYER, NAMES_A_PLAYER) == null, "a mob is a fair target");
        require(executor.refusal("hero", PLAYER, NAMES_A_PLAYER) == null, "hero names no target");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
