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

    /** Exact PrintService name to use, or blank to auto-pick the default (skipping known
     *  virtual/document-writer devices, since those pop a "Save output as" dialog). */
    public String printerName() { return get("printer.name", ""); }
    public void setPrinterName(String v) { set("printer.name", v == null ? "" : v); }

    public Path receiptsDir() {
        return Path.of(System.getProperty("user.home"), "Documents", "RoyalPizzaSahowala", "receipts");
    }

    // ---------------------------------------------------------------- offsite backup (Backblaze B2)

    /** Local staging directory — holds a dump file only transiently, between pg_dump
     *  finishing and the B2 upload confirming, after which it's deleted. Kept outside
     *  Documents/OneDrive for the same reason described in the class comment. */
    public Path backupDir() {
        String custom = get("backup.dir", "");
        return custom.isBlank() ? Path.of("C:\\RoyalPizzaSahowala\\backups") : Path.of(custom);
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
