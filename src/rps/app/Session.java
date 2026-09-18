package rps.app;

import rps.model.Role;
import rps.model.Staff;

/**
 * An authenticated session. Structural enforcement point: privileged actions call
 * require(Role.MANAGER), which throws rather than relying on a disabled button —
 * see plan Phase 3. There is deliberately no way to construct a Session without
 * going through StaffDao.authenticate() in LoginWindow.
 */
public final class Session {

    private final Staff staff;

    public Session(Staff staff) {
        if (staff == null) {
            throw new IllegalArgumentException("Session requires an authenticated staff member.");
        }
        this.staff = staff;
    }

    public Staff staff() {
        return staff;
    }

    public int staffId() {
        return staff.id();
    }

    public String staffName() {
        return staff.fullName();
    }

    public Role role() {
        return staff.role();
    }

    public boolean isManager() {
        return staff.role() == Role.MANAGER;
    }

    /** Throws AccessDeniedException if this session does not hold the given role. */
    public void require(Role required) {
        if (required == Role.MANAGER && !isManager()) {
            throw new AccessDeniedException("This action requires a manager login.");
        }
    }

    public static final class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }
}
