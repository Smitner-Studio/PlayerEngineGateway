package com.player2.playerengine.player2api;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.auth.TokenStorage;
import com.player2.playerengine.player2api.gateway.GatewayConfig;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Resolves which Player2 account bills an API call for a controller.
 */
public final class Player2PayerResolution {

    public record ApiBillingContext(ServerPlayer onlinePayer, String storedTokenUsername) {
        /** Use server-stored token for HTTP when non-null and {@code onlinePayer} is null. */
        public boolean useStoredToken() {
            return onlinePayer == null && storedTokenUsername != null && !storedTokenUsername.isEmpty();
        }

        /**
         * Stable bucket key for grouping conversation dispatch by billing client.
         * Returns {@code null} when neither path is usable; callers should skip dispatch.
         */
        public String billingKey() {
            if (onlinePayer != null) {
                return onlinePayer.getUUID().toString();
            }
            if (storedTokenUsername != null && !storedTokenUsername.isEmpty()) {
                return "token:" + storedTokenUsername;
            }
            return null;
        }
    }

    private Player2PayerResolution() {
    }

    public static ApiBillingContext resolve(PlayerEngineController mod, String chainInitiatorUsername,
            String clientId) {
        Player2ServerConfigHolder.get(); // ensure loaded
        var cfg = Player2ServerConfigHolder.get();
        MinecraftServer server = mod.getPlayer() != null ? mod.getPlayer().level().getServer() : null;

        Player owner = mod.getOwner();
        String ownerName = owner != null ? owner.getName().getString() : "";

        if (cfg.getPayerMode() == Player2PayerMode.OWNER_PAYS_ALL) {
            ServerPlayer ownerSp = owner instanceof ServerPlayer o ? o : null;
            if (ownerSp != null && server != null && server.getPlayerList().getPlayer(ownerSp.getUUID()) != null) {
                return new ApiBillingContext(ownerSp, null);
            }
            if (cfg.isOwnerOfflineServerContinuation() && !cfg.isDedicatedClientProxy()
                    && owner != null
                    && !TokenStorage.getToken(ownerName, clientId).isEmpty()) {
                return new ApiBillingContext(null, ownerName);
            }
            return new ApiBillingContext(null, null);
        }

        String billName = chainInitiatorUsername != null && !chainInitiatorUsername.isBlank()
                ? chainInitiatorUsername
                : ownerName;
        ServerPlayer bill = findByName(server, billName);
        return new ApiBillingContext(bill, null);
    }

    /**
     * Name -> online {@link ServerPlayer} linear scan. Package-private (was {@code private}) so the
     * {@code segment_done}/{@code message_done} C2S handlers can resolve a bot's PROMPTER
     * (chain initiator) to a present player WITHOUT routing through {@link #resolve} /
     * {@code ApiBillingContext} (the payer is billing only — never gesture authority). Do NOT
     * duplicate this scan elsewhere; reuse this single helper so both branches stay in parity.
     */
    public static ServerPlayer findByName(MinecraftServer server, String name) {
        if (server == null || name == null) {
            return null;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Resolves billing for server-wide tasks (e.g. mod-intelligence enrichment) without an NPC controller.
     * Uses any online player in PROMPTER_PAYS mode, or stored owner token in OWNER_PAYS_ALL + offline continuation.
     */
    public static ApiBillingContext resolveForServer(MinecraftServer server, String clientId) {
        Player2ServerConfigHolder.get();
        var cfg = Player2ServerConfigHolder.get();
        if (server == null) {
            return new ApiBillingContext(null, null);
        }

        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (cfg.getPayerMode() == Player2PayerMode.OWNER_PAYS_ALL) {
                return new ApiBillingContext(online, null);
            }
            return new ApiBillingContext(online, null);
        }

        if (cfg.getPayerMode() == Player2PayerMode.OWNER_PAYS_ALL
                && cfg.isOwnerOfflineServerContinuation()
                && !cfg.isDedicatedClientProxy()) {
            String storedUser = TokenStorage.findFirstUsernameWithToken(clientId);
            if (storedUser != null && !TokenStorage.getToken(storedUser, clientId).isEmpty()) {
                return new ApiBillingContext(null, storedUser);
            }
        }

        // The gateway key belongs to the server operator, not a player, so server-wide work
        // does not need anyone online to be billable.
        if (GatewayConfig.isEnabled()) {
            return new ApiBillingContext(null, GatewayConfig.SYNTHETIC_USER);
        }

        return new ApiBillingContext(null, null);
    }

    public static boolean canBillServer(MinecraftServer server, String clientId) {
        ApiBillingContext ctx = resolveForServer(server, clientId);
        return ctx.billingKey() != null;
    }
}
