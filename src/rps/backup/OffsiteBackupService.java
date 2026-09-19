package rps.backup;

import rps.db.Db;
import rps.util.AppSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The only backup path in this app: dumps the database via pg_dump into a small local
 * staging file and uploads it straight to Backblaze B2, deleting the local file the
 * moment the upload succeeds. Nothing persists on the local disk between syncs except a
 * single retry-pending file when an upload has failed — there is deliberately no
 * separate "local backup" archive to manage or explain.
 *
 * <p>Two trigger modes, switchable from Settings: PER_ORDER (a coalesced single-flight
 * dump+upload after every confirmed order, mirroring the old local-backup cadence) or
 * INTERVAL (a periodic timer, default every 45 minutes). PER_ORDER gives the strongest
 * safety guarantee — at most one order's worth of data at risk — at the cost of more
 * frequent uploads; INTERVAL trades that safety margin for fewer uploads.
 */
public final class OffsiteBackupService {

    public enum TriggerMode { PER_ORDER, INTERVAL }

    private static final int DUMP_TIMEOUT_SECONDS = 120;
    private static final Pattern DUMP_NAME = Pattern.compile("rps-\\d{8}-\\d{6}\\.dump");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.ROOT);

    /** Every backup uploads to this same remote name — B2 keeps each upload as a new
     *  version of it rather than a new file, so the bucket always shows exactly one
     *  entry that's automatically the latest backup, with older versions available as
     *  history until pruned by offsiteRetentionDays. */
    private static final String REMOTE_FILE_NAME = "rps-backup.dump";

    private final Db.ConnectionInfo conn;
    private final Path stagingDir;

    // PER_ORDER path: single-flight + trailing-request coalescing, same pattern the old
    // local BackupService used — a burst of orders triggers at most one dump+upload in
    // flight plus exactly one queued to run right after.
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "b2-offsite-sync");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pending = new AtomicBoolean(false);

    // INTERVAL path: a periodic timer, active only while that mode is selected.
    private ScheduledFuture<?> scheduled;

    private volatile boolean unavailable;
    private volatile String unavailableReason;
    private volatile Path pgDump;

    private volatile Consumer<String> onFailure = msg -> {};

    public void setOnFailure(Consumer<String> listener) {
        this.onFailure = listener != null ? listener : (msg -> {});
    }

    public OffsiteBackupService(Db.ConnectionInfo conn, Path stagingDir) {
        this.conn = conn;
        this.stagingDir = stagingDir;
        relocate();
        reschedule();
    }

    public boolean isConfigured() {
        return AppSettings.get().isOffsiteBackupConfigured();
    }

    public boolean isUnavailable() {
        return unavailable;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    /** Re-checks for pg_dump.exe — call after the user sets an explicit path in Settings. */
    public void relocate() {
        Optional<Path> found = locatePgDump();
        this.pgDump = found.orElse(null);
        this.unavailable = found.isEmpty();
        this.unavailableReason = found.isEmpty()
            ? "pg_dump.exe was not found. Set its location in Settings to enable backups."
            : null;
        if (unavailable) {
            System.err.println("Offsite backup disabled: " + unavailableReason);
            onFailure.accept(unavailableReason);
        }
    }

    /** Restarts the INTERVAL timer at the currently configured mode/interval — call after
     *  a settings change. A no-op (timer cancelled, nothing scheduled) while PER_ORDER is
     *  selected, since that path runs off requestBackup() instead. */
    public void reschedule() {
        if (scheduled != null) scheduled.cancel(false);
        AppSettings settings = AppSettings.get();
        if (settings.offsiteTriggerMode() == TriggerMode.INTERVAL) {
            int minutes = settings.offsiteSyncIntervalMinutes();
            scheduled = exec.scheduleWithFixedDelay(this::syncQuietly, minutes, minutes, TimeUnit.MINUTES);
        }
    }

    /** Requests a backup+upload. Never blocks, and only actually does anything in
     *  PER_ORDER mode — in INTERVAL mode the timer alone drives syncing. Coalesces bursts
     *  into at most one trailing sync. */
    public void requestBackup() {
        if (AppSettings.get().offsiteTriggerMode() != TriggerMode.PER_ORDER) return;
        if (unavailable || !isConfigured()) return;
        if (running.compareAndSet(false, true)) {
            exec.submit(this::runAndDrain);
        } else {
            pending.set(true);
        }
    }

    /** Used by the Settings "Sync Now" button via Busy, which wants the real exception
     *  to show, not just a swallowed log line. */
    public void syncNowBlocking() throws IOException, InterruptedException {
        doSync();
    }

    private void runAndDrain() {
        try {
            do {
                pending.set(false);
                syncQuietly();
            } while (pending.get());
        } finally {
            running.set(false);
            if (pending.get() && running.compareAndSet(false, true)) {
                exec.submit(this::runAndDrain);
            }
        }
    }

    private void syncQuietly() {
        try {
            doSync();
        } catch (Exception e) {
            String msg = "Offsite backup failed: " + e.getMessage();
            System.err.println(msg);
            onFailure.accept(msg);
        }
    }

    private void doSync() throws IOException, InterruptedException {
        if (!isConfigured()) return;
        if (pgDump == null) {
            relocate();
            if (unavailable) return;
        }

        // A leftover file means a previous upload failed — retry that exact file rather
        // than burning a fresh pg_dump and abandoning the one that's still pending.
        Path toUpload = newestLocalDump();
        if (toUpload == null) {
            toUpload = dumpToLocalFile();
        }

        AppSettings settings = AppSettings.get();
        B2Client client = new B2Client(settings.b2KeyId(), settings.b2ApplicationKey());
        client.authorize();
        client.upload(toUpload, REMOTE_FILE_NAME);

        Files.deleteIfExists(toUpload);
        settings.setLastOffsiteSyncTime(Instant.now());

        prune(client, settings.offsiteRetentionDays());
        sweepOrphanTempFiles();
    }

    /** Cleans up .part files left behind by a dump interrupted mid-write (e.g. app killed
     *  during pg_dump) — the atomic promote in dumpToLocalFile means these never became a
     *  real dump, so they're safe to discard once clearly stale. */
    private void sweepOrphanTempFiles() {
        Path tmpDir = stagingDir.resolve(".tmp");
        if (!Files.isDirectory(tmpDir)) return;
        try (Stream<Path> paths = Files.list(tmpDir)) {
            Instant cutoff = Instant.now().minusSeconds(3600);
            paths.filter(p -> p.getFileName().toString().endsWith(".part"))
                .forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignore) {
                        // best effort
                    }
                });
        } catch (IOException ignore) {
            // best effort
        }
    }

    private Path dumpToLocalFile() throws IOException, InterruptedException {
        String ts = LocalDateTime.now().format(TS);
        Path tmpDir = stagingDir.resolve(".tmp");
        Path part = tmpDir.resolve("rps-" + ts + ".dump.part");
        Files.createDirectories(tmpDir);

        ProcessBuilder pb = new ProcessBuilder(
            pgDump.toString(),
            "--host=" + conn.host(),
            "--port=" + conn.port(),
            "--username=" + conn.user(),
            "--dbname=" + conn.database(),
            "--format=custom",
            "--compress=6",
            "--no-owner",
            "--no-privileges",
            "--file=" + part);
        pb.environment().put("PGPASSWORD", conn.password());
        pb.environment().put("PGCLIENTENCODING", "UTF8");
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            // Must drain stdout or a chatty pg_dump fills the OS pipe buffer and the
            // process (and this thread) hangs forever waiting for a reader. The drain runs
            // on its own thread rather than inline: reading to EOF inline only returns once
            // the process exits, so a pg_dump that wedges without exiting would block here
            // forever and the timeout below would never be reached — the one case it exists for.
            StringBuilder drained = new StringBuilder();
            Thread drain = new Thread(() -> {
                try {
                    drained.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignore) {
                    // process died mid-read; exit code below is what decides success
                }
            }, "pg_dump-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = p.waitFor(DUMP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("pg_dump timed out after " + DUMP_TIMEOUT_SECONDS + "s");
            }
            drain.join(5000);
            String output = drained.toString();
            if (p.exitValue() != 0 || !Files.exists(part) || Files.size(part) == 0) {
                throw new IOException("pg_dump exited " + p.exitValue() + ": " + output.trim());
            }

            Path finalPath = stagingDir.resolve("rps-" + ts + ".dump");
            promote(part, finalPath);
            return finalPath;
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    /** Atomic promote: a staged dump is either fully written or doesn't exist — never
     *  half-written, in case the upload immediately after picks it up. */
    private void promote(Path part, Path finalPath) throws IOException {
        try {
            Files.move(part, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Windows: antivirus/sync can transiently hold the handle. Retry once.
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Files.move(part, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path newestLocalDump() throws IOException {
        if (!Files.isDirectory(stagingDir)) return null;
        try (Stream<Path> paths = Files.list(stagingDir)) {
            return paths
                .filter(p -> DUMP_NAME.matcher(p.getFileName().toString()).matches())
                .max(Comparator.comparing(p -> p.getFileName().toString()))
                .orElse(null);
        }
    }

    /** Every upload lands as a new version of the same file name, so pruning here means
     *  deleting old versions rather than old files. The newest version is never touched
     *  regardless of age — this only trims history beyond retentionDays, and exists
     *  because the free tier has ample room, not because it's close to the limit. */
    private void prune(B2Client client, int retentionDays) throws IOException, InterruptedException {
        long cutoff = Instant.now().minusSeconds(retentionDays * 86400L).toEpochMilli();
        List<B2Client.RemoteFile> versions = client.listVersions(REMOTE_FILE_NAME); // newest first
        for (int i = 1; i < versions.size(); i++) { // index 0 is the current version — always kept
            B2Client.RemoteFile v = versions.get(i);
            if (v.uploadTimestamp() < cutoff) {
                client.deleteFile(v.fileName(), v.fileId());
            }
        }
    }

    /** Resolution order: explicit setting -> bundled install (rps.pgsql.home, set by the
     *  installed launcher) -> standard EDB install roots (newest version first, since
     *  pg_dump refuses to dump a server newer than itself). There is deliberately no PATH
     *  fallback — a bare `pg_dump` on PATH is never attempted, whatever the javadoc used
     *  to imply; every candidate here is an absolute path, checked to exist before use. */
    private Optional<Path> locatePgDump() {
        String configured = AppSettings.get().pgDumpPath();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isRegularFile(p)) return Optional.of(p);
        }

        // Set by the installed launcher's --java-options to <install dir>\pgsql, so the
        // bundled server is found even though it does not live under Program
        // Files\PostgreSQL — the one location the scan below knows to look in. Absent
        // (dev run via run.ps1, or a build that predates packaging) this is simply blank
        // and resolution falls through to the scan unchanged.
        String bundled = System.getProperty("rps.pgsql.home");
        if (bundled != null && !bundled.isBlank()) {
            Path p = Path.of(bundled, "bin", "pg_dump.exe");
            if (Files.isRegularFile(p)) return Optional.of(p);
        }

        List<Path> roots = List.of(
            Path.of("C:\\Program Files\\PostgreSQL"),
            Path.of("C:\\Program Files (x86)\\PostgreSQL"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> versions = Files.list(root)) {
                Optional<Path> best = versions
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingInt(OffsiteBackupService::versionOf).reversed())
                    .map(d -> d.resolve("bin").resolve("pg_dump.exe"))
                    .filter(Files::isRegularFile)
                    .findFirst();
                if (best.isPresent()) return best;
            } catch (IOException ignore) {
                // try the next root
            }
        }
        return Optional.empty();
    }

    private static int versionOf(Path dir) {
        try {
            return Integer.parseInt(dir.getFileName().toString().split("\\.")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void shutdown() {
        exec.shutdown();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
