package rps.ui.theme;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;

/**
 * App-wide look and feel, installed once at startup. If FlatLaf fails to load for any
 * reason, falls back to the system L&F with a logged warning — a theme problem must
 * never stop the app starting (see plan Phase 0).
 */
public final class Theme {

    // ---------------------------------------------------------------- palette
    // Deep burgundy + champagne gold, warm ivory/charcoal neutrals — every pairing here
    // is verified >= WCAG AA (4.5:1 text, 3:1 focus/UI) against the surface it's used on;
    // see the design-pass plan for the full contrast table before changing any of these.

    public static final Color PRIMARY = new Color(0x9F, 0x1D, 0x25);
    public static final Color PRIMARY_DARK = new Color(0x73, 0x15, 0x1B);
    public static final Color PRIMARY_TINT = new Color(0xF7, 0xE8, 0xE9);
    /** Visible champagne gold — hairline detail and the focus ring ON a burgundy ground
     *  ONLY. At 2.3:1 it fails the 3:1 floor on light surfaces; use ACCENT_DEEP there. */
    public static final Color ACCENT = new Color(0xC8, 0xA4, 0x5D);
    /** Same gold family, one step deeper — the focus ring on ivory/white surfaces. */
    public static final Color ACCENT_DEEP = new Color(0xA6, 0x7C, 0x3C);

    public static final Color BACKGROUND = new Color(0xF5, 0xF2, 0xEC);
    public static final Color SURFACE = new Color(0xFF, 0xFD, 0xF9);
    public static final Color SURFACE_HOVER = new Color(0xF1, 0xEC, 0xE4);
    public static final Color BORDER = new Color(0xDE, 0xD8, 0xCE);

    public static final Color TEXT = new Color(0x21, 0x1F, 0x1C);
    public static final Color TEXT_MUTED = new Color(0x70, 0x6A, 0x62);
    public static final Color ON_PRIMARY = Color.WHITE;

    /** Muted, desaturated green — darkened one step from the initial proposal so
     *  status-tag TEXT on STATUS_COMPLETED_TINT still clears 4.5:1 (was 4.42, now 5.11). */
    public static final Color STATUS_COMPLETED = new Color(0x2B, 0x74, 0x49);
    public static final Color STATUS_COMPLETED_TINT = new Color(0xED, 0xF5, 0xEF);
    public static final Color STATUS_CANCELLED = new Color(0xB8, 0x32, 0x32);
    public static final Color STATUS_CANCELLED_TINT = new Color(0xF9, 0xEB, 0xEB);
    /** Amber — a Pending order (payment not yet fully received), distinct from the green
     *  "settled" and red "cancelled" states. */
    public static final Color STATUS_PENDING = new Color(0xA6, 0x6A, 0x00);
    public static final Color STATUS_PENDING_TINT = new Color(0xFB, 0xF1, 0xDE);

    // ---------------------------------------------------------------- spacing

    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 20;

    // ---------------------------------------------------------------- radius / metrics
    // Tightened from the original 4/8/10 app-style scale — smaller radii read as
    // considered and architectural rather than "friendly app" (locked design decision).

    public static final int RADIUS_SM = 2;   // checkboxes
    public static final int RADIUS_MD = 4;   // text fields, chips, status tags
    public static final int RADIUS_LG = 6;   // buttons, cards, dialogs, menu tiles

    /** Single source of truth for table row height — previously diverged to 34/36/28/30
     *  across four different panels. */
    public static final int ROW_HEIGHT = 34;

    // ---------------------------------------------------------------- typography
    // Segoe UI is the standard Windows business-app face; fall back gracefully
    // off-Windows rather than silently rendering in an arbitrary logical font.

    private static final String FAMILY = resolveFamily();

    public static final Font FONT_BRAND = new Font(FAMILY, Font.BOLD, 24);
    public static final Font FONT_TITLE = new Font(FAMILY, Font.BOLD, 19);
    public static final Font FONT_HEADING = new Font(FAMILY, Font.BOLD, 16);
    public static final Font FONT_BODY = new Font(FAMILY, Font.PLAIN, 14);
    public static final Font FONT_BODY_BOLD = new Font(FAMILY, Font.BOLD, 14);
    public static final Font FONT_SMALL = new Font(FAMILY, Font.PLAIN, 12);
    public static final Font FONT_SMALL_BOLD = new Font(FAMILY, Font.BOLD, 12);

    private Theme() {}

    private static String resolveFamily() {
        Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : new String[]{"Segoe UI", "Tahoma", "Helvetica Neue", "Arial"}) {
            if (available.contains(candidate)) return candidate;
        }
        return Font.SANS_SERIF;
    }

    public static void install() {
        try {
            UIManager.put("Button.arc", RADIUS_LG * 2);
            UIManager.put("Component.arc", RADIUS_MD * 2);
            UIManager.put("TextComponent.arc", RADIUS_MD * 2);
            UIManager.put("CheckBox.arc", RADIUS_SM * 2);
            UIManager.put("Component.focusWidth", 2);
            UIManager.put("Component.innerFocusWidth", 0);
            // Gold focus ring: ACCENT (visible champagne gold) on a burgundy ground,
            // ACCENT_DEEP (same gold family, darker) on the light ivory/white grounds
            // that make up nearly every focusable component — see palette contrast notes.
            UIManager.put("Component.focusColor", ACCENT_DEEP);
            UIManager.put("Button.focusedBorderColor", ACCENT);
            UIManager.put("Button.default.focusColor", ACCENT);

            UIManager.put("Component.accentColor", PRIMARY);
            UIManager.put("Button.default.background", PRIMARY);
            UIManager.put("Button.default.foreground", ON_PRIMARY);
            UIManager.put("Button.default.focusedBackground", PRIMARY_DARK);
            UIManager.put("Button.default.hoverBackground", PRIMARY_DARK);
            UIManager.put("ToggleButton.selectedBackground", PRIMARY);

            UIManager.put("Panel.background", BACKGROUND);
            UIManager.put("OptionPane.background", BACKGROUND);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("TabbedPane.selectedBackground", SURFACE);
            UIManager.put("TabbedPane.underlineColor", PRIMARY);
            // Set once, here only — MainWindow used to redundantly re-set this too.
            UIManager.put("TabbedPane.font", FONT_BODY_BOLD);

            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Table.showVerticalLines", false);
            UIManager.put("Table.gridColor", BORDER);
            UIManager.put("Table.rowHeight", ROW_HEIGHT);
            UIManager.put("Table.font", FONT_BODY);
            UIManager.put("Table.selectionBackground", PRIMARY_TINT);
            UIManager.put("Table.selectionForeground", TEXT);
            UIManager.put("TableHeader.font", FONT_SMALL_BOLD);
            UIManager.put("TableHeader.background", BACKGROUND);
            UIManager.put("TableHeader.foreground", TEXT_MUTED);

            UIManager.put("List.font", FONT_BODY);
            UIManager.put("ScrollBar.width", 12);

            UIManager.put("defaultFont", FONT_BODY);

            FlatLightLaf.setup();
        } catch (Throwable t) {
            System.err.println("FlatLaf failed to load, falling back to system look and feel: " + t.getMessage());
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall through to Swing's built-in default
            }
        }
    }

    public static Color statusColor(rps.model.PaymentStatus status) {
        return switch (status) {
            // Unpaid and Partially Paid share the amber "still owes money" family —
            // they are the same operational state, distinguished by label, not colour.
            case UNPAID, PARTIALLY_PAID -> STATUS_PENDING;
            case PAID -> STATUS_COMPLETED;
        };
    }

    public static Color statusTint(rps.model.PaymentStatus status) {
        return switch (status) {
            case UNPAID, PARTIALLY_PAID -> STATUS_PENDING_TINT;
            case PAID -> STATUS_COMPLETED_TINT;
        };
    }

    public static Color fulfilmentColor(rps.model.FulfilmentStatus status) {
        return switch (status) {
            case PENDING -> STATUS_PENDING;
            case COMPLETED -> STATUS_COMPLETED;
            case CANCELLED -> STATUS_CANCELLED;
        };
    }

    public static Color fulfilmentTint(rps.model.FulfilmentStatus status) {
        return switch (status) {
            case PENDING -> STATUS_PENDING_TINT;
            case COMPLETED -> STATUS_COMPLETED_TINT;
            case CANCELLED -> STATUS_CANCELLED_TINT;
        };
    }
}
