package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.BillActionItem;
import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.MarkPickedUpRequest;
import com.rentalshop.backend.dto.MarkReturnedRequest;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two bill-level actions behind the single "Mark picked up / returned"
 * button on the Booking Detail and Group Booking screens.
 *
 * Why this is its own service rather than a loop over
 * BookingService.updateStatus: each action bundles several writes that only
 * make sense together -- items change status, a deposit is recorded, a
 * payment is taken, a settlement decision is stored. Driving those from the
 * app as a series of calls could leave a bill half-updated if the connection
 * dropped in the middle. Here every one of them happens inside ONE
 * transaction: any failure (a stale version, a payment above the balance, a
 * missing reason) rolls the whole thing back and the bill is exactly as it
 * was.
 *
 * Both methods return every row of the affected bill as it stands afterwards
 * (versions included), so the app can refresh without a second round-trip and
 * without holding a stale version that would 409 its next action.
 *
 * PATCH /api/bookings/{id}/status still exists unchanged for older app
 * builds; nothing here alters its behaviour.
 */
@Service
@RequiredArgsConstructor
public class BillActionService {

    /**
     * The note stamped on the payment recorded for "amount due" collected at
     * pickup. It is how the app tells that payment apart in the bill's
     * history so it can show it as "Amount due paid at pickup (UPI)" -- the
     * Android app carries the same string (PICKUP_PAYMENT_NOTE), so change
     * both or neither.
     */
    public static final String PICKUP_PAYMENT_NOTE = "Amount due paid at pickup";

    private final BookingRepository bookingRepository;
    private final PaymentService paymentService;
    private final AuditLogService auditLogService;

    // ---------------------------------------------------------------------
    // Mark picked up
    // ---------------------------------------------------------------------

    /**
     * Marks the selected CONFIRMED items PICKED_UP, records the security
     * deposit (and how it was paid) against them, and takes whatever part of
     * the bill's outstanding balance staff collected at the counter.
     *
     * The deposit is entered once for everything going out and divided
     * evenly across the selected rows (see BillMath.splitEvenly) because the
     * bill has no entity of its own to hold it; the bill's deposit total is
     * the sum of its rows, so the division never changes what's shown at bill
     * level. It is never added to rentalAmount or balanceAmount.
     *
     * The balance payment is an ordinary Payment (PaymentService), applied to
     * the WHOLE bill -- not just the items going out -- because "amount due"
     * is a property of the bill: the customer may be paying for items that
     * are still to be collected later.
     */
    @Transactional
    public List<BookingResponse> markPickedUp(MarkPickedUpRequest req) {
        List<Booking> selected = loadSelected(req.getItems());
        for (Booking booking : selected) {
            if (!booking.getStatus().canTransitionTo(Booking.BookingStatus.PICKED_UP)) {
                throw new IllegalStateException(
                        "Booking " + booking.getBookingNumber() + " is " + booking.getStatus()
                                + " and can't be marked picked up");
            }
        }
        List<Booking> bill = loadBill(selected.get(0));

        BigDecimal deposit = req.getDepositAmount();
        String depositMethod = methodFor(deposit, req.getDepositPaymentMethod(), "the security deposit");

        BigDecimal duePayment = req.getDuePaymentAmount();
        String dueMethod = methodFor(duePayment, req.getDuePaymentMethod(), "the amount due payment");

        BigDecimal outstanding = totalBalance(bill);
        if (duePayment.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException(
                    "Amount received " + duePayment + " is more than the bill's balance due " + outstanding);
        }

        List<BigDecimal> depositShares = BillMath.splitEvenly(deposit, selected.size());
        for (int i = 0; i < selected.size(); i++) {
            Booking booking = selected.get(i);
            Booking.BookingStatus previous = booking.getStatus();

            booking.setDepositAmount(depositShares.get(i));
            booking.setDepositPaymentMethod(depositMethod);
            booking.setStatus(Booking.BookingStatus.PICKED_UP);

            bookingRepository.save(booking);
            auditLogService.recordBookingStatusChanged(booking, previous);
        }

        // Last, so that if it is refused the pickup above rolls back with it.
        if (duePayment.signum() > 0) {
            paymentService.recordBillPayment(
                    selected.get(0), duePayment, dueMethod, PICKUP_PAYMENT_NOTE, req.getPaymentDate());
        }

        return flushAndMap(bill);
    }

    // ---------------------------------------------------------------------
    // Mark returned
    // ---------------------------------------------------------------------

    /**
     * Marks the selected PICKED_UP items RETURNED and records staff's two
     * closing answers.
     *
     * Security deposit -- asked only while the bill still holds a deposit
     * that hasn't been handed back. "Yes" marks it RETURNED; "No" needs a
     * written reason and marks it NOT_RETURNED. The answer applies to the
     * bill's deposit as a whole, i.e. to every row that carries part of it,
     * not just the rows returned in this call: the deposit is one pool of
     * money, so a later return that says "Yes" correctly supersedes an
     * earlier "No, other items still out".
     *
     * Settlement -- "Yes" means settled full and final: whatever balance is
     * left on the bill is cleared to zero (the existing meaning of
     * SETTLED; see BookingService.applySettlement) and any earlier DUE
     * rows on the bill are closed with it. "No" leaves the balance exactly as
     * it stands and marks the returned rows DUE, so the bill shows up under
     * Amount Due Bills.
     */
    @Transactional
    public List<BookingResponse> markReturned(MarkReturnedRequest req) {
        List<Booking> selected = loadSelected(req.getItems());
        for (Booking booking : selected) {
            if (!booking.getStatus().canTransitionTo(Booking.BookingStatus.RETURNED)) {
                throw new IllegalStateException(
                        "Booking " + booking.getBookingNumber() + " is " + booking.getStatus()
                                + " and can't be marked returned");
            }
        }
        List<Booking> bill = loadBill(selected.get(0));

        // -- Security deposit answer ------------------------------------------
        List<Booking> depositRows = bill.stream().filter(BillActionService::holdsUnreturnedDeposit).toList();
        Booking.DepositReturnStatus depositOutcome = null;
        String depositReason = null;
        if (!depositRows.isEmpty()) {
            Boolean returned = req.getDepositReturned();
            if (returned == null) {
                throw new IllegalArgumentException(
                        "Say whether the security deposit was given back to the customer");
            }
            if (returned) {
                depositOutcome = Booking.DepositReturnStatus.RETURNED;
            } else {
                String reason = req.getDepositNotReturnedReason() == null
                        ? "" : req.getDepositNotReturnedReason().trim();
                if (reason.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Give a reason why the security deposit was not returned");
                }
                depositOutcome = Booking.DepositReturnStatus.NOT_RETURNED;
                depositReason = reason;
            }
        }

        Booking.SettlementStatus settlement = req.getBillSettled()
                ? Booking.SettlementStatus.SETTLED
                : Booking.SettlementStatus.DUE;

        // Snapshot every row first: rows changed only as a side effect (see
        // below) get their own audit entry, and a cleared balance is exactly
        // the kind of change that must be traceable afterwards.
        Map<Long, Map<String, Object>> before = new HashMap<>();
        for (Booking row : bill) {
            before.put(row.getId(), auditLogService.snapshotBooking(row));
        }
        Set<Long> selectedIds = new HashSet<>();

        for (Booking booking : selected) {
            Booking.BookingStatus previous = booking.getStatus();
            booking.setStatus(Booking.BookingStatus.RETURNED);
            booking.setSettlementStatus(settlement);
            bookingRepository.save(booking);
            auditLogService.recordBookingStatusChanged(booking, previous);
            selectedIds.add(booking.getId());
        }

        if (depositOutcome != null) {
            for (Booking row : depositRows) {
                row.setDepositReturnStatus(depositOutcome);
                row.setDepositReturnReason(depositReason);
                bookingRepository.save(row);
            }
        }

        if (settlement == Booking.SettlementStatus.SETTLED) {
            for (Booking row : bill) {
                if (row.getBalanceAmount().signum() != 0) {
                    row.setBalanceAmount(BigDecimal.ZERO);
                }
                // An earlier partial return may have left this row DUE; the
                // bill is now settled, so it must not linger on the Amount
                // Due Bills list.
                if (row.getStatus() == Booking.BookingStatus.RETURNED
                        && row.getSettlementStatus() == Booking.SettlementStatus.DUE) {
                    row.setSettlementStatus(Booking.SettlementStatus.SETTLED);
                }
                bookingRepository.save(row);
            }
        }

        for (Booking row : bill) {
            if (selectedIds.contains(row.getId())) {
                continue; // already audited above as a status change
            }
            Map<String, Object> beforeRow = before.get(row.getId());
            if (!auditLogService.snapshotBooking(row).equals(beforeRow)) {
                auditLogService.recordBookingItemUpdated(beforeRow, row);
            }
        }

        return flushAndMap(bill);
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /**
     * Loads every booking named in [items], checking that none is listed
     * twice, that each still exists, that the app's version matches the
     * current one, and that they all belong to the same bill.
     *
     * The version comparison is explicit rather than relying on setVersion()
     * followed by a flush: the check has to fail here, before any state
     * changes, whatever Hibernate does with a manually-assigned version on a
     * managed entity. A mismatch raises the same exception the rest of the
     * app already maps to 409 STALE_WRITE.
     */
    private List<Booking> loadSelected(List<BillActionItem> items) {
        Set<Long> seen = new HashSet<>();
        List<Booking> selected = new ArrayList<>();
        for (BillActionItem item : items) {
            if (!seen.add(item.getBookingId())) {
                throw new IllegalArgumentException("Booking " + item.getBookingId() + " is listed more than once");
            }
            Booking booking = bookingRepository.findByIdWithDetails(item.getBookingId())
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + item.getBookingId()));
            if (!booking.getVersion().equals(item.getVersion())) {
                throw new ObjectOptimisticLockingFailureException(Booking.class, booking.getId());
            }
            selected.add(booking);
        }

        String billKey = billKey(selected.get(0));
        for (Booking booking : selected) {
            if (!billKey(booking).equals(billKey)) {
                throw new IllegalArgumentException("The selected items don't all belong to the same bill");
            }
        }
        return selected;
    }

    /** Every row of the bill [anchor] belongs to -- the group's rows, or just the booking itself. */
    private List<Booking> loadBill(Booking anchor) {
        if (anchor.getGroupId() == null) {
            return List.of(anchor);
        }
        return bookingRepository.findByGroupIdWithDetails(anchor.getGroupId());
    }

    private static String billKey(Booking booking) {
        return booking.getGroupId() != null ? "group:" + booking.getGroupId() : "booking:" + booking.getId();
    }

    /**
     * The trimmed payment method for [amount], or null when nothing was paid.
     * Refuses a positive amount with no method -- the app requires a choice
     * (nothing is pre-selected), and this keeps a client bug from recording
     * money with no way to tell how it came in.
     */
    private static String methodFor(BigDecimal amount, String method, String what) {
        if (amount.signum() <= 0) {
            return null;
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Choose a payment method for " + what);
        }
        return method.trim();
    }

    /** What the bill still owes: the sum of its rows' balances (cancelled rows are already zero). */
    private static BigDecimal totalBalance(List<Booking> bill) {
        return BillMath.sum(bill.stream().map(Booking::getBalanceAmount).toList());
    }

    /** A live row that carries part of a security deposit which hasn't been handed back yet. */
    private static boolean holdsUnreturnedDeposit(Booking row) {
        return row.getStatus() != Booking.BookingStatus.CANCELLED
                && row.getDepositAmount().signum() > 0
                && row.getDepositReturnStatus() != Booking.DepositReturnStatus.RETURNED;
    }

    /**
     * Flushes so the version numbers in the response are the ones now in the
     * database (they only increment at flush time). Without this the app
     * would receive the pre-update versions and its very next action on the
     * bill would be rejected as stale.
     */
    private List<BookingResponse> flushAndMap(List<Booking> bill) {
        bookingRepository.flush();
        return bill.stream().map(BookingResponse::from).toList();
    }
}
