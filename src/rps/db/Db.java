package rps.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Owns exactly two JDBC connections:
 *  - a WRITE connection, guarded by a lock, used for everything that mutates data
 *    (including short-lived transactions via inTransaction()).
 *  - a READ-ONLY connection, guarded by a separate lock, used by background pollers
 *    and read DAOs, so a poll can never observe partial state from an in-flight
 *    write transaction on the same connection, and never collides with it.
 *
 * setAutoCommit(false) is connection-scoped in JDBC — sharing one connection between
 * a transactional writer and a polling reader is what the old Database.java did, and
 * it is unsafe. Both locks guard the connection for the FULL duration of a query, not
 * just the handout — a bare getter that returns the Connection and lets the caller run
 * a query unsynchronized would let a reprint race the dashboard poller on the same
 * PgJDBC Connection, which is not thread-safe.
 */
public final class Db {

    private static Db instance;

    private final String url;
    private final String user;
    private final String password;

    private final Object writeLock = new Object();
    private final Object readLock = new Object();
    private Connection writeConn;
    private Connection readConn;

    private Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static synchronized Db get() throws DatabaseException {
        if (instance == null) {
            Properties p = loadProperties();
            Db candidate = new Db(
                p.getProperty("db.url"),
                p.getProperty("db.user"),
                p.getProperty("db.password"));
            candidate.connectBoth();
            // Only published on success — if connectBoth() throws (e.g. Postgres isn't
            // running yet), a subsequent get() must retry from scratch, not return a
            // half-constructed instance with no live connections.
            instance = candidate;
        }
        return instance;
    }

    private static Properties loadProperties() throws DatabaseException {
        List<Path> candidates = configCandidates();
        Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        if (path == null) {
            StringBuilder looked = new StringBuilder();
            for (Path c : candidates) looked.append("\n    ").append(c.toAbsolutePath());
            throw new DatabaseException(
                "db.properties not found. Copy db.properties.example to db.properties and adjust it."
                + "\n\nLooked in:" + looked);
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            throw new DatabaseException("Could not read db.properties: " + e.getMessage(), e);
        }
        // Logged because the search below has several candidates: when a till is pointed at
        // the wrong database, the first question is which config file it actually read.
        System.out.println("Using database config: " + path.toAbsolutePath());
        return p;
    }

    /**
     * Where db.properties may live, in priority order. The working directory alone is not
     * enough once the app is installed: a Start Menu or desktop shortcut launches it with
     * the working directory set to somewhere like C:\Windows\System32, so a CWD-relative
     * lookup finds nothing and the app refuses to start. The install directory is derived
     * from where this class was actually loaded from, which is correct regardless of how
     * the app was launched.
     */
    private static List<Path> configCandidates() {
        List<Path> out = new ArrayList<>();
        String explicit = System.getProperty("rps.config");
        if (explicit != null && !explicit.isBlank()) {
            out.add(Path.of(explicit));
        }
        out.add(Path.of("db.properties"));   // development and the QA suites run from their own directory
        for (Path dir : installDirectories()) {
            out.add(dir.resolve("db.properties"));
        }
        String programData = System.getenv("ProgramData");
        out.add(Path.of(programData == null || programData.isBlank() ? "C:\\ProgramData" : programData,
            "RoyalPizzaSahowala", "db.properties"));
        return out;
    }

    /** The directory holding the compiled code and its parent — covering both a
     *  bin/-classes layout and a packaged app/<name>.jar layout. */
    private static List<Path> installDirectories() {
        try {
            var codeSource = Db.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) return List.of();
            Path location = Path.of(codeSource.getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            if (dir == null) return List.of();
            Path parent = dir.getParent();
            return parent == null ? List.of(dir) : List.of(dir, parent);
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return List.of();   // unusual classloader: fall back to the other candidates
        }
    }

    private void connectBoth() throws DatabaseException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new DatabaseException("PostgreSQL JDBC driver not found on classpath.", e);
        }
        synchronized (writeLock) {
            writeConn = openConnection();
        }
        synchronized (readLock) {
            readConn = openConnection();
            try {
                readConn.setReadOnly(true);
            } catch (SQLException e) {
                throw new DatabaseException("Could not set read-only connection: " + e.getMessage(), e);
            }
        }
    }

    private Connection openConnection() throws DatabaseException {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new DatabaseException("Could not connect to PostgreSQL: " + e.getMessage(), e);
        }
    }

    /** Host/port/database/user/password as already parsed from the JDBC URL — for tools
     *  (like pg_dump) that need the same connection details without re-reading db.properties
     *  from a second, independently-resolved relative path. */
    public record ConnectionInfo(String host, int port, String database, String user, String password) {}

    public ConnectionInfo connectionInfo() throws DatabaseException {
        // url looks like: jdbc:postgresql://host:port/db?query
        try {
            URI uri = new URI(url.substring("jdbc:".length()));
            String host = uri.getHost() == null ? "localhost" : uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            return new ConnectionInfo(host, port, db, user, password);
        } catch (URISyntaxException e) {
            throw new DatabaseException("Could not parse db.url: " + e.getMessage(), e);
        }
    }

    /** Runs a single write/read outside a transaction; reconnects if stale. Prefer
     *  inTransaction for anything multi-statement, and inReadOnly for read DAOs. */
    public Connection writeConnection() throws DatabaseException {
        synchronized (writeLock) {
            ensureValid(false);
            return writeConn;
        }
    }

    /**
     * Runs a read against the dedicated read-only connection, holding the read lock for
     * the full duration of the callback — not just the handout — so a reprint can never
     * race the dashboard poller on the same underlying PgJDBC Connection.
     */
    public <T> T inReadOnly(SqlFunction<Connection, T> work) throws DatabaseException {
        synchronized (readLock) {
            ensureValid(true);
            try {
                return work.apply(readConn);
            } catch (SQLException e) {
                throw new DatabaseException(friendlyMessage(e), e);
            }
        }
    }

    private void ensureValid(boolean readOnly) throws DatabaseException {
        try {
            Connection current = readOnly ? readConn : writeConn;
            if (current == null || !current.isValid(2)) {
                Connection fresh = openConnection();
                if (readOnly) {
                    fresh.setReadOnly(true);
                } else {
                    // nothing extra
                }
                closeQuietly(current);
                if (readOnly) readConn = fresh; else writeConn = fresh;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database connection lost and could not be re-established: "
                + e.getMessage(), e);
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignore) {
            // it was already broken; nothing more to do
        }
    }

    /** True if the write connection currently looks reachable — used for the header's
     *  connection-status indicator. Does not attempt to reconnect or throw. */
    public boolean isReachable() {
        try {
            synchronized (writeLock) {
                return writeConn != null && writeConn.isValid(2);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Runs the given work in one transaction on the write connection: autocommit off,
     * commit on normal return, rollback on any exception, autocommit restored after.
     * READ COMMITTED (the JDBC/Postgres default) is used deliberately — see plan.
     */
    public <T> T inTransaction(SqlFunction<Connection, T> work) throws DatabaseException {
        synchronized (writeLock) {
            ensureValid(false);
            Connection conn = writeConn;
            boolean prevAutoCommit;
            try {
                prevAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                throw new DatabaseException("Could not start transaction: " + e.getMessage(), e);
            }
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new DatabaseException(friendlyMessage(e), e);
            } catch (DatabaseException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignore) {
                    // connection may already be broken; nothing more we can do
                }
            }
        }
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            System.err.println("Rollback failed: " + e.getMessage());
        }
    }

    private String friendlyMessage(SQLException e) {
        String state = e.getSQLState();
        if (state == null) return e.getMessage();
        return switch (state) {
            case "23505" -> "That record already exists.";
            case "23503" -> "This action is blocked because related records still exist.";
            case "23514" -> "That value isn't allowed: " + e.getMessage();
            case "08000", "08003", "08006", "08001", "08004" ->
                "Lost the connection to the database. Please try again.";
            default -> e.getMessage();
        };
    }

    @FunctionalInterface
    public interface SqlFunction<A, R> {
        R apply(A a) throws SQLException, DatabaseException;
    }
}
