package dev.stonerestore.core;

import java.util.List;
import java.util.Set;

/**
 * Outcome of scanning one resource pack.
 *
 * @param analyzed      png textures examined (only categories that are switched on)
 * @param matched       textures that are a copy of vanilla stone although vanilla's own texture at that
 *                      path is something else (in force mode: textures with a vanilla counterpart)
 * @param keptLegit     stone copies left alone because vanilla's texture at that path IS stone
 * @param unknown       stone copies at paths vanilla does not have (new/modded/other-version assets)
 * @param flagged       whether this pack counts as a stone-replacement pack (>= minReplacements, or force)
 * @param forced        scan ran in force mode
 * @param restoredPaths png paths that will be hidden from this pack (empty unless flagged)
 * @param failures      human readable "identifier: reason" entries
 */
public record ScanResult(int analyzed, int matched, int keptLegit, int unknown,
                         boolean flagged, boolean forced,
                         Set<String> restoredPaths, List<String> failures) {

    public static final ScanResult NONE = new ScanResult(0, 0, 0, 0, false, false, Set.of(), List.of());

    public static ScanResult failed(String reason) {
        return new ScanResult(0, 0, 0, 0, false, false, Set.of(), List.of(reason));
    }

    /** True for a restored png and for its animation sidecar ({@code <png>.mcmeta}). */
    public boolean isHidden(String path) {
        if (restoredPaths.isEmpty()) return false;
        if (restoredPaths.contains(path)) return true;
        return path.endsWith(".mcmeta")
                && restoredPaths.contains(path.substring(0, path.length() - ".mcmeta".length()));
    }
}
