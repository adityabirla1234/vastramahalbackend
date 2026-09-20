package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.CreatePaymentRequest;
import com.rentalshop.backend.dto.GroupPaymentResponse;
import com.rentalshop.backend.dto.PaymentResponse;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.Payment;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments endpoints (development plan "Payments endpoints", carried over
 * from the original schema's payments table, which existed long before any
 * code touched it). A payment here is always recorded AGAINST an existing
 * booking -- there's no such thing as a standalone payment -- so every
 * method takes bookingId, matching how PaymentController nests these under
 * /api/bookings/{bookingId}/payments.
 *
 * Deliberately does NOT touch bookings.advance_amount: that field is set
 * once at booking-creation time (CreateBookingRequest.advanceAmount) and
 * represents money collected before/at booking. Everything recorded here
 * represents money collected AFTER that -- at pickup, at return, a partial
 * settlement -- and only ever adjusts balance_amount.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final AuditLogService auditLogService;

    /**
     * Records a payment and decrements the booking's outstanding balance by
     * the same amount, in one transaction. Returns empty if no booking
     * exists with this id, so the controller can map that to 404 the same
     * way BookingController.findById does.
     *
     * Rejects a payment that would take the balance below zero (400
     * BAD_REQUEST via IllegalArgumentException) -- that's a data-entry
     * mistake (e.g. amount typed twice as large), not a legitimate
     * "customer overpaid" case worth silently allowing into a negative
     * balance_amount.
     *
     * No explicit lock is taken on the booking row here the way
     * BookingService.createBooking locks the item: bookings.version already
     * gives us optimistic concurrency for free (Hibernate dirty-checks the
     * balance_amount change on save() below), and two staff members
     * recording two DIFFERENT payments against the SAME booking at the
     * exact same moment is a rare enough case that "second save gets a 409,
     * refresh and retry" (same contract as every other STALE_WRITE in this
     * app) is a perfectly fine outcome -- unlike the booking-overlap case,
     * there's no silent-corruption failure mode if both simply attempt to
     * commit in sequence.
     */
    @Transactional
    public Optional<PaymentResponse> recordPayment(Long bookingId, CreatePaymentRequest req) {
        return bookingRepository.findById(bookingId).map(booking -> {
            // A bill with several items collects money once, for the bill --
            // the customer does not pay per dress. Letting a payment in here
            // for one row of such a bill would mean staff having to decide
            // how to divide a single receipt across items, and two staff
            // members dividing the same receipt differently. So this path is
            // closed for multi-item bills and recordGroupPayment is the only
            // way in; 409 rather than 404 because the request is well-formed,
            // it's just aimed at the wrong level.
            if (isPartOfMultiItemBill(booking)) {
                throw new IllegalStateException(
                        "This booking is part of a bill with several items. Record the payment against the "
                        + "bill (POST /api/bookings/group/" + booking.getGroupId() + "/payments) rather than "
                        + "against one item.");
            }
            if (req.getAmount().compareTo(booking.getBalanceAmount()) > 0) {
                throw new IllegalArgumentException(
                        "Payment amount " + req.getAmount() + " exceeds outstanding balance " +
                        booking.getBalanceAmount() + " for booking " + booking.getBookingNumber());
            }

            Payment payment = new Payment();
            payment.setBooking(booking);
            payment.setAmount(req.getAmount());
            payment.setPaymentDate(req.getPaymentDate());
            payment.setMethod(req.getMethod());
            payment.setNotes(req.getNotes());
            Payment saved = paymentRepository.save(payment);

            booking.setBalanceAmount(booking.getBalanceAmount().subtract(req.getAmount()));
            bookingRepository.save(booking);

            auditLogService.recordPaymentCreated(saved);
            return PaymentResponse.from(saved);
        });
    }

    /**
     * Records ONE payment against a whole bill and splits it across that
     * bill's items, oldest-pickup-first, filling each item's outstanding
     * balance before moving to the next. This is the only way money is
     * collected for a multi-item bill (see recordPayment's guard).
     *
     * The split exists because balance_amount lives per booking row and
     * nothing else in the system understands a bill-level balance: a group
     * is just the rows sharing a groupId, with no entity of its own (see
     * Booking.groupId). Parking the full amount on one arbitrary row would
     * leave that row negative and its siblings still showing as owing money.
     * Every slice carries the same groupPaymentRef so the app can show the
     * one payment staff actually entered, and deleteGroupPayment can undo
     * all of it together.
     *
     * Greedy oldest-first rather than proportional, deliberately: it means
     * a part-payment clears whole items instead of leaving every item on the
     * bill a few hundred rupees short, which is what staff reading the list
     * expect to see. The customer's total owing is identical either way.
     *
     * Returns empty if no booking carries this groupId (controller maps that
     * to 404). Rejects an amount larger than the bill's total outstanding
     * balance, same reasoning as the single-booking path: that's a typo, not
     * an overpayment worth storing.
     */
    @Transactional
    public Optional<GroupPaymentResponse> recordGroupPayment(String groupId, CreatePaymentRequest req) {
        List<Booking> bookings = bookingRepository.findByGroupIdWithDetails(groupId);
        if (bookings.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal outstanding = bookings.stream()
                .map(Booking::getBalanceAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (req.getAmount().compareTo(outstanding) > 0) {
            throw new IllegalArgumentException(
                    "Payment amount " + req.getAmount() + " exceeds the bill's outstanding balance " + outstanding);
        }

        String ref = UUID.randomUUID().toString();
        BigDecimal remaining = req.getAmount();
        List<PaymentResponse> allocations = new ArrayList<>();

        for (Booking booking : bookings) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal due = booking.getBalanceAmount();
            if (due.signum() <= 0) {
                // Already paid off (or settled at return time) -- skipped
                // entirely rather than written a zero-amount row, which
                // would only clutter the bill's payment history.
                continue;
            }

            BigDecimal slice = remaining.min(due);

            Payment payment = new Payment();
            payment.setBooking(booking);
            payment.setAmount(slice);
            payment.setPaymentDate(req.getPaymentDate());
            payment.setMethod(req.getMethod());
            payment.setNotes(req.getNotes());
            payment.setGroupPaymentRef(ref);
            Payment saved = paymentRepository.save(payment);

            booking.setBalanceAmount(due.subtract(slice));
            bookingRepository.save(booking);

            auditLogService.recordPaymentCreated(saved);
            allocations.add(PaymentResponse.from(saved));
            remaining = remaining.subtract(slice);
        }

        BigDecimal balanceAfter = bookings.stream()
                .map(Booking::getBalanceAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Optional.of(GroupPaymentResponse.builder()
                .groupId(groupId)
                .groupPaymentRef(ref)
                .amount(req.getAmount())
                .paymentDate(req.getPaymentDate())
                .method(req.getMethod())
                .notes(req.getNotes())
                .allocations(allocations)
                .groupBalanceAfter(balanceAfter)
                .build());
    }

    /**
     * Records a payment against the BILL that [anchor] belongs to, choosing
     * the right existing path for it: a bill with several items takes the
     * payment through {@link #recordGroupPayment} (split across the items,
     * oldest first), anything else through {@link #recordPayment} on the
     * booking itself. That is exactly the rule recordPayment already
     * enforces -- it refuses a payment aimed at one item of a multi-item
     * bill -- so callers such as BillActionService don't have to know it.
     *
     * Joins the caller's transaction, so when it is used from "mark picked
     * up" a failure here (e.g. amount above the balance) rolls the pickup
     * back too. Every check and audit-log entry is the ordinary payment
     * path's own; nothing about balances is re-implemented here.
     */
    @Transactional
    public void recordBillPayment(Booking anchor, BigDecimal amount, String method, String notes, LocalDate paymentDate) {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setAmount(amount);
        request.setPaymentDate(paymentDate);
        request.setMethod(method);
        request.setNotes(notes);

        if (isPartOfMultiItemBill(anchor)) {
            recordGroupPayment(anchor.getGroupId(), request);
        } else {
            recordPayment(anchor.getId(), request);
        }
    }

    /** Every payment taken against any item of one bill, oldest first -- the group screen's history. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listGroupPayments(String groupId) {
        return paymentRepository.findByBooking_GroupIdOrderByPaymentDateAscCreatedAtAsc(groupId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /**
     * Undoes a bill-level payment in full: every slice it was split into is
     * removed and its amount put back on the booking it came off. All of
     * them, or none -- correcting half a receipt would leave the bill
     * claiming a balance that was never real.
     *
     * Scoped to [groupId] as well as the ref so a ref from another bill
     * can't be used to reverse payments here; returns false when nothing
     * matches, which the controller maps to 404.
     */
    @Transactional
    public boolean deleteGroupPayment(String groupId, String groupPaymentRef) {
        List<Payment> slices = paymentRepository.findByGroupPaymentRef(groupPaymentRef).stream()
                .filter(p -> groupId.equals(p.getBooking().getGroupId()))
                .toList();
        if (slices.isEmpty()) {
            return false;
        }
        for (Payment slice : slices) {
            Booking booking = slice.getBooking();
            booking.setBalanceAmount(booking.getBalanceAmount().add(slice.getAmount()));
            bookingRepository.save(booking);
            paymentRepository.delete(slice);
            auditLogService.recordPaymentDeleted(slice);
        }
        return true;
    }

    /**
     * True when this booking is one item of a bill that has several -- NOT
     * merely "has a groupId". The app's New Booking screen always submits
     * through the batch endpoint, so a one-item booking made there carries a
     * groupId too; those are ordinary single bookings and keep taking
     * payments on themselves.
     */
    private boolean isPartOfMultiItemBill(Booking booking) {
        String groupId = booking.getGroupId();
        return groupId != null && bookingRepository.countByGroupId(groupId) > 1;
    }

    /** Booking Details screen's payment history. Empty list (not 404) if the booking has none yet. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(Long bookingId) {
        return paymentRepository.findByBookingIdOrderByPaymentDateAscCreatedAtAsc(bookingId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /**
     * Corrects a mis-entered payment by removing it AND restoring the amount
     * back onto the booking's balance -- the two must happen together or the
     * balance permanently drifts from reality. Returns false if no payment
     * exists with this id (controller maps that to 404).
     */
    @Transactional
    public boolean deletePayment(Long paymentId) {
        return paymentRepository.findById(paymentId).map(payment -> {
            // One slice of a bill-level payment can't be removed on its own:
            // the other slices would stay, leaving the bill claiming a
            // payment the customer never made in that shape. The whole
            // payment comes off together via deleteGroupPayment instead.
            if (payment.getGroupPaymentRef() != null) {
                throw new IllegalStateException(
                        "This payment was recorded against the whole bill. Remove it from the bill's payment "
                        + "history, which reverses all of it at once.");
            }
            Booking booking = payment.getBooking();
            booking.setBalanceAmount(booking.getBalanceAmount().add(payment.getAmount()));
            bookingRepository.save(booking);

            paymentRepository.delete(payment);
            auditLogService.recordPaymentDeleted(payment);
            return true;
        }).orElse(false);
    }

    /** Used by PaymentController to 404 a delete against a payment that doesn't belong to this booking. */
    @Transactional(readOnly = true)
    public boolean paymentBelongsToBooking(Long paymentId, Long bookingId) {
        return paymentRepository.findById(paymentId)
                .map(p -> p.getBooking().getId().equals(bookingId))
                .orElse(false);
    }
}
