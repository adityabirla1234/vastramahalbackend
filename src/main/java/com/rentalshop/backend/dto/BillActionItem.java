package com.rentalshop.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * One booking row named in a bill-level action (mark picked up / mark
 * returned), together with the version the app last saw it at -- the same
 * optimistic-lock contract as UpdateBookingStatusRequest.version, applied per
 * row. A stale value on ANY row rejects the whole action with 409
 * STALE_WRITE, so a bill someone else just changed is never half-updated.
 */
@Getter
@Setter
public class BillActionItem {

    @NotNull
    private Long bookingId;

    @NotNull
    private Long version;
}
