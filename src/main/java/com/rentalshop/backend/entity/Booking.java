package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * How the security deposit was taken -- "Cash", "UPI" or "Card" -- entered
     * at pickup time right after the deposit amount (BillActionService.
     * markPickedUp). Null when no deposit was taken (depositAmount is zero),
     * and for rows picked up before this was recorded. Purely informational,
     * like the deposit itself: it never touches rentalAmount/balanceAmount.
     */
    @Column(name = "deposit_payment_method", length = 40)
    private String depositPaymentMethod;

    @Column(name = "advance_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    /**
     * How the advance was taken -- "Cash", "UPI" or "Card" -- chosen on the
     * New Booking form right after the advance amount. Null when there was
     * no advance, and for bookings created before this was recorded. Purely
     * informational: the advance itself still lives in advanceAmount.
     */
    @Column(name = "advance_payment_method", length = 40)
    private String advancePaymentMethod;

    @Column(name = "balance_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Free-text "fitting work" for THIS item row -- the alterations staff
     * agreed to do on the garment (hem up 2 inches, take in the waist,
     * re-stitch the blouse hooks, ...), written as as many paragraphs as
     * they need.
     *
     * Deliberately separate from [notes] rather than a second use of it:
     * notes are entered on the New Booking form at the counter, whereas
     * fitting work only exists AFTER the bill has been created -- there is
     * no field for it on the New Booking or Add-item forms, and it is set
     * exclusively through PATCH /api/bookings/{id} (the Booking History
     * "Edit" flow). Keeping it in its own column also means the app can
     * offer a dedicated "View fitting work" button that appears only once
     * something has actually been written, without guessing which part of
     * a shared notes blob was fitting instructions.
     *
     * Null (never an empty string) means "no fitting work" -- see
     * BookingService.updateBookingItem, which normalises a blank value to
     * null so every client can decide button visibility with one null check.
     * Per booking row, not per bill, for the same reason accessories are: a
     * bill with four dresses has four independent sets of alterations.
     */
    @Column(name = "fitting_work", columnDefinition = "TEXT")
    private String fittingWork;

    /**
     * The accessories staff attached to THIS item on the New Booking form
     * ("Add accessory": pant / jewellery / dupatta). Per booking row, not
     * per bill -- see BookingAccessory's class doc for why, and for why the
     * rows are snapshots rather than live joins to items.
     *
     * Cascade ALL + orphanRemoval: an accessory has no life of its own
     * outside the booking row it was attached to, so it's created with the
     * booking (one save(), no separate repository call) and would be
     * removed with it.
     *
     * LAZY with an explicit @BatchSize because the booking LIST endpoints
     * (booking history, upcoming bookings, the group-bill view) map every
     * row through BookingResponse.from, which touches this collection to
     * decide whether the "Accessories" button should appear at all. Without
     * the batch size that's a textbook N+1 -- one extra query per booking
     * on every list screen. With it, Hibernate fetches the accessories for
     * up to 64 bookings in a single IN query. EAGER would be worse still:
     * it would also fire on the many paths that never read this collection.
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    @BatchSize(size = 64)
    private List<BookingAccessory> accessories = new ArrayList<>();

    /**
     * Attaches an accessory, keeping BOTH sides of the relationship in sync.
     * Always use this rather than accessories.add(...) -- setting only one
     * side leaves the owning FK null and the insert fails at flush time.
     */
    public void addAccessory(BookingAccessory accessory) {
        accessory.setBooking(this);
        accessories.add(accessory);
    }

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

    /**
     * The shop's own bill number ("Bill No" on the New Booking form) --
     * typed in by staff, typically matching the number on the paper bill
     * they hand the customer. One bill = one number, so every row of a
     * group booking carries the SAME value (BookingService.createBookingBatch
     * stamps it onto each item). Free text, optional (null for bookings
     * made before this field existed, or if staff left it blank), and
     * deliberately NOT unique: it's a human-entered reference, not a key --
     * uniqueness is enforced on bookingNumber and idempotencyKey instead.
     */
    @Column(name = "bill_number", length = 40)
    private String billNumber;

    /**
     * Set only when this booking (or, for a group booking, every row in the
     * group) is finally marked RETURNED -- see BookingService.updateStatus.
     * Purely informational: the security deposit is never folded into
     * rentalAmount/balanceAmount at any point in the lifecycle, so this
     * column exists only so staff can see -- and Billing History can show --
     * what was decided when the item(s) came back. Null until that decision
     * is made.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_return_status", length = 20)
    private DepositReturnStatus depositReturnStatus;

    /**
     * Why the security deposit was NOT handed back -- the free-text reason
     * staff must write when they answer "No" to "did you give the security
     * deposit back?" at return time (BillActionService.markReturned). Set
     * together with depositReturnStatus = NOT_RETURNED; null for every other
     * outcome, and cleared again if a later return of the same bill answers
     * "Yes".
     */
    @Column(name = "deposit_return_reason", length = 500)
    private String depositReturnReason;

    /**
     * Set alongside depositReturnStatus, at the same "final return" moment.
     * SETTLED means staff confirmed the bill is closed full-and-final (the
     * outstanding balance is cleared to zero right here); DUE means the
     * balance is left exactly as it stood, still outstanding. Null until
     * that decision is made.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", length = 10)
    private SettlementStatus settlementStatus;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * The three outcomes staff can record for a security deposit once the
     * item(s) it was collected against physically come back. DAMAGED and
     * NOT_RETURNED are both "we kept it", but are tracked separately so the
     * reason is still visible later in Billing History rather than just a
     * flat yes/no.
     */
    public enum DepositReturnStatus {
        RETURNED, NOT_RETURNED, DAMAGED
    }

    /** Whether the bill was confirmed closed full-and-final at return time. */
    public enum SettlementStatus {
        SETTLED, DUE
    }

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
