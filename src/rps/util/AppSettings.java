package rps.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

/**
 * Persisted app settings. Two directories are used deliberately for different reasons:
 *  - receiptsDir() stays under Documents — small text files, fine to sync/back up normally.
 *  - backupDir() defaults OUTSIDE Documents/OneDrive — a multi-megabyte pg_dump written
 *    after every order into a cloud-synced folder means a continuous upload storm, a
 *    file handle held mid-write that can break the atomic rename, and Files-On-Demand
 *    potentially dehydrating old dumps to cloud-only placeholders (so restoring needs
 *    an internet connection to read your own backup). See plan Phase E.
 */
public final class AppSettings {

    private static final Path SETTINGS_FILE =
        Path.of(System.getProperty("user.home"), "Documents", "RoyalPizzaSahowala", "settings.properties");

    private static AppSettings instance;

    private final Properties props = new Properties();

    private AppSettings() {
        load();
    }

    public static synchronized AppSettings get() {
        if (instance == null) instance = new AppSettings();
        return instance;
    }

    private void load() {
        if (Files.exists(SETTINGS_FILE)) {
            try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Could not load settings: " + e.getMessage());
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(SETTINGS_FILE)) {
                props.store(out, "Royal Pizza Sahowala settings");
            }
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }

    private String get(String key, String def) {
        return props.getProperty(key, def);
    }

    private void set(String key, String value) {
        if (value == null) props.remove(key); else props.setProperty(key, value);
        save();
    }

    // ---------------------------------------------------------------- shop details (for receipts)

    public String shopName() { return get("shop.name", "Royal Pizza Sahowala"); }
    public void setShopName(String v) { set("shop.name", v); }

    public String shopAddress() {
        return get("shop.address", "Zulfiqar Sipra Plaza, Near MCB Bank Sahowala, Balochak Maanpur Kamalpur");
    }
    public void setShopAddress(String v) { set("shop.address", v); }

    public String shopPhone() { return get("shop.phone", "052-3578300 / 0331-4631348"); }
    public void setShopPhone(String v) { set("shop.phone", v); }

    public String receiptFooter() {
        return get("shop.receiptFooter", "Thank you for your order! Free home delivery on orders Rs. 700+.");
    }
    public void setReceiptFooter(String v) { set("shop.receiptFooter", v); }

    // ---------------------------------------------------------------- delivery fee

    /** Delivery orders below this subtotal pick up deliveryFeeAmount() — see
     *  OrderDao.rollupTotals, which reads both at order-save time and computes the fee
     *  in the same SQL statement as the rest of the totals (never in Java), for the same
     *  reason the discount is: a Java/SQL rounding disagreement would trip the DB's
     *  exact-equality total CHECK constraint. */
    public double deliveryFeeThreshold() {
        try {
            return Double.parseDouble(get("delivery.feeThreshold", "700"));
        } catch (NumberFormatException e) {
            return 700;
        }
    }

    public void setDeliveryFeeThreshold(double amount) {
        set("delivery.feeThreshold", Double.toString(amount));
    }

    public double deliveryFeeAmount() {
        try {
            return Double.parseDouble(get("delivery.feeAmount", "100"));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    public void setDeliveryFeeAmount(double amount) {
        set("delivery.feeAmount", Double.toString(amount));
    }

    // ---------------------------------------------------------------- order editing

    /** How long after an order is confirmed a cashier can still edit it (change items,
     *  order type, discount, etc.) from the dashboard — see OrderDao.canEdit. Past this
     *  window the only action left is Cancel, matching the "same-day correction, not an
     *  open-ended rewrite of past books" intent. */
    public int orderEditWindowMinutes() {
        try {
            return Integer.parseInt(get("order.editWindowMinutes", "15"));
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    public void setOrderEditWindowMinutes(int minutes) {
        set("order.editWindowMinutes", Integer.toString(minutes));
    }

    /** How long after an order is placed it can still be cancelled normally. Separate from
     *  the edit window on purpose: changing what is on an order and voiding it outright are
     *  different decisions with different exposure, so a shop may well want a short edit
     *  window and a longer cancellation one (or the reverse). Past this, cancelling needs
     *  the explicit Force Cancel, which is always recorded against the staff member who
     *  used it. Applies whether or not the order has been paid. */
    public int orderCancelWindowMinutes() {
        try {
            return Integer.parseInt(get("order.cancelWindowMinutes", "15"));
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    public void setOrderCancelWindowMinutes(int minutes) {
        set("order.cancelWindowMinutes", Integer.toString(minutes));
    }

    // ---------------------------------------------------------------- printing

    public boolean isSilentPrintingEnabled() {
        return Boolean.parseBoolean(get("silentPrinting", "true"));
    }

    public void setSilentPrintingEnabled(boolean enabled) {
        set("silentPrinting", Boolean.toString(enabled));
    }

    /** Paper width in mm — 80 (default, standard roll) or 58 (compact). */
    public double receiptPaperWidthMm() {
        try {
            return Double.parseDouble(get("printer.paperWidthMm", "80"));
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    public void setReceiptPaperWidthMm(double mm) {
        set("printer.paperWidthMm", Double.toString(mm));
    }

    /** Printed text size. Defaults to LARGE rather than NORMAL: at the standard 48
     *  columns an 80mm roll prints at only 7.25pt, which the shop found too small to read
     *  across a counter. See RollSpec.TextSize for what the setting actually changes. */
    public rps.print.RollSpec.TextSize receiptTextSize() {
        try {
            return rps.print.RollSpec.TextSize.valueOf(get("printer.textSize", "LARGE"));
        } catch (IllegalArgumentException e) {
            return rps.print.RollSpec.TextSize.LARGE;
        }
    }

    public void setReceiptTextSize(rps.print.RollSpec.TextSize size) {
        set("printer.textSize", size.name());
    }

    /** Exact PrintService name to use, or blank to auto-pick the default (skipping known
     *  virtual/document-writer devices, since those pop a "Save output as" dialog). */
    public String printerName() { return get("printer.name", ""); }
    public void setPrinterName(String v) { set("printer.name", v == null ? "" : v); }

    public Path receiptsDir() {
        return Path.of(System.getProperty("user.home"), "Documents", "RoyalPizzaSahowala", "receipts");
    }

    // ---------------------------------------------------------------- stay signed in

    /** The staff member to sign back in as automatically on next launch, or empty if
     *  nobody should be — cleared on explicit logout. Storing only the id (not the
     *  password) means a restored session always re-reads the staff's current row, so a
     *  role change or deactivation since the last launch takes effect immediately. */
    public java.util.Optional<Integer> stayedSignedInStaffId() {
        String v = get("session.stayedSignedInStaffId", "");
        if (v.isBlank()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    public void setStayedSignedInStaffId(Integer id) {
        set("session.stayedSignedInStaffId", id == null ? null : String.valueOf(id));
    }

    // ---------------------------------------------------------------- offsite backup (Backblaze B2)

    /** Local staging directory — holds a dump file only transiently, between pg_dump
     *  finishing and the B2 upload confirming, after which it's deleted. Kept outside
     *  Documents/OneDrive for the same reason described in the class comment.
     *
     *  <p>Defaults under %ProgramData%, not a bare C:\ path — a standard (non-admin)
     *  Windows account cannot create a directory at the root of C:\, so that literal
     *  default silently failed every backup with AccessDeniedException on a client
     *  machine. ProgramData is writable by the installer up front and by any local user
     *  thereafter. Falls back to the previous literal only if the environment variable
     *  is somehow unset, which should not happen on any real Windows install. */
    public Path backupDir() {
        String custom = get("backup.dir", "");
        if (!custom.isBlank()) return Path.of(custom);
        String programData = System.getenv("ProgramData");
        return programData == null || programData.isBlank()
            ? Path.of("C:\\RoyalPizzaSahowala\\backups")
            : Path.of(programData, "Royal Pizza Sahowala", "backups");
    }

    public void setBackupDir(String dir) { set("backup.dir", dir); }

    /** Explicit override for pg_dump.exe's location; blank means auto-locate. */
    public String pgDumpPath() { return get("backup.pgDumpPath", ""); }
    public void setPgDumpPath(String path) { set("backup.pgDumpPath", path == null ? "" : path); }

    /** Deliberately no setters exposed anywhere in the UI for keyId/applicationKey — these
     *  are meant to be installed once per client (baked into the installer's config, one
     *  B2 application key per client, scoped to that client's own folder) rather than
     *  typed or edited from the till. Settings only ever shows read-only status. */
    public String b2KeyId() { return get("offsite.b2KeyId", ""); }
    public String b2ApplicationKey() { return get("offsite.b2ApplicationKey", ""); }

    public boolean isOffsiteBackupConfigured() {
        return !b2KeyId().isBlank() && !b2ApplicationKey().isBlank();
    }

    public Instant lastOffsiteSyncTime() {
        String v = get("offsite.lastSuccess", "");
        if (v.isBlank()) return null;
        try {
            return Instant.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    public void setLastOffsiteSyncTime(Instant instant) {
        set("offsite.lastSuccess", instant.toString());
    }

    /** PER_ORDER (default) dumps+uploads after every confirmed order, coalesced so a
     *  burst never queues up multiple uploads. INTERVAL instead syncs on a timer at
     *  offsiteSyncIntervalMinutes(), trading a wider data-loss window for fewer uploads.
     *  Both are user-editable from Settings. */
    public rps.backup.OffsiteBackupService.TriggerMode offsiteTriggerMode() {
        try {
            return rps.backup.OffsiteBackupService.TriggerMode.valueOf(get("offsite.triggerMode", "PER_ORDER"));
        } catch (IllegalArgumentException e) {
            return rps.backup.OffsiteBackupService.TriggerMode.PER_ORDER;
        }
    }

    public void setOffsiteTriggerMode(rps.backup.OffsiteBackupService.TriggerMode mode) {
        set("offsite.triggerMode", mode.name());
    }

    public int offsiteSyncIntervalMinutes() {
        try {
            return Integer.parseInt(get("offsite.syncIntervalMinutes", "45"));
        } catch (NumberFormatException e) {
            return 45;
        }
    }

    public void setOffsiteSyncIntervalMinutes(int minutes) {
        set("offsite.syncIntervalMinutes", Integer.toString(minutes));
    }

    public int offsiteRetentionDays() {
        try {
            return Integer.parseInt(get("offsite.retentionDays", "90"));
        } catch (NumberFormatException e) {
            return 90;
        }
    }
}
