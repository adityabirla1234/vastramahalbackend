package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.CreatePaymentRequest;
import com.rentalshop.backend.dto.PaymentResponse;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.Payment;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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
