package rps.db;

import rps.model.Category;
import rps.model.MenuItem;
import rps.model.MenuItemVariant;
import rps.model.OptionGroup;
import rps.model.OptionValue;
import rps.util.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MenuDao {

    private final Db db;

    public MenuDao(Db db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- categories

    public List<Category> listCategories(boolean activeOnly) throws DatabaseException {
        String sql = "SELECT id, name, display_order, active FROM category "
            + (activeOnly ? "WHERE active = TRUE " : "")
            + "ORDER BY display_order, name";
        return db.inReadOnly(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                List<Category> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapCategory(rs));
                }
                return result;
            }
        });
    }

    public Category createCategory(String name, int displayOrder) throws DatabaseException {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO category (name, display_order) VALUES (?, ?) RETURNING id, name, display_order, active")) {
                ps.setString(1, name.trim());
                ps.setInt(2, displayOrder);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return mapCategory(rs);
                }
            }
        });
    }

    // ---------------------------------------------------------------- option groups

    /** All option groups with their live (non-deleted) values, for the menu admin screen
     *  and for attaching to items. {@code activeOnly} also restricts to available values. */
    public List<OptionGroup> listOptionGroups(boolean activeOnly) throws DatabaseException {
        String sql = """
            SELECT og.id AS group_id, og.name AS group_name, og.display_order AS group_order,
                   ov.id AS value_id, ov.name AS value_name, ov.display_order AS value_order
            FROM option_group og
            LEFT JOIN option_value ov ON ov.option_group_id = og.id AND ov.deleted_at IS NULL
                %s
            WHERE %s
            ORDER BY og.display_order, og.name, ov.display_order
            """.formatted(activeOnly ? "AND ov.available = TRUE" : "", activeOnly ? "og.active = TRUE" : "TRUE");
        return db.inReadOnly(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                return assembleOptionGroups(rs);
            }
        });
    }

    private List<OptionGroup> assembleOptionGroups(ResultSet rs) throws SQLException {
        Map<Integer, String> namesById = new LinkedHashMap<>();
        Map<Integer, Integer> ordersById = new LinkedHashMap<>();
        Map<Integer, List<OptionValue>> valuesByGroup = new LinkedHashMap<>();
        while (rs.next()) {
            int groupId = rs.getInt("group_id");
            namesById.put(groupId, rs.getString("group_name"));
            ordersById.put(groupId, rs.getInt("group_order"));
            valuesByGroup.computeIfAbsent(groupId, k -> new ArrayList<>());
            int valueId = rs.getInt("value_id");
            if (!rs.wasNull()) {
                valuesByGroup.get(groupId).add(new OptionValue(
                    valueId, groupId, rs.getString("value_name"), rs.getInt("value_order")));
            }
        }
        List<OptionGroup> result = new ArrayList<>();
        for (Integer groupId : namesById.keySet()) {
            result.add(new OptionGroup(groupId, namesById.get(groupId), ordersById.get(groupId),
                valuesByGroup.get(groupId)));
        }
        return result;
    }

    /** Creates an option group with its values in one transaction. */
    public OptionGroup createOptionGroup(String name, List<String> valueNames) throws DatabaseException {
        return db.inTransaction(conn -> {
            int groupId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO option_group (name) VALUES (?) RETURNING id")) {
                ps.setString(1, name.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    groupId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO option_value (option_group_id, name, display_order) VALUES (?, ?, ?)
                    """)) {
                for (int i = 0; i < valueNames.size(); i++) {
                    ps.setInt(1, groupId);
                    ps.setString(2, valueNames.get(i).trim());
                    ps.setInt(3, (i + 1) * 10);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return findOptionGroupById(conn, groupId);
        });
    }

    public void addOptionValue(int groupId, String name) throws DatabaseException {
        db.inTransaction(conn -> {
            int nextOrder;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(display_order), 0) + 10 FROM option_value WHERE option_group_id = ?")) {
                ps.setInt(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    nextOrder = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO option_value (option_group_id, name, display_order) VALUES (?, ?, ?)
                    """)) {
                ps.setInt(1, groupId);
                ps.setString(2, name.trim());
                ps.setInt(3, nextOrder);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void removeOptionValue(int valueId) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE option_value SET deleted_at = now(), available = FALSE WHERE id = ?")) {
                ps.setInt(1, valueId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Attaches (or clears, when groupId is null) the option group an item offers at order time. */
    public void setItemOptionGroup(int itemId, Integer groupId) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE menu_item SET option_group_id = ? WHERE id = ?")) {
                if (groupId == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, groupId);
                ps.setInt(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private OptionGroup findOptionGroupById(Connection conn, int groupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT og.id AS group_id, og.name AS group_name, og.display_order AS group_order,
                       ov.id AS value_id, ov.name AS value_name, ov.display_order AS value_order
                FROM option_group og
                LEFT JOIN option_value ov ON ov.option_group_id = og.id AND ov.deleted_at IS NULL
                WHERE og.id = ?
                ORDER BY ov.display_order
                """)) {
            ps.setInt(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                List<OptionGroup> groups = assembleOptionGroups(rs);
                return groups.isEmpty() ? null : groups.get(0);
            }
        }
    }

    // ---------------------------------------------------------------- menu items

    /** All live (non-deleted) items for the POS, grouped by category, available only. */
    public List<MenuItem> listAvailableItems() throws DatabaseException {
        return listItems(true, false);
    }

    /** All non-deleted items for the manager's menu admin screen (including unavailable). */
    public List<MenuItem> listAllLiveItems() throws DatabaseException {
        return listItems(false, false);
    }

    /**
     * Cheap content hash of everything the POS screen shows (name, price, availability,
     * ordering, attached option group and its values) for the live/available catalog —
     * lets PosPanel poll for menu changes the same way DashboardPanel polls for order
     * changes, without re-fetching and rebuilding the tile grid on every tick. Unlike
     * orders, menu rows have no updated_at column to fingerprint against, so this hashes
     * the actual field values instead.
     */
    public String fingerprint() throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("""
                        SELECT md5(
                            COALESCE((
                                SELECT string_agg(
                                    mi.id || ':' || mi.name || ':' || mi.display_order || ':' ||
                                    COALESCE(mi.option_group_id, 0) || ':' ||
                                    v.id || ':' || COALESCE(v.size_label, '') || ':' || v.price || ':' || v.display_order,
                                    ',' ORDER BY mi.id, v.id)
                                FROM menu_item mi
                                JOIN menu_item_variant v ON v.menu_item_id = mi.id
                                    AND v.deleted_at IS NULL AND v.available
                                WHERE mi.deleted_at IS NULL AND mi.available
                            ), '')
                            || '|' ||
                            COALESCE((
                                SELECT string_agg(
                                    og.id || ':' || og.name || ':' || ov.id || ':' || ov.name || ':' || ov.display_order,
                                    ',' ORDER BY og.id, ov.id)
                                FROM option_group og
                                JOIN option_value ov ON ov.option_group_id = og.id
                                    AND ov.deleted_at IS NULL AND ov.available
                                WHERE og.active
                            ), '')
                        )
                        """)) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    /** Soft-deleted ("Removed") items, for the restore view. */
    public List<MenuItem> listSoftDeletedItems() throws DatabaseException {
        return listItems(false, true);
    }

    private List<MenuItem> listItems(boolean availableOnly, boolean deletedOnly) throws DatabaseException {
        String where = deletedOnly ? "mi.deleted_at IS NOT NULL"
            : "mi.deleted_at IS NULL" + (availableOnly ? " AND mi.available = TRUE" : "");
        String sql = """
            SELECT mi.id, mi.category_id, mi.name, mi.description, mi.sized, mi.available, mi.display_order,
                   mi.option_group_id,
                   v.id AS variant_id, v.size_label, v.price, v.available AS variant_available, v.display_order AS variant_order
            FROM menu_item mi
            LEFT JOIN menu_item_variant v ON v.menu_item_id = mi.id AND v.deleted_at IS NULL
                %s
            WHERE %s
            ORDER BY mi.category_id, mi.display_order, mi.name, v.display_order
            """.formatted(availableOnly ? "AND v.available = TRUE" : "", where);

        return db.inReadOnly(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                return assembleItems(rs);
            }
        });
    }

    /** Assembles rows into items, attaching each item's option group (looked up separately —
     *  a straight join here would cross-multiply against the variant rows already joined). */
    private List<MenuItem> assembleItems(ResultSet rs) throws SQLException, DatabaseException {
        Map<Integer, MenuItem> byId = new LinkedHashMap<>();
        Map<Integer, List<MenuItemVariant>> variantsByItem = new LinkedHashMap<>();
        Map<Integer, Integer> optionGroupIdByItem = new LinkedHashMap<>();

        while (rs.next()) {
            int itemId = rs.getInt("id");
            variantsByItem.computeIfAbsent(itemId, k -> new ArrayList<>());
            int variantId = rs.getInt("variant_id");
            if (!rs.wasNull()) {
                variantsByItem.get(itemId).add(new MenuItemVariant(
                    variantId,
                    itemId,
                    rs.getString("size_label"),
                    Money.of(rs.getBigDecimal("price")),
                    rs.getBoolean("variant_available"),
                    rs.getInt("variant_order")
                ));
            }
            if (!byId.containsKey(itemId)) {
                int optionGroupId = rs.getInt("option_group_id");
                if (!rs.wasNull()) {
                    optionGroupIdByItem.put(itemId, optionGroupId);
                }
                byId.put(itemId, new MenuItem(
                    itemId,
                    rs.getInt("category_id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getBoolean("sized"),
                    rs.getBoolean("available"),
                    rs.getInt("display_order"),
                    variantsByItem.get(itemId),
                    null
                ));
            }
        }

        // Reads via the separate read-only connection (db.inReadOnly), so this is only
        // safe to call from within a transaction that has NOT itself just set
        // option_group_id on one of these items in the same transaction (none of the
        // call sites below do — that's a separate setItemOptionGroup call).
        Map<Integer, OptionGroup> groupsById = new LinkedHashMap<>();
        if (!optionGroupIdByItem.isEmpty()) {
            for (OptionGroup g : listOptionGroups(true)) {
                groupsById.put(g.id(), g);
            }
        }

        // Rebuild with the now-complete variant lists and resolved option group (records are immutable).
        List<MenuItem> result = new ArrayList<>();
        for (MenuItem mi : byId.values()) {
            Integer groupId = optionGroupIdByItem.get(mi.id());
            OptionGroup group = groupId == null ? null : groupsById.get(groupId);
            result.add(new MenuItem(mi.id(), mi.categoryId(), mi.name(), mi.description(),
                mi.sized(), mi.available(), mi.displayOrder(), variantsByItem.get(mi.id()), group));
        }
        return result;
    }

    /** Creates a menu item with its size/price variants in one transaction. */
    public MenuItem createMenuItem(int categoryId, String name, String description, boolean sized,
                                    List<String> sizeLabels, List<Money> prices) throws DatabaseException {
        return db.inTransaction(conn -> {
            int itemId;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO menu_item (category_id, name, description, sized)
                    VALUES (?, ?, ?, ?) RETURNING id
                    """)) {
                ps.setInt(1, categoryId);
                ps.setString(2, name.trim());
                ps.setString(3, description);
                ps.setBoolean(4, sized);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    itemId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO menu_item_variant (menu_item_id, size_label, price, display_order)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < sizeLabels.size(); i++) {
                    ps.setInt(1, itemId);
                    String label = sized ? sizeLabels.get(i) : null;
                    if (label == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, label);
                    ps.setBigDecimal(3, prices.get(i).asBigDecimal());
                    ps.setInt(4, (i + 1) * 10);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return findItemById(conn, itemId);
        });
    }

    public void updateMenuItem(int itemId, String name, String description) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE menu_item SET name = ?, description = ? WHERE id = ?")) {
                ps.setString(1, name.trim());
                ps.setString(2, description);
                ps.setInt(3, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void updateVariantPrice(int variantId, Money price) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE menu_item_variant SET price = ? WHERE id = ?")) {
                ps.setBigDecimal(1, price.asBigDecimal());
                ps.setInt(2, variantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setItemAvailable(int itemId, boolean available) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE menu_item SET available = ? WHERE id = ?")) {
                ps.setBoolean(1, available);
                ps.setInt(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** True if this item appears in any saved order — used only to word the confirm prompt. */
    public boolean hasOrderHistory(int itemId) throws DatabaseException {
        return db.inReadOnly(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM order_line WHERE menu_item_id = ?)")) {
                ps.setInt(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
    }

    public enum DeleteOutcome { HARD_DELETED, SOFT_DELETED }

    /**
     * Atomically hard-deletes the item if it has no order history, otherwise soft-deletes it.
     * No time-of-check/time-of-use race: the guard is inside the same DELETE.
     */
    public DeleteOutcome deleteMenuItem(int itemId) throws DatabaseException {
        return db.inTransaction(conn -> {
            boolean hardDeleted;
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM menu_item mi
                    WHERE mi.id = ?
                      AND NOT EXISTS (SELECT 1 FROM order_line ol WHERE ol.menu_item_id = ?)
                    RETURNING mi.id
                    """)) {
                ps.setInt(1, itemId);
                ps.setInt(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    hardDeleted = rs.next();
                }
            }
            if (!hardDeleted) {
                try (PreparedStatement ps1 = conn.prepareStatement("""
                        UPDATE menu_item SET deleted_at = now(), available = FALSE
                        WHERE id = ? AND deleted_at IS NULL
                        """)) {
                    ps1.setInt(1, itemId);
                    if (ps1.executeUpdate() == 0) {
                        throw new DatabaseException("Menu item not found.");
                    }
                }
                try (PreparedStatement ps2 = conn.prepareStatement("""
                        UPDATE menu_item_variant SET deleted_at = now(), available = FALSE
                        WHERE menu_item_id = ? AND deleted_at IS NULL
                        """)) {
                    ps2.setInt(1, itemId);
                    ps2.executeUpdate();
                }
            }
            return hardDeleted ? DeleteOutcome.HARD_DELETED : DeleteOutcome.SOFT_DELETED;
        });
    }

    public void restoreMenuItem(int itemId) throws DatabaseException {
        db.inTransaction(conn -> {
            try (PreparedStatement ps1 = conn.prepareStatement(
                    "UPDATE menu_item SET deleted_at = NULL, available = TRUE WHERE id = ?")) {
                ps1.setInt(1, itemId);
                ps1.executeUpdate();
            }
            try (PreparedStatement ps2 = conn.prepareStatement(
                    "UPDATE menu_item_variant SET deleted_at = NULL, available = TRUE WHERE menu_item_id = ?")) {
                ps2.setInt(1, itemId);
                ps2.executeUpdate();
            }
            return null;
        });
    }

    private MenuItem findItemById(Connection conn, int itemId) throws SQLException, DatabaseException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT mi.id, mi.category_id, mi.name, mi.description, mi.sized, mi.available, mi.display_order,
                       mi.option_group_id,
                       v.id AS variant_id, v.size_label, v.price, v.available AS variant_available, v.display_order AS variant_order
                FROM menu_item mi
                LEFT JOIN menu_item_variant v ON v.menu_item_id = mi.id AND v.deleted_at IS NULL
                WHERE mi.id = ?
                ORDER BY v.display_order
                """)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MenuItem> items = assembleItems(rs);
                return items.isEmpty() ? null : items.get(0);
            }
        }
    }

    private Category mapCategory(ResultSet rs) throws SQLException {
        return new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("display_order"), rs.getBoolean("active"));
    }
}
