package rps.print;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;

/**
 * A thermal roll size. printableWidthMm is the print HEAD width, which is narrower than
 * the paper itself — 80mm paper prints across ~72mm (576 dots at 203dpi), 58mm paper
 * across ~48mm (384 dots). Using the full paper width as printable clips the right edge.
 */
public record RollSpec(double paperWidthMm, double printableWidthMm, int columns) {

    public static final RollSpec MM_80 = new RollSpec(80, 72, 48);
    public static final RollSpec MM_58 = new RollSpec(58, 48, 32);

    public static RollSpec closestTo(double paperWidthMm) {
        return Math.abs(paperWidthMm - MM_58.paperWidthMm()) < Math.abs(paperWidthMm - MM_80.paperWidthMm())
            ? MM_58 : MM_80;
    }

    static final double PT_PER_MM = 72.0 / 25.4;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    static Font mono(float size) {
        return new Font(Font.MONOSPACED, Font.PLAIN, 10).deriveFont(size);
    }

    static float lineHeightPt(float size) {
        LineMetrics lm = mono(size).getLineMetrics("Hg", FRC);
        return lm.getHeight();
    }

    private static double charAdvancePt(float size) {
        return mono(size).getStringBounds("M", FRC).getWidth();
    }

    /** Largest point size at which `columns` monospace characters fit the printable width. */
    public float fitFontSize() {
        double printableWidthPt = printableWidthMm * PT_PER_MM;
        for (float size = 14f; size >= 4f; size -= 0.25f) {
            if (charAdvancePt(size) * columns <= printableWidthPt) return size;
        }
        return 4f;
    }
}
