package rps.print;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;

/** Renders a receipt onto a single printed page: an oversized bold headline (the order
 *  number) centred at the top, then pre-split lines of monospaced body text. */
final class TextPrintable implements Printable {

    private final String headline;
    private final float headlineSize;
    private final String[] lines;
    private final float fontSize;

    TextPrintable(String headline, float headlineSize, String[] lines, float fontSize) {
        this.headline = headline;
        this.headlineSize = headlineSize;
        this.lines = lines;
        this.fontSize = fontSize;
    }

    @Override
    public int print(Graphics g, PageFormat pf, int pageIndex) throws PrinterException {
        if (pageIndex > 0) return NO_SUCH_PAGE;

        Graphics2D g2 = (Graphics2D) g;
        g2.translate(pf.getImageableX(), pf.getImageableY());
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        int y = 0;
        if (headline != null && !headline.isBlank()) {
            Font headlineFont = new Font(Font.MONOSPACED, Font.BOLD, 10).deriveFont(headlineSize);
            g2.setFont(headlineFont);
            FontMetrics hm = g2.getFontMetrics();
            // Centred geometrically against the actual printable width rather than by
            // padding with spaces: at this size the headline's own font metrics are the
            // only thing that knows how wide it really is.
            int x = Math.max(0, (int) ((pf.getImageableWidth() - hm.stringWidth(headline)) / 2));
            y += hm.getAscent();
            g2.drawString(headline, x, y);
            y += hm.getDescent() + hm.getLeading();
        }

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 10).deriveFont(fontSize);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int lineHeight = fm.getHeight();

        y += fm.getAscent();
        for (String line : lines) {
            g2.drawString(line, 0, y);
            y += lineHeight;
        }
        return PAGE_EXISTS;
    }
}
