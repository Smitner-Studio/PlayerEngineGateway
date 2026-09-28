package com.player2.playerengine.player2api.plan;

import com.player2.playerengine.player2api.Event;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Who may start long work. Ownership is the authenticated sender UUID only, never a name in the
 * message, and a command-feedback turn inherits the owner status of the chain that started it.
 */
public final class OwnerGate {
    /** Commands only the authenticated owner may have run: long earthworks change the world. */
    public static final Set<String> OWNER_ONLY_COMMAND_IDS = Set.of("excavate", "fill");

    private OwnerGate() {
    }

    public static boolean isOwner(Event.UserMessage message, UUID ownerUuid) {
        UUID sender = message == null ? null : message.authenticatedUserUuid();
        return sender != null && sender.equals(ownerUuid);
    }

    /**
     * The decision turn {@code lastEvent} starts. A feedback turn in an owner's chain is charged to the
     * owner, so a plan the model sends after a command finishes is budgeted like one sent on the chat turn.
     */
    public static PlanCoordinator.Turn turn(Event lastEvent, UUID ownerUuid, boolean chainInitiatorIsOwner) {
        if (lastEvent instanceof Event.UserMessage um) {
            return new PlanCoordinator.Turn(true, isOwner(um, ownerUuid), um.authenticatedUserUuid());
        }
        return new PlanCoordinator.Turn(false, chainInitiatorIsOwner, chainInitiatorIsOwner ? ownerUuid : null);
    }

    /**
     * The first owner-only command among the line's {@code ;} parts, or null. CommandExecutor runs every
     * part, so checking only the first would let {@code goto 1 2 3; excavate 9 4 9} through.
     *
     * @param resolveId registered command id for a name or alias, or null when unknown
     */
    public static String ownerOnlyCommandIn(String line, String prefix, Function<String, String> resolveId) {
        if (line == null) {
            return null;
        }
        for (String part : line.split(";")) {
            String p = part.trim();
            if (prefix != null && !prefix.isEmpty() && p.startsWith(prefix)) {
                p = p.substring(prefix.length()).trim();
            }
            if (p.isEmpty()) {
                continue;
            }
            String name = p.split("\\s+")[0].toLowerCase(Locale.ROOT);
            String id = resolveId.apply(name);
            String effective = id == null ? name : id;
            if (OWNER_ONLY_COMMAND_IDS.contains(effective)) {
                return effective;
            }
        }
        return null;
    }
}
