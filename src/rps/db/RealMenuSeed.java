package rps.db;

import java.util.ArrayList;
import java.util.List;

/**
 * The real Royal Pizza Sahowala menu (replacing the placeholder demo catalog seeded by
 * migration 6) — transcribed from the shop's current printed trifold menu. Deals have no
 * dedicated schema (menu_item is a single priced thing, not a bundle of other items), so
 * each deal is its own menu item whose name states its full contents — the kitchen ticket
 * and customer receipt print whatever the item's name is, so this is what makes a deal's
 * contents show up on both without any schema change. See migration 9's comment.
 */
final class RealMenuSeed {

    private RealMenuSeed() {}

    static List<String> statements() {
        List<String> sql = new ArrayList<>();

        sql.add("""
            INSERT INTO category (name, display_order) VALUES
                ('Pizzas', 10), ('Special Pizzas', 20), ('Sides', 30), ('Burgers', 40),
                ('Burger Deals', 50), ('Shawarma', 60), ('Drinks', 70), ('Chicken Broast', 80),
                ('Chicken Broast Deals', 90), ('Pizza Deals', 100), ('Extra Topping', 110)
            """);

        String[] classicSizes = {"Pan 7\"", "Small 10\"", "Medium 12\"", "Large 14\""};
        String[] classicPrices = {"600.00", "1000.00", "1450.00", "1799.00"};
        addSizedItem(sql, "Pizzas", "Tikka Pizza", 10, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "BBQ Pizza", 20, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "Chicken Fajita Pizza", 30, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "Chicken Mexican Pizza", 40, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "New Chicken Achari Pizza", 50, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "Bonfire Pizza", 60, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "Hot & Spicy Pizza", 70, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "Smoke Pizza", 80, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "New Tandoori Pizza", 90, classicSizes, classicPrices);
        addSizedItem(sql, "Pizzas", "New Super Supreme Pizza", 100, classicSizes, classicPrices);

        String[] specialSizes = {"Small 10\"", "Medium 12\"", "Large 14\""};
        String[] specialPrices = {"1100.00", "1550.00", "1999.00"};
        addSizedItem(sql, "Special Pizzas", "Royal Kabab Crust Pizza", 10, specialSizes, specialPrices);
        addSizedItem(sql, "Special Pizzas", "Malai Donner Pizza", 20, specialSizes, specialPrices);
        addSizedItem(sql, "Special Pizzas", "Hot & Spicy Donner", 30, specialSizes, specialPrices);
        addSizedItem(sql, "Special Pizzas", "Malai Pizza", 40, specialSizes, specialPrices);
        addSizedItem(sql, "Special Pizzas", "New Crown Crust Pizza", 50, specialSizes, specialPrices);
        addSizedItem(sql, "Special Pizzas", "American Heat", 60, specialSizes, specialPrices);

        addSingleItem(sql, "Sides", "Chicken Cheese Stick", 10, "600.00");
        addSingleItem(sql, "Sides", "5 Pcs Chicken Nuggets", 20, "300.00");
        addSingleItem(sql, "Sides", "10 Pcs Chicken Nuggets", 30, "500.00");
        addSingleItem(sql, "Sides", "5 Pcs Hot Wings", 40, "350.00");
        addSingleItem(sql, "Sides", "10 Pcs Hot Wings", 50, "600.00");
        addSingleItem(sql, "Sides", "New 10 Pcs BBQ Wings", 60, "600.00");
        addSingleItem(sql, "Sides", "Regular Fries", 70, "200.00");
        addSingleItem(sql, "Sides", "Large Fries", 80, "250.00");
        addSingleItem(sql, "Sides", "Loaded Fries", 90, "700.00");
        addSingleItem(sql, "Sides", "Mayo Dip", 100, "50.00");

        addSingleItem(sql, "Burgers", "Zinger Burger", 10, "350.00");
        addSingleItem(sql, "Burgers", "Double Dacker Burger", 20, "600.00");
        addSingleItem(sql, "Burgers", "New Chicken Grill Burger", 30, "350.00");
        addSingleItem(sql, "Burgers", "Thunder Burger", 40, "400.00");
        addSingleItem(sql, "Burgers", "New Chicken Mighty Burger", 50, "400.00");
        addSingleItem(sql, "Burgers", "Chicken Patty Burger", 60, "300.00");

        addSingleItem(sql, "Burger Deals", "4 Zinger Burgers + 1 Ltr Drink", 10, "1400.00");
        addSingleItem(sql, "Burger Deals", "4 Chicken Grill Burgers + 1 Ltr Drink", 20, "1400.00");
        addSingleItem(sql, "Burger Deals", "4 Chicken Mighty Burgers + 1 Ltr Drink", 30, "1600.00");

        addSingleItem(sql, "Shawarma", "Regular Shawarma", 10, "250.00");
        addSingleItem(sql, "Shawarma", "Open Shawarma", 20, "500.00");
        addSingleItem(sql, "Shawarma", "Special Grill Shawarma", 30, "400.00");
        addSingleItem(sql, "Shawarma", "Special Cheese Grill Shawarma", 40, "450.00");
        addSingleItem(sql, "Shawarma", "Zinger Shawarma", 50, "350.00");
        addSingleItem(sql, "Shawarma", "Chicken Seekh Kabab Shawarma", 60, "400.00");

        addSingleItem(sql, "Drinks", "Regular Drink", 10, "80.00");
        addSingleItem(sql, "Drinks", "1.5 Ltr Drink", 20, "220.00");
        addSingleItem(sql, "Drinks", "Mineral Water (Small)", 30, "60.00");
        addSingleItem(sql, "Drinks", "Mineral Water (Large)", 40, "110.00");
        addSingleItem(sql, "Drinks", "1 Ltr Drink", 50, "160.00");

        addSingleItem(sql, "Chicken Broast", "1 Pc Fried Chicken", 10, "250.00");
        addSingleItem(sql, "Chicken Broast", "3 Pcs Fried Chicken", 20, "700.00");
        addSingleItem(sql, "Chicken Broast", "5 Pcs Fried Chicken", 30, "1200.00");
        addSingleItem(sql, "Chicken Broast", "10 Pcs Fried Chicken", 40, "2000.00");

        addSingleItem(sql, "Chicken Broast Deals",
            "Deal 1: 2 Pcs Fried Chicken + 1 Reg Fries + 1 Reg Drink", 10, "700.00");
        addSingleItem(sql, "Chicken Broast Deals",
            "Deal 2: 4 Pcs Fried Chicken + 2 Reg Fries + 2 Reg Drinks", 20, "1450.00");
        addSingleItem(sql, "Chicken Broast Deals",
            "Deal 3: 9 Pcs Fried Chicken + 4 Reg Fries + 1.5 Ltr Drink", 30, "2800.00");
        addSingleItem(sql, "Chicken Broast Deals",
            "Deal 4: 2 Zinger Burgers + 2 Pcs Fried Chicken + 2 Reg Drinks", 40, "1200.00");

        addSingleItem(sql, "Pizza Deals", "Pizza Deal 1: 2 Pan Pizza + 2 Reg Drink", 10, "1200.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 2: 1 Small Pizza + 2 Reg Drink + 5 Hot Wings", 20, "1300.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 3: 2 Medium Pizza + 1.5 Ltr Drink", 30, "2850.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 4: 1 Large Pizza + 1.5 Ltr Drink + 10 Hot Wings", 40, "2300.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 5: 1 Medium Pizza + 10 Hot Wings + 1 Ltr Drink", 50, "2150.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 6: 2 Large Pizza", 60, "3300.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 7: 2 Large Donar Pizza", 70, "3600.00");
        addSingleItem(sql, "Pizza Deals", "Pizza Deal 8: 2 Small Pizza", 80, "1800.00");
        addSingleItem(sql, "Pizza Deals",
            "Heavy Deal: 2 Chicken Burger + 2 Zinger Burger + 2 Thunder Burger + 1 Cheese Stick + 1.5 Ltr Drink",
            90, "2300.00");

        addSingleItem(sql, "Extra Topping", "Extra Topping (Small)", 10, "100.00");
        addSingleItem(sql, "Extra Topping", "Extra Topping (Medium)", 20, "150.00");
        addSingleItem(sql, "Extra Topping", "Extra Topping (Large)", 30, "200.00");

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
                WHERE mi.name = '%s' AND c.name = '%s' AND mi.deleted_at IS NULL
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
            WHERE mi.name = '%s' AND c.name = '%s' AND mi.deleted_at IS NULL
            """, price, escape(name), escape(category)));
    }

    private static String escape(String s) {
        return s.replace("'", "''");
    }
}
