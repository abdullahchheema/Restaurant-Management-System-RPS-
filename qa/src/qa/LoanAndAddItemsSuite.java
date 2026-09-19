package qa;

import rps.db.Db;
import rps.db.MenuDao;
import rps.db.OrderDao;
import rps.db.ReportsDao;
import rps.model.DraftLine;
import rps.model.FulfilmentStatus;
import rps.model.MenuItem;
import rps.model.Order;
import rps.model.OrderDraft;
import rps.model.OrderType;
import rps.model.PaymentStatus;
import rps.util.Money;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static qa.QA.check;
import static qa.QA.draft;
import static qa.QA.line;
import static qa.QA.section;
import static qa.QA.track;

/**
 * Covers the two behaviours added on 2026-09-19: pay-later (loan) orders, and adding items
 * to an order whose edit window has closed.
 *
 * <p>The loan checks are written against the FIGURES rather than the flag — "does the
 * dashboard stop listing it", "does revenue move by exactly its total", "does outstanding
 * drop by exactly its balance" — because the flag being set is not the thing that would
 * hurt if it broke; the money being counted in two places, or in none, is.
 */
public final class LoanAndAddItemsSuite {

    public static void main(String[] args) throws Exception {
        Db db = Db.get();
        rps.db.Migrations.applyAll(db);
        OrderDao orderDao = new OrderDao(db);
        MenuDao menuDao = new MenuDao(db);
        ReportsDao reportsDao = new ReportsDao(db);
        LocalDate today = OrderDao.businessDate(ZonedDateTime.now());
        OrderDao.OrderFilter todayFilter = OrderDao.OrderFilter.todayAllTypes();

        MenuItem item = QA.anyItem(menuDao);

        // ============================================== PAY LATER
        section("LOAN 001-010  Pay later / loan ledger");

        OrderDao.DailySummary before = orderDao.dailySummary(todayFilter);
        Money revenueBefore = before.revenue();
        Money outstandingBefore = before.outstanding();
        long countBefore = before.orderCount();

        OrderDraft d = draft(OrderType.TAKEAWAY);
        d.addLine(line(item, 2));
        Order unpaid = track(orderDao.saveOrder(d, 1, "QA"));
        check("LOAN001", "A new order starts unpaid and not a loan",
            unpaid.paymentStatus() == PaymentStatus.UNPAID && !unpaid.isOnLoanLedger(),
            unpaid.paymentStatus().label());

        OrderDao.DailySummary afterOrder = orderDao.dailySummary(todayFilter);
        check("LOAN002", "Unpaid order raises outstanding, not revenue",
            afterOrder.revenue().equals(revenueBefore)
                && afterOrder.outstanding().equals(outstandingBefore.add(unpaid.totals().total())),
            "rev " + afterOrder.revenue().format() + " out " + afterOrder.outstanding().format());

        boolean moved = orderDao.moveToLoan(unpaid.id(), 1);
        check("LOAN003", "Order moves to the loan ledger", moved, "");

        Order asLoan = orderDao.loadOrderForPrint(unpaid.id());
        check("LOAN004", "Loan flag and timestamp are set",
            asLoan.isOnLoanLedger() && asLoan.loanAt() != null, String.valueOf(asLoan.loanAt()));
        check("LOAN005", "Payment status is untouched by moving to a loan",
            asLoan.paymentStatus() == PaymentStatus.UNPAID, asLoan.paymentStatus().label());

        List<OrderDao.OrderRow> dash = orderDao.loadOrders(todayFilter);
        check("LOAN006", "Loan order leaves the dashboard list",
            dash.stream().noneMatch(r -> r.order().id() == unpaid.id()),
            dash.size() + " rows listed");

        OrderDao.DailySummary afterLoan = orderDao.dailySummary(todayFilter);
        check("LOAN007", "Loan counts as revenue immediately",
            afterLoan.revenue().equals(revenueBefore.add(unpaid.totals().total())),
            "was " + revenueBefore.format() + " now " + afterLoan.revenue().format());
        check("LOAN008", "Loan clears from outstanding",
            afterLoan.outstanding().equals(outstandingBefore),
            "was " + outstandingBefore.format() + " now " + afterLoan.outstanding().format());
        check("LOAN009", "Loan leaves the dashboard order count",
            afterLoan.orderCount() == countBefore,
            "before " + countBefore + " after " + afterLoan.orderCount());

        List<OrderDao.OrderRow> owing = orderDao.loadLoanOrders();
        check("LOAN010", "Loan appears in the Pay Later ledger with its balance",
            owing.stream().anyMatch(r -> r.order().id() == unpaid.id()
                && r.order().totals().balanceDue().equals(unpaid.totals().total())),
            "balance " + unpaid.totals().total().format());

        section("LOAN 011-016  Collecting against a loan");

        OrderDao.LoanSummary ls = orderDao.loanSummary();
        Money owedBefore = ls.outstandingAmount();
        Money receivedBefore = ls.receivedTotal();

        Money half = Money.of(unpaid.totals().total().asBigDecimal()
            .divide(java.math.BigDecimal.valueOf(2), 2, java.math.RoundingMode.DOWN));
        check("LOAN011", "Partial collection is accepted",
            orderDao.recordPayment(unpaid.id(), half, 1), "paid " + half.format());

        Order partly = orderDao.loadOrderForPrint(unpaid.id());
        check("LOAN012", "Part-paid loan stays a loan",
            partly.isOnLoanLedger() && partly.paymentStatus() == PaymentStatus.PARTIALLY_PAID,
            partly.paymentStatus().label());

        // The bug this pins down: the third summary card used to be SUM(total) over every
        // loan ever given, so it climbed when a new loan was added and never moved when one
        // was repaid -- money collected was invisible. Owed and Received are now the two
        // halves of the same pot, and a collection must move the money across, not vanish.
        OrderDao.LoanSummary ls2 = orderDao.loanSummary();
        check("LOAN013", "Loan ledger's owed figure drops by what was collected",
            ls2.outstandingAmount().equals(owedBefore.subtract(half)),
            "was " + owedBefore.format() + " now " + ls2.outstandingAmount().format());
        check("LOAN013b", "Received figure rises by exactly what was collected",
            ls2.receivedTotal().equals(receivedBefore.add(half)),
            "was " + receivedBefore.format() + " now " + ls2.receivedTotal().format());
        check("LOAN013c", "Collecting moves money across, leaving the pot unchanged",
            ls2.outstandingAmount().add(ls2.receivedTotal())
                .equals(owedBefore.add(receivedBefore)),
            "pot " + ls2.outstandingAmount().add(ls2.receivedTotal()).format());

        OrderDao.DailySummary afterPart = orderDao.dailySummary(todayFilter);
        check("LOAN014", "Collecting on a loan does not double-count revenue",
            afterPart.revenue().equals(revenueBefore.add(unpaid.totals().total())),
            afterPart.revenue().format());

        // The reported bug: a debt collected in full used to STAY on the ledger, showing
        // its money a second time under "Received Back" after the same money had already
        // been counted as revenue when credit was given. It must now settle and go home.
        orderDao.recordPayment(unpaid.id(), partly.totals().balanceDue(), 1);
        Order settled = orderDao.loadOrderForPrint(unpaid.id());
        check("LOAN015", "Collecting in full settles the loan and takes it off the ledger",
            settled.paymentStatus() == PaymentStatus.PAID && !settled.isOnLoanLedger()
                && settled.loanSettledAt() != null,
            settled.paymentStatus().label() + " settledAt=" + settled.loanSettledAt());
        check("LOAN015b", "A settled loan is still recognisable as a credit sale",
            settled.wasSoldOnCredit(), "loanAt=" + settled.loanAt());
        check("LOAN016", "Settling does NOT add the amount to revenue a second time",
            orderDao.dailySummary(todayFilter).revenue()
                .equals(revenueBefore.add(unpaid.totals().total())),
            orderDao.dailySummary(todayFilter).revenue().format());

        check("LOAN017", "Settled loan leaves the Pay Later list entirely",
            orderDao.loadLoanOrders().stream().noneMatch(r -> r.order().id() == unpaid.id()), "");
        check("LOAN018", "Settled loan is back on the Dashboard, shown as paid",
            orderDao.loadOrders(todayFilter).stream()
                .anyMatch(r -> r.order().id() == unpaid.id()
                    && r.order().paymentStatus() == PaymentStatus.PAID), "");

        OrderDao.LoanSummary afterSettle = orderDao.loanSummary();
        check("LOAN018b", "Pay Later shows nothing owed once the only debt is cleared",
            afterSettle.outstandingAmount().equals(Money.ZERO)
                && afterSettle.outstandingCount() == 0,
            "owed " + afterSettle.outstandingAmount().format()
                + " count " + afterSettle.outstandingCount());
        check("LOAN018c", "A settled loan's money leaves 'Received Back' along with it",
            afterSettle.receivedTotal().equals(Money.ZERO),
            "received " + afterSettle.receivedTotal().format());
        check("LOAN018f", "A settled loan does not reappear as outstanding on the Dashboard",
            orderDao.dailySummary(todayFilter).outstanding().equals(outstandingBefore),
            orderDao.dailySummary(todayFilter).outstanding().format());

        // The other half of the reported bug: a new loan arriving must raise what is OWED,
        // and must not touch what has been received.
        OrderDraft nextLoanDraft = draft(OrderType.TAKEAWAY);
        nextLoanDraft.addLine(line(item, 1));
        Order nextLoan = track(orderDao.saveOrder(nextLoanDraft, 1, "QA"));
        orderDao.moveToLoan(nextLoan.id(), 1);
        OrderDao.LoanSummary afterNext = orderDao.loanSummary();
        check("LOAN018d", "A new loan raises what is owed",
            afterNext.outstandingAmount().equals(nextLoan.totals().total())
                && afterNext.outstandingCount() == 1,
            "owed " + afterNext.outstandingAmount().format());
        check("LOAN018e", "A new loan does NOT change what has been received",
            afterNext.receivedTotal().equals(afterSettle.receivedTotal()),
            "received " + afterNext.receivedTotal().format()
                + " (was " + afterSettle.receivedTotal().format() + ")");

        section("LOAN 019-023  Refusals and undo");

        OrderDraft paidDraft = draft(OrderType.TAKEAWAY);
        paidDraft.addLine(line(item, 1));
        paidDraft.setCashTendered(Money.of("100000"));
        Order fullyPaid = track(orderDao.saveOrder(paidDraft, 1, "QA"));
        QA.expectReject("LOAN019", "A fully paid order cannot be put on account",
            () -> orderDao.moveToLoan(fullyPaid.id(), 1));

        OrderDraft cancelDraft = draft(OrderType.TAKEAWAY);
        cancelDraft.addLine(line(item, 1));
        Order toCancel = track(orderDao.saveOrder(cancelDraft, 1, "QA"));
        orderDao.updateFulfilment(toCancel.id(), FulfilmentStatus.PENDING,
            FulfilmentStatus.CANCELLED, 1, true);
        QA.expectReject("LOAN020", "A cancelled order cannot be put on account",
            () -> orderDao.moveToLoan(toCancel.id(), 1));

        OrderDraft undoDraft = draft(OrderType.TAKEAWAY);
        undoDraft.addLine(line(item, 1));
        Order undoMe = track(orderDao.saveOrder(undoDraft, 1, "QA"));
        orderDao.moveToLoan(undoMe.id(), 1);
        check("LOAN021", "Moving to a loan twice is a no-op, not an error",
            !orderDao.moveToLoan(undoMe.id(), 1), "second call returns false");
        check("LOAN022", "Returning from the ledger clears the flag",
            orderDao.returnFromLoan(undoMe.id())
                && !orderDao.loadOrderForPrint(undoMe.id()).isOnLoanLedger(), "");
        check("LOAN023", "A returned order is back on the dashboard",
            orderDao.loadOrders(todayFilter).stream().anyMatch(r -> r.order().id() == undoMe.id()), "");

        section("LOAN 024  Reports agree with the dashboard");

        Money reportRevenue = reportsDao.staffTotals(today, today).stream()
            .map(ReportsDao.StaffTotal::revenue).reduce(Money.ZERO, Money::add);
        check("LOAN024", "Reports revenue == dashboard revenue (loans included in both)",
            reportRevenue.equals(orderDao.dailySummary(todayFilter).revenue()),
            "reports " + reportRevenue.format()
                + " dashboard " + orderDao.dailySummary(todayFilter).revenue().format());

        // ============================================== ADD ITEMS AFTER THE WINDOW
        section("ADD 001-008  Adding items to a placed order");

        OrderDraft addDraft = draft(OrderType.DINE_IN);
        addDraft.addLine(line(item, 1));
        Order base = track(orderDao.saveOrder(addDraft, 1, "QA"));
        Money baseTotal = base.totals().total();
        int baseLines = base.lines().size();

        check("ADD001", "A fresh order is both editable and addable",
            OrderDao.canEdit(base) && OrderDao.canAddItems(base), "");

        Order grown = orderDao.addItemsToOrder(base.id(), List.of(line(item, 3)), null, 1, "QA");
        check("ADD002", "Added line is appended, not replacing what was there",
            grown.lines().size() == baseLines + 1, grown.lines().size() + " lines");
        check("ADD003", "Original line survives unchanged",
            grown.lines().get(0).quantity() == base.lines().get(0).quantity()
                && grown.lines().get(0).lineTotal().equals(base.lines().get(0).lineTotal()), "");
        check("ADD004", "line_no continues rather than colliding",
            grown.lines().stream().map(rps.model.OrderLine::lineNo).distinct().count()
                == grown.lines().size(),
            grown.lines().stream().map(l -> String.valueOf(l.lineNo())).reduce("", (a, b) -> a + b + " "));
        check("ADD005", "Total grows by exactly the added line",
            grown.totals().total().equals(baseTotal.add(grown.lines().get(baseLines).lineTotal())),
            "was " + baseTotal.format() + " now " + grown.totals().total().format());

        // A paid order that grows owes money again — the single most consequential
        // consequence of this feature, since it decides whether the till asks for more cash.
        OrderDraft paidThenGrown = draft(OrderType.DINE_IN);
        paidThenGrown.addLine(line(item, 1));
        Order settledOrder = track(orderDao.saveOrder(paidThenGrown, 1, "QA"));
        orderDao.recordPayment(settledOrder.id(), settledOrder.totals().total(), 1);
        check("ADD006", "Order is PAID before more is added",
            orderDao.loadOrderForPrint(settledOrder.id()).paymentStatus() == PaymentStatus.PAID, "");
        Order reopened = orderDao.addItemsToOrder(settledOrder.id(), List.of(line(item, 1)), null, 1, "QA");
        check("ADD007", "Adding to a paid order puts it back into debt",
            reopened.paymentStatus() == PaymentStatus.PARTIALLY_PAID
                && reopened.totals().balanceDue().isPositive(),
            reopened.paymentStatus().label() + " owing " + reopened.totals().balanceDue().format());

        OrderDraft cancelled2 = draft(OrderType.DINE_IN);
        cancelled2.addLine(line(item, 1));
        Order dead = track(orderDao.saveOrder(cancelled2, 1, "QA"));
        orderDao.updateFulfilment(dead.id(), FulfilmentStatus.PENDING,
            FulfilmentStatus.CANCELLED, 1, true);
        check("ADD008", "A cancelled order is gone, not just marked cancelled",
            orderDao.loadOrderForPrint(dead.id()) == null, "");
        QA.expectReject("ADD009", "addItemsToOrder rejects a cancelled (deleted) order",
            () -> orderDao.addItemsToOrder(dead.id(), List.of(line(item, 1)), null, 1, "QA"));
        QA.expectReject("ADD010", "addItemsToOrder rejects an empty addition",
            () -> orderDao.addItemsToOrder(base.id(), List.<DraftLine>of(), null, 1, "QA"));


        section("LOAN 027-029  Self-healing a stuck fully-paid loan");

        // Reported live: an order reached PAID (via a build predating applyPayment's
        // auto-settle step) but never left the ledger, showing "Still Owed Rs 0.00" with no
        // way to clear it -- Collect demands an amount greater than zero, which a paid-up
        // order has none of. Simulate exactly that stuck state and confirm the self-heal
        // (run by LoanPanel before every refresh) repairs it without manual intervention.
        OrderDraft stuckDraft = draft(OrderType.TAKEAWAY);
        stuckDraft.addLine(line(item, 2));
        Order stuckSource = track(orderDao.saveOrder(stuckDraft, 1, "QA"));
        orderDao.moveToLoan(stuckSource.id(), 1);
        orderDao.recordPayment(stuckSource.id(), stuckSource.totals().total(), 1);
        db.inTransaction(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("UPDATE customer_order SET loan_settled_at = NULL WHERE id = " + stuckSource.id());
            }
            return null;
        });
        Order stuck = orderDao.loadOrderForPrint(stuckSource.id());
        check("LOAN027", "Reproduced the stuck state: fully paid but still on the ledger",
            stuck.isOnLoanLedger() && stuck.totals().balanceDue().equals(Money.ZERO),
            "onLedger=" + stuck.isOnLoanLedger() + " balance=" + stuck.totals().balanceDue().format());

        int fixed = orderDao.reconcileLoanLedger();
        check("LOAN028", "reconcileLoanLedger repairs the stuck row", fixed >= 1, fixed + " row(s) fixed");

        Order healed = orderDao.loadOrderForPrint(stuckSource.id());
        check("LOAN029", "Stuck row is off the ledger after reconciling, back on the Dashboard",
            !healed.isOnLoanLedger() && healed.loanSettledAt() != null
                && orderDao.loadOrders(todayFilter).stream()
                    .anyMatch(r -> r.order().id() == stuckSource.id()),
            "settledAt=" + healed.loanSettledAt());

        section("CANCEL 001-003  Total Orders excludes cancelled orders");

        OrderDao.DailySummary beforeCancelTest = orderDao.dailySummary(todayFilter);
        long countBeforeCancelTest = beforeCancelTest.orderCount();

        OrderDraft toCancelDraft = draft(OrderType.TAKEAWAY);
        toCancelDraft.addLine(line(item, 1));
        Order toCancelOrder = track(orderDao.saveOrder(toCancelDraft, 1, "QA"));
        check("CANCEL001", "A live order raises Total Orders",
            orderDao.dailySummary(todayFilter).orderCount() == countBeforeCancelTest + 1,
            "count=" + orderDao.dailySummary(todayFilter).orderCount());

        orderDao.updateFulfilment(toCancelOrder.id(), FulfilmentStatus.PENDING,
            FulfilmentStatus.CANCELLED, 1, true);
        check("CANCEL002", "Cancelling it drops Total Orders back down — a voided order isn't 'an order'",
            orderDao.dailySummary(todayFilter).orderCount() == countBeforeCancelTest,
            "count=" + orderDao.dailySummary(todayFilter).orderCount());

        // Cancelling now deletes the row outright, so no order can ever sit in the
        // CANCELLED state -- filtering to it must show a real, honest zero, not
        // (accidentally, via some leftover special-case) a count of live orders.
        OrderDao.OrderFilter cancelledOnly = new OrderDao.OrderFilter(
            null, null, FulfilmentStatus.CANCELLED, todayFilter.from(), todayFilter.to());
        check("CANCEL003", "Filtering TO Cancelled shows zero -- cancelled orders no longer exist to find",
            orderDao.dailySummary(cancelledOnly).orderCount() == 0,
            "count=" + orderDao.dailySummary(cancelledOnly).orderCount());

        int violations = QA.integritySweep(db, "after loan + add-items suite");
        check("SWEEP", "Zero integrity violations", violations == 0, violations + " violation(s)");

        QA.cleanup(db);
        QA.summary("LOAN + ADD ITEMS SUITE");
        System.exit(QA.failed == 0 ? 0 : 1);
    }
}
