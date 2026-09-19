package rps.print;

import rps.model.Order;
import rps.util.AppSettings;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.QueuedJobCount;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Auto-saves both receipts as files (no prompts, no chosen filename) and, if a real
 * (non-virtual) printer is configured or found, auto-prints them sized to the actual
 * roll width with a content-sized page height — never a wasted A4 sheet. Everything
 * runs on a dedicated background thread: printing can block on the OS spooler for
 * seconds, and this used to run directly on the EDT, freezing the whole app mid-order.
 */
public final class ReceiptPrinter {

    /** How long to wait, PER COPY, for the spooler to empty before concluding the printer
     *  is off. This runs on the background print thread, so waiting a little longer costs
     *  the cashier nothing — but waiting too little costs a lot: purgeQueue() force-cancels
     *  whatever is still in the queue once this elapses, and killing a job that a cheap
     *  thermal printer is still actively receiving (not stuck, just genuinely still
     *  printing) can leave its firmware wedged until the next power cycle. Reproduced
     *  live at a client site: a flat 8s budget for BOTH kitchen copies together (not per
     *  copy) meant the second copy's job was routinely still legitimately printing when
     *  this fired, got force-cancelled mid-transmission, and the printer then refused
     *  every subsequent order until physically power-cycled — a fresh cycle "fixed" it
     *  for exactly one order before the same premature cutoff broke it again. Scaling per
     *  copy and widening the budget per copy is what actually addresses the underlying
     *  problem; a fixed total does not, since KITCHEN_COPIES could change independently. */
    private static final long DRAIN_TIMEOUT_MS_PER_COPY = 10_000;
    private static final long DRAIN_POLL_MS = 250;
    private static final int PURGE_TIMEOUT_SECONDS = 15;

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

    /** How many identical kitchen tickets every order prints. Two: one stays with the
     *  kitchen, one travels with the order. Nothing customer-facing is printed at all —
     *  this shop hands the customer no receipt, so there is no second document to render. */
    private static final int KITCHEN_COPIES = 2;

    /** Fire-and-forget: saves the file first (so a printer failure never loses the ticket),
     *  then prints if a real printer is available. Never blocks the caller. */
    public void printKitchenTicketsAsync(Order order) {
        printExecutor.submit(() -> printKitchenTicketsNow(order));
    }

    /** Re-prints an already-saved order on demand from the dashboard — same async path,
     *  and the same two copies. */
    public void reprintAsync(Order order) {
        printKitchenTicketsAsync(order);
    }

    private void printKitchenTicketsNow(Order order) {
        RollSpec roll = RollSpec.closestTo(AppSettings.get().receiptPaperWidthMm(),
            AppSettings.get().receiptTextSize());
        ReceiptRenderer renderer = new ReceiptRenderer(roll.columns());
        ReceiptDoc kitchen = renderer.kitchenTicket(order);

        saveToFile(order, "kitchen", kitchen.toPlainText(roll.columns()));

        if (!AppSettings.get().isSilentPrintingEnabled()) return;

        Optional<PrintService> resolved = resolveThermalPrinter();
        if (resolved.isEmpty()) {
            report("No receipt printer configured or found — receipts saved to file only.");
            return;
        }
        PrintService service = resolved.get();

        // Queue depth, not printer status, is what tells us the printer is unavailable.
        // Measured on the shop's XP-80C: with the printer switched OFF, Windows reports
        // exactly the same thing as when it is on — PrinterStatus Idle, accepting-jobs,
        // unchanged WorkOffline. A cheap USB thermal printer has no status channel back
        // to the driver, so the spooler accepts jobs blindly and flushes the lot when the
        // device reappears. That is how receipts for orders taken hours earlier all
        // printed at once. What DOES change is that submitted jobs stop draining.
        int stuck = queuedJobCount(service);
        if (stuck > 0) {
            boolean cleared = purgeQueue(service.getName());
            report("Printer is not responding (" + stuck + " receipt(s) were stuck in its queue)"
                + " — receipt saved to file, not printed. Reprint it from the Dashboard once"
                + " the printer is back on."
                + (cleared ? "" : " The stuck job could not be cleared automatically — open"
                    + " Windows' \"Printers & scanners\", open this printer's queue, and cancel"
                    + " it by hand, or every order will keep hitting the same stuck job."));
            return;
        }

        for (int copy = 0; copy < KITCHEN_COPIES; copy++) {
            silentPrint(service, roll, kitchen);
        }

        // Submitting succeeds even with no printer attached, so the only proof it really
        // printed is the queue emptying again. If it hasn't drained by now the device is
        // off: discard the jobs rather than leave them primed to print later. The budget
        // scales with how many copies were actually submitted, not a flat number, so
        // KITCHEN_COPIES can change without silently starving this check again.
        if (!queueDrains(service, KITCHEN_COPIES * DRAIN_TIMEOUT_MS_PER_COPY)) {
            boolean cleared = purgeQueue(service.getName());
            report("Printer did not respond — receipts for " + order.orderNumber()
                + " were saved to file but not printed. Reprint from the Dashboard once"
                + " the printer is back on."
                + (cleared ? "" : " The stuck job could not be cleared automatically — open"
                    + " Windows' \"Printers & scanners\", open this printer's queue, and cancel"
                    + " it by hand, or every order will keep hitting the same stuck job."));
        }
    }

    private static int queuedJobCount(PrintService service) {
        QueuedJobCount count = service.getAttribute(QueuedJobCount.class);
        return count == null ? 0 : count.getValue();   // unsupported: assume nothing stuck
    }

    /** Waits for the spooler to empty, which is what actually proves the receipt printed. */
    private static boolean queueDrains(PrintService service, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queuedJobCount(service) == 0) return true;
            try {
                Thread.sleep(DRAIN_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;   // don't purge on the way down; treat as inconclusive
            }
        }
        return queuedJobCount(service) == 0;
    }

    /**
     * Drops everything waiting in this printer's queue.
     *
     * <p>Only ever called once the queue has been shown not to drain, i.e. the printer is
     * off — at which point those jobs are stale receipts that would otherwise all print
     * the moment it is switched on. There is no pure-Java way to cancel an already-spooled
     * Windows job, so this shells out; the printer name goes through the environment
     * rather than the command string so a name containing quotes cannot break or inject
     * into the command.
     *
     * <p>Uses the {@code Win32_PrintJob} WMI class, not the {@code Get-PrintJob}/
     * {@code Remove-PrintJob} cmdlets that originally lived here — those belong to the
     * PrintManagement PowerShell module, which does not exist on Windows 10 Home (and on
     * some Pro installs unless explicitly added). Reproduced live at a client site: on a
     * machine without that module, the old command failed with "the term 'Get-PrintJob'
     * is not recognized" — which this method never checked for, so it logged nothing,
     * returned normally, and left the queue exactly as stuck as before. Every subsequent
     * order then hit the same already-stuck job on {@link #printKitchenTicketsNow}'s own
     * pre-flight check and refused to print, forever, with no way out except clearing the
     * OS spool by hand. Win32_PrintJob is core WMI, present on every Windows edition, so
     * this has no optional-component dependency to silently fail on. The return value
     * says whether the purge actually removed anything, so callers no longer have to
     * assume success the way this used to.
     */
    private static boolean purgeQueue(String printerName) {
        // Passing the script as a `-Command "<string>"` argument is unreliable on
        // Windows: Java's ProcessBuilder re-quotes the whole argv for CreateProcess, and
        // an argument containing embedded double-quote characters (unavoidable here --
        // $env:... interpolation needs a double-quoted PowerShell string) can come out
        // mangled on the far side. Reproduced live: Get-CimInstance's -Filter value lost
        // its surrounding quotes entirely and PowerShell parsed "LIKE" as a bogus
        // positional parameter -- exactly the same underlying cause as the jpackage
        // --java-options bug found earlier in this project. Writing the script to a real
        // file and passing only a bare path sidesteps argv-quoting entirely: there is
        // nothing left for CreateProcess to mangle. The printer name itself still goes
        // through the environment, not string-concatenated into the script, so a name
        // containing quotes can't break out of the WQL filter either.
        Path script = null;
        try {
            script = Files.createTempFile("rps-purge-queue", ".ps1");
            // The printer name is never embedded inside quoted PowerShell script syntax
            // (a WQL -Filter string, an interpolated "..." literal, anything) -- it is
            // read into $name and compared via a plain .NET method call instead. A WQL
            // -Filter version of this was tried first and broke on a name containing a
            // literal double quote (the interpolating "..." string it sat inside ended
            // early); StartsWith() on a bare variable has no quote characters of its own
            // to fight, so no printer name can break it regardless of what it contains.
            String content = """
                $name = $env:RPS_PRINTER_NAME
                $jobs = Get-CimInstance -ClassName Win32_PrintJob | Where-Object { $_.Name.StartsWith($name + ',') }
                if ($jobs) { $jobs | Remove-CimInstance }
                Write-Output ('REMOVED:' + (@($jobs).Count))
                """;
            Files.writeString(script, content, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-File", script.toAbsolutePath().toString());
            pb.environment().put("RPS_PRINTER_NAME", printerName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(PURGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("Clearing the print queue timed out after " + PURGE_TIMEOUT_SECONDS + "s.");
                return false;
            }
            if (p.exitValue() != 0 || !output.contains("REMOVED:")) {
                System.err.println("Could not clear the print queue (exit " + p.exitValue() + "): " + output.trim());
                return false;
            }
            return true;
        } catch (IOException e) {
            System.err.println("Could not clear the print queue: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignore) {
                    // A leftover temp file is harmless — not worth failing the purge over.
                }
            }
        }
    }

    private static void report(String message) {
        System.err.println(message);
        onFailure.accept(message);
    }

    /**
     * Why this printer cannot take a job right now, or null if it can.
     *
     * <p>Two separate signals, because drivers differ in which one they set when a device
     * is powered off or unplugged: PrinterIsAcceptingJobs is the explicit "queue is
     * closed" flag, while PrinterState STOPPED is what many USB thermal drivers report
     * instead. A driver that reports neither attribute returns null here and is treated
     * as usable — refusing to print because a printer is merely uninformative would be
     * worse than the queueing it is meant to prevent.
     */
    private static String offlineReason(PrintService service) {
        var accepting = service.getAttribute(
            javax.print.attribute.standard.PrinterIsAcceptingJobs.class);
        if (accepting == javax.print.attribute.standard.PrinterIsAcceptingJobs.NOT_ACCEPTING_JOBS) {
            return "Printer \"" + service.getName() + "\" is not accepting jobs — check it's powered on and connected.";
        }
        var state = service.getAttribute(javax.print.attribute.standard.PrinterState.class);
        if (state == javax.print.attribute.standard.PrinterState.STOPPED) {
            return "Printer \"" + service.getName() + "\" is stopped or offline — check it's powered on and connected.";
        }
        return null;
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
        String reported = offlineReason(service.get());
        if (reported != null) return Optional.of(reported);

        // Same queue signal the print path gates on, so the indicator and the decision to
        // actually submit can never disagree. Note the honest limit of this check: a
        // printer that is switched off with an EMPTY queue is indistinguishable from a
        // healthy idle one — the hardware reports nothing — so this can only go red once
        // a receipt has actually failed to drain. It is not a live connectivity lamp.
        int stuck = queuedJobCount(service.get());
        if (stuck > 0) {
            return Optional.of("Printer \"" + service.get().getName() + "\" is not printing — "
                + stuck + " job(s) stuck in its queue. Check it's powered on and has paper.");
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

    private void silentPrint(PrintService service, RollSpec roll, ReceiptDoc doc) {
        try {
            String[] lines = doc.bodyLines();
            float fontSize = roll.fitFontSize();
            float headlineSize = roll.headlineFontSize(doc.headline().length());
            PageFormat pf = pageFormatFor(roll, lines.length, fontSize, headlineSize);

            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(service);
            job.setJobName("Kitchen Ticket");
            job.setPrintable(new TextPrintable(doc.headline(), headlineSize, lines, fontSize), pf);
            job.print(); // no printDialog() anywhere on this path
        } catch (PrinterException e) {
            String msg = "Printing skipped (printer offline or unavailable): " + e.getMessage();
            System.err.println(msg);
            onFailure.accept(msg);
        }
    }

    /** A page sized to the paper width and to the ACTUAL content height, with a short
     *  blank tail so the last line clears the tear bar — not a full A4 sheet. The headline
     *  is measured at its own (much larger) size: sizing the page as if every line were
     *  body height would cut the bottom of the ticket off by the difference. */
    static PageFormat pageFormatFor(RollSpec roll, int lineCount, float fontSize, float headlineSize) {
        double paperWidthPt = roll.paperWidthMm() * RollSpec.PT_PER_MM;
        double printableWidthPt = roll.printableWidthMm() * RollSpec.PT_PER_MM;
        double leftMarginPt = (paperWidthPt - printableWidthPt) / 2.0;

        double lineHeightPt = RollSpec.lineHeightPt(fontSize);
        double contentHeightPt = RollSpec.lineHeightPt(headlineSize) + lineCount * lineHeightPt;
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

    /** receipts/kitchen/20260825-007.txt — the order number is already unique, so the name
     *  stays short and sorts naturally. One file per order, not per printed copy: the two
     *  copies are byte-identical, so a second file would be pure duplication. */
    private void saveToFile(Order order, String folder, String content) {
        try {
            Path dir = AppSettings.get().receiptsDir().resolve(folder);
            Files.createDirectories(dir);
            Path file = dir.resolve(order.orderNumber() + ".txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            String msg = "Could not save kitchen ticket copy: " + e.getMessage();
            System.err.println(msg);
            onFailure.accept(msg);
        }
    }
}
