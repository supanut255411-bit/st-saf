package dev.stonerestore.pack;

import dev.stonerestore.StoneRestoreClient;
import dev.stonerestore.StoneRestoreEngine;
import dev.stonerestore.core.RestoreSettings;
import dev.stonerestore.core.ScanResult;
import dev.stonerestore.core.StoneScanner;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Everything belonging to one resource reload: the wrapped packs, the shared scanner, the verification. */
public final class ReloadSession {

    private final StoneRestoreEngine engine;
    private final RestoreSettings settings;
    private final boolean applyRestoration;
    private final boolean preserveOthers;
    private final boolean debug;
    private final ResourcePack vanillaPack;
    private final PackTextureSource vanillaSource;
    private final List<RestoringResourcePack> wrappers = new ArrayList<>();
    private volatile StoneScanner scanner;

    public ReloadSession(StoneRestoreEngine engine, RestoreSettings settings, boolean applyRestoration,
                         boolean preserveOthers, boolean debug, ResourcePack vanillaPack) {
        this.engine = engine;
        this.settings = settings;
        this.applyRestoration = applyRestoration;
        this.preserveOthers = preserveOthers;
        this.debug = debug;
        this.vanillaPack = vanillaPack;
        this.vanillaSource = new PackTextureSource(vanillaPack);
    }

    public ResourcePack wrap(ResourcePack pack) {
        RestoringResourcePack wrapper = new RestoringResourcePack(pack, vanillaPack, this);
        wrappers.add(wrapper);
        return wrapper;
    }

    public boolean applyRestoration() {
        return applyRestoration;
    }

    public boolean preserveOthers() {
        return preserveOthers;
    }

    private StoneScanner scanner() throws IOException {
        StoneScanner s = scanner;
        if (s != null) return s;
        synchronized (this) {
            if (scanner == null) scanner = StoneScanner.create(vanillaSource);
            return scanner;
        }
    }

    /** Called (lazily, once per wrapper) from a loader thread. Never throws. */
    ScanResult scanPack(RestoringResourcePack wrapper) {
        String id = wrapper.packId();
        String key = engine.cacheKey(id, settings);
        if (key != null) {
            ScanResult cached = engine.cachedScan(key);
            if (cached != null) {
                if (debug) StoneRestoreClient.LOG.info("[StoneRestore] '{}': using cached scan", id);
                return cached;
            }
        }
        try {
            StoneScanner s = scanner();
            ScanResult r = s.scan(new PackTextureSource(wrapper.delegate()), settings,
                    debug ? msg -> StoneRestoreClient.LOG.info("[StoneRestore][debug] {}: {}", id, msg) : null);
            if (key != null) engine.storeScan(key, r);
            return r;
        } catch (Exception e) {
            StoneRestoreClient.LOG.error("[StoneRestore] Scan of '{}' failed - pack left untouched", id, e);
            return ScanResult.failed("scan of pack '" + id + "' failed: " + e);
        }
    }

    /**
     * After the reload finished: ask the real resource manager what each restored identifier now
     * resolves to. Anything that still resolves to the stone texture (or to nothing) is reported with
     * its exact identifier instead of being counted as restored.
     */
    public ReloadReport verify(ResourceManager manager) {
        long start = System.nanoTime();
        List<ReloadReport.PackReport> reports = new ArrayList<>();
        for (RestoringResourcePack w : wrappers) {
            ScanResult r = w.plan();
            boolean applied = applyRestoration && r.flagged();
            List<String> problems = new ArrayList<>(r.failures());
            int bad = 0;
            if (applied) {
                StoneScanner s;
                try {
                    s = scanner();
                } catch (IOException e) {
                    s = null;
                    problems.add("verification unavailable: " + e.getMessage());
                }
                if (s != null) {
                    for (String path : r.restoredPaths()) {
                        String problem = check(manager, s, path);
                        if (problem != null) {
                            bad++;
                            problems.add("minecraft:" + path + ": " + problem);
                            StoneRestoreClient.LOG.warn("[StoneRestore] Not restored: minecraft:{} ({})", path, problem);
                        }
                    }
                }
            }
            for (String p : r.failures()) {
                StoneRestoreClient.LOG.warn("[StoneRestore] Pack '{}': {}", w.packId(), p);
            }
            reports.add(new ReloadReport.PackReport(w.packId(), r.analyzed(), r.matched(), r.flagged(), r.forced(),
                    applied, applied ? r.restoredPaths().size() - bad : 0, List.copyOf(problems)));
        }
        return new ReloadReport(List.copyOf(reports), (System.nanoTime() - start) / 1_000_000L);
    }

    private static String check(ResourceManager manager, StoneScanner scanner, String path) {
        Optional<Resource> resource = manager.getResource(Identifier.of("minecraft", path));
        if (resource.isEmpty()) return "no resource found after restoration";
        try (InputStream in = resource.get().getInputStream()) {
            byte[] data = in.readNBytes(StoneScanner.PACK_LIMIT + 1);
            if (data.length > StoneScanner.PACK_LIMIT) return null; // too big to be the stone texture
            return scanner.isStone(data) ? "still resolves to the stone texture" : null;
        } catch (IOException e) {
            return "could not read restored texture: " + e.getMessage();
        }
    }
}
