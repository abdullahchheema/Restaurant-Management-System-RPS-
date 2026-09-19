package rps.print;

import java.util.Locale;

/**
 * A rendered receipt, split into the one line that prints oversized and bold (the order
 * number, so the kitchen can identify a ticket at a glance from across the counter) and
 * the monospaced body beneath it.
 *
 * <p>The split exists because the two halves print at different font sizes, which a single
 * String cannot express — TextPrintable draws them with different fonts. Everything that
 * is not a printer (the saved .txt copy, the on-screen preview) wants one flat string
 * instead and calls {@link #toPlainText}, so there is still exactly one rendering of the
 * receipt rather than two that could drift apart.
 */
public record ReceiptDoc(String headline, String body) {

    /** The whole receipt as plain text, headline centred over the body, for the saved file
     *  copy and the preview dialog. Centring is approximate for the file copy — the printed
     *  headline is centred geometrically at its own larger font size, which no fixed-width
     *  text representation can reproduce exactly. */
    public String toPlainText(int columns) {
        int pad = Math.max(0, (columns - headline.length()) / 2);
        return " ".repeat(pad) + headline + "\n\n" + body;
    }

    public String[] bodyLines() {
        return body.split("\n", -1);
    }

    /** "20260918-007" -> "#007". The day's sequence is what anyone actually says out loud
     *  ("order seven"), and at headline size the yyyyMMdd- prefix would crowd out the only
     *  part being read — the full number stays printed in the body's detail block, so
     *  nothing is lost. Anything not in DATE-SEQ shape is shown whole rather than guessed
     *  at, same rule as the dashboard's own column. */
    public static String headlineFor(String orderNumber) {
        int dash = orderNumber.lastIndexOf('-');
        String seq = dash < 0 || dash == orderNumber.length() - 1
            ? orderNumber
            : orderNumber.substring(dash + 1);
        return "#" + seq.toUpperCase(Locale.ROOT);
    }
}
