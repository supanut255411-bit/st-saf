package dev.stonerestore;

import dev.stonerestore.command.StoneRestoreCommand;
import dev.stonerestore.config.StoneRestoreConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoneRestoreClient implements ClientModInitializer {

    public static final String MOD_ID = "stonerestore";
    public static final Logger LOG = LoggerFactory.getLogger("StoneRestore");

    private static StoneRestoreEngine engine;

    /** Lazily created so the reload mixin works even if it fires before onInitializeClient. */
    public static synchronized StoneRestoreEngine engine() {
        if (engine == null) engine = new StoneRestoreEngine(StoneRestoreConfig.load());
        return engine;
    }

    @Override
    public void onInitializeClient() {
        StoneRestoreEngine e = engine();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                StoneRestoreCommand.register(dispatcher, e));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> e.onJoin());
        LOG.info("[StoneRestore] Loaded. Restoration is {}.", e.config().enabled ? "enabled" : "disabled");
    }
}
