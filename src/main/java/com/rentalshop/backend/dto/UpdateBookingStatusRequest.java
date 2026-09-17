package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Booking;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

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
}
