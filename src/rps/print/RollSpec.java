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

    /**
     * How big the printed text is, expressed as how many characters are packed across the
     * roll — which is the only thing that actually controls it. fitFontSize() picks the
     * largest point size at which `columns` monospace characters still fit the print head,
     * so asking for fewer columns is what makes the type bigger. Measured on the 80mm
     * roll: 48 columns gives 7.25pt, 40 gives 8.25pt, 36 gives 9.25pt. The 58mm counts are
     * chosen to land on the same three point sizes, so the setting means the same thing
     * whichever roll the shop runs.
     *
     * <p>The trade is only line length: fewer columns means a long item name wraps onto
     * more lines and the receipt gets taller. Nothing is ever clipped — ReceiptRenderer
     * wraps to whatever width it is given.
     */
    public enum TextSize {
        NORMAL("Normal", 48, 32),
        LARGE("Large", 40, 28),
        EXTRA_LARGE("Extra large", 36, 24);

        private final String label;
        private final int columns80;
        private final int columns58;

        TextSize(String label, int columns80, int columns58) {
            this.label = label;
            this.columns80 = columns80;
            this.columns58 = columns58;
        }

        public String label() {
            return label;
        }
    }

    public static RollSpec closestTo(double paperWidthMm) {
        return closestTo(paperWidthMm, TextSize.NORMAL);
    }

    public static RollSpec closestTo(double paperWidthMm, TextSize textSize) {
        boolean narrow = Math.abs(paperWidthMm - MM_58.paperWidthMm())
            < Math.abs(paperWidthMm - MM_80.paperWidthMm());
        RollSpec base = narrow ? MM_58 : MM_80;
        return new RollSpec(base.paperWidthMm(), base.printableWidthMm(),
            narrow ? textSize.columns58 : textSize.columns80);
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
        return fitFontSizeFor(columns, 14f);
    }

    /**
     * Point size for the oversized order-number headline: as large as will still fit
     * across the roll, capped at a multiple of the body size so a very short number
     * ("#7") doesn't print absurdly tall.
     *
     * <p>Sized against charCount + 2 rather than the exact length so the headline never
     * runs edge to edge — and because a bold face can measure marginally wider than the
     * plain one these advances are computed from.
     */
    public float headlineFontSize(int charCount) {
        float ceiling = fitFontSize() * 3.0f;
        return Math.min(ceiling, fitFontSizeFor(Math.max(1, charCount) + 2, 72f));
    }

    private float fitFontSizeFor(int charCount, float startSize) {
        double printableWidthPt = printableWidthMm * PT_PER_MM;
        for (float size = startSize; size >= 4f; size -= 0.25f) {
            if (charAdvancePt(size) * charCount <= printableWidthPt) return size;
        }
        return 4f;
    }
}
