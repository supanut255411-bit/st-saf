package dev.stonerestore.pack;

import dev.stonerestore.core.PngPixels;
import dev.stonerestore.core.StoneScanner;
import net.minecraft.resource.ResourcePack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the test resource packs from the player's own vanilla assets (so no Mojang textures are
 * shipped inside this mod):
 *
 * <ul>
 *   <li>{@code StoneRestore-TestPack-AllStone.zip} - every vanilla block texture replaced by vanilla stone.</li>
 *   <li>{@code StoneRestore-TestPack-Custom.zip} - two legitimate custom block textures (solid magenta
 *       diamond block, solid cyan gold block) used to check that unrelated packs are preserved.</li>
 * </ul>
 */
public final class TestPacks {

    public static final String ALL_STONE = "StoneRestore-TestPack-AllStone.zip";
    public static final String CUSTOM = "StoneRestore-TestPack-Custom.zip";

    // pack_format 75 = 1.21.11; the supported range keeps neighbouring versions from showing a warning.
    private static String mcmeta(String description) {
        return "{\"pack\":{\"pack_format\":75,\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":99},"
                + "\"description\":\"" + description + "\"}}";
    }

    private TestPacks() {}

    public static List<String> generate(ResourcePack vanilla, Path resourcePackDir) throws IOException {
        PackTextureSource source = new PackTextureSource(vanilla);
        byte[] stone = source.read(StoneScanner.REFERENCE, StoneScanner.VANILLA_LIMIT);
        if (stone == null) throw new IOException("vanilla " + StoneScanner.REFERENCE + " not found");

        List<String> blockTextures = new ArrayList<>();
        for (String path : source.listTextures()) {
            if (path.startsWith("textures/block/") && path.endsWith(".png")) blockTextures.add(path);
        }
        blockTextures.sort(String::compareTo);

        Files.createDirectories(resourcePackDir);
        Path allStone = resourcePackDir.resolve(ALL_STONE);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(allStone))) {
            put(zip, "pack.mcmeta", mcmeta("StoneRestore test pack: every block texture is vanilla stone").getBytes(StandardCharsets.UTF_8));
            for (String path : blockTextures) put(zip, "assets/minecraft/" + path, stone);
        }

        Path custom = resourcePackDir.resolve(CUSTOM);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(custom))) {
            put(zip, "pack.mcmeta", mcmeta("StoneRestore test pack: legitimate custom textures").getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/minecraft/textures/block/diamond_block.png", PngPixels.solid(16, 0xFFFF00FF));
            put(zip, "assets/minecraft/textures/block/gold_block.png", PngPixels.solid(16, 0xFF00FFFF));
        }

        List<String> lines = new ArrayList<>();
        lines.add("Wrote " + ALL_STONE + " (" + blockTextures.size() + " block textures, all vanilla stone) and " + CUSTOM + ".");
        lines.add("Enable them in Options > Resource Packs. Put the custom pack BELOW the stone pack to see it preserved.");
        return lines;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
