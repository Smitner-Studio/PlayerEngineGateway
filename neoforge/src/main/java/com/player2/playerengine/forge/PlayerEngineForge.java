package com.player2.playerengine.forge;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.PlayerEngineClient;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;


@Mod(PlayerEngine.MOD_ID)
public final class PlayerEngineForge {
    public PlayerEngineForge(IEventBus modEventBus, ModContainer modContainer) {
        PlayerEngine.onInitialize();
        NeoForgeChunkTickets.install(modEventBus);
        if (Platform.getEnvironment() == Env.CLIENT) {
            PlayerEngineClient.onInitializeClient();
        }
    }
}
