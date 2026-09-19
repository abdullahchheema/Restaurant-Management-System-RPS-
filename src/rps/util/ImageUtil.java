package rps.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Turns whatever photo a manager picks off their own device into a small, uniform JPEG
 * fit for a menu tile — resized so a multi-megapixel phone photo doesn't bloat the
 * database row or the payload PosPanel's poll fetches every few seconds, and re-encoded
 * so what's stored is always a format ImageIO can decode again regardless of what the
 * original file was (HEIC/WEBP/etc. are exactly the formats a phone camera defaults to
 * and Java's ImageIO cannot read — those fail loudly here, at upload time, rather than
 * silently later every time a tile tries to paint).
 */
public final class ImageUtil {

    public static final int MAX_DIMENSION = 480;
    private static final float JPEG_QUALITY = 0.85f;

    private ImageUtil() {}

    /** Reads a file from disk, downscales (never upscales) and re-encodes it. Returns
     *  null if the file's bytes cannot be decoded as an image at all — the caller should
     *  treat that as a validation failure, not silently store nothing. */
    public static byte[] loadAndResize(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(raw));
        if (original == null) return null;
        return resizeToJpeg(original);
    }

    static byte[] resizeToJpeg(BufferedImage original) throws IOException {
        int w = original.getWidth();
        int h = original.getHeight();
        double scale = Math.min(1.0, MAX_DIMENSION / (double) Math.max(w, h));
        int targetW = Math.max(1, Math.round((float) (w * scale)));
        int targetH = Math.max(1, Math.round((float) (h * scale)));

        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // Primed white first: JPEG has no alpha channel, and an un-primed TYPE_INT_RGB
        // canvas would otherwise expose whatever was already in that memory through any
        // transparent pixel in the source (e.g. a PNG with a transparent background).
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(original.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer available in this JRE.");
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            try (var ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(scaled, null, null), param);
            }
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Decodes stored bytes into an icon that FITS inside a {@code size}x{@code size}
     *  box without stretching — scaled by the smaller of the two ratios, so a
     *  non-square photo (almost all of them) keeps its real proportions and is centered
     *  by the caller's JLabel rather than squashed to fill a square it was never shaped
     *  for. Returns null if the bytes are absent/corrupt — callers fall back to their
     *  own placeholder rather than this throwing mid-render. */
    public static ImageIcon toIcon(byte[] bytes, int size) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            double scale = Math.min((double) size / img.getWidth(), (double) size / img.getHeight());
            int targetW = Math.max(1, Math.round((float) (img.getWidth() * scale)));
            int targetH = Math.max(1, Math.round((float) (img.getHeight() * scale)));
            Image scaled = img.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Decodes stored bytes into an icon that FILLS a {@code targetW}x{@code targetH} box
     * edge to edge, center-cropping whatever doesn't fit — the opposite trade-off from
     * {@link #toIcon}, which letterboxes rather than crop. For a menu tile photo, an
     * edge-to-edge fill reads as an intentional product photo; a letterboxed one framed by
     * empty background on two sides reads as a broken layout. Returns null on the same
     * conditions as toIcon.
     */
    public static ImageIcon toIconCover(byte[] bytes, int targetW, int targetH) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            double scale = Math.max((double) targetW / img.getWidth(), (double) targetH / img.getHeight());
            int scaledW = Math.max(1, Math.round((float) (img.getWidth() * scale)));
            int scaledH = Math.max(1, Math.round((float) (img.getHeight() * scale)));
            Image scaledImg = img.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH);

            BufferedImage cropped = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = cropped.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            // Centered: whichever dimension overshoots the target is trimmed equally from
            // both sides, rather than anchoring to a corner and losing one whole edge.
            int x = (targetW - scaledW) / 2;
            int y = (targetH - scaledH) / 2;
            g.drawImage(scaledImg, x, y, null);
            g.dispose();
            return new ImageIcon(cropped);
        } catch (IOException e) {
            return null;
        }
    }
}
