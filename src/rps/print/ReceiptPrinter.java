package rps.print;

import rps.model.Order;
import rps.util.AppSettings;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.awt.print.Paper;
import java.awt.print.PageFormat;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Auto-saves both receipts as files (no prompts, no chosen filename) and, if a real
 * (non-virtual) printer is configured or found, auto-prints them sized to the actual
 * roll width with a content-sized page height — never a wasted A4 sheet. Everything
 * runs on a dedicated background thread: printing can block on the OS spooler for
 * seconds, and this used to run directly on the EDT, freezing the whole app mid-order.
 */
public final class ReceiptPrinter {

    /** Windows always ships a few virtual "printers" that pop a Save-As dialog instead
     *  of actually printing — auto-selecting one of these is exactly the failure mode
     *  the "no filename prompts, ever" requirement rules out. */
    private static final Set<String> VIRTUAL_DEVICE_NAMES = Set.of(
        "microsoft print to pdf", "microsoft xps document writer",
        "onenote", "onenote (desktop)", "send to onenote", "fax", "pdf");

    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "receipt-printer");
        t.setDaemon(true);
        return t;
    });

    /** Static, not per-instance: PosPanel and DashboardPanel each construct their own
     *  ReceiptPrinter, but a print failure from either one is the same operational
     *  event and should reach the same single header warning chip. Optional — lets
     *  the UI surface "printing failed" instead of the cashier believing a silent
     *  success. Purely additive: printing still runs fire-and-forget on its own thread
     *  regardless of whether a listener is registered. */
    private static volatile Consumer<String> onFailure = msg -> {};

    public static void setOnFailure(Consumer<String> listener) {
        onFailure = listener != null ? listener : (msg -> {});
    }

    /** Fire-and-forget: saves files first (so a printer failure never loses the receipt),
     *  then prints if a real printer is available. Never blocks the caller. */
    public void printBothAsync(Order order) {
        printExecutor.submit(() -> printBothNow(order));
    }

    /** Re-prints an already-saved order on demand from the dashboard — same async path. */
    public void reprintAsync(Order order) {
        printBothAsync(order);
    }

    private void printBothNow(Order order) {
        RollSpec roll = RollSpec.closestTo(AppSettings.get().receiptPaperWidthMm());
        ReceiptRenderer renderer = new ReceiptRenderer(roll.columns());
        String kitchen = renderer.kitchenTicket(order);
        String customer = renderer.customerReceipt(order);

        saveToFile(order, "kitchen", kitchen);
        saveToFile(order, "customer", customer);

        if (!AppSettings.get().isSilentPrintingEnabled()) return;
        resolveThermalPrinter().ifPresentOrElse(
            service -> {
                silentPrint(service, roll, kitchen);
                silentPrint(service, roll, customer);
            },
            () -> {
                String msg = "No receipt printer configured or found — receipts saved to file only.";
                System.err.println(msg);
                onFailure.accept(msg);
            }
        );
    }

    /** The configured printer, or the system default UNLESS it's a known virtual device
     *  (in which case printing to it would pop a filename dialog, so it's skipped). */
    static Optional<PrintService> resolveThermalPrinter() {
        String configured = AppSettings.get().printerName();
        PrintService[] all = PrinterJob.lookupPrintServices();

        if (configured != null && !configured.isBlank()) {
            for (PrintService s : all) {
                if (s.getName().equalsIgnoreCase(configured.trim())) return Optional.of(s);
            }
            return Optional.empty(); // configured printer isn't attached right now
        }

        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def == null || isVirtualDevice(def.getName())) return Optional.empty();
        return Optional.of(def);
    }

    private static boolean isVirtualDevice(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return VIRTUAL_DEVICE_NAMES.stream().anyMatch(n::contains);
    }

    /**
     * For a persistent header indicator, not the fire-and-forget print path: whether the
     * receipt printer is actually usable right now. Two distinct problems, both worth
     * catching before an order is even placed rather than only after a print silently
     * fails — not configured/found at all, or configured but the OS reports it as not
     * accepting jobs (the standard signal for "plugged in but powered off or offline").
     * Returns empty when everything's fine, or when silent printing is intentionally off
     * (nothing to warn about in that mode).
     */
    public static Optional<String> checkPrinterProblem() {
        if (!AppSettings.get().isSilentPrintingEnabled()) return Optional.empty();
        Optional<PrintService> service = resolveThermalPrinter();
        if (service.isEmpty()) {
            return Optional.of("No receipt printer configured or found — receipts will only be saved to file.");
        }
        var accepting = service.get().getAttribute(
            javax.print.attribute.standard.PrinterIsAcceptingJobs.class);
        if (accepting == javax.print.attribute.standard.PrinterIsAcceptingJobs.NOT_ACCEPTING_JOBS) {
            return Optional.of("Printer \"" + service.get().getName()
                + "\" is not accepting jobs — check it's powered on and connected.");
        }
        return Optional.empty();
    }

    /** All non-virtual printers, for the Settings picker. */
    public static java.util.List<PrintService> listCandidatePrinters() {
        java.util.List<PrintService> result = new java.util.ArrayList<>();
        for (PrintService s : PrinterJob.lookupPrintServices()) {
            if (!isVirtualDevice(s.getName())) result.add(s);
        }
        return result;
    }

    private void silentPrint(PrintService service, RollSpec roll, String text) {
        try {
            String[] lines = text.split("\n", -1);
            float fontSize = roll.fitFontSize();
            PageFormat pf = pageFormatFor(roll, lines.length, fontSize);

            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(service);
            job.setJobName("Receipt");
            job.setPrintable(new TextPrintable(lines, fontSize), pf);
            job.print(); // no printDialog() anywhere on this path
        } catch (PrinterException e) {
            String msg = "Printing skipped (printer offline or unavailable): " + e.getMessage();
            System.err.println(msg);
            onFailure.accept(msg);
        }
    }

    /** A page sized to the paper width and to the ACTUAL content height, with a short
     *  blank tail so the last line clears the tear bar — not a full A4 sheet. */
    static PageFormat pageFormatFor(RollSpec roll, int lineCount, float fontSize) {
        double paperWidthPt = roll.paperWidthMm() * RollSpec.PT_PER_MM;
        double printableWidthPt = roll.printableWidthMm() * RollSpec.PT_PER_MM;
        double leftMarginPt = (paperWidthPt - printableWidthPt) / 2.0;

        double lineHeightPt = RollSpec.lineHeightPt(fontSize);
        double contentHeightPt = lineCount * lineHeightPt;
        double tailPt = 12.0 * RollSpec.PT_PER_MM;
        double paperHeightPt = contentHeightPt + tailPt;

        Paper paper = new Paper();
        paper.setSize(paperWidthPt, paperHeightPt);
        paper.setImageableArea(leftMarginPt, 0, printableWidthPt, paperHeightPt);

        PageFormat pf = new PageFormat();
        pf.setOrientation(PageFormat.PORTRAIT);
        pf.setPaper(paper);
        return pf;
    }

    /** receipts/kitchen/20260825-007.txt and receipts/customer/20260825-007.txt —
     *  the order number is already unique, so the name stays short and sorts naturally.
     *  Reprints overwrite identically since a committed Order never changes. */
    private void saveToFile(Order order, String folder, String content) {
        try {
            Path dir = AppSettings.get().receiptsDir().resolve(folder);
            Files.createDirectories(dir);
            Path file = dir.resolve(order.orderNumber() + ".txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            String msg = "Could not save receipt copy: " + e.getMessage();
            System.err.println(msg);
            onFailure.accept(msg);
        }
    }
}
