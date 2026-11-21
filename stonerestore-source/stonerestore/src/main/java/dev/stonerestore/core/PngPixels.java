package dev.stonerestore.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Dependency-free PNG helper used for fingerprinting.
 *
 * <ul>
 *   <li>{@link #peekSize} reads width/height from the IHDR chunk without decoding anything.</li>
 *   <li>{@link #decode} handles 8-bit, non-interlaced PNGs (gray, RGB, palette, gray+alpha, RGBA),
 *       which covers every vanilla-style texture. Anything else returns {@code null} and the caller
 *       falls back to byte comparison.</li>
 *   <li>{@link #encodeRgba} is a minimal encoder used to generate the test packs.</li>
 * </ul>
 * AWT/ImageIO is deliberately avoided: it clashes with GLFW on macOS.
 */
public final class PngPixels {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final long MAX_PIXELS = 4_194_304L;

    private PngPixels() {}

    public record Size(int width, int height) {}

    public record Image(int width, int height, int[] argb) {}

    public static Size peekSize(byte[] png) {
        if (png == null || png.length < 33 || !hasSignature(png)) return null;
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
        int w = readInt(png, 16);
        int h = readInt(png, 20);
        if (w <= 0 || h <= 0) return null;
        return new Size(w, h);
    }

    public static Image decode(byte[] png) {
        Size size = peekSize(png);
        if (size == null) return null;
        int w = size.width();
        int h = size.height();
        if ((long) w * h > MAX_PIXELS) return null;

        int bitDepth = png[24] & 0xFF;
        int colorType = png[25] & 0xFF;
        int interlace = png[28] & 0xFF;
        if (bitDepth != 8 || interlace != 0) return null;
        int channels = switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 0;
        };
        if (channels == 0) return null;

        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] palette = null;
        byte[] trns = null;
        int pos = 8;
        while (pos + 12 <= png.length) {
            int len = readInt(png, pos);
            if (len < 0 || (long) pos + 12 + len > png.length) return null;
            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int dataPos = pos + 8;
            switch (type) {
                case "IDAT" -> idat.write(png, dataPos, len);
                case "PLTE" -> palette = Arrays.copyOfRange(png, dataPos, dataPos + len);
                case "tRNS" -> trns = Arrays.copyOfRange(png, dataPos, dataPos + len);
                default -> { }
            }
            if (type.equals("IEND")) break;
            pos += 12 + len;
        }
        if (colorType == 3 && palette == null) return null;
        if (trns != null && (colorType == 0 || colorType == 2)) return null; // colour-key transparency: unsupported

        int stride = w * channels;
        int expected = (stride + 1) * h;
        byte[] raw = new byte[expected];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(idat.toByteArray());
            int off = 0;
            while (off < expected) {
                int n = inflater.inflate(raw, off, expected - off);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                off += n;
            }
            if (off != expected) return null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }

        int[] argb = new int[w * h];
        byte[] cur = new byte[stride];
        byte[] prev = new byte[stride];
        for (int y = 0; y < h; y++) {
            int rowStart = y * (stride + 1);
            int filter = raw[rowStart] & 0xFF;
            if (filter > 4) return null;
            System.arraycopy(raw, rowStart + 1, cur, 0, stride);
            for (int i = 0; i < stride; i++) {
                int a = i >= channels ? cur[i - channels] & 0xFF : 0;
                int b = prev[i] & 0xFF;
                int c = i >= channels ? prev[i - channels] & 0xFF : 0;
                int x = cur[i] & 0xFF;
                int v = switch (filter) {
                    case 1 -> x + a;
                    case 2 -> x + b;
                    case 3 -> x + ((a + b) >>> 1);
                    case 4 -> x + paeth(a, b, c);
                    default -> x;
                };
                cur[i] = (byte) v;
            }
            for (int px = 0; px < w; px++) {
                int p = px * channels;
                int r;
                int g;
                int b;
                int a;
                switch (colorType) {
                    case 0 -> { r = g = b = cur[p] & 0xFF; a = 255; }
                    case 2 -> { r = cur[p] & 0xFF; g = cur[p + 1] & 0xFF; b = cur[p + 2] & 0xFF; a = 255; }
                    case 3 -> {
                        int idx = cur[p] & 0xFF;
                        if (idx * 3 + 2 >= palette.length) return null;
                        r = palette[idx * 3] & 0xFF;
                        g = palette[idx * 3 + 1] & 0xFF;
                        b = palette[idx * 3 + 2] & 0xFF;
                        a = (trns != null && idx < trns.length) ? trns[idx] & 0xFF : 255;
                    }
                    case 4 -> { r = g = b = cur[p] & 0xFF; a = cur[p + 1] & 0xFF; }
                    default -> { r = cur[p] & 0xFF; g = cur[p + 1] & 0xFF; b = cur[p + 2] & 0xFF; a = cur[p + 3] & 0xFF; }
                }
                argb[y * w + px] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return new Image(w, h, argb);
    }

    /** SHA-256 over dimensions + pixels; fully transparent pixels are normalised so hidden RGB doesn't matter. */
    public static byte[] pixelHash(Image img) {
        int[] argb = img.argb();
        ByteBuffer bb = ByteBuffer.allocate(8 + argb.length * 4);
        bb.putInt(img.width()).putInt(img.height());
        for (int p : argb) bb.putInt((p >>> 24) == 0 ? 0 : p);
        return sha256(bb.array());
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal 8-bit RGBA encoder (filter 0, zlib level 9). */
    public static byte[] encodeRgba(int w, int h, int[] argb) {
        byte[] raw = new byte[(w * 4 + 1) * h];
        int o = 0;
        for (int y = 0; y < h; y++) {
            raw[o++] = 0;
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                raw[o++] = (byte) (p >> 16);
                raw[o++] = (byte) (p >> 8);
                raw[o++] = (byte) p;
                raw[o++] = (byte) (p >>> 24);
            }
        }
        Deflater deflater = new Deflater(9);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            z.write(buf, 0, n);
        }
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE, 0, SIGNATURE.length);
        byte[] ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array();
        writeChunk(out, "IHDR", ihdr);
        writeChunk(out, "IDAT", z.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** A solid-colour square, handy for "legit custom texture" test packs. */
    public static byte[] solid(int size, int argb) {
        int[] px = new int[size * size];
        Arrays.fill(px, argb);
        return encodeRgba(size, size, px);
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static boolean hasSignature(byte[] b) {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (b[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
