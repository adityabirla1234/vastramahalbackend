package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_number", nullable = false, unique = true, length = 40)
    private String bookingNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "pickup_date", nullable = false)
    private LocalDate pickupDate;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    @Column(name = "rental_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal rentalAmount = BigDecimal.ZERO;

    @Column(name = "deposit_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Column(name = "advance_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    @Column(name = "balance_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Client-generated key (e.g. UUID created once per booking attempt on the
     * Android device). A unique DB constraint on this column is what actually
     * stops a network-retry from creating a duplicate booking — see Section 3.8.
     */
    @Column(name = "idempotency_key", unique = true, length = 80)
    private String idempotencyKey;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Shared across every item submitted together in one New Booking
     * session (Section 3.7 multi-item redesign) -- a client-generated or
     * server-assigned UUID, stamped identically onto each item's row by
     * BookingService.createBookingBatch. Null for a standalone
     * single-item booking created via the plain POST /api/bookings path.
     * Deliberately NOT a foreign key to any "booking group" table -- there
     * is no separate group entity/lifecycle to keep in sync; a group is
     * simply every Booking row that happens to share this value, and the
     * "overall bill" is computed on the fly as their sum (see
     * BookingService.getGroupBookings), never stored anywhere.
     */
    @Column(name = "group_id", length = 40)
    private String groupId;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum BookingStatus {
        PENDING, CONFIRMED, PICKED_UP, RETURNED, CANCELLED;

        /** Only these statuses actually occupy the item's calendar / block new bookings. */
        public boolean occupiesCalendar() {
            return this == PENDING || this == CONFIRMED || this == PICKED_UP;
        }

        /**
         * The booking lifecycle state machine. Deliberately conservative:
         * once an item has physically left the shop (PICKED_UP), a booking
         * can no longer be CANCELLED -- only RETURNED closes it out, since
         * "cancel" implies the item never went out. RETURNED and CANCELLED
         * are both terminal; nothing transitions out of them.
         *
         *   PENDING   -> CONFIRMED, CANCELLED
         *   CONFIRMED -> PICKED_UP, CANCELLED
         *   PICKED_UP -> RETURNED
         *   RETURNED  -> (terminal)
         *   CANCELLED -> (terminal)
         */
        public boolean canTransitionTo(BookingStatus target) {
            if (target == null || target == this) {
                return false;
            }
            return switch (this) {
                case PENDING -> target == CONFIRMED || target == CANCELLED;
                case CONFIRMED -> target == PICKED_UP || target == CANCELLED;
                case PICKED_UP -> target == RETURNED;
                case RETURNED, CANCELLED -> false;
            };
        }
    }
}
