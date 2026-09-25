package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Booking;
import lombok.Getter;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class BookingResponse {
    private Long id;
    private String bookingNumber;
    private Long itemId;
    private String itemCode;
    private String itemName;

    /**
     * The item's size ("M", "38", "Free size", ...) read live off the
     * inventory row, so booking history can answer "which size went out?"
     * without a second per-item lookup on every history row. Null when the
     * item has no size recorded -- the app renders nothing in that case
     * rather than an empty "Size:" label.
     *
     * Deliberately NOT snapshotted onto the booking the way accessory
     * fields are: a dress's size is a property of the physical garment and
     * doesn't change under it, unlike a name or category that staff may
     * re-file at any time.
     */
    private String itemSize;

    /**
     * Public URL of the item's primary photo (same value ItemResponse would
     * report for this item), so any screen that renders a booking -- booking
     * history, a bill's item list, the WhatsApp confirmation -- can offer a
     * "View Image" affordance without a second per-item lookup. Null when
     * the item has no photo uploaded yet. Resolved by BookingService the
     * same way ItemService resolves ItemResponse.primaryImageUrl: a bulk
     * lookup for list endpoints, a single lookup for single-booking reads.
     */
    private String itemImageUrl;
    private Long customerId;
    private String customerName;
    /** Amount Due Bills (Customers section): lets the app show who to call without a second lookup. */
    private String customerPhone;
    private LocalDate pickupDate;
    private LocalDate eventDate;
    private LocalDate returnDate;
    private BigDecimal rentalAmount;
    private BigDecimal depositAmount;
    /** "Cash" / "UPI" / "Card" -- how the security deposit was paid. Null when there was none, or for older rows. */
    private String depositPaymentMethod;
    private BigDecimal advanceAmount;
    private String advancePaymentMethod;
    private BigDecimal balanceAmount;
    private Booking.BookingStatus status;
    /** Optimistic-lock value for PATCH /api/bookings/{id}/status -- see UpdateBookingStatusRequest. */
    private Long version;
    /** Section 3.7 multi-item booking -- null for a standalone booking.
     * See Booking.groupId's Javadoc; GET /api/bookings/group/{groupId}
     * fetches every row sharing this value for the "overall bill" view. */
    private String groupId;

    /** The shop's own "Bill No" -- shared by every row of a group booking. Null if none was entered. */
    private String billNumber;

    /** Null until the bill is finally closed out -- see UpdateBookingStatusRequest's Javadoc. */
    private Booking.DepositReturnStatus depositReturnStatus;
    /** The reason staff gave when the deposit was NOT handed back. Null otherwise. */
    private String depositReturnReason;
    private Booking.SettlementStatus settlementStatus;

    /**
     * Whatever staff typed in this item row's Notes field at booking time.
     * Newly exposed here: the column has always existed on Booking, but no
     * response carried it, so the app had no way to show it back. Now every
     * screen that renders a booking (Booking Detail, booking history, the
     * group bill view) can offer a "View notes" button -- and, crucially,
     * knows to HIDE that button when this is null/blank, which is why it's
     * sent as-is rather than defaulted to an empty string.
     */
    private String notes;

    /**
     * Alteration instructions added to this item row AFTER the bill was
     * created (see Booking.fittingWork). Null when there are none -- the
     * app uses exactly that to decide between showing "Add fitting work"
     * and "View fitting work", so it is sent as-is, never defaulted to "".
     */
    private String fittingWork;

    /**
     * This item row's accessories, oldest-attached first. Empty (never
     * null) when none were added, so the app's "show the Accessories button
     * only if there are any" check is a plain isEmpty() with no null guard.
     * See BookingAccessoryResponse for why these are snapshots.
     */
    private List<BookingAccessoryResponse> accessories;

    /** Convenience for call sites that don't have (or don't need) the item's photo resolved -- see {@link #from(Booking, String)}. */
    public static BookingResponse from(Booking b) {
        return from(b, null);
    }

    /** [itemImageUrl] is resolved by the caller (BookingService) -- this DTO has no repository access of its own. */
    public static BookingResponse from(Booking b, String itemImageUrl) {
        return BookingResponse.builder()
                .id(b.getId())
                .bookingNumber(b.getBookingNumber())
                .itemId(b.getItem().getId())
                .itemCode(b.getItem().getItemCode())
                .itemName(b.getItem().getName())
                .itemSize(b.getItem().getSize())
                .itemImageUrl(itemImageUrl)
                .customerId(b.getCustomer().getId())
                .customerName(b.getCustomer().getName())
                .customerPhone(b.getCustomer().getPhone())
                .pickupDate(b.getPickupDate())
                .eventDate(b.getEventDate())
                .returnDate(b.getReturnDate())
                .rentalAmount(b.getRentalAmount())
                .depositAmount(b.getDepositAmount())
                .depositPaymentMethod(b.getDepositPaymentMethod())
                .advanceAmount(b.getAdvanceAmount())
                .advancePaymentMethod(b.getAdvancePaymentMethod())
                .balanceAmount(b.getBalanceAmount())
                .status(b.getStatus())
                .version(b.getVersion())
                .groupId(b.getGroupId())
                .billNumber(b.getBillNumber())
                .depositReturnStatus(b.getDepositReturnStatus())
                .depositReturnReason(b.getDepositReturnReason())
                .settlementStatus(b.getSettlementStatus())
                .notes(b.getNotes())
                .fittingWork(b.getFittingWork())
                // Touches the LAZY accessories collection, so every caller
                // of this method must be inside an open transaction -- they
                // all are (every BookingService method that builds a
                // BookingResponse is @Transactional). Booking.accessories
                // carries @BatchSize precisely so doing this per row on a
                // list endpoint stays one extra query for the whole page
                // rather than one per booking.
                .accessories(b.getAccessories() == null ? List.of() : b.getAccessories().stream()
                        .map(BookingAccessoryResponse::from)
                        .toList())
                .build();
    }
}
