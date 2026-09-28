package com.player2.playerengine.player2api.plan;

import com.player2.playerengine.player2api.Event;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Who a turn belongs to. Any authenticated player may give any companion any command or plan
 * (ruling R1, "no difference between owners and strangers"); identity is the authenticated sender
 * UUID only, never a name in the message, and a command-feedback turn belongs to the player whose
 * chat started the chain. Ownership still decides the greeting, never permission.
 */
public final class OwnerGate {
    /**
     * Area commands a companion never takes from another companion's chat: a peer turn is not a
     * player's request, and a long earthwork started by one would answer to no one.
     */
    public static final Set<String> PEER_REFUSED_COMMAND_IDS = Set.of("excavate", "fill");

    private OwnerGate() {
    }

    public static boolean isOwner(Event.UserMessage message, UUID ownerUuid) {
        UUID sender = message == null ? null : message.authenticatedUserUuid();
        return sender != null && sender.equals(ownerUuid);
    }

    /**
     * The decision turn {@code lastEvent} starts. A chat turn belongs to its authenticated sender; a
     * feedback turn to {@code chainInitiator}, so a plan the model sends after a command finishes is
     * budgeted to the player who asked, like one sent on the chat turn.
     */
    public static PlanCoordinator.Turn turn(Event lastEvent, UUID chainInitiator) {
        if (lastEvent instanceof Event.UserMessage um) {
            return new PlanCoordinator.Turn(true, um.authenticatedUserUuid());
        }
        return new PlanCoordinator.Turn(false, chainInitiator);
    }

    /**
     * The first peer-refused command among the line's {@code ;} parts, or null. CommandExecutor runs
     * every part, so checking only the first would let {@code goto 1 2 3; excavate 9 4 9} through.
     *
     * @param resolveId registered command id for a name or alias, or null when unknown
     */
    public static String peerRefusedCommandIn(String line, String prefix, Function<String, String> resolveId) {
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
            if (PEER_REFUSED_COMMAND_IDS.contains(effective)) {
                return effective;
            }
        }
        return null;
    }
}
