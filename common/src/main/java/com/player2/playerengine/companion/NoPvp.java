package com.player2.playerengine.companion;

import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Ruling R2: a companion never targets or hits a player, whoever asks, its owner included. Fixed,
 * not configurable. Every companion hit goes through {@code PlayerExtraController.attack}, which
 * checks {@link #isProtected}; the {@code attack} command refuses a player name up front so the
 * model hears why.
 */
public final class NoPvp {
    public static final String REFUSAL = "Companions never attack players, whoever asks.";

    private NoPvp() {
    }

    public static boolean isProtected(Entity target) {
        return target instanceof Player;
    }

    /** Whether {@code name} names a player: an online player's name, or the word itself. */
    public static boolean namesAPlayer(String name, MinecraftServer server) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("player") || n.equals("players") || n.equals("minecraft:player")) {
            return true;
        }
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(name.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}
