import dev.stonerestore.core.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Stand-alone self test for the Minecraft-free detection core. Run (no Gradle needed):
 *
 *   javac -d out src/main/java/dev/stonerestore/core/*.java tools/CoreSelfTest.java
 *   java -cp out CoreSelfTest
 */
public class CoreSelfTest {

    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + name);
        }
    }

    static class MapSource implements TextureSource {
        final Map<String, byte[]> files = new TreeMap<>();
        MapSource put(String path, byte[] data) { files.put(path, data); return this; }
        public Collection<String> listTextures() { return new ArrayList<>(files.keySet()); }
        public byte[] read(String path, int max) {
            byte[] b = files.get(path);
            return (b == null || b.length > max) ? null : b;
        }
        public boolean exists(String path) { return files.containsKey(path); }
    }

    static int[] noise(int w, int h, long seed, boolean alpha) {
        Random r = new Random(seed);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int a = alpha ? (r.nextInt(4) == 0 ? 0 : 255) : 255;
            px[i] = (a << 24) | (r.nextInt(256) << 16) | (r.nextInt(256) << 8) | r.nextInt(256);
        }
        return px;
    }

    static byte[] viaImageIO(int w, int h, int[] argb, int type) throws IOException {
        BufferedImage img = new BufferedImage(w, h, type);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb[y * w + x]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // ---- PNG decoding ------------------------------------------------------------------
        System.out.println("PNG decoder");
        int[] px = noise(16, 16, 7, true);
        byte[] mine = PngPixels.encodeRgba(16, 16, px);
        PngPixels.Image back = PngPixels.decode(mine);
        check(back != null && Arrays.equals(back.argb(), px), "own encoder -> decoder round trip (RGBA)");
        check(PngPixels.peekSize(mine).equals(new PngPixels.Size(16, 16)), "peekSize");

        int[] big = noise(40, 33, 99, true);
        byte[] ioArgb = viaImageIO(40, 33, big, BufferedImage.TYPE_INT_ARGB);
        PngPixels.Image d = PngPixels.decode(ioArgb);
        check(d != null && Arrays.equals(d.argb(), normalise(big)), "ImageIO ARGB (adaptive filters) decodes identically");

        int[] opaque = noise(40, 33, 5, false);
        byte[] ioRgb = viaImageIO(40, 33, opaque, BufferedImage.TYPE_3BYTE_BGR);
        d = PngPixels.decode(ioRgb);
        check(d != null && Arrays.equals(d.argb(), opaque), "ImageIO RGB decodes identically");

        BufferedImage idx = new BufferedImage(24, 24, BufferedImage.TYPE_BYTE_INDEXED);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) idx.setRGB(x, y, opaque[y * 33 + x] | 0xFF000000);
        ByteArrayOutputStream o2 = new ByteArrayOutputStream();
        ImageIO.write(idx, "png", o2);
        d = PngPixels.decode(o2.toByteArray());
        boolean palOk = d != null;
        if (palOk) for (int y = 0; y < 24 && palOk; y++) for (int x = 0; x < 24; x++)
            if (d.argb()[y * 24 + x] != idx.getRGB(x, y)) { palOk = false; break; }
        check(palOk, "ImageIO palette PNG decodes identically");

        BufferedImage gray = new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_GRAY);
        Random gr = new Random(3);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) gray.getRaster().setSample(x, y, 0, gr.nextInt(256));
        ByteArrayOutputStream o3 = new ByteArrayOutputStream();
        ImageIO.write(gray, "png", o3);
        d = PngPixels.decode(o3.toByteArray());
        boolean grayOk = d != null;
        if (grayOk) for (int y = 0; y < 20 && grayOk; y++) for (int x = 0; x < 20; x++) {
            int v = gray.getRaster().getSample(x, y, 0);
            if (d.argb()[y * 20 + x] != (0xFF000000 | v << 16 | v << 8 | v)) { grayOk = false; break; }
        }
        check(grayOk, "ImageIO grayscale PNG decodes identically");

        check(PngPixels.decode(new byte[]{1, 2, 3}) == null, "garbage -> null");
        check(PngPixels.decode(Arrays.copyOf(mine, mine.length / 2)) == null, "truncated PNG -> null, no exception");

        // ---- scanner -----------------------------------------------------------------------
        System.out.println("Stone scanner");
        int[] stonePx = noise(16, 16, 1, false);
        byte[] stone = PngPixels.encodeRgba(16, 16, stonePx);
        byte[] stoneReencoded = viaImageIO(16, 16, stonePx, BufferedImage.TYPE_INT_ARGB);
        check(!Arrays.equals(stone, stoneReencoded), "(setup) re-encoded stone differs byte-wise");

        MapSource vanilla = new MapSource().put("textures/block/stone.png", stone);
        String[] blocks = {"dirt", "grass_block_side", "oak_planks", "diamond_ore", "netherrack", "bedrock",
                "sand", "gravel", "obsidian", "end_stone", "diamond_block"};
        long seed = 100;
        for (String b : blocks) vanilla.put("textures/block/" + b + ".png", PngPixels.encodeRgba(16, 16, noise(16, 16, seed++, true)));
        vanilla.put("textures/block/water_still.png", PngPixels.encodeRgba(16, 64, noise(16, 64, seed++, true)));
        vanilla.put("textures/block/water_still.png.mcmeta", "{\"animation\":{}}".getBytes());
        vanilla.put("textures/item/diamond.png", PngPixels.encodeRgba(16, 16, noise(16, 16, seed++, true)));
        vanilla.put("textures/entity/zombie.png", PngPixels.encodeRgba(64, 64, noise(64, 64, seed++, true)));

        MapSource pack = new MapSource().put("textures/block/stone.png", stone);
        for (String b : new String[]{"dirt", "grass_block_side", "oak_planks", "diamond_ore", "netherrack", "bedrock",
                "sand", "gravel", "obsidian"}) pack.put("textures/block/" + b + ".png", stone);
        pack.put("textures/block/water_still.png", stone);
        pack.put("textures/block/water_still.png.mcmeta", "{\"animation\":{}}".getBytes());
        pack.put("textures/block/end_stone.png", stoneReencoded);                     // re-encoded copy
        byte[] magenta = PngPixels.solid(16, 0xFFFF00FF);
        pack.put("textures/block/diamond_block.png", magenta);                        // legit custom texture
        pack.put("textures/block/modded_thing.png", stone);                           // unknown to vanilla
        pack.put("textures/item/diamond.png", stone);
        pack.put("textures/entity/zombie.png", stone);

        StoneScanner scanner = StoneScanner.create(vanilla);
        RestoreSettings all = new RestoreSettings(true, true, true, true, true, false, 8);
        ScanResult r = scanner.scan(pack, all, null);
        check(r.analyzed() == 16, "analyzed counts every png (got " + r.analyzed() + ")");
        check(r.matched() == 13, "13 stone copies on non-stone paths (got " + r.matched() + ")");
        check(r.keptLegit() == 1, "stone.png itself kept (got " + r.keptLegit() + ")");
        check(r.unknown() == 1, "modded path left alone (got " + r.unknown() + ")");
        check(r.flagged(), "pack flagged");
        check(r.restoredPaths().contains("textures/block/dirt.png"), "dirt restored");
        check(r.restoredPaths().contains("textures/block/end_stone.png"), "re-encoded stone detected via pixel hash");
        check(!r.restoredPaths().contains("textures/block/stone.png"), "stone block not touched");
        check(!r.restoredPaths().contains("textures/block/diamond_block.png"), "legit custom texture preserved");
        check(!r.restoredPaths().contains("textures/block/modded_thing.png"), "unknown asset untouched");
        check(r.isHidden("textures/block/water_still.png.mcmeta"), "animation sidecar hidden with its png");
        check(!r.isHidden("textures/block/diamond_block.png.mcmeta"), "sidecar of a kept texture not hidden");

        ScanResult noItems = scanner.scan(pack, new RestoreSettings(true, false, true, true, true, false, 8), null);
        check(noItems.matched() == 12 && !noItems.restoredPaths().contains("textures/item/diamond.png"),
                "item toggle respected (matched " + noItems.matched() + ")");

        MapSource small = new MapSource().put("textures/block/stone.png", stone)
                .put("textures/block/dirt.png", stone).put("textures/block/sand.png", stone).put("textures/block/gravel.png", stone);
        ScanResult sm = scanner.scan(small, all, null);
        check(sm.matched() == 3 && !sm.flagged() && sm.restoredPaths().isEmpty(),
                "3 stone copies stay below the threshold -> not flagged");

        ScanResult forced = scanner.scan(pack, new RestoreSettings(true, true, true, true, true, true, 8), null);
        check(forced.flagged() && forced.forced(), "force mode flags the pack");
        check(forced.restoredPaths().contains("textures/block/diamond_block.png"), "force mode also overrides custom textures (by design)");
        check(!forced.restoredPaths().contains("textures/block/modded_thing.png"), "force mode still leaves assets vanilla lacks");

        MapSource clean = new MapSource().put("textures/block/dirt.png", PngPixels.encodeRgba(16, 16, noise(16, 16, 777, true)));
        ScanResult cl = scanner.scan(clean, all, null);
        check(!cl.flagged() && cl.matched() == 0, "ordinary pack is not flagged");

        MapSource huge = new MapSource().put("textures/block/dirt.png", new byte[StoneScanner.PACK_LIMIT + 1]);
        check(!scanner.scan(huge, all, null).flagged(), "oversized file skipped safely");

        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** pixelHash/decoder keep alpha-0 pixels' RGB; decode returns raw values, so compare as-is. */
    static int[] normalise(int[] argb) { return argb; }
}
