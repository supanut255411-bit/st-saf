package dev.stonerestore.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Finds textures in a resource pack that are copies of the vanilla stone texture even though vanilla's
 * own texture at that path is not stone.
 *
 * <p>A texture counts as "stone" if its bytes equal vanilla {@code stone.png}, or (for re-encoded files)
 * its dimensions match and its decoded pixels hash equal. Stone at {@code textures/block/stone.png}
 * itself, or anywhere vanilla also uses stone, is never touched. Paths vanilla doesn't have are never
 * touched. A pack is only flagged once it has at least {@code minReplacements} such textures, so a pack
 * that deliberately retextures one or two blocks as stone is left alone.
 */
public final class StoneScanner {

    public static final String REFERENCE = "textures/block/stone.png";

    /** A stone texture is tiny; anything bigger in a pack can't be a copy of it. */
    public static final int PACK_LIMIT = 64 * 1024;
    public static final int VANILLA_LIMIT = 16 * 1024 * 1024;

    private final TextureSource vanilla;
    private final byte[] stoneBytes;
    private final PngPixels.Size stoneSize;
    private final byte[] stonePixelHash; // null if vanilla stone isn't decodable by PngPixels

    private StoneScanner(TextureSource vanilla, byte[] stoneBytes) {
        this.vanilla = vanilla;
        this.stoneBytes = stoneBytes;
        this.stoneSize = PngPixels.peekSize(stoneBytes);
        PngPixels.Image img = PngPixels.decode(stoneBytes);
        this.stonePixelHash = img == null ? null : PngPixels.pixelHash(img);
    }

    public static StoneScanner create(TextureSource vanilla) throws IOException {
        byte[] stone = vanilla.read(REFERENCE, VANILLA_LIMIT);
        if (stone == null) throw new IOException("vanilla " + REFERENCE + " could not be read");
        return new StoneScanner(vanilla, stone);
    }

    public boolean isStone(byte[] png) {
        if (png == null) return false;
        if (Arrays.equals(png, stoneBytes)) return true;
        if (stonePixelHash == null || stoneSize == null) return false;
        if (!stoneSize.equals(PngPixels.peekSize(png))) return false;
        PngPixels.Image img = PngPixels.decode(png);
        return img != null && Arrays.equals(PngPixels.pixelHash(img), stonePixelHash);
    }

    public ScanResult scan(TextureSource pack, RestoreSettings settings, Consumer<String> trace) throws IOException {
        int analyzed = 0;
        int matched = 0;
        int legit = 0;
        int unknown = 0;
        Set<String> candidates = new TreeSet<>();
        List<String> failures = new ArrayList<>();

        for (String path : pack.listTextures()) {
            if (!path.endsWith(".png")) continue;
            if (!settings.allows(TextureCategory.of(path))) continue;
            analyzed++;
            try {
                if (settings.force()) {
                    if (vanilla.exists(path)) {
                        matched++;
                        candidates.add(path);
                        note(trace, path, "force mode: will use vanilla texture");
                    } else {
                        unknown++;
                        note(trace, path, "no vanilla counterpart, left alone");
                    }
                    continue;
                }
                byte[] bytes = pack.read(path, PACK_LIMIT);
                if (!isStone(bytes)) continue;
                byte[] original = vanilla.read(path, VANILLA_LIMIT);
                if (original == null) {
                    unknown++;
                    note(trace, path, "stone copy, but vanilla has no such texture - left alone");
                } else if (isStone(original)) {
                    legit++;
                    note(trace, path, "vanilla texture here is stone too - left alone");
                } else {
                    matched++;
                    candidates.add(path);
                    note(trace, path, "stone copy replacing a non-stone vanilla texture");
                }
            } catch (IOException e) {
                failures.add(path + ": " + e.getMessage());
            }
        }

        boolean flagged = settings.force()
                ? matched > 0
                : matched >= Math.max(1, settings.minReplacements());
        Set<String> restored = flagged ? Set.copyOf(candidates) : Set.of();
        return new ScanResult(analyzed, matched, legit, unknown, flagged, settings.force(), restored, List.copyOf(failures));
    }

    private static void note(Consumer<String> trace, String path, String message) {
        if (trace != null) trace.accept(path + ": " + message);
    }
}
