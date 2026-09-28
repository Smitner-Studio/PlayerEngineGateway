package com.player2.playerengine.commands.base;

import java.util.UUID;

/**
 * Who a command line runs for: the authenticated player whose turn produced it, and whether that
 * player is a server operator. Identity is never a name taken from chat.
 *
 * @param player the player's UUID, or null for a line no player asked for (idle, gestures, a plan
 *               restored with nobody online)
 * @param operator whether the player holds operator permission (level 2) on this server
 */
public record CommandCaller(UUID player, boolean operator) {
   /** A line with no operator behind it. The default, so a path that forgets its caller gets no ADMIN. */
   public static final CommandCaller UNPRIVILEGED = new CommandCaller(null, false);
}
