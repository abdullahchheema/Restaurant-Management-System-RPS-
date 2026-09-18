package rps.db;

import java.util.ArrayList;
import java.util.List;

/** Seed data for a fresh install: categories and a realistic Royal Pizza Sahowala menu. */
final class Seed {

    private Seed() {}

    static List<String> statements() {
        List<String> sql = new ArrayList<>();

        sql.add("""
            INSERT INTO category (name, display_order) VALUES
                ('Pizzas', 10), ('Shawarmas', 20), ('Burgers', 30),
                ('Fries', 40), ('Drinks', 50), ('Desserts', 60)
            """);

        // Sized items: Pizzas, Shawarmas (deal), Burgers (single/double) get size variants.
        // Single-price items: Fries, Drinks, Desserts get one variant with a NULL size label.

        addSizedItem(sql, "Pizzas", "Chicken Tikka Pizza", 10,
            new String[]{"Small", "Medium", "Large"}, new String[]{"799.00", "1299.00", "1799.00"});
        addSizedItem(sql, "Pizzas", "Fajita Pizza", 20,
            new String[]{"Small", "Medium", "Large"}, new String[]{"799.00", "1299.00", "1799.00"});
        addSizedItem(sql, "Pizzas", "Cheese Lovers Pizza", 30,
            new String[]{"Small", "Medium", "Large"}, new String[]{"699.00", "1099.00", "1499.00"});
        addSizedItem(sql, "Pizzas", "Pepperoni Pizza", 40,
            new String[]{"Small", "Medium", "Large"}, new String[]{"849.00", "1349.00", "1899.00"});
        addSizedItem(sql, "Pizzas", "Veggie Supreme Pizza", 50,
            new String[]{"Small", "Medium", "Large"}, new String[]{"699.00", "1099.00", "1499.00"});

        addSizedItem(sql, "Shawarmas", "Chicken Shawarma Roll", 10,
            new String[]{"Regular", "Large"}, new String[]{"250.00", "350.00"});
        addSizedItem(sql, "Shawarmas", "Zinger Shawarma Roll", 20,
            new String[]{"Regular", "Large"}, new String[]{"280.00", "380.00"});
        addSingleItem(sql, "Shawarmas", "Shawarma Platter", 30, "550.00");

        addSizedItem(sql, "Burgers", "Zinger Burger", 10,
            new String[]{"Single", "Double"}, new String[]{"450.00", "699.00"});
        addSizedItem(sql, "Burgers", "Beef Burger", 20,
            new String[]{"Single", "Double"}, new String[]{"420.00", "650.00"});
        addSingleItem(sql, "Burgers", "Grilled Chicken Burger", 30, "480.00");

        addSingleItem(sql, "Fries", "Regular Fries", 10, "199.00");
        addSingleItem(sql, "Fries", "Peri Peri Fries", 20, "249.00");
        addSingleItem(sql, "Fries", "Loaded Cheese Fries", 30, "349.00");

        addSingleItem(sql, "Drinks", "Coca-Cola", 10, "120.00");
        addSingleItem(sql, "Drinks", "Sprite", 20, "120.00");
        addSingleItem(sql, "Drinks", "Mineral Water", 30, "80.00");
        addSingleItem(sql, "Drinks", "Fresh Lemonade", 40, "180.00");

        addSingleItem(sql, "Desserts", "Chocolate Lava Cake", 10, "320.00");
        addSingleItem(sql, "Desserts", "Kunafa Slice", 20, "380.00");
        addSingleItem(sql, "Desserts", "Ice Cream Cup", 30, "220.00");

        return sql;
    }

    private static void addSizedItem(List<String> sql, String category, String name, int order,
                                      String[] sizes, String[] prices) {
        sql.add(String.format("""
            INSERT INTO menu_item (category_id, name, sized, display_order)
            SELECT id, '%s', TRUE, %d FROM category WHERE name = '%s'
            """, escape(name), order, escape(category)));
        for (int i = 0; i < sizes.length; i++) {
            sql.add(String.format("""
                INSERT INTO menu_item_variant (menu_item_id, size_label, price, display_order)
                SELECT mi.id, '%s', %s, %d
                FROM menu_item mi JOIN category c ON c.id = mi.category_id
                WHERE mi.name = '%s' AND c.name = '%s'
                """, escape(sizes[i]), prices[i], (i + 1) * 10, escape(name), escape(category)));
        }
    }

    private static void addSingleItem(List<String> sql, String category, String name, int order, String price) {
        sql.add(String.format("""
            INSERT INTO menu_item (category_id, name, sized, display_order)
            SELECT id, '%s', FALSE, %d FROM category WHERE name = '%s'
            """, escape(name), order, escape(category)));
        sql.add(String.format("""
            INSERT INTO menu_item_variant (menu_item_id, size_label, price, display_order)
            SELECT mi.id, NULL, %s, 10
            FROM menu_item mi JOIN category c ON c.id = mi.category_id
            WHERE mi.name = '%s' AND c.name = '%s'
            """, price, escape(name), escape(category)));
    }

    private static String escape(String s) {
        return s.replace("'", "''");
    }
}
