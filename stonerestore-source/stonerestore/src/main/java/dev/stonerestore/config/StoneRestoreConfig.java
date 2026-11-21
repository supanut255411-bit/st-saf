package dev.stonerestore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.stonerestore.StoneRestoreClient;
import dev.stonerestore.core.RestoreSettings;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** {@code config/stonerestore.json}. Public fields so Gson (and the config screen) can use them directly. */
public final class StoneRestoreConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public boolean automaticDetection = true;
    public boolean automaticRestoration = true;
    public boolean restoreBlocks = true;
    public boolean restoreItems = true;
    public boolean restoreEntities = true;
    public boolean restoreGui = true;
    public boolean restoreParticles = true;
    /** true: hidden stone textures fall through to the next pack below (other packs keep working).
     *  false: the stone pack itself serves the vanilla texture, overriding lower packs too. */
    public boolean preserveOtherPacks = true;
    /** Skip detection: restore every texture of the targeted packs that has a vanilla counterpart. */
    public boolean forceVanilla = false;
    public boolean debug = false;
    /** A pack needs at least this many stone-for-something-else textures before it is treated as a stone pack. */
    public int minReplacements = 8;
    /** Pack ids (see /stonerestore scan). Empty = every enabled pack may be examined. */
    public List<String> targetPacks = new ArrayList<>();
    /** Pack ids that are never touched. */
    public List<String> excludedPacks = new ArrayList<>();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("stonerestore.json");
    }

    public static StoneRestoreConfig load() {
        Path p = path();
        if (Files.isRegularFile(p)) {
            try (Reader reader = Files.newBufferedReader(p)) {
                StoneRestoreConfig c = GSON.fromJson(reader, StoneRestoreConfig.class);
                if (c != null) {
                    c.sanitize();
                    return c;
                }
            } catch (IOException | JsonParseException e) {
                StoneRestoreClient.LOG.warn("[StoneRestore] Could not read {} ({}); using defaults", p, e.toString());
            }
        }
        StoneRestoreConfig c = new StoneRestoreConfig();
        c.save();
        return c;
    }

    public synchronized void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer writer = Files.newBufferedWriter(p)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            StoneRestoreClient.LOG.warn("[StoneRestore] Could not write {}: {}", p, e.toString());
        }
    }

    public void resetToDefaults() {
        StoneRestoreConfig d = new StoneRestoreConfig();
        enabled = d.enabled;
        automaticDetection = d.automaticDetection;
        automaticRestoration = d.automaticRestoration;
        restoreBlocks = d.restoreBlocks;
        restoreItems = d.restoreItems;
        restoreEntities = d.restoreEntities;
        restoreGui = d.restoreGui;
        restoreParticles = d.restoreParticles;
        preserveOtherPacks = d.preserveOtherPacks;
        forceVanilla = d.forceVanilla;
        debug = d.debug;
        minReplacements = d.minReplacements;
        targetPacks = new ArrayList<>();
        excludedPacks = new ArrayList<>();
    }

    private void sanitize() {
        if (targetPacks == null) targetPacks = new ArrayList<>();
        if (excludedPacks == null) excludedPacks = new ArrayList<>();
        if (minReplacements < 1) minReplacements = 1;
    }

    /** Hash of everything that changes what a reload does; the screen reloads only when this changed. */
    public int fingerprint() {
        return Objects.hash(enabled, automaticDetection, automaticRestoration, restoreBlocks, restoreItems,
                restoreEntities, restoreGui, restoreParticles, preserveOtherPacks, forceVanilla,
                minReplacements, targetPacks, excludedPacks);
    }

    public RestoreSettings toSettings() {
        return new RestoreSettings(restoreBlocks, restoreItems, restoreEntities, restoreGui, restoreParticles,
                forceVanilla, minReplacements);
    }

    public boolean isTargeted(String packId) {
        if (excludedPacks.contains(packId)) return false;
        return targetPacks.isEmpty() || targetPacks.contains(packId);
    }
}
