package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per payment/installment received against a booking (schema.sql's
 * payments table, present since the original schema but never given an
 * entity/service/controller until now). A booking can have several rows
 * here — e.g. an advance recorded at CreateBookingRequest time is NOT
 * duplicated into this table (it lives on bookings.advance_amount, per the
 * original design), but every payment collected AFTER that — at pickup, at
 * return, a partial settlement — goes through here and decrements
 * Booking.balanceAmount (see PaymentService).
 *
 * No @Version here: schema.sql doesn't give payments a version column, and
 * a payment row is never edited in place (see PaymentService.deletePayment
 * for corrections) so optimistic locking isn't needed on this entity.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(length = 40)
    private String method;

    @Column(length = 300)
    private String notes;

    /**
     * Ties together the rows created by ONE payment taken against a group
     * booking. A bill with four items that takes a single Rs. 5000 payment
     * writes up to four rows here (each decrementing its own booking's
     * balance -- see PaymentService.recordGroupPayment for why the money has
     * to be split rather than parked on one row), and they all carry the
     * same value here. That's what lets the app show it back as the one
     * payment it actually was, and lets a correction remove all of its
     * pieces together instead of leaving a bill half-unpaid.
     *
     * Null for a payment recorded against a standalone booking -- there was
     * nothing to split, so there is nothing to tie together.
     */
    @Column(name = "group_payment_ref", length = 40)
    private String groupPaymentRef;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
