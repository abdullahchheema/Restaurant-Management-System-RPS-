package rps.model;

public record Staff(
    int id,
    /** Salted PBKDF2 hash, never a usable password — named so no caller mistakes it
     *  for something displayable or comparable with equals(). */
    String passwordHash,
    String firstName,
    String lastName,
    Role role,
    boolean active
) {
    public String fullName() {
        return firstName + " " + lastName;
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }
}
