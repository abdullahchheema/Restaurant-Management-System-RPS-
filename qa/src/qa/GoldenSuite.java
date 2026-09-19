package qa;

import rps.db.Db;
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
            o5.paymentStatus() == PaymentStatus.PAID && o5.totals().changeDue().isZero(),
            o5.paymentStatus() + " change=" + o5.totals().changeDue());

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
            o7.paymentStatus() == PaymentStatus.PARTIALLY_PAID
                && o7.totals().balanceDue().equals(o7.totals().total().subtract(Money.of("1"))),
            o7.paymentStatus() + " balance=" + o7.totals().balanceDue());

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

        boolean cancelTwice = orderDao.updateFulfilment(o3.id(), o3.fulfilmentStatus(), FulfilmentStatus.CANCELLED, 1, false);
        boolean cancelAgain = orderDao.updateFulfilment(o3.id(), o3.fulfilmentStatus(), FulfilmentStatus.CANCELLED, 1, false);
        check("TEST009", "Double-cancel: 2nd is rejected by optimistic guard",
            cancelTwice && !cancelAgain, "first=" + cancelTwice + " second=" + cancelAgain);

        // ================================================== CANCELLATION
        section("TEST 010  Cancellation");
        // Cancelling now deletes the order outright rather than marking it CANCELLED and
        // keeping the row -- a voided order leaves no trace anywhere in the system.
        Order o3After = orderDao.loadOrderForPrint(o3.id());
        check("TEST010", "Cancelled order is gone, not marked CANCELLED",
            o3After == null, o3After == null ? "gone" : "" + o3After.fulfilmentStatus());

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
            oFull.totals().total().isZero() && oFull.paymentStatus() == PaymentStatus.PAID,
            "total=" + oFull.totals().total() + " status=" + oFull.paymentStatus());

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
            String k = r.kitchenTicket(oRec).body();
            int w = width;
            check("TEST026-" + width, "Kitchen ticket fits " + width + "-col roll",
                k.lines().allMatch(l -> l.length() <= w),
                "maxK=" + k.lines().mapToInt(String::length).max().orElse(0));
        }
        ReceiptRenderer r48 = new ReceiptRenderer(48);
        check("TEST027", "Ticket total == DB total",
            r48.kitchenTicket(oRec).body().contains(oRec.totals().total().format()),
            "want " + oRec.totals().total().format());
        check("TEST028", "Kitchen ticket carries the order note",
            r48.kitchenTicket(oRec).body().contains("no onions"), "");
        // The order number now prints as an oversized headline rather than inside the
        // body text, so it must survive as its own field -- a ticket whose headline went
        // missing is one the kitchen cannot identify at a glance, which is the whole
        // point of the change.
        check("TEST028h", "Headline carries the day's order sequence",
            r48.kitchenTicket(oRec).headline()
                .equals("#" + oRec.orderNumber().substring(oRec.orderNumber().lastIndexOf('-') + 1)),
            r48.kitchenTicket(oRec).headline());

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
            String kw = rw.kitchenTicket(wideOrder).body();
            int worst = kw.lines().mapToInt(String::length).max().orElse(0);
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
                var doc = new ReceiptRenderer(48).kitchenTicket(oRec);
                // Headline included: it runs through toUpperCase, which is exactly the
                // call that turns "i" into "İ" under tr-TR.
                String receipt = doc.headline() + "\n" + doc.body();
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
        var filter = new OrderDao.OrderFilter(null, null, null, today, today);
        var summary = orderDao.dailySummary(filter);
        // business_date=today's DATE LITERAL, not CURRENT_DATE: the app's own business
        // day runs 4am-to-4am (OrderDao#businessDate), so the two disagree for roughly
        // four hours after midnight -- reproduced live when this suite happened to run at
        // 00:12, where CURRENT_DATE had already rolled over to the new calendar date but
        // `today` correctly had not. Binding the same value the DAO itself uses is what
        // keeps this test meaningful at every hour, not just outside that window.
        String todayLiteral = "'" + today + "'";
        Money rawRevenue = money(db,
            "SELECT COALESCE(SUM(total),0) FROM customer_order WHERE business_date=" + todayLiteral
                + " AND payment_status='PAID' AND fulfilment_status<>'CANCELLED'");
        check("TEST029", "dailySummary revenue == raw SQL",
            summary.revenue().equals(rawRevenue), "dao=" + summary.revenue() + " sql=" + rawRevenue);

        Money rawOutstanding = money(db,
            "SELECT COALESCE(SUM(total-amount_paid),0) FROM customer_order WHERE business_date=" + todayLiteral
                + " AND payment_status IN ('UNPAID','PARTIALLY_PAID') AND fulfilment_status<>'CANCELLED'");
        check("TEST030", "dailySummary outstanding == raw SQL",
            summary.outstanding().equals(rawOutstanding), "dao=" + summary.outstanding() + " sql=" + rawOutstanding);

        // A cancelled order is excluded from order_count too, not just the money figures
        // -- a voided order was never really "an order" from the shop's point of view, so
        // Total Orders reads as a count of real trade rather than a count of table rows.
        long rawCount = count(db, "SELECT count(*) FROM customer_order "
            + "WHERE business_date=" + todayLiteral + " AND fulfilment_status<>'CANCELLED'");
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
        orderDao.updateFulfilment(oSm.id(), FulfilmentStatus.PENDING, FulfilmentStatus.CANCELLED, 1, false);

        // Rejected loudly rather than with a quiet false, unlike TEST036 below: an
        // already-settled order is a benign no-op, but taking cash against a cancelled
        // order is a mistake the cashier has to be told about. Cancelling now deletes the
        // row outright, so this rejects with "order no longer exists" rather than a
        // cancelled-specific message — either way, no payment can land on a voided order.
        expectReject("TEST034", "A cancelled (deleted) order refuses further payment",
            () -> orderDao.recordPayment(oSm.id(), Money.of("100"), 1));

        boolean illegalAccepted;
        String how;
        try {
            illegalAccepted = orderDao.updateFulfilment(oSm.id(), FulfilmentStatus.CANCELLED, FulfilmentStatus.COMPLETED, 1, false);
            how = illegalAccepted ? "ACCEPTED" : "returned false";
        } catch (Exception e) {
            illegalAccepted = false;
            how = "threw: " + e.getMessage();
        }
        check("TEST035", "CANCELLED -> PAYMENT_RECEIVED is an ILLEGAL transition",
            !illegalAccepted, how);
        if (illegalAccepted) {
            orderDao.updateFulfilment(oSm.id(), FulfilmentStatus.PENDING, FulfilmentStatus.CANCELLED, 1, false);
        }

        // Legal transitions must still work.
        OrderDraft dLegal = draft(OrderType.TAKEAWAY);
        dLegal.addLine(line(itemA, 1));
        Order oLegal = track(orderDao.saveOrder(dLegal, 1, "QA"));
        check("TEST035b", "PENDING -> CANCELLED still allowed",
            orderDao.updateFulfilment(oLegal.id(), FulfilmentStatus.PENDING, FulfilmentStatus.CANCELLED, 1, false), "");

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
