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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
