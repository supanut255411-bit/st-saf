package dev.stonerestore;

import dev.stonerestore.config.StoneRestoreConfig;
import dev.stonerestore.core.RestoreSettings;
import dev.stonerestore.core.ScanResult;
import dev.stonerestore.pack.ReloadReport;
import dev.stonerestore.pack.ReloadSession;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceReload;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glue between Minecraft's resource reload and the detection core.
 *
 * <p>Flow of one reload: {@link #beginReload} wraps the candidate packs (cheap, no file access) ->
 * Minecraft loads resources, the wrappers scan lazily on the loader threads -> {@link #afterReload}
 * verifies the result against the real resource manager on a background thread and posts the report.
 * Nothing in here ever starts a reload by itself, so reload loops are impossible; user actions that
 * need a reload go through {@link #requestReload()}, which coalesces requests made during a reload.
 */
public final class StoneRestoreEngine {

    public static final String VANILLA_ID = "vanilla";
    private static final long STALE_RELOAD_NANOS = 120_000_000_000L;
    private static final int MAX_CACHE_ENTRIES = 64;

    private final StoneRestoreConfig config;
    private final Map<String, ScanResult> cache = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StoneRestore-Worker");
        t.setDaemon(true);
        return t;
    });

    private volatile ReloadSession session;
    private volatile ReloadReport lastReport;
    private volatile boolean reloading;
    private volatile long reloadStartedNanos;
    private volatile boolean reloadQueued;
    private volatile List<String> pendingChat;

    public StoneRestoreEngine(StoneRestoreConfig config) {
        this.config = config;
    }

    public StoneRestoreConfig config() {
        return config;
    }

    public void runAsync(Runnable task) {
        worker.execute(task);
    }

    // ---------------------------------------------------------------- reload hooks (called by the mixin)

    /** Returns the pack list Minecraft should actually load. Runs on the thread that starts the reload. */
    public List<ResourcePack> beginReload(List<ResourcePack> packs) {
        reloading = true;
        reloadStartedNanos = System.nanoTime();
        session = null;
        if (!config.enabled || (!config.automaticDetection && !config.forceVanilla)) return packs;

        ResourcePack vanilla = null;
        for (ResourcePack p : packs) {
            if (VANILLA_ID.equals(p.getId())) {
                vanilla = p;
                break;
            }
        }
        if (vanilla == null) {
            StoneRestoreClient.LOG.warn("[StoneRestore] vanilla pack missing from reload list - restoration inactive for this reload");
            return packs;
        }

        ReloadSession s = new ReloadSession(this, config.toSettings(),
                config.automaticRestoration || config.forceVanilla, config.preserveOtherPacks, config.debug, vanilla);
        List<ResourcePack> out = new ArrayList<>(packs.size());
        for (ResourcePack p : packs) out.add(isCandidate(p, vanilla) ? s.wrap(p) : p);
        session = s;
        return out;
    }

    private boolean isCandidate(ResourcePack pack, ResourcePack vanilla) {
        if (pack == vanilla || VANILLA_ID.equals(pack.getId())) return false;
        if (!config.isTargeted(pack.getId())) return false;
        try {
            return pack.getNamespaces(ResourceType.CLIENT_RESOURCES).contains("minecraft");
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void afterReload(ResourceManager manager, ResourceReload reload) {
        final ReloadSession s = this.session;
        reload.whenComplete().whenCompleteAsync((ignored, error) -> {
            try {
                if (error == null && s != null && s == this.session) publish(s.verify(manager));
            } catch (Throwable t) {
                StoneRestoreClient.LOG.error("[StoneRestore] Post-reload verification failed", t);
            } finally {
                finishReload();
            }
        }, worker);
    }

    private void publish(ReloadReport report) {
        lastReport = report;
        for (String line : report.logLines()) StoneRestoreClient.LOG.info("[StoneRestore] {}", line);
        if (report.anyFlagged()) chat(report.chatLines());
    }

    private void finishReload() {
        reloading = false;
        if (reloadQueued) {
            reloadQueued = false;
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.send(mc::reloadResources);
        }
    }

    public boolean isReloading() {
        return reloading && System.nanoTime() - reloadStartedNanos < STALE_RELOAD_NANOS;
    }

    /** Reload resources once (or once more after the current reload ends). Safe to call from anywhere. */
    public void requestReload() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> {
            if (isReloading()) reloadQueued = true;
            else mc.reloadResources();
        });
    }

    // ---------------------------------------------------------------- scan cache

    /** null = not cacheable (directory packs, server packs: always rescanned). */
    public String cacheKey(String packId, RestoreSettings settings) {
        if (!packId.startsWith("file/")) return null;
        try {
            Path p = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(packId.substring("file/".length()));
            if (!Files.isRegularFile(p)) return null;
            return packId + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis() + "|" + settings.hashCode();
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    public ScanResult cachedScan(String key) {
        return cache.get(key);
    }

    public void storeScan(String key, ScanResult result) {
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(key, result);
    }

    public void clearCache() {
        cache.clear();
    }

    // ---------------------------------------------------------------- chat / status

    public static Text prefixed(String message) {
        return Text.literal("[StoneRestore] ").formatted(Formatting.GOLD)
                .append(Text.literal(message).formatted(Formatting.WHITE));
    }

    /** Shows lines in chat; if no world is loaded yet they are shown when the player joins one. */
    public void chat(List<String> lines) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player == null || mc.inGameHud == null) {
                pendingChat = List.copyOf(lines);
                return;
            }
            for (String line : lines) mc.inGameHud.getChatHud().addMessage(prefixed(line));
        });
    }

    public void onJoin() {
        List<String> pending = pendingChat;
        if (pending != null) {
            pendingChat = null;
            chat(pending);
        }
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Status: " + (config.enabled ? "ENABLED" : "DISABLED") + (config.forceVanilla ? " (force mode)" : ""));
        lines.add("Detection " + onOff(config.automaticDetection) + ", restoration " + onOff(config.automaticRestoration)
                + ", preserve other packs " + onOff(config.preserveOtherPacks));
        ReloadReport r = lastReport;
        if (r == null) {
            lines.add("No resource reload has been verified yet.");
        } else if (!r.anyFlagged()) {
            lines.add("Last reload: no stone replacements found (" + r.packs().size() + " pack(s) examined).");
        } else {
            for (ReloadReport.PackReport p : r.packs()) {
                if (!p.flagged()) continue;
                lines.add("Last reload: '" + p.packId() + "' - " + p.analyzed() + " analyzed, " + p.restored()
                        + " restored, " + p.failures().size() + " problem(s)" + (p.applied() ? "" : " (not applied)"));
            }
        }
        lines.add("Cached pack scans: " + cache.size() + (isReloading() ? " | reload in progress" : ""));
        return lines;
    }

    /** One-line status for the config screen. */
    public record Status(String text, int color) {}

    public Status statusIndicator() {
        if (!config.enabled) return new Status("Restoration: DISABLED", 0xFF5555);
        ReloadReport r = lastReport;
        if (r == null) return new Status("Restoration: waiting for the first resource reload", 0xAAAAAA);
        int restored = 0;
        boolean applied = false;
        for (ReloadReport.PackReport p : r.packs()) {
            if (p.applied()) {
                applied = true;
                restored += p.restored();
            }
        }
        if (applied) return new Status("Restoration: ACTIVE - " + restored + " textures restored", 0x55FF55);
        if (r.anyFlagged()) return new Status("Restoration: stone pack detected but NOT applied", 0xFFAA00);
        return new Status("Restoration: standing by - no stone pack detected", 0xAAAAAA);
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }
}
