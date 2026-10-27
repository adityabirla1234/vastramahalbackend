package com.rentalshop.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * POST /api/bookings/{anchorBookingId}/items -- adds ONE new item row to
 * the bill [anchorBookingId] already belongs to (Booking History
 * "Add item to bill" action). Deliberately does NOT repeat customerId,
 * groupId or billNumber: those are always resolved server-side from the
 * anchor booking (BookingService.addItemToBill), the same "never trust a
 * client-supplied group/customer" posture CreateBookingBatchRequest
 * already applies to a brand-new group.
 *
 * If [anchorBookingId] is a standalone booking (no groupId yet -- a
 * "booking per row"), adding an item here is what turns it into a group:
 * the anchor is stamped with a freshly generated groupId so the two rows
 * become one bill, exactly mirroring what createBookingBatch does when a
 * New Booking session has more than one row.
 */
@Getter
@Setter
public class AddBookingItemRequest {

    @NotNull
    private Long itemId;

    @NotNull
    private LocalDate pickupDate;

    private LocalDate eventDate;

    @NotNull
    private LocalDate returnDate;

    /**
     * Time of day for {pickup,return}Date, "HH:mm". Optional: older app builds
     * omit it, in which case pickup defaults to 00:00 and return to 23:59
     * (whole days blocked, the pre-time behaviour). An item is occupied up to
     * and including the return minute and can be booked again from the next one.
     */
    private LocalTime pickupTime;
    private LocalTime returnTime;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rentalAmount;

    @DecimalMin("0.0")
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    private String notes;

    @Valid
    private List<BookingAccessoryRequest> accessories;

    /**
     * Same role as CreateBookingRequest.idempotencyKey -- generated once on
     * the device when staff tap "Add item" and reused verbatim on any retry,
     * so a network timeout can never double-add the same row.
     */
    @NotBlank
    private String idempotencyKey;
}
