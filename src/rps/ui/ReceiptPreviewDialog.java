package rps.ui;

import rps.model.Order;
import rps.print.ReceiptRenderer;
import rps.print.RollSpec;
import rps.ui.theme.Theme;
import rps.ui.theme.UiFactory;
import rps.util.AppSettings;

import javax.swing.*;
import java.awt.*;

/** Read-only preview of both receipt documents, rendered exactly as they would print —
 *  same RollSpec/column width ReceiptPrinter uses — without touching a printer. Distinct
 *  from "Reprint Receipts" (which sends straight to the printer) and "View Details"
 *  (which shows a formatted summary, not the literal printed text). */
final class ReceiptPreviewDialog extends JDialog {

    ReceiptPreviewDialog(Component parent, Order order) {
        super(SwingUtilities.getWindowAncestor(parent), "Receipt Preview — " + order.orderNumber(),
            ModalityType.APPLICATION_MODAL);

        RollSpec roll = RollSpec.closestTo(AppSettings.get().receiptPaperWidthMm());
        ReceiptRenderer renderer = new ReceiptRenderer(roll.columns());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Customer Receipt", receiptArea(renderer.customerReceipt(order)));
        tabs.addTab("Kitchen Ticket", receiptArea(renderer.kitchenTicket(order)));

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(Theme.SURFACE);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.add(tabs, BorderLayout.CENTER);

        JButton close = UiFactory.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setOpaque(false);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);

        getContentPane().setBackground(Theme.SURFACE);
        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        setPreferredSize(new Dimension(420, 560));
        pack();
        setLocationRelativeTo(parent);
    }

    private static JComponent receiptArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setBackground(Theme.SURFACE);
        area.setForeground(Theme.TEXT);
        area.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        // JTextArea only tracks the viewport's width when line-wrap is on — without it,
        // an 80mm/48-column receipt at this font size can be wider than the dialog, and
        // with the horizontal scrollbar disabled below, the overflow would be silently
        // clipped rather than reachable. Wrapping (not on a word boundary, since this is
        // fixed-width receipt text, not prose) keeps every character visible.
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        JScrollPane scroll = new JScrollPane(area,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        return scroll;
    }
}
