package rps.print;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;

/** Renders pre-split lines of monospaced text (a receipt) onto a single printed page. */
final class TextPrintable implements Printable {

    private final String[] lines;
    private final float fontSize;

    TextPrintable(String[] lines, float fontSize) {
        this.lines = lines;
        this.fontSize = fontSize;
    }

    @Override
    public int print(Graphics g, PageFormat pf, int pageIndex) throws PrinterException {
        if (pageIndex > 0) return NO_SUCH_PAGE;

        Graphics2D g2 = (Graphics2D) g;
        g2.translate(pf.getImageableX(), pf.getImageableY());
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 10).deriveFont(fontSize);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int lineHeight = fm.getHeight();

        int y = fm.getAscent();
        for (String line : lines) {
            g2.drawString(line, 0, y);
            y += lineHeight;
        }
        return PAGE_EXISTS;
    }
}
