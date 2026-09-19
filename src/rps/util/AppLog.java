package rps.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Makes the app's existing diagnostics survive being launched with no console attached.
 *
 * <p>Every startup/shutdown/error message in this codebase already goes to
 * {@code System.out}/{@code System.err} (see Db, Migrations, AppSettings,
 * OffsiteBackupService, ReceiptPrinter, Theme) — a deliberate, minimal choice at the time,
 * since a developer running run.ps1 sees a terminal. A packaged launcher opens no console
 * window, so every one of those messages was otherwise silently discarded on the client's
 * machine, including exactly the ones (a lost DB connection, a failed migration) an
 * operator would need when something goes wrong.
 *
 * <p>This class does not change any of those call sites. It replaces the two streams
 * once, at startup, with a tee that writes to both the original stream (harmless if there
 * is no console) and a rotating file. Nothing already printing to stdout/stderr had to
 * change, and nothing new was invented to print through.
 */
public final class AppLog {

    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    /** Keeps the log from growing without bound on a till that runs for months without
     *  anyone looking at it. Small on purpose — this is a diagnostic trail, not an audit
     *  log; the database itself is the record of what actually happened. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int KEEP_ROTATIONS = 3;

    private static boolean installed;

    private AppLog() {}

    /** Idempotent — safe to call more than once (e.g. from both App and Bootstrap without
     *  either needing to know whether the other already ran). Failure to open the log
     *  file is swallowed: a missing log must never be why the app itself won't start. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Path dir = logDir();
            Files.createDirectories(dir);
            Path logFile = dir.resolve("app.log");
            rotateIfNeeded(logFile);

            OutputStream fileOut = new FileOutputStream(logFile.toFile(), true);
            System.setOut(new PrintStream(new TeeStream(System.out, fileOut, false), true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new TeeStream(System.err, fileOut, true), true, java.nio.charset.StandardCharsets.UTF_8));

            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
                System.err.println("Uncaught exception on thread \"" + thread.getName() + "\":");
                ex.printStackTrace(System.err);
            });

            System.out.println("==== Royal Pizza Sahowala starting ====");
        } catch (IOException e) {
            // No file to log to is not itself fatal — fall back to whatever streams
            // already existed rather than aborting startup over a logging problem.
            System.err.println("Could not open log file, continuing without one: " + e.getMessage());
        }
    }

    /** %ProgramData%\Royal Pizza Sahowala\logs — machine-wide, not per-user, because the
     *  operator diagnosing a problem is rarely the same Windows account that hit it, and
     *  because this is exactly the directory the installer already creates and grants
     *  the logged-in user write access to (see installer/scripts/setup-database.ps1).
     *  Falls back next to the running jar if ProgramData is somehow unavailable, so a dev
     *  run from run.ps1 (no ProgramData entry ever created for this app) still logs
     *  somewhere rather than throwing. */
    private static Path logDir() {
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "Royal Pizza Sahowala", "logs");
        }
        return Path.of("logs");
    }

    private static void rotateIfNeeded(Path logFile) {
        try {
            if (!Files.isRegularFile(logFile) || Files.size(logFile) < MAX_BYTES) return;
            for (int i = KEEP_ROTATIONS; i >= 1; i--) {
                Path from = i == 1 ? logFile : logFile.resolveSibling("app.log." + (i - 1));
                Path to = logFile.resolveSibling("app.log." + i);
                if (Files.exists(from)) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            // Rotation failing is not worth aborting startup over; the file will just
            // keep growing until the next successful rotation attempt.
            System.err.println("Log rotation failed: " + e.getMessage());
        }
    }

    /** Duplicates every write to a second stream, prefixing each line written to the file
     *  with a timestamp so entries can be correlated with when they actually happened —
     *  the original console output (if any) is left exactly as the existing call sites
     *  already format it. */
    private static final class TeeStream extends OutputStream {
        private final PrintStream original;
        private final OutputStream file;
        private final boolean isErr;
        private final StringBuilder lineBuffer = new StringBuilder();

        TeeStream(PrintStream original, OutputStream file, boolean isErr) {
            this.original = original;
            this.file = file;
            this.isErr = isErr;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            original.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                lineBuffer.append((char) b);
            }
        }

        private void flushLine() throws IOException {
            String prefix = "[" + ZonedDateTime.now().format(TS) + "]" + (isErr ? " [ERR] " : " ");
            file.write((prefix + lineBuffer + System.lineSeparator())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            file.flush();
            lineBuffer.setLength(0);
        }

        @Override
        public synchronized void flush() throws IOException {
            original.flush();
        }
    }
}
