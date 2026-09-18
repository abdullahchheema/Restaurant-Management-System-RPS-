package rps.db;

import rps.model.Role;
import rps.model.Staff;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class StaffDao {

    private final Db db;

    public StaffDao(Db db) {
        this.db = db;
    }

    public List<Staff> listActive() throws DatabaseException {
        String sql = "SELECT id, password, first_name, last_name, role, active FROM staff "
            + "WHERE active = TRUE ORDER BY last_name, first_name";
        return db.inReadOnly(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                List<Staff> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        });
    }

    public Staff findById(int id) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, password, first_name, last_name, role, active FROM staff WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        });
    }

    /**
     * Verifies against the salted PBKDF2 hash stored by migration 12. Returns null for
     * every failure reason (unknown id, inactive, wrong password) so the caller cannot
     * distinguish them and leak which staff IDs exist.
     */
    public Staff authenticate(int id, String password) throws DatabaseException {
        Staff s = findById(id);
        if (s == null || !s.active() || !rps.util.PasswordHasher.verify(password, s.passwordHash())) {
            return null;
        }
        return s;
    }

    public Staff create(String firstName, String lastName, String password, Role role) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO staff (password, first_name, last_name, role)
                    VALUES (?, ?, ?, ?)
                    RETURNING id, password, first_name, last_name, role, active
                    """)) {
                ps.setString(1, rps.util.PasswordHasher.hash(password));
                ps.setString(2, firstName.trim());
                ps.setString(3, lastName.trim());
                ps.setString(4, role.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return map(rs);
                }
            }
        });
    }

    /**
     * Updates a staff member. A blank/null password means "leave the existing password
     * alone" — the edit form can no longer pre-fill the current password (it only has a
     * one-way hash to show), so without this an edit to someone's name would otherwise
     * silently overwrite their password with whatever was left in the box.
     */
    public void update(int id, String firstName, String lastName, String password, Role role) throws DatabaseException {
        boolean changePassword = password != null && !password.isBlank();
        db.inTransaction(conn -> {
            guardLastManager(conn, id, role);
            String sql = changePassword
                ? "UPDATE staff SET first_name = ?, last_name = ?, role = ?, password = ? WHERE id = ?"
                : "UPDATE staff SET first_name = ?, last_name = ?, role = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, firstName.trim());
                ps.setString(2, lastName.trim());
                ps.setString(3, role.name());
                if (changePassword) {
                    ps.setString(4, rps.util.PasswordHasher.hash(password));
                    ps.setInt(5, id);
                } else {
                    ps.setInt(4, id);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Sets a staff member's own password, used by the forced change at sign-in when an
     * account is still on the shipped default. Separate from update() because that method
     * also takes a role and runs guardLastManager — changing your own password must never
     * be able to trip a role guard, and must not require manager rights.
     */
    public void changePassword(int id, String newPassword) throws DatabaseException {
        String problem = rps.util.Validators.passwordProblem(newPassword);
        if (problem != null) {
            throw new DatabaseException(problem);
        }
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE staff SET password = ? WHERE id = ?")) {
                ps.setString(1, rps.util.PasswordHasher.hash(newPassword));
                ps.setInt(2, id);
                if (ps.executeUpdate() == 0) {
                    throw new DatabaseException("That staff account no longer exists.");
                }
            }
            return null;
        });
    }

    /** Soft-disable rather than hard delete — orders reference staff_id with ON DELETE SET NULL,
     *  but disabling preserves the login history cleanly without touching past orders at all. */
    public void deactivate(int id) throws DatabaseException {
        db.inTransaction(conn -> {
            guardLastManager(conn, id, null);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE staff SET active = FALSE WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Refuses any change that would leave the shop with zero active managers.
     *
     * <p>Manager-only screens are not merely disabled for an employee — MainWindow never
     * adds those tabs at all — so with no manager left, nobody can reach the Staff screen
     * to appoint one. The system would be permanently unusable and only recoverable by
     * editing the database by hand. The UI already blocks deactivating your OWN account,
     * but nothing stopped a manager from DEMOTING the last manager (including themselves)
     * to Employee, which reaches the same dead end. Enforced here rather than in the panel
     * so it holds inside the transaction and cannot be bypassed by another code path.
     *
     * @param newRole the role being assigned, or null when the staff member is being deactivated
     */
    private void guardLastManager(Connection conn, int id, Role newRole) throws SQLException, DatabaseException {
        boolean stillAnActiveManager = newRole == Role.MANAGER;
        if (stillAnActiveManager) return;

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM staff WHERE active = TRUE AND role = 'MANAGER' AND id <> ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    throw new DatabaseException(
                        "This is the last manager account. Appoint another manager first — "
                        + "otherwise no one would be able to reach the Staff, Menu, Reports "
                        + "or Settings screens again.");
                }
            }
        }
    }

    private Staff map(ResultSet rs) throws SQLException {
        return new Staff(
            rs.getInt("id"),
            rs.getString("password"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            Role.valueOf(rs.getString("role")),
            rs.getBoolean("active")
        );
    }
}
