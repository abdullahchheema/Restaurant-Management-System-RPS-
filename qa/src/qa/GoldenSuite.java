package qa;

import rps.db.Db;
import rps.db.DeliveryDao;
import rps.db.MenuDao;
import rps.db.OrderDao;
import rps.db.ReportsDao;
import rps.model.*;
import rps.print.ReceiptRenderer;
import rps.util.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static qa.QA.*;

/**
 * TEST 001-0xx — the permanent regression suite (§43), expanded to this architecture.
 * Runs against rms_test only. Baseline run happens BEFORE any code is modified.
 */
public final class GoldenSuite {

    public static void main(String[] args) throws Exception {
        Db db = Db.get();
        MenuDao menuDao = new MenuDao(db);
        OrderDao orderDao = new OrderDao(db);
        DeliveryDao deliveryDao = new DeliveryDao(db);
        ReportsDao reportsDao = new ReportsDao(db);

        List<MenuItem> menu = items(menuDao, 3);
        MenuItem itemA = menu.get(0), itemB = menu.get(1), itemC = menu.get(2);
        Money pA = itemA.variants().get(0).price();

        int before = (int) count(db, "SELECT count(*) FROM customer_order");

        // ================================================== ORDER CREATION
        section("TEST 001-004  Order creation, all three types");

        OrderDraft d1 = draft(OrderType.DINE_IN);
        d1.addLine(line(itemA, 1));
        Order o1 = track(orderDao.saveOrder(d1, 1, "QA"));
        check("TEST001", "Dine-in, one item",
            o1.lines().size() == 1 && o1.totals().total().equals(pA), "total=" + o1.totals().total());

        OrderDraft d2 = draft(OrderType.DINE_IN);
        d2.addLine(line(itemA, 2));
        d2.addLine(line(itemB, 3));
        Order o2 = track(orderDao.saveOrder(d2, 1, "QA"));
        Money expect2 = itemA.variants().get(0).price().multiply(2)
            .add(itemB.variants().get(0).price().multiply(3));
        check("TEST002", "Dine-in, multiple items+qty",
            o2.totals().total().equals(expect2), "got " + o2.totals().total() + " want " + expect2);

        OrderDraft d3 = draft(OrderType.TAKEAWAY);
        d3.addLine(line(itemA, 1));
        Order o3 = track(orderDao.saveOrder(d3, 1, "QA"));
        check("TEST003", "Takeaway order", o3.type() == OrderType.TAKEAWAY, "");

        OrderDraft d4 = draft(OrderType.DELIVERY);
        d4.addLine(line(itemA, 1));
        Order o4 = track(orderDao.saveOrder(d4, 1, "QA"));
        check("TEST004", "Delivery order", o4.type() == OrderType.DELIVERY
            && o4.deliveryAddress() != null, "");

        // ================================================== CASH
        section("TEST 005-007  Cash payment");

        OrderDraft d5 = draft(OrderType.TAKEAWAY);
        d5.addLine(line(itemA, 1));
        d5.setCashTendered(pA);
        Order o5 = track(orderDao.saveOrder(d5, 1, "QA"));
        check("TEST005", "Exact cash -> Payment Received, change 0",
            o5.status() == OrderStatus.PAYMENT_RECEIVED && o5.totals().changeDue().isZero(),
            o5.status() + " change=" + o5.totals().changeDue());

        OrderDraft d6 = draft(OrderType.TAKEAWAY);
        d6.addLine(line(itemA, 1));
        d6.setCashTendered(pA.add(Money.of("500")));
        Order o6 = track(orderDao.saveOrder(d6, 1, "QA"));
        check("TEST006", "Overpayment -> correct change, amount_paid capped",
            o6.totals().changeDue().equals(Money.of("500"))
                && o6.totals().amountPaid().equals(o6.totals().total()),
            "change=" + o6.totals().changeDue() + " paid=" + o6.totals().amountPaid());

        OrderDraft d7 = draft(OrderType.TAKEAWAY);
        d7.addLine(line(itemA, 1));
        d7.setCashTendered(Money.of("1"));
        Order o7 = track(orderDao.saveOrder(d7, 1, "QA"));
        check("TEST007", "Underpayment -> NOT settled (Partially Paid)",
            o7.status() == OrderStatus.PARTIALLY_PAID
                && o7.totals().balanceDue().equals(o7.totals().total().subtract(Money.of("1"))),
            o7.status() + " balance=" + o7.totals().balanceDue());

        // ================================================== DOUBLE SUBMIT
        section("TEST 008-009  Duplicate submission (§7)");

        OrderDraft d8 = draft(OrderType.TAKEAWAY);
        d8.addLine(line(itemA, 1));
        Order first = track(orderDao.saveOrder(d8, 1, "QA"));
        Order second = track(orderDao.saveOrder(d8, 1, "QA"));   // same draft submitted twice
        check("TEST008", "saveOrder is NOT idempotent (2 clicks = 2 orders)",
            first.id() != second.id(),
            "DAO has no idempotency key -> UI MUST prevent the 2nd call. ids "
                + first.id() + "/" + second.id());

        boolean cancelTwice = orderDao.updateStatus(o3.id(), o3.status(), OrderStatus.CANCELLED, 1);
        boolean cancelAgain = orderDao.updateStatus(o3.id(), o3.status(), OrderStatus.CANCELLED, 1);
        check("TEST009", "Double-cancel: 2nd is rejected by optimistic guard",
            cancelTwice && !cancelAgain, "first=" + cancelTwice + " second=" + cancelAgain);

        // ================================================== CANCELLATION
        section("TEST 010  Cancellation");
        Order o3After = orderDao.loadOrderForPrint(o3.id());
        check("TEST010", "Cancelled order holds CANCELLED",
            o3After.status() == OrderStatus.CANCELLED, "" + o3After.status());

        // ================================================== HISTORICAL PRICE
        section("TEST 011-012  Menu price change must not rewrite history");

        MenuItemVariant vC = itemC.variants().get(0);
        Money originalPrice = vC.price();
        OrderDraft dHist = draft(OrderType.TAKEAWAY);
        dHist.addLine(line(vC, itemC.name(), 1));
        Order histOrder = track(orderDao.saveOrder(dHist, 1, "QA"));
        Money pricedAt = histOrder.lines().get(0).unitPrice();

        menuDao.updateVariantPrice(vC.id(), originalPrice.add(Money.of("999")));
        Order histAfter = orderDao.loadOrderForPrint(histOrder.id());
        check("TEST011", "Menu price change applies to the menu",
            !menuDao.listAvailableItems().stream().filter(m -> m.id() == itemC.id())
                .findFirst().orElseThrow().variants().get(0).price().equals(originalPrice), "");
        check("TEST012", "Historical order keeps the price actually charged",
            histAfter.lines().get(0).unitPrice().equals(pricedAt)
                && histAfter.totals().total().equals(histOrder.totals().total()),
            "was " + pricedAt + " now " + histAfter.lines().get(0).unitPrice());
        menuDao.updateVariantPrice(vC.id(), originalPrice);   // restore

        // ================================================== VALIDATION / NEGATIVE
        section("TEST 013-018  Negative & boundary input (§6, §29)");

        expectReject("TEST013", "Empty order rejected", () -> {
            OrderDraft e = draft(OrderType.TAKEAWAY);
            orderDao.saveOrder(e, 1, "QA");
        });

        expectReject("TEST014", "Dine-in without table number rejected", () -> {
            OrderDraft e = new OrderDraft();
            e.setType(OrderType.DINE_IN);
            e.addLine(line(itemA, 1));
            orderDao.saveOrder(e, 1, "QA");
        });

        expectReject("TEST015", "Delivery without address rejected", () -> {
            OrderDraft e = new OrderDraft();
            e.setType(OrderType.DELIVERY);
            e.setCustomerPhone("03001234567");
            e.addLine(line(itemA, 1));
            orderDao.saveOrder(e, 1, "QA");
        });

        expectReject("TEST016", "Takeaway with invalid phone rejected", () -> {
            OrderDraft e = new OrderDraft();
            e.setType(OrderType.TAKEAWAY);
            e.setCustomerPhone("not-a-phone");
            e.addLine(line(itemA, 1));
            orderDao.saveOrder(e, 1, "QA");
        });

        expectReject("TEST017", "Quantity 0 rejected by DB CHECK", () -> {
            OrderDraft e = draft(OrderType.TAKEAWAY);
            e.addLine(line(itemA, 0));
            orderDao.saveOrder(e, 1, "QA");
        });

        expectReject("TEST018", "Quantity -1 rejected by DB CHECK", () -> {
            OrderDraft e = draft(OrderType.TAKEAWAY);
            e.addLine(line(itemA, -1));
            orderDao.saveOrder(e, 1, "QA");
        });

        expectReject("TEST018b", "Quantity 1000 (>999) rejected", () -> {
            OrderDraft e = draft(OrderType.TAKEAWAY);
            e.addLine(line(itemA, 1000));
            orderDao.saveOrder(e, 1, "QA");
        });

        OrderDraft dMax = draft(OrderType.TAKEAWAY);
        dMax.addLine(line(itemA, 999));
        Order oMax = track(orderDao.saveOrder(dMax, 1, "QA"));
        check("TEST018c", "Quantity 999 (max) accepted, total correct",
            oMax.totals().total().equals(pA.multiply(999)), "total=" + oMax.totals().total());

        // ================================================== DISCOUNT MATH
        section("TEST 019-022  Discount & rounding (§9)");

        OrderDraft dPct = draft(OrderType.TAKEAWAY);
        dPct.addLine(line(itemA, 3));
        dPct.setDiscount(DiscountMode.PERCENT, new BigDecimal("33.33"));
        Order oPct = track(orderDao.saveOrder(dPct, 1, "QA"));
        Money sub = pA.multiply(3);
        check("TEST019", "33.33% discount rounds to 2dp, equation holds",
            oPct.totals().total().equals(sub.subtract(oPct.totals().discountTotal()))
                && oPct.totals().discountTotal().asBigDecimal().scale() <= 2,
            "sub=" + sub + " disc=" + oPct.totals().discountTotal() + " total=" + oPct.totals().total());

        OrderDraft dOver = draft(OrderType.TAKEAWAY);
        dOver.addLine(line(itemA, 1));
        dOver.setDiscount(DiscountMode.AMOUNT, pA.asBigDecimal().add(new BigDecimal("10000")));
        Order oOver = track(orderDao.saveOrder(dOver, 1, "QA"));
        check("TEST020", "Discount larger than subtotal is clamped, total >= 0",
            oOver.totals().discountTotal().equals(sub0(oOver)) && !oOver.totals().total().isNegative(),
            "disc=" + oOver.totals().discountTotal() + " total=" + oOver.totals().total());

        OrderDraft dFull = draft(OrderType.TAKEAWAY);
        dFull.addLine(line(itemA, 1));
        dFull.setDiscount(DiscountMode.PERCENT, new BigDecimal("100"));
        Order oFull = track(orderDao.saveOrder(dFull, 1, "QA"));
        check("TEST021", "100% discount -> total 0, settles (not stuck Pending)",
            oFull.totals().total().isZero() && oFull.status() == OrderStatus.PAYMENT_RECEIVED,
            "total=" + oFull.totals().total() + " status=" + oFull.status());

        expectReject("TEST022", "Discount > 100% rejected", () -> {
            OrderDraft e = draft(OrderType.TAKEAWAY);
            e.addLine(line(itemA, 1));
            e.setDiscount(DiscountMode.PERCENT, new BigDecimal("150"));
            orderDao.saveOrder(e, 1, "QA");
        });

        // ================================================== EDIT
        section("TEST 023-025  Order editing");

        OrderDraft dEdit = draft(OrderType.TAKEAWAY);
        dEdit.addLine(line(itemA, 5));
        Order oEdit = track(orderDao.saveOrder(dEdit, 1, "QA"));
        orderDao.recordPayment(oEdit.id(), oEdit.totals().total(), 1);

        OrderDraft dEdit2 = draft(OrderType.TAKEAWAY);
        dEdit2.addLine(line(itemA, 1));
        orderDao.updateOrder(oEdit.id(), dEdit2, 1, "QA");
        Order oEdited = orderDao.loadOrderForPrint(oEdit.id());
        check("TEST023", "Edit lowers total; amount_paid re-capped, never exceeds",
            oEdited.totals().amountPaid().compareTo(oEdited.totals().total()) <= 0,
            "paid=" + oEdited.totals().amountPaid() + " total=" + oEdited.totals().total());
        check("TEST024", "Overpayment after edit shows as change owed",
            oEdited.totals().changeDue().isPositive(), "change=" + oEdited.totals().changeDue());
        check("TEST025", "Edited order keeps its identity (order_number)",
            oEdited.orderNumber().equals(oEdit.orderNumber()), "");

        // ================================================== RECEIPTS
        section("TEST 026-028  Receipt rendering (§26)");

        OrderDraft dRec = draft(OrderType.DELIVERY);
        dRec.addLine(line(itemA, 2));
        dRec.setNotes("QA note: no onions");
        Order oRec = track(orderDao.saveOrder(dRec, 1, "QA"));
        for (int width : new int[]{48, 32}) {
            ReceiptRenderer r = new ReceiptRenderer(width);
            String k = r.kitchenTicket(oRec), c = r.customerReceipt(oRec);
            int w = width;
            check("TEST026-" + width, "Receipts fit " + width + "-col roll",
                k.lines().allMatch(l -> l.length() <= w) && c.lines().allMatch(l -> l.length() <= w),
                "maxK=" + k.lines().mapToInt(String::length).max().orElse(0)
                    + " maxC=" + c.lines().mapToInt(String::length).max().orElse(0));
        }
        ReceiptRenderer r48 = new ReceiptRenderer(48);
        check("TEST027", "Receipt total == DB total",
            r48.customerReceipt(oRec).contains(oRec.totals().total().format()),
            "want " + oRec.totals().total().format());
        check("TEST028", "Kitchen ticket carries the order note",
            r48.kitchenTicket(oRec).contains("no onions"), "");

        // The real catalogue contains combo names near 100 characters ("Heavy Deal: 2
        // Chicken Burger + ..."), which used to be written to the roll unwrapped and were
        // then clipped at the paper edge by the printer -- on the kitchen ticket that
        // means staff cannot see what to cook. Driven from the widest name actually in
        // the menu, so the guard tracks the catalogue rather than a fixture.
        MenuItem widestItem = menuDao.listAvailableItems().stream()
            .filter(m -> !m.variants().isEmpty() && !m.hasOptions())
            .max(java.util.Comparator.comparingInt(m -> m.name().length()))
            .orElseThrow();
        MenuItemVariant widestVariant = widestItem.variants().get(0);
        OrderDraft wideDraft = new OrderDraft();
        wideDraft.setType(OrderType.DELIVERY);
        wideDraft.setCustomerPhone("03001234567");
        // No spaces anywhere: nothing for a word-boundary wrapper to break on.
        wideDraft.setDeliveryAddress("HouseNo123StreetNo45MohallahGulshanEIqbalNearMCBBankSahowalaSialkot");
        wideDraft.addLine(new DraftLine(widestVariant.id(),
            widestItem.name() + " (" + widestVariant.sizeLabel() + ")",
            widestVariant.price(), 99, null, null, null, null));
        Order wideOrder = track(orderDao.saveOrder(wideDraft, 1, "QA"));
        for (int width : new int[]{48, 32}) {
            ReceiptRenderer rw = new ReceiptRenderer(width);
            String kw = rw.kitchenTicket(wideOrder);
            String cw = rw.customerReceipt(wideOrder);
            int worst = Math.max(kw.lines().mapToInt(String::length).max().orElse(0),
                                 cw.lines().mapToInt(String::length).max().orElse(0));
            check("TEST028w-" + width,
                "Longest real menu name + unbreakable address fit " + width + "-col roll",
                worst <= width, "widestName=" + widestItem.name().length() + " worstLine=" + worst);
        }

        // The till inherits whatever locale the client's Windows is set to. Under a
        // locale whose number system isn't Western (ar, bn) an unpinned %d emits Eastern
        // Arabic digits, and under tr the unpinned toUpperCase turns "Pizza" into
        // "PİZZA" — so money, the order number and the receipt are all checked against
        // hostile locales rather than only the en_US the developer happens to run.
        section("TEST 028L  Locale independence");
        Locale originalLocale = Locale.getDefault();
        try {
            for (Locale hostile : new Locale[]{
                    Locale.forLanguageTag("bn-IN"), Locale.forLanguageTag("ar-EG"),
                    Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("tr-TR")}) {
                Locale.setDefault(hostile);
                String rendered = Money.of("1234.50").format();
                String receipt = new ReceiptRenderer(48).customerReceipt(oRec);
                boolean ok = rendered.equals("Rs 1,234.50")
                    && receipt.chars().allMatch(c -> c < 128 || c == '\n');
                check("TEST028L-" + hostile.toLanguageTag(),
                    "Money and receipt unaffected by " + hostile.toLanguageTag() + " locale",
                    ok, "money=" + rendered);
            }
        } finally {
            Locale.setDefault(originalLocale);
        }

        // ================================================== REPORTS
        section("TEST 029-031  Report vs raw DB reconciliation (§27)");

        var today = OrderDao.businessDate(java.time.ZonedDateTime.now());
        var filter = new OrderDao.OrderFilter(null, null, today, today);
        var summary = orderDao.dailySummary(filter);
        Money rawRevenue = money(db,
            "SELECT COALESCE(SUM(total),0) FROM customer_order WHERE business_date=CURRENT_DATE AND status='PAYMENT_RECEIVED'");
        check("TEST029", "dailySummary revenue == raw SQL",
            summary.revenue().equals(rawRevenue), "dao=" + summary.revenue() + " sql=" + rawRevenue);

        Money rawOutstanding = money(db,
            "SELECT COALESCE(SUM(total-amount_paid),0) FROM customer_order WHERE business_date=CURRENT_DATE AND status IN ('PENDING','PARTIALLY_PAID')");
        check("TEST030", "dailySummary outstanding == raw SQL",
            summary.outstanding().equals(rawOutstanding), "dao=" + summary.outstanding() + " sql=" + rawOutstanding);

        long rawCount = count(db, "SELECT count(*) FROM customer_order WHERE business_date=CURRENT_DATE");
        check("TEST031", "dailySummary order count == raw SQL",
            summary.orderCount() == rawCount, "dao=" + summary.orderCount() + " sql=" + rawCount);

        Money staffTotal = Money.ZERO;
        for (var s : reportsDao.staffTotals(today, today)) staffTotal = staffTotal.add(s.revenue());
        check("TEST032", "Reports staffTotals sum == dailySummary revenue",
            staffTotal.equals(summary.revenue()), "staff=" + staffTotal + " summary=" + summary.revenue());

        Money typeTotal = Money.ZERO;
        for (var t : reportsDao.orderTypeMix(today, today)) typeTotal = typeTotal.add(t.revenue());
        check("TEST033", "Reports typeMix sum == dailySummary revenue",
            typeTotal.equals(summary.revenue()), "typeMix=" + typeTotal + " summary=" + summary.revenue());

        // ================================================== STATE MACHINE
        section("TEST 034-036  Status state machine (§11)");

        OrderDraft dSm = draft(OrderType.TAKEAWAY);
        dSm.addLine(line(itemA, 1));
        Order oSm = track(orderDao.saveOrder(dSm, 1, "QA"));
        orderDao.updateStatus(oSm.id(), OrderStatus.PENDING, OrderStatus.CANCELLED, 1);

        check("TEST034", "CANCELLED order refuses further payment",
            !orderDao.recordPayment(oSm.id(), Money.of("100"), 1), "");

        boolean illegalAccepted;
        String how;
        try {
            illegalAccepted = orderDao.updateStatus(oSm.id(), OrderStatus.CANCELLED, OrderStatus.PAYMENT_RECEIVED, 1);
            how = illegalAccepted ? "ACCEPTED" : "returned false";
        } catch (Exception e) {
            illegalAccepted = false;
            how = "threw: " + e.getMessage();
        }
        check("TEST035", "CANCELLED -> PAYMENT_RECEIVED is an ILLEGAL transition",
            !illegalAccepted, how);
        if (illegalAccepted) {
            orderDao.updateStatus(oSm.id(), OrderStatus.PAYMENT_RECEIVED, OrderStatus.CANCELLED, 1);
        }

        // Legal transitions must still work.
        OrderDraft dLegal = draft(OrderType.TAKEAWAY);
        dLegal.addLine(line(itemA, 1));
        Order oLegal = track(orderDao.saveOrder(dLegal, 1, "QA"));
        check("TEST035b", "PENDING -> CANCELLED still allowed",
            orderDao.updateStatus(oLegal.id(), OrderStatus.PENDING, OrderStatus.CANCELLED, 1), "");

        Order settled = orderDao.loadOrderForPrint(o5.id());
        check("TEST036", "Settled order refuses further payment",
            !orderDao.recordPayment(settled.id(), Money.of("50"), 1), "");

        // ================================================== SPECIAL CHARS / INJECTION
        section("TEST 037-039  Special characters & injection (§22, §30)");

        String nasty = "Robert'); DROP TABLE customer_order;-- \" \\ & % $ # @ ! ? ( ) [ ] { } <>";
        OrderDraft dInj = draft(OrderType.DELIVERY);
        dInj.addLine(line(itemA, 1));
        dInj.setDeliveryAddress(nasty + " long enough address");
        dInj.setNotes(nasty);
        dInj.setCustomerName(nasty);
        Order oInj = track(orderDao.saveOrder(dInj, 1, "QA"));
        Order oInjBack = orderDao.loadOrderForPrint(oInj.id());
        check("TEST037", "SQL-injection string stored verbatim, tables intact",
            oInjBack.notes().equals(nasty) && count(db, "SELECT count(*) FROM customer_order") > 0,
            "round-tripped safely");

        String unicode = "پیزا 🍕 café naïve 中文";
        OrderDraft dUni = draft(OrderType.DELIVERY);
        dUni.addLine(line(itemA, 1));
        dUni.setDeliveryAddress(unicode + " some longer address");
        dUni.setNotes(unicode);
        Order oUni = track(orderDao.saveOrder(dUni, 1, "QA"));
        check("TEST038", "Unicode round-trips intact",
            orderDao.loadOrderForPrint(oUni.id()).notes().equals(unicode), "");

        String longText = "x".repeat(5000);
        OrderDraft dLong = draft(OrderType.DELIVERY);
        dLong.addLine(line(itemA, 1));
        dLong.setDeliveryAddress(longText);
        dLong.setNotes(longText);
        Order oLong = track(orderDao.saveOrder(dLong, 1, "QA"));
        check("TEST039", "5000-char address/notes accepted (TEXT columns)",
            orderDao.loadOrderForPrint(oLong.id()).notes().length() == 5000, "");

        // ================================================== DELIVERY RUNS
        section("TEST 040-043  Delivery runs");

        Rider rider = deliveryDao.createRider("QA Rider", "03001112222");
        OrderDraft dr1 = draft(OrderType.DELIVERY); dr1.addLine(line(itemA, 1));
        OrderDraft dr2 = draft(OrderType.DELIVERY); dr2.addLine(line(itemB, 1));
        Order r1 = track(orderDao.saveOrder(dr1, 1, "QA"));
        Order r2 = track(orderDao.saveOrder(dr2, 1, "QA"));
        DeliveryRun run = deliveryDao.startRun(rider.id(), List.of(r1.id(), r2.id()), 1, "QA");
        check("TEST040", "Run expects sum of unpaid balances",
            run.expectedAmount().equals(r1.totals().total().add(r2.totals().total())),
            "expected=" + run.expectedAmount());

        expectReject("TEST041", "Short collection rejected",
            () -> deliveryDao.completeRun(run.id(), Money.of("1"), 1));

        deliveryDao.recordPaymentForOrder(r1.id(), r1.totals().total(), 1);
        check("TEST042", "Per-order collection settles just that order",
            orderDao.loadOrderForPrint(r1.id()).status() == OrderStatus.PAYMENT_RECEIVED
                && orderDao.loadOrderForPrint(r2.id()).status() == OrderStatus.PENDING, "");

        deliveryDao.completeRun(run.id(), deliveryDao.loadRun(run.id()).expectedAmount(), 1);
        check("TEST043", "Completing run settles the remainder",
            orderDao.loadOrderForPrint(r2.id()).status() == OrderStatus.PAYMENT_RECEIVED, "");

        // ================================================== DAILY COUNTER
        section("TEST 044  Order numbering");
        long distinct = count(db, "SELECT count(DISTINCT order_number) FROM customer_order WHERE business_date=CURRENT_DATE");
        long total = count(db, "SELECT count(*) FROM customer_order WHERE business_date=CURRENT_DATE");
        check("TEST044", "All order numbers unique today", distinct == total,
            "distinct=" + distinct + " total=" + total);

        int after = (int) count(db, "SELECT count(*) FROM customer_order");
        System.out.println("\n  Orders created by this run: " + (after - before));

        int violations = integritySweep(db, "after golden suite");
        check("SWEEP", "Zero integrity violations", violations == 0, violations + " violation(s)");

        cleanup(db);
        integritySweep(db, "after cleanup");
        summary("GOLDEN SUITE (baseline)");
        System.exit(QA.failed > 0 ? 1 : 0);
    }

    private static Money sub0(Order o) { return o.totals().subtotal(); }
}
