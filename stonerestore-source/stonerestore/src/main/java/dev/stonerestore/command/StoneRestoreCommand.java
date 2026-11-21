package dev.stonerestore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.stonerestore.StoneRestoreClient;
import dev.stonerestore.StoneRestoreEngine;
import dev.stonerestore.config.StoneRestoreConfig;
import dev.stonerestore.core.RestoreSettings;
import dev.stonerestore.core.ScanResult;
import dev.stonerestore.core.StoneScanner;
import dev.stonerestore.gui.StoneRestoreConfigScreen;
import dev.stonerestore.pack.PackTextureSource;
import dev.stonerestore.pack.TestPacks;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /stonerestore on | off | scan | reload | status | force [off] | reset | config | exclude <pack> | maketestpack */
public final class StoneRestoreCommand {

    private StoneRestoreCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, StoneRestoreEngine engine) {
        dispatcher.register(literal("stonerestore")
                .then(literal("on").executes(c -> setEnabled(c, engine, true)))
                .then(literal("off").executes(c -> setEnabled(c, engine, false)))
                .then(literal("scan").executes(c -> scan(c, engine)))
                .then(literal("reload").executes(c -> reload(c, engine)))
                .then(literal("status").executes(c -> status(c, engine)))
                .then(literal("force")
                        .executes(c -> setForce(c, engine, true))
                        .then(literal("off").executes(c -> setForce(c, engine, false))))
                .then(literal("reset").executes(c -> reset(c, engine)))
                .then(literal("config").executes(c -> openConfig()))
                .then(literal("exclude")
                        .then(argument("pack", StringArgumentType.greedyString()).executes(c -> exclude(c, engine))))
                .then(literal("maketestpack").executes(c -> makeTestPack(c, engine))));
    }

    private static void say(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendFeedback(StoneRestoreEngine.prefixed(message));
    }

    private static void fail(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendError(StoneRestoreEngine.prefixed(message));
    }

    private static int setEnabled(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine, boolean value) {
        StoneRestoreConfig config = engine.config();
        if (config.enabled == value) {
            say(ctx, "Already " + (value ? "enabled" : "disabled") + ".");
            return 1;
        }
        config.enabled = value;
        config.save();
        say(ctx, value ? "Enabled. Reloading resources..." : "Disabled. Reloading resources to show the packs as they are...");
        engine.requestReload();
        return 1;
    }

    private static int setForce(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine, boolean value) {
        StoneRestoreConfig config = engine.config();
        if (config.forceVanilla == value) {
            say(ctx, "Force mode is already " + (value ? "on" : "off") + ".");
            return 1;
        }
        config.forceVanilla = value;
        config.save();
        say(ctx, value
                ? "Force mode ON: every texture of targeted packs that has a vanilla counterpart is restored. Reloading..."
                : "Force mode OFF. Reloading...");
        engine.requestReload();
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        engine.clearCache();
        say(ctx, "Cleared cached scans. Reloading restoration data and textures...");
        engine.requestReload();
        return 1;
    }

    private static int reset(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        StoneRestoreConfig config = engine.config();
        int before = config.fingerprint();
        config.resetToDefaults();
        config.save();
        engine.clearCache();
        if (config.fingerprint() != before) {
            say(ctx, "Configuration reset to defaults. Reloading...");
            engine.requestReload();
        } else {
            say(ctx, "Configuration reset to defaults (nothing changed, no reload needed).");
        }
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        for (String line : engine.statusLines()) say(ctx, line);
        return 1;
    }

    private static int openConfig() {
        MinecraftClient mc = MinecraftClient.getInstance();
        // queued so the chat screen closing after this command cannot replace our screen
        mc.send(() -> mc.setScreen(new StoneRestoreConfigScreen(null)));
        return 1;
    }

    private static int exclude(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        String id = StringArgumentType.getString(ctx, "pack").trim();
        List<String> excluded = engine.config().excludedPacks;
        boolean removed = excluded.remove(id);
        if (!removed) excluded.add(id);
        engine.config().save();
        engine.clearCache();
        say(ctx, removed ? "'" + id + "' is no longer excluded. Reloading..."
                         : "'" + id + "' is now excluded from restoration. Reloading...");
        engine.requestReload();
        return 1;
    }

    private static int scan(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ResourcePackManager manager = mc.getResourcePackManager();
        ResourcePackProfile vanillaProfile = manager.getProfile(StoneRestoreEngine.VANILLA_ID);
        if (vanillaProfile == null) {
            fail(ctx, "Vanilla resources are not available.");
            return 0;
        }
        ResourcePack vanilla = vanillaProfile.createResourcePack();
        Map<String, ResourcePack> packs = new LinkedHashMap<>();
        for (ResourcePackProfile profile : manager.getEnabledProfiles()) {
            if (StoneRestoreEngine.VANILLA_ID.equals(profile.getId())) continue;
            try {
                packs.put(profile.getId(), profile.createResourcePack());
            } catch (RuntimeException e) {
                StoneRestoreClient.LOG.warn("[StoneRestore] Could not open pack '{}' for scanning: {}", profile.getId(), e.toString());
            }
        }
        say(ctx, "Scanning " + packs.size() + " enabled pack(s)...");

        RestoreSettings configured = engine.config().toSettings();
        // /scan reports what detection finds, so it never runs in force mode
        RestoreSettings settings = new RestoreSettings(configured.blocks(), configured.items(), configured.entities(),
                configured.gui(), configured.particles(), false, configured.minReplacements());
        engine.runAsync(() -> {
            List<String> out = new ArrayList<>();
            try {
                StoneScanner scanner = StoneScanner.create(new PackTextureSource(vanilla));
                for (Map.Entry<String, ResourcePack> entry : packs.entrySet()) {
                    String id = entry.getKey();
                    try {
                        ScanResult r = scanner.scan(new PackTextureSource(entry.getValue()), settings, null);
                        String verdict = r.flagged()
                                ? "STONE PACK - " + r.restoredPaths().size() + " textures would be restored"
                                : "not a stone pack";
                        String excluded = engine.config().isTargeted(id) ? "" : " [excluded]";
                        out.add(id + excluded + ": " + r.analyzed() + " analyzed, " + r.matched()
                                + " stone copies on non-stone paths -> " + verdict);
                    } catch (IOException | RuntimeException e) {
                        out.add(id + ": scan failed - " + e);
                        StoneRestoreClient.LOG.warn("[StoneRestore] Scan of '{}' failed", id, e);
                    }
                }
                if (packs.isEmpty()) out.add("No resource packs besides vanilla are enabled.");
            } catch (IOException e) {
                out.add("Could not read the vanilla textures: " + e.getMessage());
            } finally {
                vanilla.close();
                packs.values().forEach(ResourcePack::close);
            }
            engine.chat(out);
        });
        return 1;
    }

    private static int makeTestPack(CommandContext<FabricClientCommandSource> ctx, StoneRestoreEngine engine) {
        ResourcePackProfile vanillaProfile = MinecraftClient.getInstance().getResourcePackManager()
                .getProfile(StoneRestoreEngine.VANILLA_ID);
        if (vanillaProfile == null) {
            fail(ctx, "Vanilla resources are not available.");
            return 0;
        }
        ResourcePack vanilla = vanillaProfile.createResourcePack();
        Path dir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
        say(ctx, "Generating test packs in " + dir + " ...");
        engine.runAsync(() -> {
            try {
                engine.chat(TestPacks.generate(vanilla, dir));
            } catch (IOException e) {
                engine.chat(List.of("Could not write the test packs: " + e.getMessage()));
            } finally {
                vanilla.close();
            }
        });
        return 1;
    }
}
