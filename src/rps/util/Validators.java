package rps.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class Validators {

    // Pakistani mobile: 03XXXXXXXXX (11 digits) or +923XXXXXXXXX
    private static final Pattern PK_MOBILE = Pattern.compile("^(0|\\+92)3\\d{9}$");

    private Validators() {}

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static boolean isValidName(String s) {
        return !isBlank(s) && s.trim().length() <= 120;
    }

    public static boolean isValidPakistaniPhone(String phone) {
        if (isBlank(phone)) return false;
        String normalized = phone.trim().replaceAll("[\\s\\-()]", "");
        return PK_MOBILE.matcher(normalized).matches();
    }

    /** Canonical storage form (digits + leading 0 or +92, no spaces/dashes), or null if invalid. */
    public static String normalizePhone(String phone) {
        if (isBlank(phone)) return null;
        String normalized = phone.trim().replaceAll("[\\s\\-()]", "");
        return PK_MOBILE.matcher(normalized).matches() ? normalized : null;
    }

    /** Only non-blank is required — a real address can legitimately be short (a house
     *  number and a well-known local landmark, common in this shop's own delivery area),
     *  so the shop asked for the old 10-character floor to be dropped. The database has
     *  the matching change: see Migrations#19, ck_delivery_address_required. */
    public static boolean isValidAddress(String address) {
        return !isBlank(address);
    }

    /** Parses a user-entered price string. Returns null if invalid (not a workable decimal, or negative). */
    public static Money parsePrice(String text) {
        if (isBlank(text)) return null;
        try {
            BigDecimal bd = new BigDecimal(text.trim());
            if (bd.signum() < 0) return null;
            if (bd.scale() > 2) return null;
            return Money.of(bd);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isValidQuantity(int qty) {
        return qty >= 1 && qty <= 999;
    }

    // ---------------------------------------------------------------- passwords

    /** The password migration 2 seeds the first manager account with, plus the handful of
     *  strings people reach for when asked to "just set something". */
    private static final Set<String> UNACCEPTABLE_PASSWORDS = Set.of(
        "admin123", "admin", "password", "password123", "1234", "12345", "123456",
        "12345678", "letmein", "qwerty", "royalpizza", "pizza123");

    public static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * True for the password this app ships with and its obvious cousins. The seeded
     * admin123 account is documented in the installer notes and is therefore public
     * knowledge on every install — an account still using it is effectively unprotected,
     * so LoginWindow forces a change before the till opens.
     */
    public static boolean isUnacceptablePassword(String password) {
        return password != null
            && UNACCEPTABLE_PASSWORDS.contains(password.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Why the given password can't be used, or null if it's fine. Deliberately modest
     * rules: this is a shop till whose staff type their password many times a shift on a
     * touchscreen, so length and "not a known default" catch the real risk without
     * pushing people towards writing a complex password on a note by the register.
     */
    public static String passwordProblem(String password) {
        if (password == null || password.isEmpty()) {
            return "Enter a new password.";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.";
        }
        if (password.trim().isEmpty()) {
            return "Password cannot be only spaces.";
        }
        if (isUnacceptablePassword(password)) {
            return "That password is too easy to guess. Choose something else.";
        }
        return null;
    }
}
