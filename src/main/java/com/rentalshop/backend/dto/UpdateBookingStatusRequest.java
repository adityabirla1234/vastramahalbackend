package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Booking;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
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
     * every other transition. Folds into Booking.balanceAmount the same way
     * rentalAmount/advanceAmount already do at creation.
     */
    @DecimalMin("0.0")
    private BigDecimal depositAmount;
}
