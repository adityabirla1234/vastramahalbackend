package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Booking;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UpdateBookingStatusRequest {

    /** The status to transition to. Validity is checked against
     * Booking.BookingStatus#canTransitionTo -- an out-of-order transition
     * (e.g. PENDING straight to RETURNED) is rejected with 409 INVALID_STATE,
     * not silently coerced. */
    @NotNull
    private Booking.BookingStatus targetStatus;

    /** Same optimistic-lock contract as UpdateItemRequest.version: the
     * client's last-known version. A stale value means another device
     * already changed this booking, and the app should refresh + retry
     * rather than overwrite. */
    @NotNull
    private Long version;

    /**
     * Section 3.7 redesign, step 5: the security deposit is collected and
     * entered here, at PICKED_UP time, not at booking creation. Required by
     * BookingService.updateStatus whenever targetStatus is PICKED_UP (the
     * app must prompt for it before allowing that transition); ignored for
     * every other transition. Recorded on the booking for display only --
     * deliberately never folded into Booking.rentalAmount or
     * Booking.balanceAmount, here or anywhere else in the lifecycle.
     */
    @DecimalMin("0.0")
    private BigDecimal depositAmount;

    /**
     * How the deposit above was paid -- "Cash", "UPI" or "Card". Optional
     * here (older app builds don't send it) and only read on the PICKED_UP
     * transition; stored as null when there is no deposit. The current app
     * collects the deposit through POST /api/bookings/mark-picked-up
     * instead, where the method is required -- see MarkPickedUpRequest.
     */
    @Size(max = 40)
    private String depositPaymentMethod;

    /**
     * Required by BookingService.updateStatus whenever this transition is
     * the one that finally closes out the bill: targetStatus RETURNED on a
     * standalone booking, or on the last still-active item of a group
     * booking. Ignored (may be left null) on every other RETURNED
     * transition -- i.e. a non-final item inside a group being returned
     * while siblings are still open. Never affects rentalAmount or
     * balanceAmount; purely a record of what happened to the deposit.
     */
    private Booking.DepositReturnStatus depositReturnStatus;

    /**
     * Required alongside depositReturnStatus, same "final return" rule.
     * SETTLED zeroes out the remaining balanceAmount (bill closed
     * full-and-final); DUE leaves balanceAmount exactly as it stood.
     */
    private Booking.SettlementStatus settlementStatus;
}
